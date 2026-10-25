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
data class PhotoText(val photo: String, val width: Int, val height: Int, val lines: List<TextLine>)

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
