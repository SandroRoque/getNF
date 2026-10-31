package br.com.roqlab.caderneta.benchmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frame analysis branches, timed on the phone over recorded capture frames (camera plan, step 0). Each measured
 * iteration analyses the next frame of the set, so the numbers are per frame across real receipts, not one image.
 * `ocr` and the scanners are the branches; `frame*` runs OCR and a scanner side by side, as the capture does: the
 * analysis period. `*Products` adds the product barcode formats (EAN-13, EAN-8, UPC), to see what one camera costs.
 *
 * The frames aren't in the APK (they're real receipts): tools/device-bench.sh pushes them to the test app's own
 * files/frames/ after installing it.
 */
@RunWith(AndroidJUnit4::class)
class FrameAnalysisBenchmark {
  @get:Rule val benchmark = BenchmarkRule()

  @Test fun ocr() = each { Tasks.await(text.process(it)) }
  @Test fun scanQr() = each { Tasks.await(qr.process(it)) }
  @Test fun scanQrProducts() = each { Tasks.await(qrAndProducts.process(it)) }
  @Test fun frame() = each { Tasks.await(Tasks.whenAllComplete(text.process(it), qr.process(it))) }
  @Test fun frameProducts() = each { Tasks.await(Tasks.whenAllComplete(text.process(it), qrAndProducts.process(it))) }

  private fun each(analyse: (InputImage) -> Unit) {
    assumeTrue("no frames pushed (run tools/device-bench.sh)", frames.isNotEmpty())
    var i = 0
    benchmark.measureRepeated { analyse(frames[i++ % frames.size]) }
  }

  companion object {
    private lateinit var frames: List<InputImage>
    private lateinit var text: com.google.mlkit.vision.text.TextRecognizer
    private lateinit var qr: BarcodeScanner
    private lateinit var qrAndProducts: BarcodeScanner

    @BeforeClass @JvmStatic fun load() {
      val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "frames")
      // As NV21 bytes, the way camera frames reach ML Kit, so its own conversion is part of the time as in the app.
      frames = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }.map { f ->
        val bitmap = BitmapFactory.decodeFile(f.path)
        try { InputImage.fromByteArray(nv21(bitmap), bitmap.width, bitmap.height, 0, InputImage.IMAGE_FORMAT_NV21) }
        finally { bitmap.recycle() }
      }
      text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
      qr = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
      qrAndProducts = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE,
        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build())
      // The models load on first use: not part of any frame's time.
      frames.firstOrNull()?.let { Tasks.await(text.process(it)); Tasks.await(qr.process(it)); Tasks.await(qrAndProducts.process(it)) }
    }

    @AfterClass @JvmStatic fun close() {
      if (::text.isInitialized) { text.close(); qr.close(); qrAndProducts.close() }
    }

    /** BT.601 full range, as camera frames are; even width and height (the dumps' 1440×1920 are). */
    private fun nv21(bitmap: Bitmap): ByteArray {
      val w = bitmap.width and 1.inv(); val h = bitmap.height and 1.inv()
      val argb = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
      val out = ByteArray(w * h * 3 / 2)
      var uv = w * h
      for (y in 0 until h) for (x in 0 until w) {
        val c = argb[y * w + x]
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        out[y * w + x] = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255).toByte()
        if (y % 2 == 0 && x % 2 == 0) {
          out[uv++] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255).toByte() // V
          out[uv++] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255).toByte() // U
        }
      }
      return out
    }
  }
}
