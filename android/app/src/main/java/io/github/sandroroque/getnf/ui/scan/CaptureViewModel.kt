package io.github.sandroroque.getnf.ui.scan

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.sync.SyncWorker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One capture's lifetime: the camera's frames through OCR on one thread ([executor]), and into a [CaptureController] on
 * another, so the next frame's OCR runs while the session takes the last (D88). A frame waiting for the session is
 * replaced by a newer one.
 */
internal class CaptureViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as GetNFApp
  val executor = Executors.newSingleThreadExecutor()
  private val sessionThread = Executors.newSingleThreadExecutor()
  private class Pending(val frame: CameraFrame, val lines: List<OcrQuad>, val codes: List<Pair<String, List<DocumentPoint>>>)
  private val pending = AtomicReference<Pending?>(null)
  private val attached = AtomicBoolean(false)
  private val closing = AtomicBoolean(false)
  private val _state = MutableStateFlow(CaptureUiState())
  val state = _state.asStateFlow()
  private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private val qr = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
  private val controller by lazy {
    // Debug builds dump the frames analysed when files/capture-dump/enabled exists (to replay a session on the PC).
    val dump = java.io.File(app.filesDir, "capture-dump").takeIf { BuildConfig.DEBUG && java.io.File(it, "enabled").exists() }
      ?.let { java.io.File(it, java.time.LocalDateTime.now().toString().replace(':', '-').take(19)) }
    CaptureController(app.captureSessions, app.receiptStore, { _state.value = it }, { SyncWorker.enqueue(app) }, dump)
  }
  val analyzer = IngestAnalyzer(text, qr, executor, { attached.get() && !closing.get() }, { frame, lines, codes ->
    // A frame already waiting is dropped for this newer one; only when none was waiting is a run scheduled.
    if (pending.getAndSet(Pending(frame, lines, codes)) == null) sessionThread.execute(::drain)
  }, ::report)

  private fun drain() {
    val next = pending.getAndSet(null) ?: return
    if (closing.get()) return
    try { controller.offer(next.frame, next.lines, next.codes) } catch (failure: Exception) { report(failure) }
  }

  fun setAttached(value: Boolean) { attached.set(value) }

  fun exit(choice: ExitChoice) {
    attached.set(false)
    sessionThread.execute { try { controller.exit(choice) } catch (failure: Exception) { report(failure) } }
  }

  private fun report(failure: Throwable) {
    Log.w("ReceiptCapture", "Capture processing failed", failure)
    val detail = if (BuildConfig.DEBUG) " (${failure::class.simpleName}: ${failure.message})" else ""
    _state.value = _state.value.copy(saving = false, error = "Não foi possível concluir a operação. As fotos anteriores estão preservadas.$detail")
  }

  override fun onCleared() {
    closing.set(true); attached.set(false)
    analyzer.closeWhenIdle {
      sessionThread.execute {
        try { controller.close() } finally { text.close(); qr.close(); executor.shutdown(); sessionThread.shutdown() }
      }
    }
  }
}
