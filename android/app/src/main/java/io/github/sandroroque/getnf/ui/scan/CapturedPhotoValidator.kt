package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/** Recheck the encoded pixels using the same paper mask, never a bounding rectangle or whole-scene score. */
internal fun validateCapturedSegment(file: File, smallMask: Mat, expected: List<CaptureBand>, qr: List<DocumentPoint>): Boolean {
  val bitmap = BitmapFactory.decodeFile(file.path) ?: return false
  val rgba = Mat(); val gray = Mat(); val mask = Mat()
  return try {
    Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.resize(smallMask, mask, gray.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
    val valid = mergeBands(DocumentVision.quality(gray, mask, qr).filter { it.usable }.map { it.band })
    expected.all { band -> valid.any { it.start <= band.start + .001 && it.end >= band.end - .001 } }
  } finally { bitmap.recycle(); rgba.release(); gray.release(); mask.release() }
}

internal fun writeBitmap(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
  FileOutputStream(file).use { out ->
    check(bitmap.compress(format, if (format == Bitmap.CompressFormat.JPEG) 92 else 100, out)) { "Could not encode capture" }
    out.fd.sync()
  }
}
