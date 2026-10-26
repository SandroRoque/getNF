package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.data.CaptureSegment
import io.github.sandroroque.getnf.data.CaptureSessionManifest
import io.github.sandroroque.getnf.data.CaptureSessionStore
import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.data.ReceiptStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlinx.coroutines.runBlocking

private const val TAG = "ReceiptCapture"

/** What the capture screen shows: the paper found, where to go, and the receipt so far (D85). */
internal data class CaptureUiState(
  /** The paper's outline in the preview's coordinates, when found in the last frame. */
  val outline: List<DocumentPoint>? = null,
  /** The direction down the receipt on screen, in degrees (0 = down the screen), when the paper is found. */
  val down: Float? = null,
  val hint: CaptureHint = CaptureHint.FIND_RECEIPT,
  /** The receipt's rows so far, in order, for the map; [view] the ones in the last frame. */
  val rows: List<RowMark> = emptyList(),
  val view: IntRange? = null,
  val topClosed: Boolean = false,
  val bottomClosed: Boolean = false,
  val saved: Int = 0,
  val qr: Boolean = false,
  val completed: Boolean = false,
  val exited: Boolean = false,
  val saving: Boolean = false,
  val error: String? = null,
  /** Too dark for long enough that the torch should come on (D93). */
  val dark: Boolean = false,
)

internal data class RowMark(val captured: Boolean, val gapAfter: Double)

internal enum class CaptureHint(val label: String) {
  FIND_RECEIPT("Aponte para a nota"),
  BACK_TO_CAPTURED("Volte ao trecho já lido"),
  CLOSER("Aproxime a câmera"),
  HOLD_STILL("Segure firme"),
  MOVE_UP("Suba pela nota"),
  MOVE_DOWN("Desça pela nota"),
  SHOW_QR("Mostre o QR Code"),
  OTHER_RECEIPT("Outra nota à vista. Volte à desta captura"),
  DONE("Pronto"),
}

internal enum class ExitChoice { KEEP_CODE, DISCARD }

/**
 * One analysed camera frame, upright: its pixels for the paper finder, its mapping to the view, and the camera's planes
 * to make its photo from when it is saved ([pixels]; most frames never are, and converting each would slow every
 * frame).
 */
internal class CameraFrame(val image: GrayImage, val pixels: FramePixels, val mapping: FrameMapping, val atMs: Long) {
  /** The frame's mean luminance (0–255): auto exposure holds it near 140 while there is light enough. */
  val luma: Int by lazy { image.pixels.let { p -> var sum = 0L; var n = 0; var i = 0; while (i < p.size) { sum += p[i].toInt() and 0xff; n++; i += 61 }; if (n == 0) 0 else (sum / n).toInt() } }
}

/** A copy of a YUV_420_888 camera frame's planes and how to turn them upright. */
internal class FramePixels(
  private val y: ByteArray, private val yRow: Int, private val yPixel: Int,
  private val u: ByteArray, private val v: ByteArray, private val uvRow: Int, private val uvPixel: Int,
  private val width: Int, private val height: Int, private val rotation: Int,
) {
  val uprightWidth get() = if (rotation % 180 == 0) width else height
  val uprightHeight get() = if (rotation % 180 == 0) height else width

  /** The frame in colour, upright (BT.601, full range, as camera frames are). */
  fun toBitmap(): Bitmap {
    val ow = uprightWidth
    val argb = IntArray(width * height)
    for (by in 0 until height) {
      val (start, step) = uprightIndex(by, ow)
      val row = by * yRow; val crow = (by / 2) * uvRow
      var o = start
      for (bx in 0 until width) {
        val l = y[row + bx * yPixel].toInt() and 0xff
        val c = crow + (bx / 2) * uvPixel
        val cu = (u.getOrElse(c) { -128 }.toInt() and 0xff) - 128; val cv = (v.getOrElse(c) { -128 }.toInt() and 0xff) - 128
        val r = (l + (1436 * cv shr 10)).coerceIn(0, 255)
        val g = (l - (352 * cu + 731 * cv shr 10)).coerceIn(0, 255)
        val b = (l + (1815 * cu shr 10)).coerceIn(0, 255)
        argb[o] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        o += step
      }
    }
    return Bitmap.createBitmap(argb, ow, uprightHeight, Bitmap.Config.ARGB_8888)
  }

  /** Where buffer row [by] starts in the upright frame ([ow] wide), and the step from one pixel of it to the next. */
  fun uprightIndex(by: Int, ow: Int): Pair<Int, Int> = when (rotation) {
    90 -> (height - 1 - by) to ow
    180 -> (height - 1 - by) * ow + (width - 1) to -1
    270 -> (width - 1) * ow + by to -ow
    else -> by * ow to 1
  }

  companion object {
    fun of(image: ImageProxy): FramePixels {
      val (y, u, v) = image.planes.let { Triple(it[0], it[1], it[2]) }
      // Each plane copied once, then indexed: reading the buffers pixel by pixel is several times slower.
      fun bytes(plane: ImageProxy.PlaneProxy) = plane.buffer.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
      return FramePixels(bytes(y), y.rowStride, y.pixelStride, bytes(u), bytes(v), u.rowStride, u.pixelStride,
        image.width, image.height, image.imageInfo.rotationDegrees)
    }
  }

  /**
   * Upright luminance, and colourfulness from the chroma planes (paper is neutral, D85), rotated so OCR's coordinates
   * (turned upright, [upright]) apply as they are.
   */
  fun gray(): GrayImage {
    val ow = uprightWidth
    val gray = ByteArray(width * height); val chroma = ByteArray(width * height)
    // Colourfulness per 2×2 block, as the chroma planes are sampled.
    val cw = (width + 1) / 2; val colour = ByteArray(cw * ((height + 1) / 2))
    for (cy in 0 until (height + 1) / 2) for (cx in 0 until cw) {
      val c = cy * uvRow + cx * uvPixel
      val cu = (u.getOrElse(c) { -128 }.toInt() and 0xff) - 128; val cv = (v.getOrElse(c) { -128 }.toInt() and 0xff) - 128
      colour[cy * cw + cx] = min(255, (abs(cu) + abs(cv)) * 3 / 2).toByte()
    }
    for (by in 0 until height) {
      val (start, step) = uprightIndex(by, ow)
      val row = by * yRow; val crow = (by / 2) * cw
      var o = start
      for (bx in 0 until width) {
        gray[o] = y[row + bx * yPixel]
        chroma[o] = colour[crow + bx / 2]
        o += step
      }
    }
    return GrayImage(ow, uprightHeight, gray, chroma)
  }
}

/**
 * A point in the camera buffer ([width] × [height]) in the upright frame: ML Kit reports its boxes in the buffer's own
 * orientation even when told the rotation, while the frame the paper finder walks is turned upright.
 */
internal fun upright(x: Int, y: Int, width: Int, height: Int, rotation: Int): DocumentPoint = when (rotation) {
  90 -> DocumentPoint(height - 1.0 - y, x.toDouble())
  180 -> DocumentPoint(width - 1.0 - x, height - 1.0 - y)
  270 -> DocumentPoint(y.toDouble(), width - 1.0 - x)
  else -> DocumentPoint(x.toDouble(), y.toDouble())
}

/**
 * Takes the newest camera frame whenever the last one is done (a few a second: OCR is the slow part), reads its text
 * and QR codes on the device, and hands everything to [onFrame]. Frames arriving meanwhile are dropped.
 */
internal class IngestAnalyzer(
  private val text: TextRecognizer,
  private val qr: BarcodeScanner,
  private val executor: Executor,
  private val active: () -> Boolean,
  private val onFrame: (CameraFrame, List<OcrQuad>, List<Pair<String, List<DocumentPoint>>>) -> Unit,
  private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
  private val busy = AtomicBoolean(false)
  @Volatile private var sensorToView: Matrix? = null
  @Volatile private var closing = false
  private var closeAction: (() -> Unit)? = null

  override fun getDefaultTargetResolution() = Size(1920, 1440)
  override fun getTargetCoordinateSystem() = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED
  override fun updateTransform(matrix: Matrix?) { sensorToView = matrix?.let(::Matrix) }

  @OptIn(ExperimentalGetImage::class)
  override fun analyze(image: ImageProxy) {
    if (closing || !active() || !busy.compareAndSet(false, true)) { image.close(); return }
    val media = image.image
    if (media == null) { image.close(); busy.set(false); return }
    try {
      val started = System.nanoTime()
      val rotation = image.imageInfo.rotationDegrees
      val pixels = FramePixels.of(image)
      val bufferToSensor = Matrix().also { check(image.imageInfo.sensorToBufferTransformMatrix.invert(it)) }
      val mapping = FrameMapping(image.width, image.height, rotation, bufferToSensor, sensorToView?.let(::Matrix), 0)
      val input = InputImage.fromMediaImage(media, rotation)
      val reading = text.process(input); val codes = qr.process(input)
      // The upright copy is made while OCR runs on its own threads.
      val gray = pixels.gray()
      val frame = CameraFrame(gray, pixels, mapping, image.imageInfo.timestamp / 1_000_000)
      val converted = System.nanoTime()
      Tasks.whenAllComplete(reading, codes).addOnCompleteListener(executor) {
        image.close()
        try {
          val lines = if (reading.isSuccessful) reading.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val corners = line.cornerPoints?.takeIf { it.size == 4 } ?: return@mapNotNull null
            OcrQuad(corners.map { upright(it.x, it.y, image.width, image.height, rotation) }, line.confidence.toDouble(), line.text.length, line.text)
          } else emptyList()
          val found = if (codes.isSuccessful) codes.result.mapNotNull { code ->
            val raw = code.rawValue ?: return@mapNotNull null
            raw to code.cornerPoints.orEmpty().map { upright(it.x, it.y, image.width, image.height, rotation) }
          } else emptyList()
          if (BuildConfig.DEBUG) Log.d(TAG, "frame: convert %d ms, OCR done at %d ms".format((converted - started) / 1_000_000, (System.nanoTime() - started) / 1_000_000))
          // The session runs on its own thread: the next frame's OCR starts now, alongside it.
          if (active()) onFrame(frame, lines, found)
        } catch (failure: Exception) { onError(failure) }
        finally { busy.set(false); finishClose() }
      }
    } catch (failure: Exception) {
      image.close(); busy.set(false); onError(failure); finishClose()
    }
  }

  fun closeWhenIdle(action: () -> Unit) { closing = true; closeAction = action; finishClose() }
  private fun finishClose() { if (!busy.get()) closeAction?.also { closeAction = null }?.invoke() }
}

/**
 * Runs one capture (D85): every frame goes through the [IngestSession]; the frame it holds is kept, the frames it
 * commits are saved as the attempt's photos, and when it is done and the receipt's QR code has been read inside the
 * paper, the photos become the receipt's. One serial executor calls it.
 */
internal class CaptureController(
  private val sessions: CaptureSessionStore,
  private val receipts: ReceiptStore,
  private val publish: (CaptureUiState) -> Unit,
  private val enqueueUpload: () -> Unit,
  /** Debug builds: when set, every analysed frame and its OCR are written here, to replay the session on the PC. */
  private val dump: File? = null,
) {
  private val ingest = IngestSession()
  private var manifest: CaptureSessionManifest = sessions.create()
  private class Held(val id: String, val pixels: FramePixels, val paper: PaperOutline, val view: IntRange?, val score: Double)
  private var held: Held? = null
  private var state = CaptureUiState()
  private var done = false
  private var frames = 0
  private var darkSince: Long? = null
  /** Dark: the frame's mean luminance under [DARK_LUMA] (auto exposure has run out) for [DARK_MS] in a row. */
  private fun dark(frame: CameraFrame): Boolean {
    if (frame.luma >= DARK_LUMA) { darkSince = null; return false }
    val since = darkSince ?: frame.atMs.also { darkSince = it }
    return frame.atMs - since >= DARK_MS
  }

  /** The photo each committed frame was saved as. */
  private val savedAs = mutableMapOf<String, CaptureSegment>()
  /** The debug dump writes on a thread of its own, off the session's. */
  private val dumping by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

  fun offer(frame: CameraFrame, lines: List<OcrQuad>, codes: List<Pair<String, List<DocumentPoint>>>) {
    if (done) return
    val id = "f${frames++}"
    dump?.let { dir -> dumping.execute { dumpFrame(dir, id, frame, lines) } }
    val started = System.nanoTime()
    val step = ingest.offer(IngestFrame(id, frame.image, lines, frame.atMs))
    if (BuildConfig.DEBUG) Log.d(TAG, "frame $id: session %d ms".format((System.nanoTime() - started) / 1_000_000))
    dump?.let { dir -> dumping.execute { logDecision(dir, id, frame.atMs, step) } }
    identify(step.paper, codes, frame)
    val score = lines.map { it.confidence }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    val current = step.paper?.let { Held(id, frame.pixels, it, step.view, score) }
    for (commit in step.commits) when (commit) {
      held?.id -> held?.let(::save)
      id -> current?.let(::save)
    }
    if (step.hold && current != null) held = current

    // A code read inside the paper that isn't this receipt's: another receipt is in view.
    val keysInPaper = step.paper?.let { p -> codes.filter { it.second.isNotEmpty() && contains(p, it.second, frame.image) }.mapNotNull { Chave.fromQr(it.first) } }.orEmpty()
    val conflicting = manifest.chave != null && keysInPaper.any { it != manifest.chave }
    val hint = when {
      conflicting -> CaptureHint.OTHER_RECEIPT
      step.complete && manifest.chave == null -> CaptureHint.SHOW_QR
      else -> step.hint.toCapture()
    }
    state = state.copy(
      outline = step.paper?.corners?.mapNotNull { frame.mapping.map(it) }?.takeIf { it.size == 4 },
      down = step.down?.let { d -> arrow(frame, step.paper, d) },
      hint = hint,
      rows = ingest.rows.map { RowMark(ingest.isCaptured(it), it.gapAfter) },
      view = step.view, topClosed = ingest.topClosed, bottomClosed = ingest.bottomClosed,
      saved = manifest.segments.size, qr = manifest.chave != null,
      dark = dark(frame),
    )
    if (step.complete && manifest.chave != null) finish() else publish(state)
  }

  /** Writes the frame and its OCR as `VideoIngestTest` reads them (`f0001.jpg` and `f0001.jpg.ocr.json`). */
  private fun dumpFrame(dir: File, id: String, frame: CameraFrame, lines: List<OcrQuad>) {
    val name = "f%04d.jpg".format(id.removePrefix("f").toInt() + 1)
    dir.mkdirs()
    val bitmap = frame.pixels.toBitmap()
    // Near-lossless: the replay finds the paper's edges on this copy, and a borderline edge must come out the same.
    try { FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) } } finally { bitmap.recycle() }
    val text = io.github.sandroroque.getnf.discounts.PhotoText(name, frame.pixels.uprightWidth, frame.pixels.uprightHeight, lines.map { q ->
      io.github.sandroroque.getnf.discounts.TextLine(q.text, q.corners.minOf { it.x }.toInt(), q.corners.minOf { it.y }.toInt(),
        q.corners.maxOf { it.x }.toInt(), q.corners.maxOf { it.y }.toInt(), q.confidence.toFloat(), q.corners.flatMap { listOf(it.x.toInt(), it.y.toInt()) })
    })
    File(dir, "$name.ocr.json").writeText(kotlinx.serialization.json.Json.encodeToString(text))
  }

  /**
   * Appends the session's decision on a frame to `live.tsv` (frame, its camera time, hint, held, committed, complete,
   * the paper's ends seen: `T`, `B`, `TB` or none, then `-`; empty when no paper was found),
   * so the replay can use the frames' real timing and show where it would decide differently.
   */
  private fun logDecision(dir: File, id: String, atMs: Long, step: IngestStep) {
    fun name(id: String) = "f%04d".format(id.removePrefix("f").toInt() + 1)
    val ends = step.paper?.let { (if (it.topSeen) "T" else "") + (if (it.bottomSeen) "B" else "") + "-" } ?: ""
    File(dir, "live.tsv").appendText(listOf(name(id), atMs, step.hint, step.hold, step.commits.joinToString(",", transform = ::name), step.complete, ends)
      .joinToString("\t") + "\n")
  }

  /** The receipt's code: a QR code read inside the paper found (another receipt's code beside it doesn't count). */
  private fun identify(paper: PaperOutline?, codes: List<Pair<String, List<DocumentPoint>>>, frame: CameraFrame) {
    if (manifest.chave != null || paper == null) return
    for ((raw, corners) in codes) {
      val key = Chave.fromQr(raw) ?: continue
      if (corners.isEmpty() || !contains(paper, corners, frame.image)) continue
      manifest = manifest.copy(chave = key, qrUrl = raw)
      sessions.write(manifest)
      return
    }
  }

  private fun contains(paper: PaperOutline, points: List<DocumentPoint>, image: GrayImage): Boolean {
    val quad = paper.corners.map { DocumentPoint(it.x * image.width, it.y * image.height) }
    val c = DocumentPoint(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
    var inside = false
    for (i in quad.indices) {
      val a = quad[i]; val b = quad[(i + 1) % quad.size]
      if ((a.y > c.y) != (b.y > c.y) && c.x < (b.x - a.x) * (c.y - a.y) / (b.y - a.y) + a.x) inside = !inside
    }
    return inside
  }

  /** The direction down the receipt on screen, from two points of the frame mapped to the view. */
  private fun arrow(frame: CameraFrame, paper: PaperOutline?, down: DocumentPoint): Float? {
    val c = paper?.corners ?: return null
    val w = frame.image.width.toDouble(); val h = frame.image.height.toDouble()
    val centre = DocumentPoint(c.sumOf { it.x } / 4, c.sumOf { it.y } / 4)
    val ahead = DocumentPoint(centre.x + down.x * 50 / w, centre.y + down.y * 50 / h)
    val a = frame.mapping.map(centre) ?: return null; val b = frame.mapping.map(ahead) ?: return null
    return Math.toDegrees(atan2(-(b.x - a.x), b.y - a.y)).toFloat()
  }

  /** Writes a committed frame as the attempt's next photo, with a mask of the paper and its segment metadata. */
  private fun save(frame: Held) {
    if (manifest.segments.size >= MAX_PHOTOS) return
    val name = "capture-${manifest.id}-${manifest.segments.size}"
    val dir = sessions.dir(manifest.id)
    val image = File(dir, "$name.jpg"); val mask = File(dir, "$name.mask.png")
    val bitmap = frame.pixels.toBitmap()
    try { FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it); it.fd.sync() } } finally { bitmap.recycle() }
    // The mask: the paper's outline filled, at a quarter of the photo's size (the upload crops by the polygon, D61).
    val small = Bitmap.createBitmap(frame.pixels.uprightWidth / 4, frame.pixels.uprightHeight / 4, Bitmap.Config.ARGB_8888)
    try {
      val path = Path().apply {
        frame.paper.corners.forEachIndexed { i, p -> val x = (p.x * small.width).toFloat(); val y = (p.y * small.height).toFloat(); if (i == 0) moveTo(x, y) else lineTo(x, y) }
        close()
      }
      Canvas(small).apply { drawColor(Color.BLACK); drawPath(path, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }) }
      FileOutputStream(mask).use { small.compress(Bitmap.CompressFormat.PNG, 100, it); it.fd.sync() }
    } finally { small.recycle() }
    val segment = CaptureSegment(image.name, mask.name, frame.paper.corners.map { listOf(it.x, it.y) },
      listOfNotNull(frame.view?.let { listOf(it.first.toDouble(), it.last.toDouble()) }),
      if (frame.paper.topSeen) 0.0 else null, if (frame.paper.bottomSeen) frame.view?.last?.toDouble() else null,
      frame.score, System.currentTimeMillis(), manifest.id)
    manifest = manifest.copy(segments = manifest.segments + segment)
    savedAs[frame.id] = segment
    sessions.write(manifest)
    if (BuildConfig.DEBUG) Log.d(TAG, "saved ${frame.id} as ${image.name}: rows ${frame.view}")
  }

  private fun finish() {
    publish(state.copy(saving = true))
    // Photos saved on the way that the rest read entirely (an overview a closer frame read better) are dropped.
    val keep = ingest.keep().mapNotNull(savedAs::get).toSet()
    if (keep.isNotEmpty()) {
      val dir = sessions.dir(manifest.id)
      for (s in manifest.segments - keep) { File(dir, s.photo).delete(); File(dir, s.mask).delete() }
      manifest = manifest.copy(segments = manifest.segments.filter { it in keep })
      sessions.write(manifest)
    }
    held = null
    manifest = manifest.copy(committing = true)
    sessions.write(manifest)
    runBlocking { receipts.importCapture(manifest, sessions.dir(manifest.id)) }
    sessions.remove(manifest)
    done = true
    enqueueUpload()
    state = state.copy(saving = false, completed = true, hint = CaptureHint.DONE)
    publish(state)
  }

  fun exit(choice: ExitChoice) {
    if (done) return
    if (choice == ExitChoice.KEEP_CODE && manifest.chave != null) {
      runBlocking { receipts.open(manifest.chave!!, manifest.qrUrl) }
      enqueueUpload()
    }
    held = null
    sessions.remove(manifest)
    done = true
    state = state.copy(exited = true)
    publish(state)
  }

  fun close() {
    if (!done) exit(ExitChoice.KEEP_CODE)
    if (dump != null) dumping.shutdown()
  }

  private fun IngestHint.toCapture() = when (this) {
    IngestHint.FIND_RECEIPT -> CaptureHint.FIND_RECEIPT
    IngestHint.BACK_TO_CAPTURED -> CaptureHint.BACK_TO_CAPTURED
    IngestHint.CLOSER -> CaptureHint.CLOSER
    IngestHint.HOLD_STILL -> CaptureHint.HOLD_STILL
    IngestHint.MOVE_UP -> CaptureHint.MOVE_UP
    IngestHint.MOVE_DOWN -> CaptureHint.MOVE_DOWN
    IngestHint.DONE -> CaptureHint.DONE
  }

  private companion object {
    const val MAX_PHOTOS = 30
    // Frames in normal light measured 111–160 (the benchmark's phone sessions); half the usual level means the camera
    // can't expose any longer, and print turns grainy and blurred.
    const val DARK_LUMA = 70
    const val DARK_MS = 1000L
  }
}
