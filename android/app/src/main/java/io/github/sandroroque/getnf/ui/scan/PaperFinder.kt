package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/**
 * The receipt's paper in a frame, found from its print (D82): normalized corners top-left, top-right, bottom-right,
 * bottom-left (rows of print run from the left corners to the right ones, and read downwards), whether each end is
 * the paper's own end rather than where the frame cuts it, and the OCR lines that belong to it.
 */
internal data class PaperOutline(
  val corners: List<DocumentPoint>,
  val topSeen: Boolean,
  val bottomSeen: Boolean,
  /** The OCR lines that belong to this receipt (its text block), in frame pixels. */
  val lines: List<OcrQuad> = emptyList(),
)

/** One line of text as OCR read it: its corners in frame pixels (top-left, clockwise) and how sure it is. */
internal data class OcrQuad(val corners: List<DocumentPoint>, val confidence: Double, val characters: Int, val text: String = "") {
  val start: DocumentPoint get() = DocumentPoint((corners[0].x + corners[3].x) / 2, (corners[0].y + corners[3].y) / 2)
  val end: DocumentPoint get() = DocumentPoint((corners[1].x + corners[2].x) / 2, (corners[1].y + corners[2].y) / 2)
  val direction: Double get() = atan2(end.y - start.y, end.x - start.x)
  val height: Double get() = (distance(corners[0], corners[3]) + distance(corners[1], corners[2])) / 2
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
  /**
   * Whether a line of text is light print on a dark ground: inside its box the ground is most of the pixels, so the
   * median is the ground's level, and the print is the tail on the far side, darker on paper, lighter on packaging.
   */
  internal fun lightOnDark(image: GrayImage, line: OcrQuad): Boolean {
    // With half a line of ground around it: a box drawn tight around bold print can be half print.
    val pad = line.height / 2
    val x0 = (line.corners.minOf { it.x } - pad).toInt().coerceIn(0, image.width - 1); val x1 = (line.corners.maxOf { it.x } + pad).toInt().coerceIn(0, image.width - 1)
    val y0 = (line.corners.minOf { it.y } - pad).toInt().coerceIn(0, image.height - 1); val y1 = (line.corners.maxOf { it.y } + pad).toInt().coerceIn(0, image.height - 1)
    val counts = IntArray(256); var n = 0
    var y = y0
    while (y <= y1) { var x = x0; while (x <= x1) { counts[image[x, y]]++; n++; x += 2 }; y += 2 }
    if (n < 50) return false
    fun at(share: Double): Int { var seen = 0; val want = (n * share).toInt(); for (g in 0..255) { seen += counts[g]; if (seen > want) return g }; return 255 }
    val median = at(.5)
    return at(.97) - median > (median - at(.03)) * 1.5
  }


  private const val SIZE = 1000        // long side of the working copy for the edge walk
  private const val EDGE = .72         // the paper ends where brightness falls under this share of the paper's

  fun find(image: GrayImage, lines: List<OcrQuad>, trace: PaperTrace? = null): PaperOutline? {
    fun stop(why: String): PaperOutline? { trace?.stopped = why; return null }
    val text = lines.filter { it.characters >= 3 && it.height > 0 }
    if (text.size < 4) return stop("too few OCR lines")

    // 1. Orientation: the length-weighted circular median of the lines' directions, refined by their mean nearby.
    val weighted = text.map { it.direction to distance(it.start, it.end) }
    val coarse = (0 until 72).map { it * PI / 36 - PI }.maxBy { a ->
      weighted.sumOf { (d, w) -> if (abs(angleBetween(d, a)) <= PI / 18) w else 0.0 }
    }
    val rows = text.filter { abs(angleBetween(it.direction, coarse)) <= PI / 18 }
    if (rows.size < 4) return stop("too few lines in one direction")
    val angle = coarse + rows.sumOf { angleBetween(it.direction, coarse) * distance(it.start, it.end) } /
      rows.sumOf { distance(it.start, it.end) }
    trace?.lines = rows; trace?.angle = angle
    val s = sin(angle); val c = cos(angle)
    // u along the rows (reading direction), v down the receipt.
    fun u(p: DocumentPoint) = p.x * c + p.y * s
    fun v(p: DocumentPoint) = -p.x * s + p.y * c
    fun xy(u: Double, v: Double) = DocumentPoint(u * c - v * s, u * s + v * c)

    // 2. Text block: lines grouped with those they share columns with and lie near (another receipt beside this one
    //    forms its own group); in the largest group, the longest run down the receipt, split where the gap is many
    //    lines tall (a QR code is up to about fifteen, with nothing beside it).
    val lineHeight = rows.map { it.height }.sorted()[rows.size / 2]
    val group = IntArray(rows.size) { it }
    fun root(i: Int): Int { var r = i; while (group[r] != r) r = group[r]; return r }
    val spans = rows.map { l -> Triple(l.corners.minOf(::u), l.corners.maxOf(::u), l.corners.minOf(::v)) }
    for (i in rows.indices) for (j in i + 1 until rows.size) {
      val (a0, a1, av) = spans[i]; val (b0, b1, bv) = spans[j]
      val overlap = min(a1, b1) - max(a0, b0)
      if (overlap >= min(a1 - a0, b1 - b0) * .3 && abs(av - bv) <= lineHeight * 20) group[root(i)] = root(j)
    }
    val column = rows.indices.groupBy(::root).values.maxBy { it.size }.map { rows[it] }
    val sorted = column.sortedBy { v(it.start) }
    val runs = mutableListOf(mutableListOf(sorted.first()))
    for (line in sorted.drop(1)) {
      val run = runs.last()
      if (line.corners.minOf(::v) - run.maxOf { it.corners.maxOf(::v) } > lineHeight * 20) runs += mutableListOf(line) else run += line
    }
    val block = runs.maxBy { r -> r.maxOf { it.corners.maxOf(::v) } - r.minOf { it.corners.minOf(::v) } }
    if (block.size < 4) return stop("text block too small")
    // Its sides: nearly all lines start and end inside them (a stray word on the table shouldn't widen it).
    val starts = block.map { it.corners.minOf(::u) }.sorted(); val ends = block.map { it.corners.maxOf(::u) }.sorted()
    val blockU0 = starts[block.size / 20]; val blockU1 = ends[block.size - 1 - block.size / 20]
    val blockV0 = block.minOf { it.corners.minOf(::v) }; val blockV1 = block.maxOf { it.corners.maxOf(::v) }
    trace?.block = listOf(xy(blockU0, blockV0), xy(blockU1, blockV0), xy(blockU1, blockV1), xy(blockU0, blockV1))
    if (blockU1 - blockU0 < lineHeight * 4) return stop("text block too narrow")
    // A receipt is dark print on light paper. Light print on a dark ground (the back of a coffee bag) is packaging.
    if (block.count { lightOnDark(image, it) } * 2 > block.size) return stop("light text on a dark background")

    // 3. Paper: walk outwards on a smaller copy until the paper's look drops, against the paper's level nearby. The look
    //    is brightness less colourfulness: paper is light and neutral, and a coloured surface (an orange envelope in
    //    the shade) can be as bright as paper in shadow while its colour sets it clearly apart.
    val k = max(1, ceil(max(image.width, image.height) / SIZE.toDouble()).toInt())
    val small = downscale(image, k)
    fun gray(p: DocumentPoint): Double? {
      val x = p.x / k; val y = p.y / k
      return if (x < 0 || y < 0 || x > small.width - 1 || y > small.height - 1) null else bilinear(small, x, y)
    }
    /** The working copy at (u, v), NaN outside: the walks' inner loop, without allocating a point per sample. */
    fun grayUv(uu: Double, vv: Double): Double {
      val x = (uu * c - vv * s) / k; val y = (uu * s + vv * c) / k
      return if (x < 0 || y < 0 || x > small.width - 1 || y > small.height - 1) Double.NaN else bilinear(small, x, y)
    }
    fun level(u0: Double, u1: Double, v0: Double, v1: Double): Double {
      val values = mutableListOf<Double>()
      val step = k * 2.0
      var vv = v0
      while (vv <= v1) { var uu = u0; while (uu <= u1) { gray(xy(uu, vv))?.let(values::add); uu += step }; vv += step }
      return if (values.isEmpty()) 0.0 else values.sorted()[(values.size - 1) * 8 / 10]
    }
    val width = blockU1 - blockU0
    /**
     * Distance from (u, v) along (du, dv) to where the paper ends: the largest sustained step down in brightness
     * (means over a line and a half either side), when clear (at least 8% of the paper's level and 10 grey levels).
     * Print is too short to make a sustained step; a pale but different surface still makes a clear one. Beside the
     * paper ([sliver]) a few samples of that surface before the frame's border are enough; beyond an end the full
     * window must show it, since a false end stops the capture early and the frame's corners darken. Null when the
     * border comes first without one; [lastBorder] is then how far the border is.
     */
    var lastDrop = 0.0
    var lastBorder = 0.0
    /** The last walk reached the frame's border too soon to tell (rather than finding no step on the way). */
    var lastShort = false
    fun walk(u0: Double, v0: Double, du: Double, dv: Double, paper: Double, sliver: Boolean = false): Double? {
      val reach = width * 1.2 + lineHeight * 20
      val samples = DoubleArray(max(1, ((reach - 2.0 * k) / k).toInt() + 2))
      var count = 0
      var t = 2.0 * k
      while (t < reach) {
        var sum = 0.0; var inside = true
        for (o in -4..4) { val g = grayUv(u0 + du * t - dv * o * k, v0 + dv * t + du * o * k); if (g.isNaN()) { inside = false; break }; sum += g }
        if (!inside) break
        samples[count++] = sum / 9
        t += k
      }
      lastBorder = t
      val w = max(3, (lineHeight * 1.5 / k).toInt())
      val after = if (sliver) 3 else w
      lastShort = count < 3 + after + w
      // The margin between the print and the edge can be short: the window before the step is what there is (3 at
      // least) up to a line and a half.
      if (count < 3 + after) return null
      val prefix = DoubleArray(count + 1).also { for (i in 0 until count) it[i + 1] = it[i] + samples[i] }
      fun mean(a: Int, b: Int) = (prefix[b] - prefix[a]) / (b - a)
      var best = -1; var bestDrop = 0.0
      for (i in 3..count - after) {
        val drop = mean(max(0, i - w), i) - mean(i, min(count, i + w))
        if (drop > bestDrop) { bestDrop = drop; best = i }
      }
      if (best < 0 || bestDrop < max(10.0, paper * .08)) return null
      // The step is spread over the averaging window; its foot is where the brightness first falls below halfway.
      val half = (mean(max(0, best - w), best) + mean(best, min(count, best + w))) / 2
      val foot = (max(0, best - w) until min(count, best + w)).firstOrNull { samples[it] < half } ?: best
      lastDrop = bestDrop
      return 2.0 * k + foot * k
    }
    val bands = (0..20).map { blockV0 + (blockV1 - blockV0) * it / 20.0 }
    val sideDrops = mutableListOf<Double>()
    val bandLevels = mutableMapOf<Double, Double>() // the paper's level per band, the same for both sides
    /** Edge points of one side; where the frame cuts the paper with blank paper still beside the print, the border. */
    fun side(u0: Double, du: Double): Triple<List<Pair<Double, Double>>, List<Pair<Double, Double>>, Int> {
      val found = mutableListOf<Pair<Double, Double>>(); val border = mutableListOf<Pair<Double, Double>>(); var cut = 0
      for (vv in bands) {
        val paper = bandLevels.getOrPut(vv) { level(blockU0, blockU1, vv - lineHeight, vv + lineHeight) }
        val t = walk(u0, vv, du, 0.0, paper, sliver = true)
        when {
          t != null && t < width * .5 -> { found += vv to u0 + du * t; sideDrops += lastDrop; trace?.edges?.add(xy(u0 + du * t, vv)) }
          t == null && lastBorder >= lineHeight * .5 -> border += vv to u0 + du * (lastBorder - k)
          t == null -> cut++
        }
      }
      return Triple(found, border, cut)
    }
    val (left, leftBorder, leftCut) = side(blockU0, -1.0)
    val (right, rightBorder, rightCut) = side(blockU1, 1.0)
    // A side whose edge is mostly beyond the frame counts only if the frame still shows blank paper beside all the
    // print: the whole width of the text is in view. Print running into the border is cut.
    fun usable(found: List<Pair<Double, Double>>, border: List<Pair<Double, Double>>, cut: Int) =
      found.size >= 5 || (border.size + found.size >= bands.size * 2 / 3 && cut <= bands.size / 6)
    if (!usable(left, leftBorder, leftCut) || !usable(right, rightBorder, rightCut)) {
      return stop("sides: left ${left.size}+${leftBorder.size} found $leftCut cut, right ${right.size}+${rightBorder.size} found $rightCut cut")
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
    val leftLine = sideLine(left.takeIf { it.size >= 5 } ?: (left + leftBorder), blockU0)
    val rightLine = sideLine(right.takeIf { it.size >= 5 } ?: (right + rightBorder), blockU1)

    // With both sides beyond the frame there is no step to compare an end with; a clear step is then enough.
    val sideDrop = if (sideDrops.isEmpty()) 0.0 else sideDrops.sorted()[sideDrops.size / 2]
    fun end(v0: Double, dv: Double): Pair<Pair<Double, Double>, Boolean> {
      // Columns where the edge was found; where no step was found; where the border came too soon to tell (a tilted
      // receipt's end can leave the frame at one corner).
      val found = mutableListOf<Pair<Double, Double>>(); var cut = 0; var short = 0
      val paper = if (dv < 0) level(blockU0, blockU1, v0, v0 + lineHeight * 2) else level(blockU0, blockU1, v0 - lineHeight * 2, v0)
      for (f in listOf(.2, .35, .5, .65, .8)) {
        val uu = blockU0 + width * f
        // The paper's end steps onto the same background as its sides: a much weaker step is shading or a crease.
        // From two lines inside the print: a receipt can be torn right through its last line, which then pokes past
        // the edge, so a walk started at the last line's edge may already be off the paper.
        val start = v0 - dv * lineHeight * 2
        val t = walk(uu, start, 0.0, dv, paper)?.takeIf { lastDrop >= sideDrop * .35 }
        if (t != null) { found += uu to start + dv * t; trace?.edges?.add(xy(uu, start + dv * t)) }
        else if (lastShort) short++ else cut++
      }
      // Cut by the frame: the end is as far as the paper's whole width stays inside the frame.
      fun cutEnd(): Pair<Pair<Double, Double>, Boolean> {
        fun inside(t: Double) = listOf(leftLine, rightLine).all { (a, b) -> gray(xy(a + b * (v0 + dv * t), v0 + dv * t)) != null } &&
          gray(xy(blockU0 + width / 2, v0 + dv * t)) != null
        var t = 0.0
        while (inside(t + k)) t += k
        return (v0 + dv * t to 0.0) to false
      }
      if (found.size < 2 || cut > 1) return cutEnd()
      // An end runs straight across, like the print: its lean is kept small, as the sides'.
      val (a, b) = robustLine(found)
      val lean = b.coerceIn(-.15, .15)
      val mid = (blockU0 + blockU1) / 2
      val line = (a + b * mid - lean * mid) to lean
      // No print of this receipt lies beyond its end: if some does, the step was something else (a crease, a shadow).
      // (A box can poke past a torn edge; its centre can't.)
      val beyond = block.any { l -> DocumentPoint(l.corners.sumOf { it.x } / 4, l.corners.sumOf { it.y } / 4).let { p -> (v(p) - (line.first + line.second * u(p))) * dv > 0 } }
      return if (beyond) cutEnd() else line to true
    }
    val (topLine, topSeen) = end(blockV0, -1.0)
    val (bottomLine, bottomSeen) = end(blockV1, 1.0)

    // Corners: side lines u = a + b·v meet end lines v = a + b·u.
    fun corner(side: Pair<Double, Double>, end: Pair<Double, Double>): DocumentPoint {
      val vv = (end.first + end.second * side.first) / (1 - end.second * side.second)
      return xy(side.first + side.second * vv, vv)
    }
    val corners = listOf(corner(leftLine, topLine), corner(rightLine, topLine), corner(rightLine, bottomLine), corner(leftLine, bottomLine))
    // The paper's print: every box inside it, not only the column the paper was measured from (an item's number sits
    // apart from its line, in a narrow column of its own).
    val inside = lines.filter { l -> l.text.isNotBlank() && contains(corners, DocumentPoint(l.corners.sumOf { it.x } / 4, l.corners.sumOf { it.y } / 4)) }
    return PaperOutline(corners.map { DocumentPoint(it.x / image.width, it.y / image.height) }, topSeen, bottomSeen, (block + inside).distinct())
  }

  private fun contains(quad: List<DocumentPoint>, c: DocumentPoint): Boolean {
    var inside = false
    for (i in quad.indices) {
      val a = quad[i]; val b = quad[(i + 1) % quad.size]
      if ((a.y > c.y) != (b.y > c.y) && c.x < (b.x - a.x) * (c.y - a.y) / (b.y - a.y) + a.x) inside = !inside
    }
    return inside
  }

  /** Signed difference between two directions, in (-π, π]. */
  private fun angleBetween(a: Double, b: Double): Double = ((a - b) % (2 * PI) + 3 * PI) % (2 * PI) - PI

  /** A smaller copy whose value is the paper-likeness: brightness less one and a half times the colourfulness. */
  private fun downscale(image: GrayImage, k: Int): GrayImage {
    val w = image.width / k; val h = image.height / k
    val out = ByteArray(w * h)
    val pixels = image.pixels; val chroma = image.chroma
    for (y in 0 until h) for (x in 0 until w) {
      var sum = 0; var colour = 0
      for (dy in 0 until k) {
        var i = (y * k + dy) * image.width + x * k
        for (dx in 0 until k) { sum += pixels[i].toInt() and 0xff; if (chroma != null) colour += chroma[i].toInt() and 0xff; i++ }
      }
      out[y * w + x] = ((sum - colour * 3 / 2) / (k * k)).coerceIn(0, 255).toByte()
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
