package io.github.sandroroque.getnf.ui.scan

import android.graphics.Matrix

/** Continuous image coordinates (pixel edges), shared by QR, bitmap and preview transforms. */
internal object ImageCoordinates {
  fun rotated(point: DocumentPoint, degrees: Int): DocumentPoint = when ((degrees % 360 + 360) % 360) {
    90 -> DocumentPoint(1 - point.y, point.x)
    180 -> DocumentPoint(1 - point.x, 1 - point.y)
    270 -> DocumentPoint(point.y, 1 - point.x)
    else -> point
  }
  fun inverseRotation(width: Int, height: Int, degrees: Int): Matrix = Matrix().apply {
    setValues(when ((degrees % 360 + 360) % 360) {
      90 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, height.toFloat(), 0f, 0f, 1f)
      180 -> floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
      270 -> floatArrayOf(0f, -1f, width.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
      else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    })
  }
}
