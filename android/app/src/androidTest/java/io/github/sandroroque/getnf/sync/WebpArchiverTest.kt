package io.github.sandroroque.getnf.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandroroque.getnf.data.CaptureSegment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** D61's archive copy: cropped to the receipt outline, grey, WebP, and much smaller than the JPEG. */
@RunWith(AndroidJUnit4::class)
class WebpArchiverTest {
  private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

  /** A 3072×4096 photo: a white "receipt" with lines of text in the middle third, on a coloured, noisy background. */
  private fun photo(): File {
    val bitmap = Bitmap.createBitmap(3072, 4096, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val random = java.util.Random(1)
    val noise = Paint()
    for (i in 0 until 4000) { noise.color = Color.rgb(random.nextInt(256), random.nextInt(256), 60); canvas.drawCircle(random.nextFloat() * 3072, random.nextFloat() * 4096, 30f, noise) }
    canvas.drawRect(1024f, 0f, 2048f, 4096f, Paint().apply { color = Color.WHITE })
    val text = Paint().apply { color = Color.BLACK; textSize = 40f; isAntiAlias = true }
    for (y in 60 until 4096 step 60) canvas.drawText("7896014400287 POLPA BRASFRUT 100G 4 UN 3,49", 1040f, y.toFloat(), text)
    return File(dir, "archiver-test.jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }; bitmap.recycle() }
  }

  @Test fun aCaptureIsCroppedToItsOutlineGreyAndSmall() {
    val jpeg = photo()
    val segment = CaptureSegment("p.jpg", "p.mask.png", listOf(listOf(1 / 3.0, 0.0), listOf(2 / 3.0, 0.0), listOf(2 / 3.0, 1.0), listOf(1 / 3.0, 1.0)),
      emptyList(), score = 1.0, capturedAt = 0, sessionId = "s")
    val webp = WebpArchiver { emptyList() }.archive(jpeg, segment)
    val out = BitmapFactory.decodeByteArray(webp, 0, webp.size)
    // The outline is the middle third, plus a 2% margin on each side.
    assertEquals(1024 + 2 * 61, out.width, 2f)
    assertEquals(4096, out.height)
    val sample = out.getPixel(out.width / 4, out.height / 3)
    assertTrue("grey: r=g=b", Color.red(sample) == Color.green(sample) && Color.green(sample) == Color.blue(sample))
    assertTrue("${webp.size} bytes vs ${jpeg.length()}", webp.size * 5 < jpeg.length())
  }

  @Test fun aPhotoWithoutAnOutlineIsKeptWhole() {
    val webp = WebpArchiver { emptyList() }.archive(photo(), null)
    val out = BitmapFactory.decodeByteArray(webp, 0, webp.size)
    assertEquals(3072 to 4096, out.width to out.height)
  }

  @Test fun whatIsPrivateIsBlankedInTheCopy() {
    // A box on the receipt (photo pixels 1100–1900 × 1000–1100) comes out black, offset by the crop; the rest doesn't.
    val segment = CaptureSegment("p.jpg", "p.mask.png", listOf(listOf(1 / 3.0, 0.0), listOf(2 / 3.0, 0.0), listOf(2 / 3.0, 1.0), listOf(1 / 3.0, 1.0)),
      emptyList(), score = 1.0, capturedAt = 0, sessionId = "s")
    val webp = WebpArchiver { listOf(Blank(listOf(1100.0 to 1000.0, 1900.0 to 1000.0, 1900.0 to 1100.0, 1100.0 to 1100.0))) }.archive(photo(), segment)
    val out = BitmapFactory.decodeByteArray(webp, 0, webp.size)
    val left = 1024 - 61
    for (x in listOf(1110, 1500, 1890)) assertTrue("blanked at $x", Color.red(out.getPixel(x - left, 1050)) < 30)
    assertTrue("the paper elsewhere stays light", Color.red(out.getPixel(1500 - left, 1300)) > 120 || Color.red(out.getPixel(1500 - left, 1330)) > 120)
  }

  private fun assertEquals(expected: Int, actual: Int, tolerance: Float) =
    assertTrue("expected $expected±$tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
