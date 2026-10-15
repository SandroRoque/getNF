package io.github.sandroroque.getnf.ui.scan

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

internal class VisionFrame(val gray: Mat, val mask: Mat, val observation: DocumentObservation, val rotation: Int = 0) : AutoCloseable {
  override fun close() { gray.release(); mask.release() }
}

/** Detection may use scene context. Focus measurements use only an eroded receipt mask. */
internal object DocumentVision {
  private val loaded by lazy { OpenCVLoader.initLocal().also { if (it) Core.setNumThreads(2) } }
  fun ensureLoaded() { check(loaded) { "Não foi possível iniciar a análise da câmera" } }
  fun gray(image: ImageProxy): Mat {
    check(loaded)
    val plane = image.planes[0]; val buffer = plane.buffer.duplicate(); val base = buffer.position()
    val bytes = ByteArray(image.width * image.height)
    for (y in 0 until image.height) {
      if (plane.pixelStride == 1) {
        buffer.position(base + y * plane.rowStride)
        buffer.get(bytes, y * image.width, image.width)
      } else for (x in 0 until image.width) {
        bytes[y * image.width + x] = buffer.get(base + y * plane.rowStride + x * plane.pixelStride)
      }
    }
    return Mat(image.height, image.width, CvType.CV_8UC1).also { result ->
      result.put(0, 0, bytes)
      when (image.imageInfo.rotationDegrees) {
        90 -> Core.rotate(result, result, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(result, result, Core.ROTATE_180)
        270 -> Core.rotate(result, result, Core.ROTATE_90_COUNTERCLOCKWISE)
      }
    }
  }
  /**
   * Colour saturation (distance of U/V from neutral) at a quarter of the luma resolution, oriented like [gray].
   * Receipts are always light, in white or pastel tones; a saturated region isn't paper (D39).
   */
  fun chroma(image: ImageProxy): Mat {
    check(loaded)
    val u = image.planes[1]; val v = image.planes[2]
    val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate(); val ubase = ub.position(); val vbase = vb.position()
    val w = image.width / 4; val h = image.height / 4
    val bytes = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      // Chroma planes are half resolution; sample every other chroma pixel.
      val du = (ub.get(ubase + (y * 2) * u.rowStride + (x * 2) * u.pixelStride).toInt() and 255) - 128
      val dv = (vb.get(vbase + (y * 2) * v.rowStride + (x * 2) * v.pixelStride).toInt() and 255) - 128
      bytes[y * w + x] = min(255, sqrt((du * du + dv * dv).toDouble()).toInt()).toByte()
    }
    return Mat(h, w, CvType.CV_8UC1).also { result ->
      result.put(0, 0, bytes)
      when (image.imageInfo.rotationDegrees) {
        90 -> Core.rotate(result, result, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(result, result, Core.ROTATE_180)
        270 -> Core.rotate(result, result, Core.ROTATE_90_COUNTERCLOCKWISE)
      }
    }
  }
  fun chroma(rgba: Mat): Mat {
    val rgb = Mat(); val yuv = Mat(); val channels = mutableListOf<Mat>(); val result = Mat()
    try {
      Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(rgb, yuv, Imgproc.COLOR_RGB2YUV)
      Core.split(yuv, channels)
      val du = Mat(); val dv = Mat()
      try {
        channels[1].convertTo(du, CvType.CV_32F, 1.0, -128.0); channels[2].convertTo(dv, CvType.CV_32F, 1.0, -128.0)
        Core.magnitude(du, dv, du); du.convertTo(result, CvType.CV_8UC1)
      } finally { du.release(); dv.release() }
      Imgproc.resize(result, result, Size(rgba.cols() / 4.0, rgba.rows() / 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
      return result
    } finally { rgb.release(); yuv.release(); channels.forEach { it.release() } }
  }

  /**
   * Whether a proposed region looks like a receipt, all inputs at the same size (D39): mostly light (a phone's shadow
   * on a white table is darker than the scene; a dark board around a receipt is not mostly light), not saturated (a
   * red board under the paper), and printed (text texture spread over the region, unlike a flat shadow). Pastel
   * white/yellow/green/blue paper passes.
   */
  fun paperLike(gray: Mat, region: Mat, chroma: Mat?): Boolean {
    // Far enough from the boundary that its ramp (a soft shadow edge) can't pass for print.
    val interior = Mat(); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
    val blurred = Mat(); val deviation = Mat(); val ink = Mat(); val scaled = Mat(); val bright = Mat(); val hist = Mat()
    try {
      Imgproc.erode(region, interior, kernel)
      val area = Core.countNonZero(interior)
      if (area < 200) return false
      Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(64), MatOfFloat(0f, 256f))
      val counts = DoubleArray(64) { hist.get(it, 0)[0] }; val total = counts.sum()
      var acc = 0.0; var p90 = 255.0
      for (i in 63 downTo 0) { acc += counts[i]; if (acc >= total * .10) { p90 = i * 4.0 + 2; break } }
      Imgproc.threshold(gray, bright, p90 * PAPER_MIN_RELATIVE_LIGHT, 255.0, Imgproc.THRESH_BINARY)
      Core.bitwise_and(bright, interior, bright)
      val light = Core.countNonZero(bright).toDouble() / area
      val saturation = if (chroma == null) 0.0 else {
        Imgproc.resize(chroma, scaled, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); Core.mean(scaled, interior).`val`[0]
      }
      Imgproc.GaussianBlur(gray, blurred, Size(0.0, 0.0), 3.0)
      Core.absdiff(gray, blurred, deviation)
      Imgproc.threshold(deviation, ink, PAPER_INK_DEVIATION, 255.0, Imgproc.THRESH_BINARY)
      Core.bitwise_and(ink, interior, ink)
      val printed = Core.countNonZero(ink).toDouble() / area
      val ok = light >= PAPER_MIN_LIGHT_FRACTION && saturation <= PAPER_MAX_CHROMA && printed >= PAPER_MIN_INK
      if (android.util.Log.isLoggable("PaperLike", android.util.Log.VERBOSE) || DEBUG_PAPER)
        android.util.Log.v("PaperLike", "area=$area light=%.2f chroma=%.1f ink=%.4f ok=$ok".format(light, saturation, printed))
      return ok
    } finally { listOf(interior, kernel, blurred, deviation, ink, scaled, bright, hist).forEach { it.release() } }
  }
  @Volatile var DEBUG_PAPER = false
  /**
   * Half the interior must be light: a receipt partly under the phone's shadow still passes, a shadow alone (0.00 on
   * real frames) doesn't. A dark board around a receipt can pass, so PaperRegion prefers the receipt inside it (D40).
   */
  private const val PAPER_MIN_LIGHT_FRACTION = .5
  private const val PAPER_MIN_RELATIVE_LIGHT = .6
  /** Pastel yellow/green/blue paper is ~20; a red board read ~80 (D39). */
  private const val PAPER_MAX_CHROMA = 40.0
  private const val PAPER_INK_DEVIATION = 6.0
  private const val PAPER_MIN_INK = .004

  fun fromBitmap(bitmap: Bitmap): VisionFrame {
    check(loaded)
    val rgba = Mat(); val gray = Mat(); var chroma: Mat? = null
    try {
      Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
      chroma = chroma(rgba)
      return analyze(gray, chroma = chroma)
    } finally { rgba.release(); gray.release(); chroma?.release() }
  }

  private data class Line(val a: Point, val b: Point) {
    val length = hypot(b.x - a.x, b.y - a.y)
    fun x(y: Double): Double = a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)
    val low get() = min(a.y, b.y)
    val high get() = max(a.y, b.y)
  }

  fun analyze(source: Mat, evaluateQuality: Boolean = true, preferredRotation: Int? = null, chroma: Mat? = null): VisionFrame {
    check(loaded)
    val regionAt = CapturePerf.now()
    PaperRegion.detect(source, evaluateQuality, chroma)?.let { CapturePerf.elapsed("region", regionAt); return it }
    for (turn in if (preferredRotation == 90) listOf(90, 0) else listOf(0, 90)) {
      val canonical = Mat(); val canonicalChroma = chroma?.let { Mat() }
      try {
        if (turn == 90) Core.rotate(source, canonical, Core.ROTATE_90_CLOCKWISE) else source.copyTo(canonical)
        if (chroma != null) { if (turn == 90) Core.rotate(chroma, canonicalChroma, Core.ROTATE_90_CLOCKWISE) else chroma.copyTo(canonicalChroma) }
        val fallback = validatedLines(canonical, evaluateQuality, turn)
        if (fallback.observation.located && fallback.observation.hasSides && paperLike(fallback.gray, fallback.mask, canonicalChroma)) return fallback
        fallback.close()
      } finally { canonical.release(); canonicalChroma?.release() }
    }
    val gray = Mat()
    val scale = min(1.0, 720.0 / max(source.cols(), source.rows()))
    Imgproc.resize(source, gray, Size(), scale, scale, Imgproc.INTER_AREA)
    return VisionFrame(gray, Mat.zeros(gray.size(), CvType.CV_8UC1), DocumentObservation())
  }

  private fun validatedLines(source: Mat, evaluateQuality: Boolean, rotation: Int): VisionFrame {
    // A partial strip may not form a clean threshold contour. Require photometric
    // evidence on both observed sides before allowing the line-based proposal.
    val fallback = analyzeLines(source, evaluateQuality)
    if (!fallback.observation.located) return fallback
    val pixels = ByteArray(fallback.gray.rows() * fallback.gray.cols()).also { fallback.gray.get(0, 0, it) }
    fun supported(edge: EdgeSegment): Boolean {
      val w = fallback.gray.cols(); val h = fallback.gray.rows()
      var tested = 0; var good = 0
      for (i in 1 until 30) {
        val t = i / 30.0; val x = ((edge.start.x + (edge.end.x - edge.start.x) * t) * w).toInt()
        val y = ((edge.start.y + (edge.end.y - edge.start.y) * t) * h).toInt()
        if (x !in 5 until w - 5 || y !in 0 until h) continue
        tested++
        if (abs((pixels[y*w+x+4].toInt() and 255) - (pixels[y*w+x-4].toInt() and 255)) >= 5) good++
      }
      return tested >= 12 && good.toDouble() / tested >= .7
    }
    val sides = fallback.observation.edges.filter { it.kind == EdgeKind.LEFT || it.kind == EdgeKind.RIGHT }
    if (sides.size != 2 || !sides.all(::supported)) {
      val empty = VisionFrame(fallback.gray.clone(), Mat.zeros(fallback.mask.size(), CvType.CV_8UC1), DocumentObservation())
      fallback.close(); return empty
    }
    val paths = maskContours(fallback.mask); val contour = paths.maxByOrNull { it.size }.orEmpty()
    val observed = contour.indices.mapNotNull { i ->
      val a = contour[i]; val b = contour[(i + 1) % contour.size]
      if ((a.y <= .005 && b.y <= .005) || (a.y >= .995 && b.y >= .995)) null
      else EdgeSegment(if ((a.x+b.x)/2 < .5) EdgeKind.LEFT else EdgeKind.RIGHT, a, b)
    }
    val result = VisionFrame(fallback.gray.clone(), fallback.mask.clone(), fallback.observation.copy(
      edges = observed + fallback.observation.edges.filter { it.kind == EdgeKind.TOP || it.kind == EdgeKind.BOTTOM }, contour = contour), rotation)
    fallback.close(); return result
  }

  /** Normalized contour rings; holes are retained for the exact focus-mask overlay. */
  fun maskContours(mask: Mat): List<List<DocumentPoint>> {
    val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat(); val work = mask.clone()
    return try {
      Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
      contours.map { c ->
        val curve = MatOfPoint2f(*c.toArray()); val approx = MatOfPoint2f()
        try {
          Imgproc.approxPolyDP(curve, approx, .75, true)
          approx.toArray().map { DocumentPoint(it.x / mask.cols(), it.y / mask.rows()) }
        } finally { curve.release(); approx.release() }
      }.filter { it.size >= 3 }
    } finally { contours.forEach { it.release() }; hierarchy.release(); work.release() }
  }

  fun focusMask(paperMask: Mat, qr: List<DocumentPoint> = emptyList()): Mat {
    val mask = Mat(); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
    try {
      Imgproc.erode(paperMask, mask, kernel)
      if (qr.size >= 4) {
        val poly = MatOfPoint(*qr.map { Point(it.x * mask.cols(), it.y * mask.rows()) }.toTypedArray())
        try { Imgproc.fillConvexPoly(mask, poly, Scalar(0.0)) } finally { poly.release() }
        Imgproc.erode(mask, mask, kernel)
      }
      return mask
    } catch (error: Throwable) { mask.release(); throw error }
    finally { kernel.release() }
  }

  private fun analyzeLines(source: Mat, evaluateQuality: Boolean): VisionFrame {
    check(loaded)
    val detectionAt = CapturePerf.now()
    val gray = Mat(); val edges = Mat(); val blur = Mat(); val lines = Mat()
    val mask = Mat(); val transverseEdges = Mat(); val transverseLines = Mat(); val dx = Mat(); val dy = Mat(); val direction = Mat()
    try {
      val scale = min(1.0, 720.0 / max(source.cols(), source.rows()))
      Imgproc.resize(source, gray, Size(), scale, scale, Imgproc.INTER_AREA)
      val w = gray.cols(); val h = gray.rows()
      Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), .7)
      Imgproc.Canny(blur, edges, 8.0, 24.0)
      edges.copyTo(transverseEdges)
      Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 25, h * .16, h * .07)
      val vertical = (0 until lines.rows()).map { lines.get(it, 0) }.map { Line(Point(it[0], it[1]), Point(it[2], it[3])) }
        .filter { abs(it.b.y - it.a.y) > abs(it.b.x - it.a.x) * 2.5 }
      var best: Pair<Line, Line>? = null; var bestScore = 0.0
      for (a in vertical) for (b in vertical) {
        val mid = h / 2.0; val left = a.x(mid); val right = b.x(mid); val width = right - left
        if (width < w * .18 || width > w * .96 || left < 2 || right > w - 2) continue
        if (abs((a.b.x - a.a.x) / (a.b.y - a.a.y) - (b.b.x - b.a.x) / (b.b.y - b.a.y)) > .28) continue
        val overlap = min(a.high, b.high) - max(a.low, b.low)
        if (overlap < h * .12) continue
        // Long outer edges are preferred over the short strokes and table rules inside text.
        val score = (min(a.length, b.length) / h) * .65 + width / w * .25 + overlap / h * .1
        if (score > bestScore) { best = a to b; bestScore = score }
      }
      mask.create(h, w, CvType.CV_8UC1); mask.setTo(Scalar(0.0))
      val pair = best ?: return VisionFrame(gray.clone(), mask.clone(), DocumentObservation())
      val (left, right) = pair
      val start = max(0.0, max(left.low, right.low) - h * .08)
      val end = min(h - 1.0, min(left.high, right.high) + h * .08)
      // Search transverse edges independently: long lateral lines otherwise consume Hough's
      // probabilistic samples and can hide a short but complete top/bottom edge.
      Imgproc.Sobel(blur, dx, CvType.CV_32F, 1, 0)
      Imgproc.Sobel(blur, dy, CvType.CV_32F, 0, 1)
      Core.absdiff(dx, Scalar(0.0), dx); Core.absdiff(dy, Scalar(0.0), dy)
      Core.multiply(dx, Scalar(2.0), dx)
      Core.compare(dy, dx, direction, Core.CMP_GT)
      Core.bitwise_and(transverseEdges, direction, transverseEdges)
      val paperWidth = right.x(h / 2.0) - left.x(h / 2.0)
      // Restrict voting to transverse orientations. Probabilistic unrestricted Hough can
      // consume a faint top edge into vertical chains of repeated product-text strokes.
      Imgproc.HoughLines(transverseEdges, transverseLines, 1.0, Math.PI / 360,
        max(20, (paperWidth * .35).toInt()), 0.0, 0.0, Math.PI / 2 - atan(1.0 / 3), Math.PI / 2 + atan(1.0 / 3))
      val edgePixels = ByteArray(w * h).also { transverseEdges.get(0, 0, it) }
      val horizontal = (0 until min(64, transverseLines.rows())).mapNotNull { index ->
        val polar = transverseLines.get(index, 0); val rho = polar[0]; val theta = polar[1]
        fun row(x: Double) = (rho - x * cos(theta)) / sin(theta)
        val middle = row((left.x(h / 2.0) + right.x(h / 2.0)) / 2)
        if (middle !in 0.0..(h - 1.0)) return@mapNotNull null
        val l = left.x(middle).toInt().coerceIn(0, w - 1); val r = right.x(middle).toInt().coerceIn(0, w - 1)
        val supported = (l..r).filter { x ->
          val y = row(x.toDouble()).roundToInt()
          (-1..1).any { dy -> y + dy in 0 until h && edgePixels[(y + dy) * w + x].toInt() != 0 }
        }
        if (supported.isEmpty()) return@mapNotNull null
        val groups = mutableListOf<MutableList<Int>>()
        for (x in supported) {
          if (groups.isEmpty() || x - groups.last().last() > max(2.0, paperWidth * .12)) groups += mutableListOf(x)
          else groups.last() += x
        }
        val span = groups.maxByOrNull { it.last() - it.first() } ?: return@mapNotNull null
        if (span.last() - span.first() < paperWidth * .65 || span.size < (span.last() - span.first()) * .5) return@mapNotNull null
        Line(Point(span.first().toDouble(), row(span.first().toDouble())), Point(span.last().toDouble(), row(span.last().toDouble())))
      }
      val grayPixels = ByteArray(w * h).also { gray.get(0, 0, it) }
      fun transition(line: Line): Boolean {
        val y = (line.a.y + line.b.y) / 2
        val l = left.x(y).toInt().coerceIn(1, w - 2); val r = right.x(y).toInt().coerceIn(l + 1, w - 1)
        var brighter = 0; var darker = 0; var count = 0
        for (x in l + (r - l) / 10 until r - (r - l) / 10 step 4) {
          val edgeY = (line.a.y + (x - line.a.x) * (line.b.y - line.a.y) / (line.b.x - line.a.x)).toInt()
          if (edgeY < 13 || edgeY > h - 14) continue
          val above = (6..12).sumOf { (grayPixels[(edgeY - it) * w + x].toInt() and 255).toDouble() } / 7
          val below = (6..12).sumOf { (grayPixels[(edgeY + it) * w + x].toInt() and 255).toDouble() } / 7
          // A physical edge changes paper/background appearance across most of the width; printed rules don't.
          // Paper may be darker than the table (shadows or aged thermal paper).
          if (below - above > 3.0) brighter++
          if (above - below > 3.0) darker++
          count++
        }
        return count > 0 && max(brighter, darker).toDouble() / count >= .65
      }
      val topLine = horizontal.filter { (it.a.y + it.b.y) / 2 <= start + h * .15 }
        .filter { transition(it) }.maxByOrNull { it.length }
      val bottomLine = horizontal.filter { (it.a.y + it.b.y) / 2 >= end - h * .15 }
        .filter { transition(it) }.maxByOrNull { it.length }
      val top = topLine?.let { (it.a.y + it.b.y) / 2 }
      val bottom = bottomLine?.let { (it.a.y + it.b.y) / 2 }
      val y0 = top ?: 0.0; val y1 = bottom ?: (h - 1.0)
      if (y1 <= y0 || listOf(y0, y1).any { right.x(it) - left.x(it) < w * .12 }) {
        return VisionFrame(gray.clone(), mask.clone(), DocumentObservation())
      }
      fun corner(side: Line, endLine: Line?, fallbackY: Double): Point {
        if (endLine == null) return Point(side.x(fallbackY).coerceIn(0.0, w - 1.0), fallbackY)
        val sx = side.b.x - side.a.x; val sy = side.b.y - side.a.y
        val ex = endLine.b.x - endLine.a.x; val ey = endLine.b.y - endLine.a.y
        val t = ((endLine.a.x - side.a.x) * ey - (endLine.a.y - side.a.y) * ex) / (sx * ey - sy * ex)
        return Point((side.a.x + t * sx).coerceIn(0.0, w - 1.0), (side.a.y + t * sy).coerceIn(0.0, h - 1.0))
      }
      val polygon = listOf(corner(left, topLine, y0), corner(right, topLine, y0),
        corner(right, bottomLine, y1), corner(left, bottomLine, y1))
      val segments = mutableListOf(
        EdgeSegment(EdgeKind.LEFT, DocumentPoint(left.a.x / w, left.a.y / h), DocumentPoint(left.b.x / w, left.b.y / h)),
        EdgeSegment(EdgeKind.RIGHT, DocumentPoint(right.a.x / w, right.a.y / h), DocumentPoint(right.b.x / w, right.b.y / h)),
      )
      fun add(line: Line?, kind: EdgeKind) { line?.let { segments += EdgeSegment(kind, DocumentPoint(it.a.x / w, it.a.y / h), DocumentPoint(it.b.x / w, it.b.y / h)) } }
      add(topLine, EdgeKind.TOP); add(bottomLine, EdgeKind.BOTTOM)
      val poly = MatOfPoint(*polygon.toTypedArray())
      try { Imgproc.fillConvexPoly(mask, poly, Scalar(255.0)) } finally { poly.release() }
      CapturePerf.elapsed("edges", detectionAt)
      CapturePerf.measure("mask") { refineMask(gray, mask, polygon) }
      val support = Core.countNonZero(mask).toDouble() / max(1.0, (y1 - y0) * (right.x(h / 2.0) - left.x(h / 2.0)))
      val confidence = if (support in .6..1.2) .8 else .0
      val observation = DocumentObservation(polygon.map { DocumentPoint(it.x / w, it.y / h) }, segments, confidence,
        top?.div(h), bottom?.div(h), brightness = Core.mean(gray, mask).`val`[0])
      val nativeMask = Mat()
      try {
        Imgproc.resize(mask, nativeMask, source.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        return VisionFrame(gray.clone(), mask.clone(), observation.copy(quality = if (evaluateQuality) CapturePerf.measure("quality") { quality(source, nativeMask) } else emptyList()))
      } finally { nativeMask.release() }
    } finally { listOf(gray, mask, edges, blur, lines, transverseEdges, transverseLines, dx, dy, direction).forEach { it.release() } }
  }

  /** Definite background outside detected sides; GrabCut refines the visible paper, including irregular edges. */
  private fun refineMask(gray: Mat, mask: Mat, polygon: List<Point>) {
    // Graph cut is superlinear in scene complexity. Bound its input to the paper ROI and
    // a small segmentation grid; native-resolution pixels are retained for focus below.
    val x0 = (polygon.minOf { it.x }.toInt() - 10).coerceAtLeast(0)
    val y0 = (polygon.minOf { it.y }.toInt() - 10).coerceAtLeast(0)
    val x1 = (ceil(polygon.maxOf { it.x }).toInt() + 11).coerceAtMost(gray.cols())
    val y1 = (ceil(polygon.maxOf { it.y }).toInt() + 11).coerceAtMost(gray.rows())
    val roi = Rect(x0, y0, x1 - x0, y1 - y0)
    val crop = gray.submat(roi); val original = mask.submat(roi)
    val smallGray = Mat(); val smallMask = Mat(); val refined = Mat()
    val inset = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    try {
      val scale = min(1.0, 240.0 / max(roi.width, roi.height))
      Imgproc.resize(crop, smallGray, Size(), scale, scale, Imgproc.INTER_AREA)
      Imgproc.resize(original, smallMask, smallGray.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
      val sx = smallGray.cols().toDouble() / roi.width; val sy = smallGray.rows().toDouble() / roi.height
      refineSmallMask(smallGray, smallMask, polygon.map { Point((it.x - x0) * sx, (it.y - y0) * sy) })
      // Exclude one segmentation pixel before expansion: a coarse boundary must never
      // admit table pixels into the focus mask. quality() additionally excludes filter support.
      Imgproc.erode(smallMask, smallMask, inset)
      Imgproc.resize(smallMask, refined, roi.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
      Core.bitwise_and(original, refined, original)
    } finally { listOf(crop, original, smallGray, smallMask, refined, inset).forEach { it.release() } }
  }

  private fun refineSmallMask(gray: Mat, mask: Mat, polygon: List<Point>) {
    val rgb = Mat(); val labels = Mat(gray.size(), CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))
    val bg = Mat(); val fg = Mat(); val interior = Mat(); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
    try {
      Imgproc.cvtColor(gray, rgb, Imgproc.COLOR_GRAY2RGB)
      labels.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()), mask)
      Imgproc.erode(mask, interior, kernel)
      val values = ByteArray(gray.rows() * gray.cols()).also { gray.get(0, 0, it) }
      val interiorPixels = ByteArray(values.size).also { interior.get(0, 0, it) }
      val samples = values.indices.filter { interiorPixels[it].toInt() != 0 }.map { values[it].toInt() and 255 }.sorted()
      if (samples.isEmpty()) return
      val paperTone = samples[(samples.size * .65).toInt()]
      val seeds = ByteArray(values.size) { i ->
        if (interiorPixels[i].toInt() != 0 && abs((values[i].toInt() and 255) - paperTone) <= 12) 255.toByte() else 0
      }
      val seed = Mat(gray.size(), CvType.CV_8UC1)
      try { seed.put(0, 0, seeds); labels.setTo(Scalar(Imgproc.GC_FGD.toDouble()), seed) } finally { seed.release() }
      if (Core.countNonZero(mask) < gray.total() * .95) {
        Imgproc.grabCut(rgb, labels, Rect(), bg, fg, 1, Imgproc.GC_INIT_WITH_MASK)
        val a = Mat(); val b = Mat()
        try {
          Core.compare(labels, Scalar(Imgproc.GC_FGD.toDouble()), a, Core.CMP_EQ)
          Core.compare(labels, Scalar(Imgproc.GC_PR_FGD.toDouble()), b, Core.CMP_EQ)
          Core.bitwise_or(a, b, a)
          Core.bitwise_and(a, mask, a)
          // Fill ink holes while preserving the external contour.
          val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat()
          try {
            Imgproc.findContours(a, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val largest = contours.indices.maxByOrNull { Imgproc.contourArea(contours[it]) }
            mask.setTo(Scalar(0.0))
            if (largest != null) Imgproc.drawContours(mask, contours, largest, Scalar(255.0), Imgproc.FILLED)
          } finally { contours.forEach { it.release() }; hierarchy.release() }
        } finally { a.release(); b.release() }
      }
    } finally { listOf(rgb, labels, bg, fg, interior, kernel).forEach { it.release() } }
  }

  /** Pixels outside the eroded mask never enter either Sobel neighbourhood or the statistics. */
  fun quality(source: Mat, paperMask: Mat, qr: List<DocumentPoint> = emptyList()): List<RegionQuality> {
    val mask = focusMask(paperMask, qr); val paper = Mat(); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
    val smooth = Mat(); val gx = Mat(); val gy = Mat(); val bx = Mat(); val by = Mat(); val blurred = Mat()
    val coarse = Mat(); val shading = Mat(); val band = Mat(); val inner = Mat()
    val innerKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(31.0, 31.0))
    val rows = mutableListOf<RegionQuality>()
    try {
      Imgproc.erode(paperMask, paper, kernel)
      Imgproc.GaussianBlur(source, smooth, Size(3.0, 3.0), .5)
      Imgproc.GaussianBlur(source, blurred, Size(7.0, 7.0), 1.5)
      Imgproc.Sobel(smooth, gx, CvType.CV_32F, 1, 0); Imgproc.Sobel(smooth, gy, CvType.CV_32F, 0, 1)
      Imgproc.Sobel(blurred, bx, CvType.CV_32F, 1, 0); Imgproc.Sobel(blurred, by, CvType.CV_32F, 0, 1)
      listOf(gx, gy, bx, by).forEach { Core.absdiff(it, Scalar.all(0.0), it) }
      // Text-line-scale structure survives blur; slow shading doesn't. Blurred print stays "content", so it can't
      // pass as blank paper and bridge coverage (D37).
      Imgproc.GaussianBlur(source, coarse, Size(0.0, 0.0), 1.5)
      Imgproc.GaussianBlur(source, shading, Size(0.0, 0.0), 5.0)
      Core.subtract(coarse, shading, band, Mat(), CvType.CV_32F)
      // Its support is ~15 px, so it is only read where that support stays on paper (D27).
      Imgproc.erode(mask, inner, innerKernel)
      val step = 96
      // A decoded QR proves nothing about the text's focus (D27/D28).
      var anyContent = false
      for (y in 0 until source.rows() step step) {
        var bad = false; var content = false; val scores = mutableListOf<Double>(); var paperPixels = 0
        val height = min(step, source.rows() - y)
        for (x in 0 until source.cols() step step) {
          val rect = Rect(x, y, min(step, source.cols() - x), height)
          val m = mask.submat(rect); val pixels = Core.countNonZero(m)
          val paperRegion = paper.submat(rect)
          try { paperPixels += Core.countNonZero(paperRegion) } finally { paperRegion.release() }
          if (pixels < 64) { m.release(); continue }
          val original = source.submat(rect); val structure = band.submat(rect); val innerTile = inner.submat(rect)
          val mean = MatOfDouble(); val std = MatOfDouble()
          try {
            Core.meanStdDev(original, mean, std, m)
            val contrast = std.toArray()[0]
            val blurredPrint = Core.countNonZero(innerTile) >= 64 && run {
              Core.meanStdDev(structure, mean, std, innerTile); std.toArray()[0] >= CONTENT_STRUCTURE
            }
            if (contrast >= 5.0 || blurredPrint) {
              content = true; anyContent = true
              fun ratio(sharp: Mat, soft: Mat): Double {
                val a = sharp.submat(rect); val b = soft.submat(rect)
                try { return Core.mean(a, m).`val`[0] / max(.5, Core.mean(b, m).`val`[0]) } finally { a.release(); b.release() }
              }
              // Worst direction: motion blur only softens edges across the motion, so a combined magnitude hides it.
              val score = min(ratio(gx, bx), ratio(gy, by))
              scores += score
              // Only a coarse pre-filter: the ratio depends on text size, so legibility is decided by OCR on the
              // saved candidate (TextLegibility, D37). The threshold keeps OCR from running on gross blur.
              if (score < PREFILTER) bad = true
            }
          } finally { m.release(); original.release(); structure.release(); innerTile.release(); mean.release(); std.release() }
        }
        val qrHere = qr.isNotEmpty() && qr.minOf { it.y } < (y + height).toDouble() / source.rows() && qr.maxOf { it.y } > y.toDouble() / source.rows()
        rows += RegionQuality(CaptureBand(y.toDouble() / source.rows(), (y + height).toDouble() / source.rows()),
          scores.minOrNull() ?: if (qrHere) 1.25 else 0.0, content || qrHere, !bad && paperPixels >= 64)
      }
      // Blank paper alone is not evidence of focus; blank bands can bridge text within the same good image.
      return rows.map { it.copy(usable = it.usable && anyContent) }
    } finally { listOf(mask, paper, kernel, smooth, gx, gy, bx, by, blurred, coarse, shading, band, inner, innerKernel).forEach { it.release() } }
  }
  private const val CONTENT_STRUCTURE = 1.5
  /** Synthetic benchmark: with OCR >= 0.8, no illegible band accepted and few extra legible ones rejected. */
  private const val PREFILTER = .7
}
