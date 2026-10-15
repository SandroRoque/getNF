package io.github.sandroroque.getnf.ui.scan

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Region proposals are supported by a boundary, not by the length of an arbitrary scene line. */
internal object PaperRegion {
  private data class Candidate(val contour: Array<Point>, val score: Double, val turn: Int)

  fun detect(source: Mat, evaluateQuality: Boolean): VisionFrame? {
    val gray = Mat(); val smooth = Mat(); val binary = Mat(); val edge = Mat()
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    try {
      val scale = min(1.0, 720.0 / max(source.cols(), source.rows()))
      Imgproc.resize(source, gray, Size(), scale, scale, Imgproc.INTER_AREA)
      Imgproc.GaussianBlur(gray, smooth, Size(5.0, 5.0), 1.0)
      val pixels = ByteArray(gray.rows() * gray.cols()).also { smooth.get(0, 0, it) }
      val proposals = mutableListOf<Candidate>()
      fun collect(input: Mat) {
        val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat(); val work = input.clone()
        try {
          Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
          for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(24)) {
            val area = Imgproc.contourArea(contour)
            if (area < gray.total() * .015 || area > gray.total() * .92) continue
            val curve = MatOfPoint2f(*contour.toArray()); val rect = try { Imgproc.minAreaRect(curve) } finally { curve.release() }
            val short = min(rect.size.width, rect.size.height); val long = max(rect.size.width, rect.size.height)
            if (short < 18 || area / (short * long) < .65) continue
            val bounds = contour.toArray()
            val support = boundarySupport(bounds, pixels, gray.cols(), gray.rows())
            if (support < .65) continue
            val corners = arrayOf(Point(), Point(), Point(), Point()); rect.points(corners)
            val axis = corners.indices.maxBy { i -> distance(corners[i], corners[(i + 1) % 4]) }
            val a = corners[axis]; val b = corners[(axis + 1) % 4]
            val clippedX = bounds.any { it.x <= 1 || it.x >= gray.cols() - 2 }
            val clippedY = bounds.any { it.y <= 1 || it.y >= gray.rows() - 2 }
            if (clippedX && clippedY) continue
            if (!clippedX && !clippedY && long / short < 1.2) continue
            // In a close partial view the visible patch can be wider than it is long.
            // Clipped ends identify its axis more reliably than the bounding-box aspect.
            val turn = if (clippedY) 0 else if (clippedX) 90 else if (abs(b.x - a.x) > abs(b.y - a.y)) 90 else 0
            if (short < (if (turn == 0) gray.cols() else gray.rows()) * .12) continue
            // Both lateral edges must be observed. A large background component touching
            // the side of the image is not the paper, even when it shares one strong edge.
            if (bounds.any { if (turn == 0) it.x <= 1 || it.x >= gray.cols() - 2 else it.y <= 1 || it.y >= gray.rows() - 2 }) continue
            proposals += Candidate(bounds, support * sqrt(area), turn)
          }
        } finally { contours.forEach { it.release() }; hierarchy.release(); work.release() }
      }
      Imgproc.threshold(smooth, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
      Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
      collect(binary)
      Core.bitwise_not(binary, binary); collect(binary)
      // Closed gradients retain a faint paper outline even on a lighter table.
      Imgproc.Canny(smooth, edge, 10.0, 30.0)
      Imgproc.morphologyEx(edge, edge, Imgproc.MORPH_CLOSE, kernel); collect(edge)
      val candidate = proposals.maxByOrNull { it.score } ?: return null
      val canonical = Mat(); val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
      val contour = MatOfPoint(*candidate.contour)
      try {
        Imgproc.drawContours(mask, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
        if (candidate.turn == 90) {
          Core.rotate(gray, canonical, Core.ROTATE_90_CLOCKWISE)
          Core.rotate(mask, mask, Core.ROTATE_90_CLOCKWISE)
        } else gray.copyTo(canonical)
        val paths = DocumentVision.maskContours(mask)
        val outline = paths.maxByOrNull { it.size } ?: return null
        val pts = MatOfPoint2f(*outline.map { Point(it.x * mask.cols(), it.y * mask.rows()) }.toTypedArray())
        val rectangle = try { Imgproc.minAreaRect(pts) } finally { pts.release() }
        // Contours describe pixel centres. Registration uses pixel-edge extents;
        // otherwise a one-pixel width error changes ORB sampling at every zoom level.
        rectangle.size.width += 1.0; rectangle.size.height += 1.0
        val corners = arrayOf(Point(), Point(), Point(), Point()); rectangle.points(corners)
        val sorted = corners.sortedBy { it.y }
        val top = sorted.take(2).sortedBy { it.x }; val bottom = sorted.drop(2).sortedByDescending { it.x }
        var q = (top + bottom).map { DocumentPoint((it.x / mask.cols()).coerceIn(0.0, 1.0), (it.y / mask.rows()).coerceIn(0.0, 1.0)) }
        val rawCurve = MatOfPoint2f(*outline.map { Point(it.x * mask.cols(), it.y * mask.rows()) }.toTypedArray())
        val four = MatOfPoint2f()
        val supportsSimilarity: Boolean
        try {
          Imgproc.approxPolyDP(rawCurve, four, Imgproc.arcLength(rawCurve, true) * .02, true)
          val vertices = four.toArray().sortedBy { it.y }
          val ordered = if (vertices.size == 4) vertices.take(2).sortedBy { it.x } + vertices.drop(2).sortedByDescending { it.x } else emptyList()
          val actual = ordered.map { DocumentPoint(it.x / mask.cols(), it.y / mask.rows()) }
          supportsSimilarity = ReceiptGeometry.isFrontFacing(ordered.map { DocumentPoint(it.x, it.y) })
          // A bounding rectangle must never erase perspective and enable a false similarity.
          if (actual.size == 4 && !supportsSimilarity) q = actual
        } finally { rawCurve.release(); four.release() }
        if (!ReceiptGeometry.validQuad(q)) return null
        val boundaryPixels = candidatePoints(mask)
        val touchesTop = boundaryPixels.any { it.y <= 1 }
        val touchesBottom = boundaryPixels.any { it.y >= mask.rows() - 2 }
        val segments = outline.indices.mapNotNull { i ->
          val a = outline[i]; val b = outline[(i + 1) % outline.size]
          val clipped = (a.x <= .003 && b.x <= .003) || (a.x >= .997 && b.x >= .997) ||
            (a.y <= .003 && b.y <= .003) || (a.y >= .997 && b.y >= .997)
          if (clipped) null else {
            val mid = DocumentPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
            val side = q.indices.minBy { j -> segmentDistance(mid, q[j], q[(j + 1) % 4]) }
            EdgeSegment(listOf(EdgeKind.TOP, EdgeKind.RIGHT, EdgeKind.BOTTOM, EdgeKind.LEFT)[side], a, b)
          }
        }
        // The boundary is visualized before safety erosion; focus never includes that uncertain rim.
        val safety = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try { Imgproc.erode(mask, mask, safety) } finally { safety.release() }
        val observation = DocumentObservation(q, segments, .8,
          if (touchesTop) null else q.take(2).map { it.y }.average(),
          if (touchesBottom) null else q.drop(2).map { it.y }.average(), brightness = Core.mean(canonical, mask).`val`[0], contour = outline, supportsTopSimilarity = supportsSimilarity)
        val native = Mat(); val nativeMask = Mat()
        try {
          if (candidate.turn == 90) Core.rotate(source, native, Core.ROTATE_90_CLOCKWISE) else source.copyTo(native)
          Imgproc.resize(mask, nativeMask, native.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
          return VisionFrame(canonical.clone(), mask.clone(), observation.copy(quality =
            if (evaluateQuality) DocumentVision.quality(native, nativeMask) else emptyList()), candidate.turn)
        } finally { native.release(); nativeMask.release() }
      } finally { contour.release(); canonical.release(); mask.release() }
    } finally { gray.release(); smooth.release(); binary.release(); edge.release(); kernel.release() }
  }

  private fun candidatePoints(mask: Mat): Array<Point> {
    val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat(); val work = mask.clone()
    return try {
      Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
      contours.maxByOrNull { Imgproc.contourArea(it) }?.toArray() ?: emptyArray()
    } finally { contours.forEach { it.release() }; hierarchy.release(); work.release() }
  }
  private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
  private fun segmentDistance(p: DocumentPoint, a: DocumentPoint, b: DocumentPoint): Double {
    val dx = b.x - a.x; val dy = b.y - a.y
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / max(1e-10, dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
  }
  private fun boundarySupport(contour: Array<Point>, pixels: ByteArray, w: Int, h: Int): Double {
    fun value(x: Double, y: Double): Int = pixels[y.roundToInt().coerceIn(0, h - 1) * w + x.roundToInt().coerceIn(0, w - 1)].toInt() and 255
    var tested = 0; var supported = 0
    for (i in contour.indices) {
      val a = contour[i]; val b = contour[(i + 1) % contour.size]; val length = distance(a, b)
      if (length < 2) continue
      val nx = -(b.y - a.y) / length * 4; val ny = (b.x - a.x) / length * 4
      for (j in 0..max(1, (length / 6).toInt())) {
        val t = j.toDouble() / max(1, (length / 6).toInt()); val x = a.x + t * (b.x - a.x); val y = a.y + t * (b.y - a.y)
        if (x < 5 || x >= w - 5 || y < 5 || y >= h - 5) continue
        tested++
        if (abs(value(x + nx, y + ny) - value(x - nx, y - ny)) >= 4) supported++
      }
    }
    return if (tested < 12) 0.0 else supported.toDouble() / tested
  }
}
