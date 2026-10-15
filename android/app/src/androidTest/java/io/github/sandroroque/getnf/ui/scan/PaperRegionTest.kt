package io.github.sandroroque.getnf.ui.scan

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class PaperRegionTest {
  private fun scene(): Pair<Bitmap, Bitmap> {
    DocumentVision.ensureLoaded()
    val image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val truth = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val c = Canvas(image); val t = Canvas(truth); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawColor(Color.rgb(35, 35, 40)); t.drawColor(Color.BLACK)
    p.color = Color.rgb(65, 65, 70); p.strokeWidth = 2f
    for (x in -300..900 step 11) c.drawLine(x.toFloat(), 0f, x + 260f, 1200f, p)
    p.color = Color.rgb(190, 25, 35); p.strokeWidth = 25f; c.drawLine(0f, 1150f, 900f, 1150f, p)
    val path = Path().apply {
      moveTo(260f, 100f); lineTo(290f, 115f); lineTo(620f, 100f); lineTo(650f, 150f)
      lineTo(640f, 400f); lineTo(655f, 700f); lineTo(630f, 1080f); lineTo(270f, 1090f)
      lineTo(250f, 1050f); lineTo(260f, 700f); lineTo(245f, 350f); close()
    }
    p.color = Color.rgb(235, 235, 225); p.style = Paint.Style.FILL; c.drawPath(path, p)
    p.color = Color.WHITE; t.drawPath(path, p)
    p.color = Color.BLACK; p.textSize = 23f; p.typeface = Typeface.MONOSPACE
    for (i in 0..24) c.drawText("ITEM $i 12,50", 290f, 180f + i * 33, p)
    return image to truth
  }
  @Test fun faintOpenStripWorksInBothOrientations() {
    DocumentVision.ensureLoaded()
    val image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val c = Canvas(image); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawColor(Color.rgb(253, 253, 253)); p.color = Color.rgb(240, 240, 240); c.drawRect(180f, 0f, 720f, 1200f, p)
    p.color = Color.BLACK; p.textSize = 27f; p.typeface = Typeface.MONOSPACE
    for (i in 0..28) c.drawText("ITEM ${i.toString().padStart(2, '0')} 12,50", 210f, 80f + i * 36, p)
    val side = Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(-90f) }, true)
    try {
      DocumentVision.fromBitmap(image).use { frame ->
        assertTrue("Faint partial paper must locate", frame.observation.located)
        assertTrue(frame.observation.sharp); assertNull(frame.observation.top); assertNull(frame.observation.bottom)
      }
      DocumentVision.fromBitmap(side).use { frame ->
        assertTrue("Sideways faint partial paper must locate", frame.observation.located)
        assertEquals(90, frame.rotation); assertTrue(frame.observation.sharp)
        assertNull(frame.observation.top); assertNull(frame.observation.bottom)
      }
    } finally { image.recycle(); side.recycle() }
  }
  @Test fun boundingRectangleCannotHideStrongPerspective() {
    DocumentVision.ensureLoaded()
    val image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val c = Canvas(image); val p = Paint(); c.drawColor(Color.DKGRAY); p.color = Color.WHITE
    c.drawPath(Path().apply { moveTo(300f, 80f); lineTo(600f, 80f); lineTo(760f, 1120f); lineTo(140f, 1120f); close() }, p)
    // Paper must look printed (D39).
    print(c, 330f, 140f, 570f, 1080f)
    try { DocumentVision.fromBitmap(image).use { frame ->
      assertTrue(frame.observation.located)
      assertFalse(frame.observation.supportsTopSimilarity)
      val tracker = DocumentTracker()
      try { assertFalse(tracker.track(frame, 0).geometry!!.frontFacing) } finally { tracker.close() }
    } } finally { image.recycle() }
  }
  @Test fun clippedEndsDetermineAxisEvenWhenVisiblePatchIsWide() {
    DocumentVision.ensureLoaded()
    val image = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
    val c = Canvas(image); val p = Paint()
    c.drawColor(Color.DKGRAY); p.color = Color.WHITE; c.drawRect(100f, 0f, 1100f, 600f, p)
    print(c, 140f, 20f, 1060f, 590f)
    val side = Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(-90f) }, true)
    try {
      val a = imageMat(image); val b = imageMat(side)
      try {
      PaperRegion.detect(a, false)?.use { frame ->
        assertEquals(0, frame.rotation); assertTrue(frame.observation.hasSides)
        assertNull(frame.observation.top); assertNull(frame.observation.bottom)
      } ?: fail("Wide partial region was not found")
      PaperRegion.detect(b, false)?.use { frame ->
        assertEquals(90, frame.rotation); assertTrue(frame.observation.hasSides)
        assertNull(frame.observation.top); assertNull(frame.observation.bottom)
      } ?: fail("Sideways partial region was not found")
      } finally { a.release(); b.release() }
    } finally { image.recycle(); side.recycle() }
  }
  private fun print(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f; typeface = Typeface.MONOSPACE }
    c.save(); c.clipRect(left, top, right, bottom)
    var y = top + 24
    while (y < bottom) { c.drawText("ITEM 07 FEIJAO 1KG 8,49 ".repeat(4), left, y, p); y += 34 }
    c.restore()
  }
  private fun imageMat(bitmap: Bitmap): Mat {
    val rgba = Mat(); val gray = Mat()
    try { org.opencv.android.Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); return gray }
    finally { rgba.release() }
  }
  @Test fun textureAndStrongStripeAloneAreNotPaper() {
    DocumentVision.ensureLoaded()
    val image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val c = Canvas(image); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawColor(Color.rgb(35, 35, 40)); p.color = Color.rgb(65, 65, 70); p.strokeWidth = 2f
    for (x in -300..900 step 11) c.drawLine(x.toFloat(), 0f, x + 260f, 1200f, p)
    p.color = Color.RED; p.strokeWidth = 25f; c.drawLine(0f, 1150f, 900f, 1150f, p)
    try { DocumentVision.fromBitmap(image).use { frame ->
      assertFalse("Background alone must not be located as paper", frame.observation.located)
      assertFalse(frame.observation.sharp)
    } } finally { image.recycle() }
  }
  @Test fun irregularPaperOnDiagonalTextureExcludesTheBackground() {
    val (image, truth) = scene()
    try { DocumentVision.fromBitmap(image).use { frame ->
      assertTrue("Paper not located: ${frame.observation}", frame.observation.located)
      assertTrue(frame.observation.sharp)
      assertTrue("An irregular boundary must not be replaced by four lines", frame.observation.contour.size > 4)
      val rgba = Mat(); val gray = Mat(); val resized = Mat(); val outside = Mat(); val leak = Mat(); val core = Mat(); val covered = Mat()
      val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
      try {
        org.opencv.android.Utils.bitmapToMat(truth, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.resize(gray, resized, frame.mask.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        Core.compare(resized, Scalar(250.0), outside, Core.CMP_LT); Core.bitwise_and(outside, frame.mask, leak)
        assertEquals("No table pixels may enter the focus mask", 0, Core.countNonZero(leak))
        Imgproc.erode(resized, core, kernel); Core.bitwise_and(core, frame.mask, covered)
        assertTrue("Useful paper must be preserved", Core.countNonZero(covered) >= Core.countNonZero(core) * .95)
        assertFalse(frame.observation.contains(.1, .5)); assertTrue(frame.observation.contains(.5, .5))
      } finally { listOf(rgba, gray, resized, outside, leak, core, covered, kernel).forEach { it.release() } }
    } } finally { image.recycle(); truth.recycle() }
  }
  @Test fun sidewaysPaperHasCanonicalMaskAndQuality() {
    val (upright, truth) = scene()
    val side = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(-90f) }, true)
    try { DocumentVision.fromBitmap(side).use { frame ->
      assertEquals(90, frame.rotation); assertTrue(frame.observation.located); assertTrue(frame.observation.sharp)
      assertNotNull(frame.observation.top); assertNotNull(frame.observation.bottom)
      assertTrue(frame.gray.rows() > frame.gray.cols()); assertTrue(frame.observation.contour.size > 4)
    } } finally { upright.recycle(); truth.recycle(); side.recycle() }
  }
  @Test fun exactFocusMaskExcludesQrAndFilterNeighbourhood() {
    DocumentVision.ensureLoaded()
    val paper = Mat.zeros(200, 100, CvType.CV_8UC1); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    try {
      Imgproc.rectangle(paper, Point(10.0, 10.0), Point(90.0, 190.0), Scalar(255.0), Imgproc.FILLED)
      val qr = listOf(DocumentPoint(.3, .6), DocumentPoint(.7, .6), DocumentPoint(.7, .8), DocumentPoint(.3, .8))
      val mask = DocumentVision.focusMask(paper, qr)
      try {
        assertEquals(0.0, mask.get(140, 50)[0], 0.0); assertEquals(0.0, mask.get(100, 12)[0], 0.0)
        assertEquals(255.0, mask.get(70, 50)[0], 0.0)
        assertTrue(DocumentVision.maskContours(mask).size >= 2)
      } finally { mask.release() }
    } finally { paper.release(); kernel.release() }
  }
}
