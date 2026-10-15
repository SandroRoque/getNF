package io.github.sandroroque.getnf.ui.scan

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sandroroque.getnf.data.CaptureSessionStore
import io.github.sandroroque.getnf.data.ReceiptStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class CaptureEngineTest {
  @get:Rule val tmp = TemporaryFolder()
  private val key = "42260611222333000181651090000012341123456789"
  private fun longReceipt(): Bitmap {
    val result = Bitmap.createBitmap(900, 3000, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(Color.rgb(55, 70, 75)); paint.color = Color.rgb(242, 242, 236)
    canvas.drawRect(180f, 70f, 720f, 2930f, paint)
    paint.color = Color.BLACK; paint.textSize = 27f; paint.typeface = Typeface.MONOSPACE
    val random = java.util.Random(25)
    for (i in 0..75) canvas.drawText("ITEM ${i.toString().padStart(3, '0')} ${100000 + random.nextInt(899999)} ${10 + random.nextInt(80)},50", 200f, 130f + i * 36, paint)
    return result
  }
  private fun blurred(bitmap: Bitmap): Bitmap {
    DocumentVision.ensureLoaded()
    val input = Mat(); val output = Mat()
    return try {
      Utils.bitmapToMat(bitmap, input); Imgproc.GaussianBlur(input, output, Size(41.0, 41.0), 8.0)
      Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
    } finally { input.release(); output.release() }
  }
  @Test fun blurredOverviewThenSharpTopZoomCapturesOnlyTheSharpPart() {
    val receipts = ReceiptStore(tmp.newFolder("receipts")); val sessions = CaptureSessionStore(tmp.newFolder("sessions"))
    var state = CaptureSessionState(); var uploads = 0
    val engine = CaptureEngine(sessions, receipts, { state = it }, { uploads++ })
    val original = longReceipt(); val overview = blurred(original)
    try {
      DocumentVision.fromBitmap(overview).use { vision ->
        assertTrue("The blurred overview must retain physical edges: ${vision.observation}", vision.observation.hasFullOutline)
        assertFalse("Blurred content cannot count as a photo", vision.observation.sharp)
        val packet = ReceiptFrame(VisionFrame(vision.gray.clone(), vision.mask.clone(), vision.observation), null, null, emptyList(),
          0, FrameMapping(900, 3000, 0, Matrix(), Matrix()), overview.copy(Bitmap.Config.ARGB_8888, false))
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertEquals(0, state.segmentCount); assertFalse(state.top); assertFalse(state.bottom); assertFalse(state.connected)
      repeat(7) { i ->
        val bitmap = Bitmap.createBitmap(original, 0, 0, 900, 1200).copy(Bitmap.Config.ARGB_8888, false)
        val packet = ReceiptFrame(DocumentVision.fromBitmap(bitmap), key, "https://example.invalid/",
          listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
          (i + 1) * 200L, FrameMapping(900, 1200, 0, Matrix(), Matrix()), bitmap)
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertTrue("Top zoom must confirm reference: $state", state.geometryKnown)
      assertTrue("Top zoom must save a sharp segment: $state", state.segmentCount > 0)
      assertTrue(state.top); assertFalse(state.bottom); assertFalse(state.connected); assertFalse(state.completed)
      assertEquals(0, uploads)
    } finally { engine.close(); original.recycle(); overview.recycle() }
  }
  @Test fun unanchoredFullFrameCannotSaveAndBlockRecovery() {
    val receipts = ReceiptStore(tmp.newFolder()); val sessions = CaptureSessionStore(tmp.newFolder())
    var state = CaptureSessionState()
    val engine = CaptureEngine(sessions, receipts, { state = it }, { }); val original = longReceipt()
    try {
      repeat(4) { i ->
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, false)
        val detected = DocumentVision.fromBitmap(bitmap)
        val observation = detected.observation.copy(
          polygon = listOf(DocumentPoint(.2, .1), DocumentPoint(.8, .1), DocumentPoint(.8, .9), DocumentPoint(.2, .9)),
          quality = listOf(RegionQuality(CaptureBand(0.0, 1.0), 2.0, true, true)))
        val blank = org.opencv.core.Mat(detected.gray.size(), org.opencv.core.CvType.CV_8UC1, org.opencv.core.Scalar(180.0))
        val packet = ReceiptFrame(VisionFrame(blank, detected.mask.clone(), observation), null, null, emptyList(),
          i * 200L, FrameMapping(900, 3000, 0, Matrix(), Matrix()), bitmap)
        detected.close()
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertEquals("No anchor means no candidate or saved photo", 0, state.segmentCount)
      repeat(6) { i ->
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, false)
        val packet = ReceiptFrame(DocumentVision.fromBitmap(bitmap), key, "https://example.invalid/",
          listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
          (i + 4) * 200L, FrameMapping(900, 3000, 0, Matrix(), Matrix()), bitmap)
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertTrue("A usable overview must recover before any photo: $state", state.completed)
      assertEquals(1, state.segmentCount)
    } finally { engine.close(); original.recycle() }
  }
  @Test fun sidewaysPaperCompletesWithOneCanonicalPhoto() {
    for (angle in listOf(-90f, 90f)) {
      val receipts = ReceiptStore(tmp.newFolder()); val sessions = CaptureSessionStore(tmp.newFolder())
      var state = CaptureSessionState()
      val engine = CaptureEngine(sessions, receipts, { state = it }, { }); val original = longReceipt()
      val turned = Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(angle) }, true)
      try {
        repeat(6) { i ->
          val vision = DocumentVision.fromBitmap(turned)
          val canonical = Bitmap.createBitmap(turned, 0, 0, turned.width, turned.height,
            Matrix().apply { postRotate(vision.rotation.toFloat()) }, true).copy(Bitmap.Config.ARGB_8888, false)
          val packet = ReceiptFrame(vision, key, "https://example.invalid/",
            listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
            i * 200L, FrameMapping(turned.width, turned.height, 0, Matrix(), Matrix(), vision.rotation), canonical)
          try { engine.observe(packet) } finally { packet.close() }
        }
        assertTrue("Sideways paper should finish: $state", state.completed); assertEquals(1, state.segmentCount)
        val receipt = runBlocking { receipts.get(key) }!!
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(java.io.File(receipts.dir(key), receipt.photos.single()).path, options)
        assertEquals(900, options.outWidth); assertEquals(3000, options.outHeight)
      } finally { engine.close(); original.recycle(); turned.recycle() }
    }
  }
  @Test fun fullSharpDocumentWithQrCompletesWithOnePhoto() {
    val receipts = ReceiptStore(tmp.newFolder("receipts")); val sessions = CaptureSessionStore(tmp.newFolder("sessions"))
    var state = CaptureSessionState(); var uploads = 0
    val engine = CaptureEngine(sessions, receipts, { state = it }, { uploads++ }); val original = longReceipt()
    try {
      repeat(6) { i ->
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, false)
        val packet = ReceiptFrame(DocumentVision.fromBitmap(bitmap), key, "https://example.invalid/",
          listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
          i * 200L, FrameMapping(900, 3000, 0, Matrix(), Matrix()), bitmap)
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertTrue("Full sharp document should complete: $state", state.completed)
      assertEquals(1, state.segmentCount); assertEquals(1, uploads)
      assertEquals(1, runBlocking { receipts.get(key) }!!.photos.size)
    } finally { engine.close(); original.recycle() }
  }
  @Test fun knownFullShapeCannotFillABlurredContentGap() {
    val receipts = ReceiptStore(tmp.newFolder("receipts")); val sessions = CaptureSessionStore(tmp.newFolder("sessions"))
    var state = CaptureSessionState()
    val engine = CaptureEngine(sessions, receipts, { state = it }, { }); val original = longReceipt()
    try {
      repeat(6) { i ->
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, false)
        val detected = DocumentVision.fromBitmap(bitmap)
        val observation = detected.observation.copy(quality = detected.observation.quality.map { q ->
          if (q.band.start in .4.. .6) q.copy(usable = false) else q
        })
        val packet = ReceiptFrame(VisionFrame(detected.gray.clone(), detected.mask.clone(), observation), key, "https://example.invalid/",
          listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
          i * 200L, FrameMapping(900, 3000, 0, Matrix(), Matrix()), bitmap)
        detected.close()
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertTrue(state.geometryKnown); assertTrue(state.top); assertTrue(state.bottom)
      assertFalse("Known geometry must not fill unvalidated text", state.connected); assertFalse(state.completed)
    } finally { engine.close(); original.recycle() }
  }
  @Test fun scansLongReceiptAutomaticallyAndReadsQrLast() {
    val root = tmp.newFolder("receipts"); val receipts = ReceiptStore(root)
    val sessions = CaptureSessionStore(tmp.newFolder("sessions"))
    var state = CaptureSessionState(); var uploads = 0
    val engine = CaptureEngine(sessions, receipts, { state = it }, { uploads++ })
    val original = longReceipt(); var time = 0L
    val history = StringBuilder()
    try {
      for (y in 0..1800 step 100) for (repeat in 0 until if (y == 1800) 6 else 3) {
        if (state.completed) break
        val cropped = Bitmap.createBitmap(original, 0, y, 900, 1200)
        val bitmap = cropped.copy(Bitmap.Config.ARGB_8888, false)
        if (cropped !== original) cropped.recycle()
        val vision = DocumentVision.fromBitmap(bitmap)
        val qr = y == 1800
        val packet = ReceiptFrame(vision, if (qr) key else null, if (qr) "https://example.invalid/" else null,
          if (qr) listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)) else emptyList(),
          time, FrameMapping(900, 1200, 0, Matrix(), Matrix()), bitmap)
        try { engine.observe(packet) } finally { packet.close() }
        history.append("y=$y top=${state.top} bottom=${state.bottom} count=${state.segmentCount}; ")
        time += 200
      }
      assertTrue("Long capture must complete: $state history=$history", state.completed)
      assertTrue("Must save multiple segments", state.segmentCount >= 3)
      assertEquals(1, uploads)
      val receipt = runBlocking { receipts.get(key) }
      assertNotNull(receipt); assertEquals(state.segmentCount, receipt!!.photos.size)
      assertTrue(receipt.photos.all { java.io.File(receipts.dir(key), it).exists() })
      assertTrue(receipt.captureSegments.all { java.io.File(receipts.dir(key), it.mask).exists() })
    } finally { engine.close(); original.recycle() }
  }
  @Test fun jumpingFromTopToBottomCannotCompleteUntilTheGapIsScanned() {
    val receipts = ReceiptStore(tmp.newFolder("receipts")); val sessions = CaptureSessionStore(tmp.newFolder("sessions"))
    var state = CaptureSessionState()
    val engine = CaptureEngine(sessions, receipts, { state = it }, { })
    val original = longReceipt(); var time = 0L
    fun show(y: Int, frames: Int) {
      repeat(frames) {
        val bitmap = Bitmap.createBitmap(original, 0, y, 900, 1200).copy(Bitmap.Config.ARGB_8888, false)
        val packet = ReceiptFrame(DocumentVision.fromBitmap(bitmap), key, "https://example.invalid/",
          listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
          time, FrameMapping(900, 1200, 0, Matrix(), Matrix()), bitmap)
        try { engine.observe(packet) } finally { packet.close() }
        time += 200
      }
    }
    try {
      show(0, 6); show(1800, 6)
      assertFalse("A jump without overlap must not close coverage: $state", state.completed)
      // Return to known content, then cover the missing part in the opposite direction.
      for (y in 1800 downTo 0 step 300) if (!state.completed) show(y, 3)
      assertFalse("Previously unregistered bottom must remain pending", state.completed)
      for (y in 0..1800 step 100) if (!state.completed) show(y, if (y == 1800) 6 else 3)
      assertTrue("Reacquisition followed by connected coverage must complete: $state", state.completed)
    } finally { engine.close(); original.recycle() }
  }
  @Test fun exitingWithQrKeepsCodeButDropsSessionImages() {
    val receipts = ReceiptStore(tmp.newFolder("receipts")); val sessions = CaptureSessionStore(tmp.newFolder("sessions"))
    var state = CaptureSessionState()
    val engine = CaptureEngine(sessions, receipts, { state = it }, { })
    val original = longReceipt()
    try {
      repeat(4) { i ->
        val bitmap = Bitmap.createBitmap(original, 0, 500, 900, 1200).copy(Bitmap.Config.ARGB_8888, false)
        val packet = ReceiptFrame(DocumentVision.fromBitmap(bitmap), key, "https://example.invalid/",
          listOf(DocumentPoint(.4, .6), DocumentPoint(.6, .6), DocumentPoint(.6, .8), DocumentPoint(.4, .8)),
          i * 200L, FrameMapping(900, 1200, 0, Matrix(), Matrix()), bitmap)
        try { engine.observe(packet) } finally { packet.close() }
      }
      assertTrue(state.qr); assertFalse(state.completed)
      engine.exit(ExitChoice.KEEP_CODE)
      val receipt = runBlocking { receipts.get(key) }
      assertNotNull(receipt); assertTrue(receipt!!.photos.isEmpty())
      assertTrue(state.exited)
    } finally { engine.close(); original.recycle() }
  }
}
