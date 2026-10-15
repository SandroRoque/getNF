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
    /**
     * Median OCR line confidence a band needs. Real thermal receipts read at 0.5–0.85 per line (legible JPEGs had
     * image medians 0.57–0.72; the motion-blurred one produced no line at all), so the synthetic 0.8 was far too
     * strict (D38).
     */
    const val MIN_CONFIDENCE = .5

    private enum class Verdict { LEGIBLE, ILLEGIBLE, BLANK, UNREAD }

    /**
     * Bands passing both the gradient pre-filter and OCR. A band with OCR lines needs their median confidence
     * >= [MIN_CONFIDENCE]. Content OCR couldn't read at all (a logo, the QR, a sliver of table at a paper end, a gap
     * between items) passes only between legible text bands of the same image, or holding the QR or a paper end.
     * Blank paper bridges only when the image has legible text. An image without legible text credits nothing.
     */
    fun legibleBands(quality: List<RegionQuality>, lines: List<OcrLine>, qr: List<DocumentPoint>,
      ends: List<Double> = emptyList()): List<CaptureBand> {
      val qrSpan = if (qr.size >= 4) CaptureBand(qr.minOf { it.y }, qr.maxOf { it.y }) else null
      fun overlapsQr(band: CaptureBand) = qrSpan != null && qrSpan.start < band.end && qrSpan.end > band.start
      val verdicts = quality.map { q ->
        val inside = lines.filter { it.center.y >= q.band.start && it.center.y < q.band.end }.map { it.confidence }.sorted()
        when {
          !q.usable -> Verdict.ILLEGIBLE
          inside.isNotEmpty() -> if (inside[inside.size / 2] >= MIN_CONFIDENCE) Verdict.LEGIBLE else Verdict.ILLEGIBLE
          !q.hasContent -> Verdict.BLANK
          else -> Verdict.UNREAD
        }
      }
      val text = verdicts.indices.filter { verdicts[it] == Verdict.LEGIBLE }
      if (text.isEmpty()) return emptyList()
      return mergeBands(quality.indices.filter { i ->
        val band = quality[i].band
        when (verdicts[i]) {
          Verdict.LEGIBLE, Verdict.BLANK -> true
          Verdict.ILLEGIBLE -> false
          Verdict.UNREAD -> overlapsQr(band) || ends.any { it >= band.start && it < band.end } || (text.first() < i && i < text.last())
        }
      }.map { quality[it].band })
    }
  }
}
