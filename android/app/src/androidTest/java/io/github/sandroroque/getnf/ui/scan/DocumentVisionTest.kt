package io.github.sandroroque.getnf.ui.scan

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class DocumentVisionTest {
  private fun receipt(top: Float = 70f, bottom: Float = 1130f, texture: Boolean = false, lightBackground: Boolean = false): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(if (lightBackground) Color.rgb(253, 253, 253) else Color.rgb(65, 75, 80))
    if (texture) {
      paint.color = Color.BLACK
      for (y in 0 until 1200 step 12) for (x in 0 until 900 step 12) if ((x + y) % 24 == 0) canvas.drawRect(x.toFloat(), y.toFloat(), x + 6f, y + 6f, paint)
    }
    paint.color = Color.rgb(240, 240, 235); canvas.drawRect(180f, top, 720f, bottom, paint)
    paint.color = Color.BLACK; paint.textSize = 27f; paint.typeface = Typeface.MONOSPACE
    for (line in 0..25) canvas.drawText("PRODUTO ${line.toString().padStart(2, '0')} TESTE 12,50", 200f, 130f + line * 36, paint)
    return bitmap
  }
  private fun gray(bitmap: Bitmap): Mat {
    DocumentVision.ensureLoaded()
    val rgba = Mat(); val result = Mat()
    try { Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, result, Imgproc.COLOR_RGBA2GRAY); return result } finally { rgba.release() }
  }
  @Test fun detectsFullAndPartialReceiptWithoutFourCorners() {
    for ((top, bottom) in listOf(70f to 1130f, -100f to 1300f)) {
      val bitmap = receipt(top, bottom)
      try { DocumentVision.fromBitmap(bitmap).use { frame ->
        assertTrue("Must detect side edges: ${frame.observation}", frame.observation.hasSides)
        assertTrue("Must locate paper: ${frame.observation}", frame.observation.located)
        assertTrue("Printed content must be sharp: ${frame.observation.quality}", frame.observation.sharp)
        if (top < 0) { assertNull(frame.observation.top); assertNull(frame.observation.bottom) }
        else { assertNotNull("Top edge missing: ${frame.observation}", frame.observation.top); assertNotNull("Bottom edge missing: ${frame.observation}", frame.observation.bottom) }
      } } finally { bitmap.recycle() }
    }
  }
  @Test fun narrowReceiptWithWideTableMarginsStillLocatesAndMeasuresItsOwnText() {
    val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(Color.rgb(70, 75, 80)); paint.color = Color.WHITE
    canvas.drawRect(350f, 70f, 550f, 1130f, paint)
    paint.color = Color.BLACK; paint.textSize = 25f; paint.typeface = Typeface.MONOSPACE
    for (i in 0..25) canvas.drawText("${i.toString().padStart(2, '0')} 12,50", 365f, 130f + i * 36, paint)
    try { DocumentVision.fromBitmap(bitmap).use { frame ->
      assertTrue("Narrow paper must be located: ${frame.observation}", frame.observation.located)
      assertTrue(frame.observation.sharp)
      assertFalse(frame.observation.contains(.1, .5)); assertFalse(frame.observation.contains(.9, .5))
      assertTrue(frame.observation.polygon.maxOf { it.x } - frame.observation.polygon.minOf { it.x } < .25)
    } } finally { bitmap.recycle() }
  }
  @Test fun detectsEndpointsWhenPaperIsDarkerThanTheTable() {
    val bitmap = receipt(lightBackground = true)
    try { DocumentVision.fromBitmap(bitmap).use {
      assertTrue(it.observation.located)
      assertNotNull(it.observation.top); assertNotNull(it.observation.bottom)
    } } finally { bitmap.recycle() }
  }
  @Test fun tiltedReceiptKeepsItsSlantedEndsAndInteriorMask() {
    val original = receipt(); val tilted = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(tilted); canvas.drawColor(Color.rgb(65, 75, 80)); canvas.rotate(8f, 450f, 600f)
    canvas.drawBitmap(original, 0f, 0f, null)
    try { DocumentVision.fromBitmap(tilted).use {
      assertTrue(it.observation.located); assertTrue(it.observation.sharp)
      assertNotNull(it.observation.top); assertNotNull(it.observation.bottom)
      assertTrue(kotlin.math.abs(it.observation.polygon[0].y - it.observation.polygon[1].y) > .03)
      assertFalse(it.observation.contains(.05, .05))
    } } finally { original.recycle(); tilted.recycle() }
  }
  @Test fun backgroundCannotImproveReceiptSharpness() {
    val plain = receipt(); val textured = receipt(texture = true)
    val a = gray(plain); val b = gray(textured); val mask = Mat.zeros(a.size(), CvType.CV_8UC1)
    try {
      Imgproc.rectangle(mask, Point(185.0, 75.0), Point(715.0, 1125.0), Scalar(255.0), Imgproc.FILLED)
      val qa = DocumentVision.quality(a, mask); val qb = DocumentVision.quality(b, mask)
      assertEquals(qa.map { it.usable }, qb.map { it.usable })
      qa.zip(qb).forEach { (x, y) -> assertEquals(x.score, y.score, .00001) }
    } finally { plain.recycle(); textured.recycle(); a.release(); b.release(); mask.release() }
  }
  @Test fun faintSharpTextIsNotClassifiedAsDefocus() {
    val bitmap = receipt(); val source = gray(bitmap); val mask = Mat.zeros(source.size(), CvType.CV_8UC1)
    try {
      source.convertTo(source, -1, .08, 220.0)
      Imgproc.rectangle(mask, Point(185.0, 75.0), Point(715.0, 1125.0), Scalar(255.0), Imgproc.FILLED)
      val content = DocumentVision.quality(source, mask).filter { it.hasContent }
      assertTrue(content.isNotEmpty()); assertTrue("Sharp faint content must pass: $content", content.all { it.usable })
    } finally { bitmap.recycle(); source.release(); mask.release() }
  }
  @Test fun blurredPaperOnSharpBackgroundIsNotApproved() {
    val bitmap = receipt(texture = true); val source = gray(bitmap)
    val mask = Mat.zeros(source.size(), CvType.CV_8UC1); val blurred = Mat()
    try {
      Imgproc.rectangle(mask, Point(180.0, 70.0), Point(720.0, 1130.0), Scalar(255.0), Imgproc.FILLED)
      Imgproc.GaussianBlur(source, blurred, Size(19.0, 19.0), 5.0)
      blurred.copyTo(source, mask)
      val quality = DocumentVision.quality(source, mask)
      assertTrue("Blurred text regions must be rejected: $quality", quality.any { it.hasContent && !it.usable })
      assertFalse(quality.filter { it.hasContent }.all { it.usable })
    } finally { bitmap.recycle(); source.release(); mask.release(); blurred.release() }
  }
  private fun scene(background: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(background); draw(canvas, paint); return bitmap
  }
  private fun printed(canvas: Canvas, paint: Paint, paper: Int, left: Float, top: Float, right: Float, bottom: Float) {
    paint.color = paper; canvas.drawRect(left, top, right, bottom, paint)
    paint.color = Color.rgb(40, 40, 40); paint.textSize = 24f; paint.typeface = Typeface.MONOSPACE
    var y = top + 50
    while (y < bottom - 20) { canvas.drawText("ITEM 12 ARROZ 5KG 24,90", left + 15, y, paint); y += 34 }
  }
  @Test fun phoneShadowOnAWhiteTableIsNotPaper() {
    // Live test, D39: the phone's rectangular shadow on a light table was located as the receipt.
    val bitmap = scene(Color.rgb(236, 236, 232)) { c, p ->
      p.color = Color.rgb(150, 150, 148); p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
      c.drawRect(260f, 200f, 640f, 1000f, p); p.maskFilter = null
    }
    try { DocumentVision.fromBitmap(bitmap).use { assertFalse("A flat shadow is not paper: ${it.observation}", it.observation.located) } }
    finally { bitmap.recycle() }
  }
  @Test fun receiptOnAColouredBoardIsLocatedInsteadOfTheBoard() {
    // Live test, D39: a red board under the receipt became the mask.
    val bitmap = scene(Color.rgb(236, 236, 232)) { c, p ->
      p.color = Color.rgb(190, 35, 40); c.drawRect(120f, 80f, 780f, 1120f, p)
      p.color = Color.rgb(120, 20, 25); for (y in 100 until 1100 step 60) c.drawCircle(150f, y.toFloat(), 12f, p)
      printed(c, p, Color.rgb(246, 246, 240), 260f, 150f, 640f, 1050f)
    }
    try { DocumentVision.fromBitmap(bitmap).use {
      assertTrue("The receipt must be located: ${it.observation}", it.observation.located)
      assertFalse("The red board is not paper", it.observation.contains(160.0 / 900, .5))
      assertTrue(it.observation.contains(.5, .5))
    } } finally { bitmap.recycle() }
  }
  @Test fun pastelReceiptsAreStillPaper() {
    // NFs are always light, but white, yellow, green or blue.
    for (paper in listOf(Color.rgb(250, 248, 240), Color.rgb(250, 244, 200), Color.rgb(212, 244, 212), Color.rgb(202, 224, 250))) {
      val bitmap = scene(Color.rgb(65, 75, 80)) { c, p -> printed(c, p, paper, 200f, 80f, 700f, 1120f) }
      try { DocumentVision.fromBitmap(bitmap).use { assertTrue("Paper ${Integer.toHexString(paper)} must be located: ${it.observation}", it.observation.located) } }
      finally { bitmap.recycle() }
    }
  }
  @Test fun emptySceneHasNoPaperMaskOrSharpness() {
    val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    try { DocumentVision.fromBitmap(bitmap).use { assertFalse(it.observation.located); assertFalse(it.observation.sharp) } }
    finally { bitmap.recycle() }
  }
}
