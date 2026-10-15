package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.core.Mat

internal data class OcrLine(val center: DocumentPoint, val confidence: Double)

/**
 * Legibility of a saved candidate, decided by on-device OCR (D37). The gradient score in [DocumentVision.quality]
 * can't tell legible from illegible across text sizes, so it only pre-filters; a band counts as covered here only
 * when every OCR line read inside it is confident. Runs once per candidate, never per analysis frame.
 */
internal class TextLegibility(
  private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : AutoCloseable {
  /** Blocking; call off the main thread. Lines outside the paper mask (another receipt, the table) are ignored. */
  fun read(bitmap: Bitmap, paperMask: Mat): List<OcrLine> {
    val upright = read(bitmap, paperMask, 0)
    // The canonical image can be upside down (reversed axis, D31). Only then is a second pass worth its cost.
    return if (upright.any { it.confidence >= MIN_CONFIDENCE }) upright else read(bitmap, paperMask, 180).takeIf {
      it.count { line -> line.confidence >= MIN_CONFIDENCE } > upright.count { line -> line.confidence >= MIN_CONFIDENCE }
    } ?: upright
  }
  private fun read(bitmap: Bitmap, paperMask: Mat, rotation: Int): List<OcrLine> {
    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)))
    return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
      val box = line.boundingBox ?: return@mapNotNull null
      // Boxes come in the rotated (upright) frame; map them back to the bitmap's.
      val x = box.exactCenterX().toDouble() / bitmap.width; val y = box.exactCenterY().toDouble() / bitmap.height
      val center = if (rotation == 180) DocumentPoint(1 - x, 1 - y) else DocumentPoint(x, y)
      val row = (center.y * paperMask.rows()).toInt().coerceIn(0, paperMask.rows() - 1)
      val col = (center.x * paperMask.cols()).toInt().coerceIn(0, paperMask.cols() - 1)
      if (paperMask.get(row, col)[0] > 0) OcrLine(center, line.confidence.toDouble()) else null
    }
  }
  override fun close() = recognizer.close()

  companion object {
    /** Synthetic benchmark: no illegible band accepted at 0.8 (capture-validation.md, D37). */
    const val MIN_CONFIDENCE = .8

    /**
     * Bands passing both the gradient pre-filter and OCR. A content band needs at least one OCR line and all of its
     * lines confident. A band whose only content is the decoded QR passes: the QR is its evidence. Blank paper only
     * bridges when the same image proved some text legible. A band holding a paper end ([ends]) with no OCR line is
     * treated as blank: the coarse mask can include a sliver of table there, which looks like content but isn't print.
     */
    fun legibleBands(quality: List<RegionQuality>, lines: List<OcrLine>, qr: List<DocumentPoint>,
      ends: List<Double> = emptyList()): List<CaptureBand> {
      val qrSpan = if (qr.size >= 4) CaptureBand(qr.minOf { it.y }, qr.maxOf { it.y }) else null
      fun overlapsQr(band: CaptureBand) = qrSpan != null && qrSpan.start < band.end && qrSpan.end > band.start
      var anyText = false
      val verdicts = quality.map { q ->
        val inside = lines.filter { it.center.y >= q.band.start && it.center.y < q.band.end }
        val legibleText = inside.isNotEmpty() && inside.all { it.confidence >= MIN_CONFIDENCE }
        if (q.usable && q.hasContent && legibleText) anyText = true
        q to when {
          !q.usable -> false
          !q.hasContent -> null // blank: decided below
          inside.isEmpty() && overlapsQr(q.band) -> true
          inside.isEmpty() && ends.any { it >= q.band.start && it < q.band.end } -> null
          else -> legibleText
        }
      }
      // Only text OCR actually read lets blank paper bridge; a QR alone proves nothing about the print (D27/D28).
      return mergeBands(verdicts.filter { (_, ok) -> ok == true || (ok == null && anyText) }.map { it.first.band })
    }
  }
}
