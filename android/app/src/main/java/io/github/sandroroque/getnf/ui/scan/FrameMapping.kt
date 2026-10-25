package io.github.sandroroque.getnf.ui.scan

import android.graphics.Matrix

/**
 * From a point in the analysed frame (normalized, upright, then turned by [analysisRotation]) to the preview's view
 * coordinates, through the camera buffer and the sensor. Null until the preview's transform is known.
 */
internal data class FrameMapping(val width: Int, val height: Int, val rotation: Int, val bufferToSensor: Matrix, val sensorToView: Matrix?, val analysisRotation: Int = 0) {
  fun map(point: DocumentPoint): DocumentPoint? {
    val target = sensorToView ?: return null
    val uprightW = if (rotation % 180 == 0) width else height
    val uprightH = if (rotation % 180 == 0) height else width
    val canonicalW = if (analysisRotation % 180 == 0) uprightW else uprightH
    val canonicalH = if (analysisRotation % 180 == 0) uprightH else uprightW
    val p = floatArrayOf((point.x * canonicalW).toFloat(), (point.y * canonicalH).toFloat())
    val canonicalToBuffer = Matrix().apply { setConcat(ImageCoordinates.inverseRotation(width, height, rotation),
      ImageCoordinates.inverseRotation(uprightW, uprightH, analysisRotation)) }
    val bufferToView = Matrix().apply { setConcat(target, bufferToSensor) }
    val analysisToView = Matrix().apply { setConcat(bufferToView, canonicalToBuffer) }
    analysisToView.mapPoints(p)
    return DocumentPoint(p[0].toDouble(), p[1].toDouble())
  }
}
