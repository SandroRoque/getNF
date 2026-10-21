package io.github.sandroroque.getnf.ui.scan

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Region proposals are supported by a boundary, not by the length of an arbitrary scene line. */
internal object PaperRegion {
  /** How far past its fitted line a side may reach, as a fraction of the receipt's width (D52). */
  private const val SIDE_TOLERANCE = .03
  /** A side is fitted only when this share of the rows lies on one straight line. */
  private const val MIN_SIDE_INLIERS = .3

  private data class Candidate(val contour: Array<Point>, val score: Double, val turn: Int, val area: Double, val source: String)

  fun detect(source: Mat, evaluateQuality: Boolean, chroma: Mat? = null): VisionFrame? {
    val gray = Mat(); val smooth = Mat(); val binary = Mat(); val edge = Mat(); val region = Mat()
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    try {
      val scale = min(1.0, 720.0 / max(source.cols(), source.rows()))
      Imgproc.resize(source, gray, Size(), scale, scale, Imgproc.INTER_AREA)
      Imgproc.GaussianBlur(gray, smooth, Size(5.0, 5.0), 1.0)
      val pixels = ByteArray(gray.rows() * gray.cols()).also { smooth.get(0, 0, it) }
      val proposals = mutableListOf<Candidate>()
      fun collect(input: Mat, source: String) {
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
            // A strong boundary isn't enough: a shadow or a coloured board under the receipt has one too (D39).
            region.setTo(Scalar(0.0)); Imgproc.drawContours(region, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
            if (!DocumentVision.paperLike(gray, region, chroma)) continue
            proposals += Candidate(bounds, support * sqrt(area), turn, area, source)
          }
        } finally { contours.forEach { it.release() }; hierarchy.release(); work.release() }
      }
      region.create(gray.size(), CvType.CV_8UC1)
      Imgproc.threshold(smooth, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
      Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
      collect(binary, "otsu")
      Core.bitwise_not(binary, binary); collect(binary, "otsu-inverted")
      // Closed gradients retain a faint paper outline even on a lighter table.
      Imgproc.Canny(smooth, edge, 10.0, 30.0)
      Imgproc.morphologyEx(edge, edge, Imgproc.MORPH_CLOSE, kernel); collect(edge, "canny")
      // A global threshold splits a receipt partly under the phone's shadow; dividing out slow illumination keeps
      // the shadowed end with the rest (live test, D40). The sharp paper/background step survives the division.
      val illumination = Mat(); val flat = Mat()
      try {
        Imgproc.GaussianBlur(smooth, illumination, Size(0.0, 0.0), 40.0)
        Core.divide(smooth, illumination, flat, 160.0, CvType.CV_8UC1)
        Imgproc.threshold(flat, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel); collect(binary, "flattened")
      } finally { illumination.release(); flat.release() }
      // A paper-like region well inside another (a receipt on a board, with a margin all round) is the receipt.
      // A region sharing the outer boundary is part of the same paper (its lit part, beside a shadow), and
      // near-equal areas are the same outline found by another threshold (D40).
      if (DocumentVision.DEBUG_PAPER) for (c in proposals) android.util.Log.v("PaperRegion",
        "proposal ${c.source} area=%.0f (%.2f of frame) score=%.1f turn=${c.turn}".format(c.area, c.area / gray.total(), c.score))
      var candidate = proposals.maxByOrNull { it.score } ?: return null
      while (true) {
        val outer = candidate
        val curve = MatOfPoint2f(*outer.contour)
        val inner = try {
          proposals.filter { inner -> inner.area in outer.area * .25..outer.area * .85 &&
            inner.contour.count { p -> Imgproc.pointPolygonTest(curve, p, true) >= 6.0 } >= inner.contour.size * .9 }.maxByOrNull { it.score }
        } finally { curve.release() }
        candidate = inner ?: break
      }
      if (DocumentVision.DEBUG_PAPER) android.util.Log.v("PaperRegion", "chosen ${candidate.source} area=%.0f".format(candidate.area))
      val canonical = Mat(); val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
      val contour = MatOfPoint(*candidate.contour)
      try {
        Imgproc.drawContours(mask, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
        trimLeaks(mask)
        straightenSides(mask, candidate.turn)
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
    } finally { gray.release(); smooth.release(); binary.release(); edge.release(); region.release(); kernel.release() }
  }

  /**
   * A receipt is a strip with straight sides; thin filaments of a patterned background that touch its edge leak into
   * the threshold region (live test, D41). An opening removes anything thinner than ~15 px at this scale; pixels of
   * the original within that distance of the opened shape come back, so the corners (which registration and the
   * top-edge model measure) stay sharp. Only the largest remaining piece is kept.
   */
  private fun trimLeaks(mask: Mat) {
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
    val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat(); val work = Mat(); val near = Mat()
    try {
      Imgproc.morphologyEx(mask, work, Imgproc.MORPH_OPEN, kernel)
      Imgproc.dilate(work, near, kernel)
      Core.bitwise_and(mask, near, mask)
      mask.copyTo(work)
      Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
      val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return
      mask.setTo(Scalar(0.0)); Imgproc.drawContours(mask, listOf(largest), 0, Scalar(255.0), Imgproc.FILLED)
    } finally { kernel.release(); contours.forEach { it.release() }; hierarchy.release(); work.release(); near.release() }
  }

  /**
   * A receipt's long sides are straight. A light patch of the background touching one (live test on a dark chair,
   * D52) passes the same threshold as the paper and bulges out of that side: too wide for [trimLeaks]. Each side's
   * line is fitted robustly to the mask's outermost pixel per row (rows along the receipt's axis), so a bulge
   * covering even half the side doesn't move it, and what sticks out beyond the line by more than a small tolerance
   * is cut. A side whose fit is weak (no straight stretch to speak of) is left alone, as are rows where the mask
   * reaches the image border (the edge isn't seen there).
   */
  private fun straightenSides(mask: Mat, turn: Int) {
    val work = if (turn == 90) Mat().also { Core.transpose(mask, it) } else mask
    try {
      val w = work.cols(); val h = work.rows()
      val pixels = ByteArray(w * h).also { work.get(0, 0, it) }
      val left = IntArray(h) { -1 }; val right = IntArray(h) { -1 }
      for (y in 0 until h) {
        var x = 0; while (x < w && pixels[y * w + x].toInt() == 0) x++
        if (x == w) continue
        var r = w - 1; while (pixels[y * w + r].toInt() == 0) r--
        left[y] = x; right[y] = r
      }
      val rows = (0 until h).filter { left[it] >= 0 }
      if (rows.size < 20) return
      val width = rows.map { right[it] - left[it] }.sorted()[rows.size / 2].toDouble()
      val tolerance = max(4.0, width * SIDE_TOLERANCE)
      fun fit(edge: IntArray, outward: Int): ((Int) -> Double)? {
        val seen = rows.filter { edge[it] in 2 until w - 2 }
        if (seen.size < rows.size * MIN_SIDE_INLIERS) return null
        val sample = seen.filterIndexed { i, _ -> i % max(1, seen.size / 40) == 0 }
        var best: Pair<Double, Double>? = null; var bestCount = 0
        for (i in sample.indices) for (j in i + 1 until sample.size) {
          val y0 = sample[i]; val y1 = sample[j]; if (y1 - y0 < h / 10) continue
          val slope = (edge[y1] - edge[y0]).toDouble() / (y1 - y0); val icept = edge[y0] - slope * y0
          if (abs(slope) > .35) continue // sides run along the axis; a steeper "side" is the bulge itself
          val count = seen.count { abs(edge[it] - (icept + slope * it)) <= 2.0 }
          if (count > bestCount) { bestCount = count; best = slope to icept }
        }
        val (slope0, icept0) = best ?: return null
        if (bestCount < rows.size * MIN_SIDE_INLIERS) return null
        // Least squares over the inliers.
        val inliers = seen.filter { abs(edge[it] - (icept0 + slope0 * it)) <= 2.0 }
        val my = inliers.average(); val mx = inliers.map { edge[it].toDouble() }.average()
        val sxx = inliers.sumOf { (it - my) * (it - my) }
        val slope = if (sxx < 1e-9) slope0 else inliers.sumOf { (it - my) * (edge[it] - mx) } / sxx
        val icept = mx - slope * my
        return { y -> icept + slope * y + outward * tolerance }
      }
      val leftLine = fit(left, -1); val rightLine = fit(right, 1)
      if (leftLine == null && rightLine == null) return
      var cut = 0
      for (y in rows) {
        val a = leftLine?.let { ceil(it(y)).toInt().coerceIn(0, w) } ?: 0
        val b = rightLine?.let { floor(it(y)).toInt().coerceIn(-1, w - 1) } ?: (w - 1)
        for (x in left[y] until min(a, right[y] + 1)) if (pixels[y * w + x].toInt() != 0) { pixels[y * w + x] = 0; cut++ }
        for (x in max(b + 1, left[y]) .. right[y]) if (pixels[y * w + x].toInt() != 0) { pixels[y * w + x] = 0; cut++ }
      }
      if (cut == 0) return
      work.put(0, 0, pixels)
      if (DocumentVision.DEBUG_PAPER) android.util.Log.v("PaperRegion", "straightened sides: cut $cut px (left=${leftLine != null} right=${rightLine != null})")
      if (turn == 90) Core.transpose(work, mask)
    } finally { if (work !== mask) work.release() }
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
