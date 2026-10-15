package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * Recheck the encoded pixels using the same paper mask, never a bounding rectangle or whole-scene score, and read
 * them with OCR. Returns the parts of [expected] that are legible in the stored JPEG (D37); empty rejects the photo.
 */
internal fun validateCapturedSegment(file: File, smallMask: Mat, expected: List<CaptureBand>, qr: List<DocumentPoint>,
  legibility: TextLegibility, ends: List<Double> = emptyList()): List<CaptureBand> {
  val bitmap = BitmapFactory.decodeFile(file.path) ?: return emptyList()
  val rgba = Mat(); val gray = Mat(); val mask = Mat()
  return try {
    Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.resize(smallMask, mask, gray.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
    val quality = DocumentVision.quality(gray, mask, qr)
    val lines = CapturePerf.measure("ocr") { legibility.read(bitmap, mask) }
    val legible = TextLegibility.legibleBands(quality, lines, qr, ends)
    if (io.github.sandroroque.getnf.BuildConfig.DEBUG) android.util.Log.d("ReceiptCapture", "ocr lines=${lines.size} legible=$legible " +
      "rejected=${quality.filter { q -> legible.none { it.start <= q.band.start + 1e-9 && it.end >= q.band.end - 1e-9 } }.map { q ->
        "%.3f-%.3f usable=%s content=%s conf=%s".format(q.band.start, q.band.end, q.usable, q.hasContent,
          lines.filter { it.center.y >= q.band.start && it.center.y < q.band.end }.map { "%.2f".format(it.confidence) })
      }}")
    intersectBands(expected, legible)
  } finally { bitmap.recycle(); rgba.release(); gray.release(); mask.release() }
}

internal fun intersectBands(a: List<CaptureBand>, b: List<CaptureBand>): List<CaptureBand> = mergeBands(a.flatMap { x ->
  b.mapNotNull { y -> val start = maxOf(x.start, y.start); val end = minOf(x.end, y.end); if (end > start) CaptureBand(start, end) else null }
})

/** Maps a sub-band of [image] onto [world] (the same band in receipt coordinates), linearly. */
internal fun restrictBand(image: CaptureBand, world: CaptureBand, sub: CaptureBand, reversed: Boolean): CaptureBand {
  val a = (sub.start - image.start) / image.length; val b = (sub.end - image.start) / image.length
  return if (!reversed) CaptureBand(world.start + a * world.length, world.start + b * world.length)
  else CaptureBand(world.end - b * world.length, world.end - a * world.length)
}

internal fun writeBitmap(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
  FileOutputStream(file).use { out ->
    check(bitmap.compress(format, if (format == Bitmap.CompressFormat.JPEG) 92 else 100, out)) { "Could not encode capture" }
    out.fd.sync()
  }
}
