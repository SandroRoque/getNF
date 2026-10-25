package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/**
 * The receipt's paper in a frame, found from its print (D82): normalized corners top-left, top-right, bottom-right,
 * bottom-left (rows of print run from the left corners to the right ones, and read downwards), and whether each end
 * is the paper's own end rather than where the frame cuts it.
 */
internal data class PaperOutline(val corners: List<DocumentPoint>, val topSeen: Boolean, val bottomSeen: Boolean)

/** One line of text as OCR read it: its corners in frame pixels (top-left, clockwise) and how sure it is. */
internal data class OcrQuad(val corners: List<DocumentPoint>, val confidence: Double, val characters: Int) {
  val start: DocumentPoint get() = DocumentPoint((corners[0].x + corners[3].x) / 2, (corners[0].y + corners[3].y) / 2)
  val end: DocumentPoint get() = DocumentPoint((corners[1].x + corners[2].x) / 2, (corners[1].y + corners[2].y) / 2)
  val direction: Double get() = atan2(end.y - start.y, end.x - start.x)
  val height: Double get() = (ReceiptGeometry.distance(corners[0], corners[3]) + ReceiptGeometry.distance(corners[1], corners[2])) / 2
}

/** What [PaperFinder] saw, for drawing while tuning it, in frame pixels. */
internal class PaperTrace {
  var lines: List<OcrQuad> = emptyList()
  var angle: Double? = null
  var block: List<DocumentPoint>? = null
  val edges = mutableListOf<DocumentPoint>()
  var stopped: String? = null
}

/**
 * Finds the paper from what is printed on it (D82), as on-device OCR reads it, rather than from an outline a patterned
 * board, a shadow or a white table can mislead (D39–D41):
 *
 * 1. Orientation: the direction the lines read in, which also says which way is down the receipt; lines at other
 *    angles (text on the board, keyboard keys) are dropped.
 * 2. Text block: the longest run of lines down the receipt, gaps of a few lines bridged (around the QR code).
 * 3. Paper: from the text block outwards, band by band, until the paper's brightness drops; a straight line per side
 *    and per end. Reaching the frame's border means the frame cuts the paper there.
 */
internal object PaperFinder {
  private const val SIZE = 1000        // long side of the working copy for the edge walk
  private const val EDGE = .72         // the paper ends where brightness falls under this share of the paper's

  fun find(image: GrayImage, lines: List<OcrQuad>, trace: PaperTrace? = null): PaperOutline? {
    fun stop(why: String): PaperOutline? { trace?.stopped = why; return null }
    val text = lines.filter { it.characters >= 3 && it.height > 0 }
    if (text.size < 4) return stop("too few OCR lines")

    // 1. Orientation: the length-weighted circular median of the lines' directions, refined by their mean nearby.
    val weighted = text.map { it.direction to ReceiptGeometry.distance(it.start, it.end) }
    val coarse = (0 until 72).map { it * PI / 36 - PI }.maxBy { a ->
      weighted.sumOf { (d, w) -> if (abs(angleBetween(d, a)) <= PI / 18) w else 0.0 }
    }
    val rows = text.filter { abs(angleBetween(it.direction, coarse)) <= PI / 18 }
    if (rows.size < 4) return stop("too few lines in one direction")
    val angle = coarse + rows.sumOf { angleBetween(it.direction, coarse) * ReceiptGeometry.distance(it.start, it.end) } /
      rows.sumOf { ReceiptGeometry.distance(it.start, it.end) }
    trace?.lines = rows; trace?.angle = angle
    val s = sin(angle); val c = cos(angle)
    // u along the rows (reading direction), v down the receipt.
    fun u(p: DocumentPoint) = p.x * c + p.y * s
    fun v(p: DocumentPoint) = -p.x * s + p.y * c
    fun xy(u: Double, v: Double) = DocumentPoint(u * c - v * s, u * s + v * c)

    // 2. Text block: lines sorted down the receipt, split where the gap is many lines tall (a QR code is up to about
    //    fifteen, with nothing beside it) or the next line lies outside the columns so far; the longest run wins.
    val lineHeight = rows.map { it.height }.sorted()[rows.size / 2]
    val sorted = rows.sortedBy { v(it.start) }
    val runs = mutableListOf(mutableListOf(sorted.first()))
    for (line in sorted.drop(1)) {
      val run = runs.last()
      val gap = line.corners.minOf(::v) - run.maxOf { it.corners.maxOf(::v) }
      val overlaps = line.corners.minOf(::u) < run.maxOf { it.corners.maxOf(::u) } && line.corners.maxOf(::u) > run.minOf { it.corners.minOf(::u) }
      if (gap > lineHeight * 20 || !overlaps) runs += mutableListOf(line) else run += line
    }
    val block = runs.maxBy { r -> r.maxOf { it.corners.maxOf(::v) } - r.minOf { it.corners.minOf(::v) } }
    if (block.size < 4) return stop("text block too small")
    // Its sides: nearly all lines start and end inside them (a stray word on the table shouldn't widen it).
    val starts = block.map { it.corners.minOf(::u) }.sorted(); val ends = block.map { it.corners.maxOf(::u) }.sorted()
    val blockU0 = starts[block.size / 20]; val blockU1 = ends[block.size - 1 - block.size / 20]
    val blockV0 = block.minOf { it.corners.minOf(::v) }; val blockV1 = block.maxOf { it.corners.maxOf(::v) }
    trace?.block = listOf(xy(blockU0, blockV0), xy(blockU1, blockV0), xy(blockU1, blockV1), xy(blockU0, blockV1))
    if (blockU1 - blockU0 < lineHeight * 4) return stop("text block too narrow")

    // 3. Paper: walk outwards on a smaller copy until the paper's brightness drops, against the paper's level nearby.
    val k = max(1, ceil(max(image.width, image.height) / SIZE.toDouble()).toInt())
    val small = downscale(image, k)
    fun gray(p: DocumentPoint): Double? {
      val x = p.x / k; val y = p.y / k
      return if (x < 0 || y < 0 || x > small.width - 1 || y > small.height - 1) null else bilinear(small, x, y)
    }
    fun level(u0: Double, u1: Double, v0: Double, v1: Double): Double {
      val values = mutableListOf<Double>()
      val step = k * 2.0
      var vv = v0
      while (vv <= v1) { var uu = u0; while (uu <= u1) { gray(xy(uu, vv))?.let(values::add); uu += step }; vv += step }
      return if (values.isEmpty()) 0.0 else values.sorted()[(values.size - 1) * 8 / 10]
    }
    /** Distance from (u, v) along (du, dv) to where the paper ends, or null when the frame's border comes first. */
    fun walk(u0: Double, v0: Double, du: Double, dv: Double, paper: Double): Double? {
      // Averaged a few pixels across the walk, and only a drop longer than a line and a half counts: print (a stroke,
      // a strip of the previous receipt at a torn end) is over by then, while beyond the paper's edge it stays dark.
      val sustained = max(4, (lineHeight * 1.5 / k).toInt())
      var t = 2.0 * k; var below = 0
      while (true) {
        var sum = 0.0
        for (o in -4..4) sum += gray(xy(u0 + du * t - dv * o * k, v0 + dv * t + du * o * k))
          ?: return if (below >= 4) t - below * k else null
        if (sum / 9 < paper * EDGE) { if (++below >= sustained) return t - (below - 1) * k } else below = 0
        t += k
      }
    }
    val width = blockU1 - blockU0
    val bands = (0..20).map { blockV0 + (blockV1 - blockV0) * it / 20.0 }
    fun side(u0: Double, du: Double): Pair<List<Pair<Double, Double>>, Int> {
      val found = mutableListOf<Pair<Double, Double>>(); var cut = 0
      for (vv in bands) {
        val paper = level(blockU0, blockU1, vv - lineHeight, vv + lineHeight)
        val t = walk(u0, vv, du, 0.0, paper)
        if (t == null) cut++ else if (t < width * .5) { found += vv to u0 + du * t; trace?.edges?.add(xy(u0 + du * t, vv)) }
      }
      return found to cut
    }
    val (left, leftCut) = side(blockU0, -1.0)
    val (right, rightCut) = side(blockU1, 1.0)
    // A side mostly beyond the frame: no stretch here shows the paper's full width.
    if (left.size < 5 || right.size < 5 || leftCut > bands.size / 2 || rightCut > bands.size / 2) {
      return stop("sides: left ${left.size} found $leftCut cut, right ${right.size} found $rightCut cut")
    }
    // The paper's sides run nearly parallel to its print: fit each through the edge points near its typical distance
    // from the text (a bright desk beside the paper lets a few walks run on), with its lean kept small.
    fun sideLine(points: List<Pair<Double, Double>>, edge: Double): Pair<Double, Double> {
      val typical = points.map { abs(it.second - edge) }.sorted()[points.size / 2]
      val near = points.filter { abs(abs(it.second - edge) - typical) <= max(lineHeight, typical * .3) }
      val (a, b) = robustLine(near.ifEmpty { points })
      val lean = b.coerceIn(-.15, .15)
      val mid = (blockV0 + blockV1) / 2
      return (a + b * mid - lean * mid) to lean
    }
    val leftLine = sideLine(left, blockU0); val rightLine = sideLine(right, blockU1)

    fun end(v0: Double, dv: Double): Pair<Pair<Double, Double>, Boolean> {
      val found = mutableListOf<Pair<Double, Double>>(); var cut = 0
      val paper = if (dv < 0) level(blockU0, blockU1, v0, v0 + lineHeight * 2) else level(blockU0, blockU1, v0 - lineHeight * 2, v0)
      for (f in listOf(.2, .35, .5, .65, .8)) {
        val uu = blockU0 + width * f
        val t = walk(uu, v0, 0.0, dv, paper)
        if (t == null) cut++ else { found += uu to v0 + dv * t; trace?.edges?.add(xy(uu, v0 + dv * t)) }
      }
      if (cut >= 3 || found.size < 2) {
        // Cut by the frame: the end is as far as the paper's whole width stays inside the frame.
        fun inside(t: Double) = listOf(leftLine, rightLine).all { (a, b) -> gray(xy(a + b * (v0 + dv * t), v0 + dv * t)) != null } &&
          gray(xy(blockU0 + width / 2, v0 + dv * t)) != null
        var t = 0.0
        while (inside(t + k)) t += k
        return (v0 + dv * t to 0.0) to false
      }
      return robustLine(found) to true
    }
    val (topLine, topSeen) = end(blockV0, -1.0)
    val (bottomLine, bottomSeen) = end(blockV1, 1.0)

    // Corners: side lines u = a + b·v meet end lines v = a + b·u.
    fun corner(side: Pair<Double, Double>, end: Pair<Double, Double>): DocumentPoint {
      val vv = (end.first + end.second * side.first) / (1 - end.second * side.second)
      return xy(side.first + side.second * vv, vv)
    }
    val corners = listOf(corner(leftLine, topLine), corner(rightLine, topLine), corner(rightLine, bottomLine), corner(leftLine, bottomLine))
    return PaperOutline(corners.map { DocumentPoint(it.x / image.width, it.y / image.height) }, topSeen, bottomSeen)
  }

  /** Signed difference between two directions, in (-π, π]. */
  private fun angleBetween(a: Double, b: Double): Double = ((a - b) % (2 * PI) + 3 * PI) % (2 * PI) - PI

  private fun downscale(image: GrayImage, k: Int): GrayImage {
    val w = image.width / k; val h = image.height / k
    val out = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      var sum = 0
      for (dy in 0 until k) for (dx in 0 until k) sum += image[x * k + dx, y * k + dy]
      out[y * w + x] = (sum / (k * k)).toByte()
    }
    return GrayImage(w, h, out)
  }

  private fun bilinear(image: GrayImage, x: Double, y: Double): Double {
    val x0 = min(x.toInt(), image.width - 2).coerceAtLeast(0); val y0 = min(y.toInt(), image.height - 2).coerceAtLeast(0)
    val tx = x - x0; val ty = y - y0
    return (image[x0, y0] * (1 - tx) + image[x0 + 1, y0] * tx) * (1 - ty) + (image[x0, y0 + 1] * (1 - tx) + image[x0 + 1, y0 + 1] * tx) * ty
  }

  /** `second = a + b·first` through [points], refitted without the points farthest off the first fit. */
  private fun robustLine(points: List<Pair<Double, Double>>): Pair<Double, Double> {
    fun fit(p: List<Pair<Double, Double>>): Pair<Double, Double> {
      val n = p.size.toDouble(); val mx = p.sumOf { it.first } / n; val my = p.sumOf { it.second } / n
      val sxx = p.sumOf { (it.first - mx).pow(2) }
      if (sxx < 1e-9) return my to 0.0
      val b = p.sumOf { (it.first - mx) * (it.second - my) } / sxx
      return (my - b * mx) to b
    }
    val first = fit(points)
    val residuals = points.map { abs(it.second - (first.first + first.second * it.first)) }
    val limit = residuals.sorted()[residuals.size * 7 / 10] * 1.5 + 1
    val kept = points.filterIndexed { i, _ -> residuals[i] <= limit }
    return if (kept.size >= 2) fit(kept) else first
  }
}
