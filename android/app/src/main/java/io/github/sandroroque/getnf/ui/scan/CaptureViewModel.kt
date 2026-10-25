package io.github.sandroroque.getnf.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CaptureSegment
import io.github.sandroroque.getnf.data.CaptureSessionManifest
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class DrawSegment(val start: DocumentPoint, val end: DocumentPoint, val good: Boolean)
internal data class CaptureSessionState(
  val edges: List<DrawSegment> = emptyList(),
  val maskPaths: List<List<DocumentPoint>> = emptyList(),
  val frameId: Long = -1,
  val axisAngle: Float = 0f,
  val guidance: GuidanceState = GuidanceState(listOf(CaptureHint.CENTER, CaptureHint.TOP), MovementTarget.TOP),
  val segmentCount: Int = 0,
  val geometryKnown: Boolean = false,
  val top: Boolean = false,
  val bottom: Boolean = false,
  val connected: Boolean = false,
  val qr: Boolean = false,
  val completed: Boolean = false,
  val exited: Boolean = false,
  val saving: Boolean = false,
  val error: String? = null,
)
internal enum class ExitChoice { KEEP_CODE, DISCARD }

internal class CaptureViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as GetNFApp
  val executor = Executors.newSingleThreadExecutor()
  private val attached = AtomicBoolean(false)
  private val closing = AtomicBoolean(false)
  private val _state = MutableStateFlow(CaptureSessionState())
  val state = _state.asStateFlow()
  private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
  private val engine by lazy { CaptureEngine(app.captureSessions, app.receiptStore, { _state.value = it }, { SyncWorker.enqueue(app) }) }
  val analyzer = ReceiptCameraAnalyzer(scanner, executor, { attached.get() && !closing.get() }, { frame ->
    try { engine.observe(frame) } catch (failure: Exception) { report(failure) }
  }, { failure -> report(failure) }, { bitmap ->
    attached.set(false)
    _inspection.value = bitmap
  })
  private val _inspection = MutableStateFlow<Bitmap?>(null)
  val inspection = _inspection.asStateFlow()
  fun inspectFrame() { analyzer.inspectNext = true }
  fun closeInspection() { _inspection.value = null; attached.set(true) }


  fun setAttached(value: Boolean) { attached.set(value) }
  fun exit(choice: ExitChoice) {
    attached.set(false)
    executor.execute { try { engine.exit(choice) } catch (failure: Exception) { report(failure) } }
  }
  private fun report(failure: Throwable) {
    Log.w("ReceiptCapture", "Capture processing failed", failure)
    val detail = if (BuildConfig.DEBUG) " (${failure::class.simpleName}: ${failure.message})" else ""
    _state.value = _state.value.copy(edges = emptyList(), maskPaths = emptyList(), saving = false, error = "Não foi possível concluir a operação. As fotos anteriores estão preservadas.$detail")
  }
  override fun onCleared() {
    closing.set(true); attached.set(false)
    analyzer.closeWhenIdle {
      executor.execute {
        try { engine.close() } finally { scanner.close(); executor.shutdown() }
      }
    }
  }
}

/** One serial worker owns all native resources, provisional images and coverage state. */
internal class CaptureEngine(
  private val sessions: io.github.sandroroque.getnf.data.CaptureSessionStore,
  private val receipts: ReceiptStore,
  private val publish: (CaptureSessionState) -> Unit,
  private val enqueueUpload: () -> Unit,
  private val legibility: TextLegibility = TextLegibility(),
) : AutoCloseable {
  private data class Candidate(
    val bitmap: Bitmap,
    val mask: Mat,
    val document: DocumentObservation,
    val tracked: TrackedDocument,
    val qr: List<DocumentPoint>,
    val at: Long,
  ) : AutoCloseable { override fun close() { bitmap.recycle(); mask.release() } }

  private var session: CaptureSessionManifest
  private val policy = CapturePolicy()
  private val tracker: DocumentTracker
  private var candidate: Candidate? = null
  private var windowAt = 0L
  private var lastDiagnosticAt = 0L
  private var lastOverlayAt = 0L
  private var done = false
  private var lastState = CaptureSessionState()
  private var pendingHint: GuidanceState? = null
  private var hintSince = 0L
  private var existingPhotos = 0
  private val photoAllowance get() = ReceiptStore.MAX_PHOTOS - existingPhotos
  // Once set, the attempt cannot finish: no more photos fit this NF. Exit keeps the code (D36).
  private var capMessage: String? = null
  // The gradient pre-filter is loose (D37): after OCR rejects a photo, ask for focus for a moment.
  private var illegibleAt = Long.MIN_VALUE / 2
  private var frameAt = 0L
  private var observedTop: Double? = null
  private var observedBottom: Double? = null
  private var topFrames = 0
  private var bottomFrames = 0

  init {
    DocumentVision.ensureLoaded()
    session = sessions.create()
    tracker = DocumentTracker()
  }

  fun observe(frame: ReceiptFrame) {
    if (done) return
    val doc = frame.vision.observation
    val foreignReceipt = session.chave != null && frame.key != null && frame.key != session.chave
    val result = if (foreignReceipt) {
      tracker.breakContinuity()
      TrackingResult(tracker.geometry, null)
    } else CapturePerf.measure("tracking") { tracker.track(frame.vision, frame.timestampMs, session.segments.isEmpty() && candidate == null) }
    val position = result.position
    if (position == null) {
      topFrames = 0; bottomFrames = 0; observedTop = null; observedBottom = null
    }
    val tracked = position?.let { p ->
      if (p.top != null) {
        topFrames = if (observedTop != null && kotlin.math.abs(p.top - observedTop!!) < 5) topFrames + 1 else 1
        observedTop = p.top
      } else topFrames = 0
      if (p.bottom != null) {
        bottomFrames = if (observedBottom != null && kotlin.math.abs(p.bottom - observedBottom!!) < 5) bottomFrames + 1 else 1
        observedBottom = p.bottom
      } else bottomFrames = 0
      p.copy(top = p.top.takeIf { topFrames >= 2 }, bottom = p.bottom.takeIf { bottomFrames >= 2 })
    }
    if (frame.key != null) {
      val belongs = doc.located && frame.qrPolygon.isNotEmpty() && frame.qrPolygon.all { doc.contains(it.x, it.y) }
      if (belongs && (tracked != null || policy.bands.isEmpty())) {
        if (policy.identify(frame.key)) {
          if (session.chave == null) {
            session = session.copy(chave = frame.key, qrUrl = frame.qrUrl)
            sessions.write(session)
            existingPhotos = runBlocking { receipts.get(frame.key) }?.photos?.size ?: 0
            // The QR may come last, after this attempt already saved more than the NF has room for.
            if (session.segments.size > photoAllowance) reachCap()
          }
        }
      } else if (session.chave != null && frame.key != session.chave) policy.identify(frame.key)
    }
    if (session.chave != null && frame.key == session.chave) policy.sameDocumentVisible()

    val edges = doc.edges.takeIf { doc.located }.orEmpty().mapNotNull { edge ->
      val a = frame.mapping.map(edge.start); val b = frame.mapping.map(edge.end)
      if (a == null || b == null) null else DrawSegment(a, b, doc.sharp && tracked != null)
    }
    val maskPaths = frame.focusContours.map { ring -> ring.mapNotNull { frame.mapping.map(it) } }.filter { it.size >= 3 }
    val axisStart = frame.mapping.map(DocumentPoint(.5, .3)); val axisEnd = frame.mapping.map(DocumentPoint(.5, .7))
    val axisAngle = if (axisStart != null && axisEnd != null)
      Math.toDegrees(kotlin.math.atan2(axisEnd.y - axisStart.y, axisEnd.x - axisStart.x)).toFloat() - 90f + if (position?.axisReversed == true) 180f else 0f else 0f
    frameAt = frame.timestampMs
    val guidance = policy.guidance(doc, tracked != null, tracked?.center, position?.top != null, result.geometry?.confirmed == true).let { g ->
      if (frame.timestampMs - illegibleAt > 1500 || CaptureHint.FOCUS in g.warnings) g else g.copy(warnings = listOf(CaptureHint.FOCUS) + g.warnings)
    }
    if (pendingHint != guidance) { pendingHint = guidance; hintSince = frame.timestampMs }
    val shown = if (frame.timestampMs - hintSince >= 250 || lastState.segmentCount == 0) guidance else lastState.guidance
    lastState = lastState.copy(edges = edges, maskPaths = maskPaths, frameId = frame.timestampMs, axisAngle = axisAngle, guidance = shown, qr = policy.key != null,
      top = policy.top != null, bottom = policy.bottom != null, connected = policy.connected,
      segmentCount = session.segments.size, geometryKnown = result.geometry?.confirmed == true, error = capMessage)

    // Publish one coherent frame before JPEG encoding/fsync. Storage must not hold back its contour.
    publish(lastState)
    if (frame.analysisStartedAt > 0) {
      CapturePerf.elapsed("overlayReady", frame.analysisStartedAt)
      if (lastOverlayAt > 0) CapturePerf.elapsed("overlayInterval", lastOverlayAt)
      lastOverlayAt = CapturePerf.now()
    }
    if (capMessage == null && !policy.conflictingKey && tracker.retainedAnchorCount > 0 && tracked != null && doc.sharp && policy.useful(tracked.bands, tracked.top, tracked.bottom, doc.score)) {
      if (session.segments.size >= photoAllowance) {
        if (!policy.complete) reachCap()
      } else {
        val current = candidate
        if (current == null || doc.score > current.document.score * 1.03 || policy.gain(tracked.bands) > policy.gain(current.tracked.bands) * 1.2 ||
          (tracked.top != null && current.tracked.top == null) || (tracked.bottom != null && current.tracked.bottom == null)) {
          val bitmap = frame.takeBitmap()
          if (bitmap != null) {
            if (current == null) windowAt = frame.timestampMs
            current?.close()
            candidate = Candidate(bitmap, frame.vision.mask.clone(), doc, tracked, frame.qrPolygon, frame.timestampMs)
          }
        }
      }
    }
    // A candidate is saved even if a subsequent frame is blurred; its own mask and mapping remain authoritative.
    if (candidate != null && frame.timestampMs - windowAt >= 300) CapturePerf.measure("save") { saveCandidate() }
    if (capMessage == null && policy.complete && !done && session.segments.size <= photoAllowance) complete()
    if (BuildConfig.DEBUG && frame.timestampMs - lastDiagnosticAt > 2000) {
      lastDiagnosticAt = frame.timestampMs
      Log.d("ReceiptCapture", "located=${doc.located} sides=${doc.hasSides} sharp=${doc.sharp} tracked=${tracked != null} " +
        "full=${doc.hasFullOutline} turn=${frame.vision.rotation} similarity=${doc.supportsTopSimilarity} geometryPresent=${result.geometry != null} geometry=${result.geometry?.confirmed == true} source=${position?.source} anchors=${tracker.retainedAnchorCount} top=${policy.top != null} bottom=${policy.bottom != null} connected=${policy.connected} qr=${policy.key != null} segments=${session.segments.size} " +
        "bad=${doc.quality.filter { it.hasContent && !it.usable }.map { it.band to it.score }} qrArea=${frame.qrPolygon}")
    }
    if (!done) publish(lastState)
  }

  private fun reachCap() {
    candidate?.close(); candidate = null
    capMessage = if (existingPhotos == 0) "Limite de ${ReceiptStore.MAX_PHOTOS} imagens por nota atingido. Saia e escolha guardar o código."
      else "Esta nota já tem $existingPhotos imagens, e o limite é ${ReceiptStore.MAX_PHOTOS}. Saia e escolha guardar o código."
    lastState = lastState.copy(error = capMessage)
  }
  private fun saveCandidate() {
    if (session.segments.size >= photoAllowance) {
      // Identified after this candidate was picked: it no longer fits.
      candidate?.close(); candidate = null
      if (!policy.complete) reachCap()
      return
    }
    val selected = candidate ?: return
    candidate = null
    selected.use { c ->
      val name = "capture-${session.id}-${session.segments.size}"
      val image = File(sessions.dir(session.id), "$name.jpg")
      val maskFile = File(sessions.dir(session.id), "$name.mask.png")
      var kept = false
      try {
        writeBitmap(c.bitmap, image, Bitmap.CompressFormat.JPEG)
        val imageBands = c.document.usableBands
        val legible = validateCapturedSegment(image, c.mask, imageBands, c.qr, legibility, listOfNotNull(c.document.top, c.document.bottom))
        // Credit only what OCR could read; the rest of the frame stays uncovered (D37).
        val credited = imageBands.zip(c.tracked.bands).flatMap { (imageBand, worldBand) ->
          intersectBands(listOf(imageBand), legible).map { restrictBand(imageBand, worldBand, it, c.tracked.axisReversed) }
        }.let(::mergeBands)
        if (credited.isEmpty()) {
          illegibleAt = frameAt
          if (BuildConfig.DEBUG) Log.d("ReceiptCapture", "candidateRejected=illegible top=${c.tracked.top != null} bottom=${c.tracked.bottom != null}")
          return
        }
        val maskBitmap = Bitmap.createBitmap(c.mask.cols(), c.mask.rows(), Bitmap.Config.ARGB_8888)
        try { Utils.matToBitmap(c.mask, maskBitmap); writeBitmap(maskBitmap, maskFile, Bitmap.CompressFormat.PNG) }
        finally { maskBitmap.recycle() }
        val segment = CaptureSegment(image.name, maskFile.name, c.document.polygon.map { listOf(it.x, it.y) },
          credited.map { listOf(it.start, it.end) }, c.tracked.top, c.tracked.bottom,
          c.document.score, System.currentTimeMillis(), session.id)
        session = session.copy(segments = session.segments + segment)
        sessions.write(session)
        kept = true
        policy.credit(credited, c.tracked.top, c.tracked.bottom, c.document.score)
        if (BuildConfig.DEBUG) Log.d("ReceiptCapture", "segmentAccepted bands=$credited of ${c.tracked.bands} top=${c.tracked.top} bottom=${c.tracked.bottom}")
        lastState = lastState.copy(segmentCount = session.segments.size, top = policy.top != null,
          bottom = policy.bottom != null, connected = policy.connected)
      } finally { if (!kept) { image.delete(); maskFile.delete() } }
    }
  }
  private fun complete() {
    lastState = lastState.copy(saving = true)
    publish(lastState)
    session = session.copy(committing = true, segments = session.segments.sortedBy { segment -> segment.bands.minOfOrNull { it[0] } ?: 0.0 })
    sessions.write(session)
    runBlocking { receipts.importCapture(session, sessions.dir(session.id)) }
    sessions.remove(session)
    candidate?.close(); candidate = null
    done = true
    enqueueUpload()
    lastState = lastState.copy(saving = false, completed = true, connected = true, qr = true)
    publish(lastState)
  }
  fun exit(choice: ExitChoice) {
    if (done) return
    if (choice == ExitChoice.KEEP_CODE && session.chave != null) {
      runBlocking { receipts.open(session.chave!!, session.qrUrl) }
      enqueueUpload()
    }
    candidate?.close(); candidate = null
    sessions.remove(session)
    done = true
    lastState = lastState.copy(exited = true)
    publish(lastState)
  }
  override fun close() {
    try {
      // Navigation/process cleanup conserves only an identified code, never provisional photos.
      if (!done) exit(ExitChoice.KEEP_CODE)
    } finally { tracker.close(); legibility.close() }
  }
}
