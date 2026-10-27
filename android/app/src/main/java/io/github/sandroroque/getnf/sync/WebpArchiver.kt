package io.github.sandroroque.getnf.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import io.github.sandroroque.getnf.data.CaptureSegment
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The archive copy of a receipt photo (D61): cropped to the receipt's outline when the capture recorded one, grey,
 * WebP, with what shouldn't leave the phone blanked ([blanks]: the card slip, a CPF, the operator, D98). Discounts are read from the full-quality original on the phone before this; the copy is for a person looking
 * back. On a real 4096×3072 photo (5.7 MB) a receipt crop came to ~390 KB at quality 70, and the discount lines
 * stayed clearly legible.
 */
class WebpArchiver(private val quality: Int = 70, private val blanks: (File) -> List<Blank>) : PhotoArchiver {
  override fun archive(photo: File, segment: CaptureSegment?): ByteArray {
    val source = upright(photo, BitmapFactory.decodeFile(photo.path) ?: error("can't decode ${photo.name}"))
    try {
      val crop = segment?.polygon?.takeIf { it.size >= 3 }?.let { crop(it, source.width, source.height) }
        ?: android.graphics.Rect(0, 0, source.width, source.height)
      val grey = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
      try {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        val canvas = Canvas(grey)
        canvas.drawBitmap(source, crop, android.graphics.Rect(0, 0, crop.width(), crop.height()), paint)
        // Read from the photo itself (upright, like the source here); a photo that can't be read isn't uploaded.
        val black = Paint().apply { color = android.graphics.Color.BLACK }
        for (b in blanks(photo)) canvas.drawPath(android.graphics.Path().apply {
          b.corners.forEachIndexed { i, (x, y) -> val px = (x - crop.left).toFloat(); val py = (y - crop.top).toFloat(); if (i == 0) moveTo(px, py) else lineTo(px, py) }
          close()
        }, black)
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        check(grey.compress(format, quality, out)) { "could not encode ${photo.name}" }
        return out.toByteArray()
      } finally { grey.recycle() }
    } finally { source.recycle() }
  }

  /** The outline's bounding box (normalized to the image) with a small margin, so no printed edge is lost. */
  private fun crop(polygon: List<List<Double>>, width: Int, height: Int): android.graphics.Rect? {
    val xs = polygon.map { it[0] }; val ys = polygon.map { it[1] }
    val margin = .02
    val left = (max(0.0, xs.min() - margin) * width).roundToInt(); val right = (min(1.0, xs.max() + margin) * width).roundToInt()
    val top = (max(0.0, ys.min() - margin) * height).roundToInt(); val bottom = (min(1.0, ys.max() + margin) * height).roundToInt()
    return android.graphics.Rect(left, top, right, bottom).takeIf { it.width() > 16 && it.height() > 16 }
  }

  /** Older photos came from the camera app with an EXIF rotation; capture photos are written upright already. */
  private fun upright(file: File, bitmap: Bitmap): Bitmap {
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_ROTATE_90 -> 90f
      ExifInterface.ORIENTATION_ROTATE_180 -> 180f
      ExifInterface.ORIENTATION_ROTATE_270 -> 270f
      else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
      .also { if (it !== bitmap) bitmap.recycle() }
  }
}
