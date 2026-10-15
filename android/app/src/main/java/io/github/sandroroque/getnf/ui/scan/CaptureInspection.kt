package io.github.sandroroque.getnf.ui.scan

import android.graphics.*
import org.opencv.core.Mat

/** An in-memory view of one actual analysis frame; never exported or uploaded. */
internal object CaptureInspection {
  fun render(bitmap: Bitmap, observation: DocumentObservation, mask: Mat): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true); val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.argb(70, 0, 200, 255)
    val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    for (ring in DocumentVision.maskContours(mask)) {
      ring.forEachIndexed { i, p -> if (i == 0) path.moveTo((p.x * bitmap.width).toFloat(), (p.y * bitmap.height).toFloat())
        else path.lineTo((p.x * bitmap.width).toFloat(), (p.y * bitmap.height).toFloat()) }; path.close()
    }
    canvas.drawPath(path, paint)
    paint.color = Color.YELLOW; paint.strokeWidth = 3f
    observation.edges.forEach { e -> canvas.drawLine((e.start.x * bitmap.width).toFloat(), (e.start.y * bitmap.height).toFloat(),
      (e.end.x * bitmap.width).toFloat(), (e.end.y * bitmap.height).toFloat(), paint) }
    paint.color = Color.MAGENTA; paint.style = Paint.Style.STROKE
    val quad = Path(); observation.polygon.forEachIndexed { i, p ->
      if (i == 0) quad.moveTo((p.x * bitmap.width).toFloat(), (p.y * bitmap.height).toFloat()) else quad.lineTo((p.x * bitmap.width).toFloat(), (p.y * bitmap.height).toFloat())
    }; quad.close(); canvas.drawPath(quad, paint)
    return result
  }
}
