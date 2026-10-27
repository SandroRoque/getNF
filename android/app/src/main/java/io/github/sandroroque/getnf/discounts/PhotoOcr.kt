package io.github.sandroroque.getnf.discounts

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.serialization.Serializable

/**
 * One line ML Kit read, with its box in the upright photo's pixels; [corners], when recorded, are the line's own
 * corners (x, y pairs from its top-left, clockwise), which follow the line when the receipt is turned (D82).
 */
@Serializable
data class TextLine(
  val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float,
  val corners: List<Int>? = null,
) {
  val centerY get() = (top + bottom) / 2
  val height get() = bottom - top
}

/** Everything read from one photo. */
@Serializable
data class PhotoText(val photo: String, val width: Int, val height: Int, val lines: List<TextLine>) {
  /**
   * The direction the print runs in the photo, in radians (0 = left to right, positive = turned clockwise on the image):
   * the median of the lines' own, from their corners as OCR gives them (left to right as read). Null without corners.
   */
  fun printAngle(): Double? {
    val angles = lines.mapNotNull { l ->
      l.corners?.takeIf { it.size == 8 }?.let { c -> kotlin.math.atan2((c[3] + c[5] - c[1] - c[7]) / 2.0, (c[2] + c[4] - c[0] - c[6]) / 2.0) }
    }.sorted()
    return angles.getOrNull(angles.size / 2)
  }

  /**
   * The same lines turned so the print runs left to right and down (D101): readers that look "above" or "on the same
   * line" by the boxes' top and bottom would otherwise go across the receipt when it was photographed sideways. The
   * print's direction is the median of the lines' own (their corners, left to right as read); without corners, or
   * already upright, it's unchanged. Coordinates are in a turned frame; only their relations mean anything.
   */
  fun alongPrint(): PhotoText {
    val angle = printAngle() ?: return this
    if (kotlin.math.abs(angle) < Math.toRadians(3.0)) return this
    val cos = kotlin.math.cos(-angle); val sin = kotlin.math.sin(-angle)
    fun turn(x: Int, y: Int) = listOf((x * cos - y * sin).toInt(), (x * sin + y * cos).toInt())
    return copy(lines = lines.map { l ->
      val c = l.corners?.takeIf { it.size == 8 } ?: listOf(l.left, l.top, l.right, l.top, l.right, l.bottom, l.left, l.bottom)
      val t = c.chunked(2).flatMap { (x, y) -> turn(x, y) }
      val xs = t.filterIndexed { i, _ -> i % 2 == 0 }; val ys = t.filterIndexed { i, _ -> i % 2 == 1 }
      l.copy(left = xs.min(), top = ys.min(), right = xs.max(), bottom = ys.max(), corners = t)
    })
  }
}

/**
 * On-device OCR of a saved receipt photo (D54), with ML Kit's bundled Latin model (already used for legibility, D37).
 * The photo's EXIF orientation is applied, so boxes are in the upright image.
 */
class PhotoOcr(
  private val context: Context,
  private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : AutoCloseable {
  /** Blocking; call off the main thread. */
  fun read(file: File): PhotoText {
    val image = InputImage.fromFilePath(context, Uri.fromFile(file))
    val text = Tasks.await(recognizer.process(image))
    val upright = image.rotationDegrees % 180 == 0
    return PhotoText(
      file.name,
      if (upright) image.width else image.height,
      if (upright) image.height else image.width,
      text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val b = line.boundingBox ?: return@mapNotNull null
        TextLine(line.text, b.left, b.top, b.right, b.bottom, line.confidence, line.cornerPoints?.flatMap { listOf(it.x, it.y) })
      },
    )
  }

  override fun close() = recognizer.close()
}
