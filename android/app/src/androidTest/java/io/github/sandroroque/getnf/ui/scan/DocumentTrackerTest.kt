package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class DocumentTrackerTest {
  private fun blank(full: Boolean = true, top: Boolean = true, tapered: Boolean = false): VisionFrame {
    DocumentVision.ensureLoaded()
    val q = if (tapered) listOf(DocumentPoint(.3, .1), DocumentPoint(.7, .1), DocumentPoint(.9, .9), DocumentPoint(.1, .9))
      else if (full) listOf(DocumentPoint(.2, .1), DocumentPoint(.8, .1), DocumentPoint(.8, .9), DocumentPoint(.2, .9))
      else listOf(DocumentPoint(.1, if (top) .1 else 0.0), DocumentPoint(.9, if (top) .1 else 0.0), DocumentPoint(.9, 1.0), DocumentPoint(.1, 1.0))
    val edges = mutableListOf(EdgeSegment(EdgeKind.LEFT, q[0], q[3]), EdgeSegment(EdgeKind.RIGHT, q[1], q[2]))
    if (top) edges += EdgeSegment(EdgeKind.TOP, q[0], q[1])
    if (full) edges += EdgeSegment(EdgeKind.BOTTOM, q[3], q[2])
    return VisionFrame(Mat(720, 540, CvType.CV_8UC1, Scalar(180.0)), Mat(720, 540, CvType.CV_8UC1, Scalar(255.0)),
      DocumentObservation(q, edges, .8, if (top) q.take(2).map { it.y }.average() else null,
        if (full) q.drop(2).map { it.y }.average() else null))
  }
  private fun textReceipt(): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
    val c = Canvas(bitmap); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawColor(Color.rgb(55, 70, 75)); p.color = Color.WHITE; c.drawRect(180f, 70f, 720f, 1130f, p)
    p.color = Color.BLACK; p.textSize = 27f; p.typeface = Typeface.MONOSPACE
    val random = java.util.Random(17)
    for (i in 0..25) c.drawText("ITEM ${i.toString().padStart(2, '0')} ${100000 + random.nextInt(899999)} 12,50", 200f, 130f + i * 36, p)
    return bitmap
  }
  @Test fun featurelessOverviewBootstrapsZoomByTopWithoutInventingCoverage() {
    val tracker = DocumentTracker()
    try {
      blank().use {
        val first = tracker.track(it, 0)
        assertNotNull(first.geometry); assertFalse(first.geometry!!.confirmed)
        assertEquals(LocalizationSource.FULL_OUTLINE, first.position!!.source)
        assertTrue(first.position!!.bands.isEmpty())
      }
      blank(full = false).use {
        val pending = tracker.track(it, 200)
        assertNotNull(pending.geometry); assertNull(pending.position)
        val zoomed = tracker.track(it, 400)
        assertTrue(zoomed.geometry!!.confirmed)
        assertEquals(LocalizationSource.TOP_EDGE, zoomed.position!!.source)
        assertEquals(0.0, zoomed.position!!.top!!, .001)
        assertNull(zoomed.position!!.bottom); assertTrue(zoomed.position!!.bands.isEmpty())
        assertEquals(256.0 * (576.0 / 324.0), zoomed.geometry!!.height, .001)
      }
      blank(full = false, top = false).use {
        assertNull(tracker.track(it, 600).position)
        assertTrue(tracker.geometry!!.confirmed)
      }
      blank(full = false).use {
        assertNull(tracker.track(it, 800).position)
        assertNotNull(tracker.track(it, 1000).position)
      }
    } finally { tracker.close() }
  }
  @Test fun strongPerspectiveCannotUseTopWithoutContentMatches() {
    val tracker = DocumentTracker()
    try {
      blank(tapered = true).use { assertNotNull(tracker.track(it, 0).geometry) }
      blank(full = false).use {
        assertNull(tracker.track(it, 200).position); assertNull(tracker.track(it, 400).position)
      }
      blank(tapered = true).use { assertNotNull(tracker.track(it, 600).position) }
    } finally { tracker.close() }
  }
  @Test fun unsavedUnusableContentCanAnchorAFrameWithoutVisibleEnds() {
    val tracker = DocumentTracker(); val original = textReceipt()
    val center = Bitmap.createBitmap(original, 112, 150, 675, 900)
    try {
      DocumentVision.fromBitmap(original).use { full ->
        val bad = VisionFrame(full.gray.clone(), full.mask.clone(), full.observation.copy(quality = full.observation.quality.map { it.copy(usable = false) }))
        bad.use {
          assertFalse(it.observation.sharp)
          val result = tracker.track(it, 0)
          assertTrue(result.position!!.bands.isEmpty()); assertNotNull(result.geometry)
        }
      }
      DocumentVision.fromBitmap(center).use { frame ->
        assertTrue("Center localization: confidence=${frame.observation.confidence} polygon=${frame.observation.polygon}", frame.observation.located)
        assertNull(frame.observation.top); assertNull(frame.observation.bottom)
        val result = tracker.track(frame, 200)
        assertEquals("An anchor must exist without selecting/saving any photo", LocalizationSource.CONTENT, result.position?.source)
        val retained = tracker.retainedAnchorCount
        repeat(4) { i -> assertNotNull(tracker.track(frame, (i + 2) * 200L).position) }
        assertEquals("Stationary zoom must not fill the anchor cache", retained, tracker.retainedAnchorCount)
      }
    } finally { tracker.close(); original.recycle(); center.recycle() }
  }
  @Test fun laterOverviewDoesNotRebaseAlreadyRegisteredStrip() {
    val tracker = DocumentTracker(); val original = textReceipt(); val center = Bitmap.createBitmap(original, 112, 150, 675, 900)
    try {
      val first = DocumentVision.fromBitmap(center).use {
        assertTrue("Initial center: confidence=${it.observation.confidence} sharp=${it.observation.sharp} polygon=${it.observation.polygon}", it.observation.located && it.observation.sharp)
        tracker.track(it, 0)
      }
      assertNull(first.geometry); assertNotNull(first.position)
      val overview = DocumentVision.fromBitmap(original).use { tracker.track(it, 200) }
      assertNotNull(overview.geometry); assertTrue(overview.geometry!!.outline.take(2).map { it.y }.average() < 0)
      assertEquals(first.position!!.center, overview.position!!.center, 3.0)
      val again = DocumentVision.fromBitmap(center).use { tracker.track(it, 400) }
      assertEquals(first.position!!.center, again.position!!.center, 3.0)
    } finally { tracker.close(); original.recycle(); center.recycle() }
  }
  @Test fun unanchoredPartialCannotPoisonTheInitialReference() {
    val tracker = DocumentTracker(); val original = textReceipt()
    try {
      blank(full = false).use { frame ->
        val observation = frame.observation.copy(quality = listOf(RegionQuality(CaptureBand(.1, 1.0), 2.0, true, true)))
        VisionFrame(frame.gray.clone(), frame.mask.clone(), observation).use { assertNull(tracker.track(it, 0).position) }
      }
      assertEquals(0, tracker.retainedAnchorCount)
      DocumentVision.fromBitmap(original).use { assertNotNull(tracker.track(it, 200).position) }
      assertTrue(tracker.retainedAnchorCount > 0)
    } finally { tracker.close(); original.recycle() }
  }
  @Test fun unanchoredOverviewCanBeReplacedOnlyBeforeAnyPhoto() {
    val original = textReceipt()
    for (allowReset in listOf(false, true)) {
      val tracker = DocumentTracker()
      try {
        blank().use { tracker.track(it, 0) }
        val height = tracker.geometry!!.height
        DocumentVision.fromBitmap(original).use { frame ->
          val result = tracker.track(frame, 200, allowReset)
          if (allowReset) {
            assertNotNull(result.position); assertNotEquals(height, tracker.geometry!!.height, 5.0)
            assertTrue(tracker.retainedAnchorCount > 0)
          } else { assertNull(result.position); assertEquals(height, tracker.geometry!!.height, .001) }
        }
      } finally { tracker.close() }
    }
    original.recycle()
  }
  @Test fun rotatingThePaperPreservesTheExistingWorldAndEnds() {
    for (angle in listOf(-90f, 90f)) {
      val tracker = DocumentTracker(); val original = textReceipt()
      val turned = Bitmap.createBitmap(original, 0, 0, original.width, original.height,
        android.graphics.Matrix().apply { postRotate(angle) }, true)
      try {
        val first = DocumentVision.fromBitmap(original).use { tracker.track(it, 0) }
        val second = DocumentVision.fromBitmap(turned).use { tracker.track(it, 200) }
        assertEquals("Orientation changes require content registration", LocalizationSource.CONTENT, second.position?.source)
        assertEquals(first.position!!.top!!, second.position!!.top!!, 3.0)
        assertEquals(first.position!!.bottom!!, second.position!!.bottom!!, 3.0)
        assertEquals(first.position!!.center, second.position!!.center, 3.0)
      } finally { tracker.close(); original.recycle(); turned.recycle() }
    }
  }

}
