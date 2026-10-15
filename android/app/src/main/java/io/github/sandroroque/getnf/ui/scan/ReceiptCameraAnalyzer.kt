package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import com.google.mlkit.vision.barcode.BarcodeScanner
import io.github.sandroroque.getnf.data.Chave
import org.opencv.core.Mat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal data class FrameMapping(val width: Int, val height: Int, val rotation: Int, val bufferToSensor: Matrix, val sensorToView: Matrix?, val analysisRotation: Int = 0) {
  fun map(point: DocumentPoint): DocumentPoint? {
    val target = sensorToView ?: return null
    val uprightW = if (rotation % 180 == 0) width else height
    val uprightH = if (rotation % 180 == 0) height else width
    val canonicalW = if (analysisRotation % 180 == 0) uprightW else uprightH
    val canonicalH = if (analysisRotation % 180 == 0) uprightH else uprightW
    val p = floatArrayOf((point.x * canonicalW).toFloat(), (point.y * canonicalH).toFloat())
    val canonicalToBuffer = Matrix().apply { setConcat(ImageCoordinates.inverseRotation(width, height, rotation),
      ImageCoordinates.inverseRotation(uprightW, uprightH, analysisRotation)) }
    val bufferToView = Matrix().apply { setConcat(target, bufferToSensor) }
    val analysisToView = Matrix().apply { setConcat(bufferToView, canonicalToBuffer) }
    analysisToView.mapPoints(p)
    return DocumentPoint(p[0].toDouble(), p[1].toDouble())
  }
}

internal class ReceiptFrame(
  val vision: VisionFrame,
  val key: String?,
  val qrUrl: String?,
  val qrPolygon: List<DocumentPoint>,
  val timestampMs: Long,
  val mapping: FrameMapping,
  private var bitmap: Bitmap?,
  val focusContours: List<List<DocumentPoint>> = emptyList(),
  val analysisStartedAt: Long = 0,
) : AutoCloseable {
  fun takeBitmap(): Bitmap? = bitmap.also { bitmap = null }
  override fun close() { bitmap?.recycle(); vision.close() }
}

/** Owns camera buffers only until the QR task finishes. Processing and callbacks are serialized off main. */
internal class ReceiptCameraAnalyzer(
  scanner: BarcodeScanner,
  private val executor: Executor,
  private val active: () -> Boolean,
  private val onFrame: (ReceiptFrame) -> Unit,
  private val onError: (Throwable) -> Unit,
  private val onInspection: (Bitmap) -> Unit = { it.recycle() },
) : ImageAnalysis.Analyzer {
  private data class Pending(val source: Mat, val vision: VisionFrame, val bitmap: Bitmap?, val mapping: FrameMapping, val qrAt: Long, val inspect: Boolean, val analysisStartedAt: Long)
  private val pending = mutableMapOf<Long, Pending>()
  private val processing = AtomicBoolean(false)
  private var lastAt = 0L
  private var lastLocatedRotation: Int? = null
  private val lock = Any()
  private var closing = false
  private var closeAction: (() -> Unit)? = null
  @Volatile var inspectNext = false
  @Volatile private var sensorToView: Matrix? = null
  private val delegate = MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
    val data = pending.remove(result.timestamp)
    var transferred = false
    try {
      if (data != null) {
        CapturePerf.elapsed("qr", data.qrAt)
        val codes = result.getValue(scanner).orEmpty().mapNotNull { code ->
          val raw = code.rawValue ?: return@mapNotNull null
          val key = Chave.fromQr(raw) ?: return@mapNotNull null
          Triple(key, raw, code.cornerPoints.orEmpty().map { p ->
            val ow = if (data.vision.rotation % 180 == 0) data.source.cols() else data.source.rows()
            val oh = if (data.vision.rotation % 180 == 0) data.source.rows() else data.source.cols()
            ImageCoordinates.rotated(DocumentPoint(p.x.toDouble() / ow, p.y.toDouble() / oh), data.vision.rotation)
          })
        }
        val hit = codes.takeIf { it.map { code -> code.first }.distinct().size == 1 }?.firstOrNull()
        val qr = hit?.third.orEmpty().takeIf { points ->
          points.isNotEmpty() && points.all { data.vision.observation.contains(it.x, it.y) }
        }.orEmpty()
        val mask = Mat()
        var focusContours = emptyList<List<DocumentPoint>>()
        val updated = try {
          if (!data.vision.observation.located) data.vision.observation else {
            org.opencv.imgproc.Imgproc.resize(data.vision.mask, mask, data.source.size(), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_NEAREST)
            val effective = DocumentVision.focusMask(mask, qr)
            try {
              focusContours = DocumentVision.maskContours(effective)
              if (data.inspect && data.bitmap != null) onInspection(CaptureInspection.render(data.bitmap, data.vision.observation, effective))
            } finally { effective.release() }
            data.vision.observation.copy(quality = CapturePerf.measure("quality") { DocumentVision.quality(data.source, mask, qr) })
          }
        } finally { mask.release() }
        if (data.inspect && !data.vision.observation.located && data.bitmap != null)
          onInspection(CaptureInspection.render(data.bitmap, data.vision.observation, data.vision.mask))
        val packet = ReceiptFrame(VisionFrame(data.vision.gray.clone(), data.vision.mask.clone(), updated, data.vision.rotation),
          hit?.first, hit?.second, qr, result.timestamp / 1_000_000, data.mapping, data.bitmap, focusContours, data.analysisStartedAt)
        transferred = true
        try { if (active()) CapturePerf.measure("engine") { onFrame(packet) } } finally { packet.close() }
      }
    } catch (failure: Exception) { onError(failure) }
    finally {
      if (!transferred) data?.bitmap?.recycle()
      data?.source?.release(); data?.vision?.close()
      // Bitmap ownership moves to packet; exceptions before packet construction need cleanup too.
      processing.set(false)
      finishClose()
    }
  }

  override fun getDefaultTargetResolution() = Size(1920, 1440)
  override fun getTargetCoordinateSystem() = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED
  override fun updateTransform(matrix: Matrix?) { sensorToView = matrix?.let(::Matrix) }

  override fun analyze(image: ImageProxy) {
    synchronized(lock) {
      if (closing || !active() || image.imageInfo.timestamp - lastAt < 180_000_000 || !processing.compareAndSet(false, true)) {
        image.close(); return
      }
      lastAt = image.imageInfo.timestamp
    }
    val analysisStartedAt = CapturePerf.now()
    var source: Mat? = null; var vision: VisionFrame? = null; var bitmap: Bitmap? = null
    try {
      val inverse = Matrix()
      check(image.imageInfo.sensorToBufferTransformMatrix.invert(inverse))
      source = CapturePerf.measure("gray") { DocumentVision.gray(image) }
      vision = CapturePerf.measure("vision") { DocumentVision.analyze(source, evaluateQuality = false, preferredRotation = lastLocatedRotation) }
      if (vision.observation.located) lastLocatedRotation = vision.rotation
      val mapping = FrameMapping(image.width, image.height, image.imageInfo.rotationDegrees, inverse, sensorToView?.let(::Matrix), vision.rotation)
      if (vision.rotation == 90) org.opencv.core.Core.rotate(source, source, org.opencv.core.Core.ROTATE_90_CLOCKWISE)
      val inspect = inspectNext
      if (inspect) inspectNext = false
      if ((vision.observation.located && vision.observation.hasSides) || inspect) {
        val raw = CapturePerf.measure("bitmap") { image.toBitmap() }
        val rotation = (image.imageInfo.rotationDegrees + vision.rotation) % 360
        bitmap = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
          Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
      }
      pending[image.imageInfo.timestamp] = Pending(source, vision, bitmap,
        mapping, CapturePerf.now(), inspect, analysisStartedAt)
      delegate.analyze(image)
    } catch (failure: Exception) {
      pending.remove(image.imageInfo.timestamp)
      source?.release(); vision?.close(); bitmap?.recycle(); image.close(); processing.set(false)
      onError(failure); finishClose()
    }
  }
  fun closeWhenIdle(action: () -> Unit) {
    synchronized(lock) { closing = true; closeAction = action }
    finishClose()
  }
  private fun finishClose() {
    val action = synchronized(lock) { if (!processing.get()) closeAction.also { closeAction = null } else null }
    action?.invoke()
  }
}
