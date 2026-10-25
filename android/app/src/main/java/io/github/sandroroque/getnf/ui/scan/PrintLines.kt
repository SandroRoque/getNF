package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/** An 8-bit grayscale image, row-major. Plain Kotlin, so the line model runs on the JVM as well as live (D82). */
internal class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
  init { require(pixels.size == width * height) }
  operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff
}

/** One printed row, in rows of the upright paper (see [PrintLines.read]) at its centre column, after deskew. */
internal data class PrintLine(
  val top: Double,
  val bottom: Double,
  /** How many pixels an ink edge takes to go from paper to ink: grows with blur. */
  val edgeWidth: Double,
  /** Paper brightness minus ink brightness, 0..255. */
  val contrast: Double,
  /** Share of the paper on these rows that is saturated (glare). */
  val glare: Double,
  /** Camera pixels per upright row here: straightening a tilted view stretches its far end. */
  val scale: Double = 1.0,
) {
  val height: Double get() = bottom - top
  val center: Double get() = (top + bottom) / 2
  /** The line's height as the camera saw it, which is what decides whether it can be read. */
  val sourceHeight: Double get() = height * scale
}

/**
 * What one frame shows of the receipt's print (D82), in the upright paper: the frame's paper outline straightened into
 * a rectangle, so rows run along the print however the phone was held. Thermal printers use one font on a fixed
 * pitch, so text lines are evenly spaced; `pitch(y)` is close to linear in `y` (what perspective is left after
 * straightening an imperfect outline), and integrating `1 / pitch` turns rows into print lines, the receipt's own
 * coordinate.
 */
internal data class FramePrint(
  val width: Int,
  val height: Int,
  /** Text lines. Ink much taller than a line (logo, QR code) is in [blocks]; thin rules are dropped. */
  val lines: List<PrintLine>,
  val blocks: List<PrintLine>,
  val skew: Double,
  val pitchA: Double,
  val pitchB: Double,
  /** The paper's ends at the centre column, when they are inside the frame. */
  val paperTop: Double?,
  val paperBottom: Double?,
  /** Rows where the paper is inside the frame from side to side. */
  val visibleTop: Double,
  val visibleBottom: Double,
  /** Paper width in pixels at the centre of the visible rows, to relate width to pitch. */
  val paperWidth: Double,
  /** Camera pixels per upright row, per row. */
  val rowScale: DoubleArray = DoubleArray(0),
  /** From the upright paper back to the camera frame, in pixels. */
  val toFrame: (Double, Double) -> DocumentPoint = { x, y -> DocumentPoint(x, y) },
  /** From the camera frame, in pixels, into the upright paper. */
  val fromFrame: (Double, Double) -> DocumentPoint = { x, y -> DocumentPoint(x, y) },
) {
  fun scaleAt(y: Double): Double = if (rowScale.isEmpty()) 1.0 else rowScale[y.toInt().coerceIn(0, rowScale.lastIndex)]
  fun pitch(y: Double): Double = pitchA + pitchB * y
  /** Print lines between rows [y0] and [y1]. */
  fun linesBetween(y0: Double, y1: Double): Double =
    if (abs(pitchB) < 1e-9) (y1 - y0) / pitchA else ln(pitch(y1) / pitch(y0)) / pitchB
  /** The row [lines] print lines below row [y0]; inverse of [linesBetween]. */
  fun rowAfter(y0: Double, lines: Double): Double =
    if (abs(pitchB) < 1e-9) y0 + lines * pitchA else (pitch(y0) * exp(lines * pitchB) - pitchA) / pitchB
}

internal object PrintLines {
  private const val INK = .6          // a pixel darker than this share of its row's paper is ink
  private const val ROW_INK = .015    // a row with this share of ink pixels is printed
  private const val MAX_SKEW_DEG = 4.0

  /**
   * Reads the print inside [polygon] (normalized, top-left, top-right, bottom-right, bottom-left, as in the capture's
   * segment metadata). The outline is first straightened into an upright rectangle as large as its longest edges, so
   * a tilted or turned view reads like a flat one. An edge lying along the frame's border is where the frame cuts the
   * paper, not the paper's end. Null when a side is cut (no stretch shows the paper's full width) or the frame shows
   * too few text lines to measure a pitch.
   */
  fun read(image: GrayImage, paper: PaperOutline): FramePrint? = read(image, paper.corners, paper.topSeen to paper.bottomSeen)

  /** [ends], when known (from [PaperFinder]), says whether the outline's top and bottom are the paper's own ends. */
  fun read(image: GrayImage, polygon: List<DocumentPoint>, ends: Pair<Boolean, Boolean>? = null): FramePrint? {
    require(polygon.size == 4)
    val p = polygon.map { DocumentPoint(it.x * image.width, it.y * image.height) }
    fun cut(a: DocumentPoint, b: DocumentPoint) = (a.x <= 2 && b.x <= 2) || (a.x >= image.width - 3 && b.x >= image.width - 3) ||
      (a.y <= 2 && b.y <= 2) || (a.y >= image.height - 3 && b.y >= image.height - 3)
    if (ends == null && (cut(p[0], p[3]) || cut(p[1], p[2]))) return null
    val w = max(ReceiptGeometry.distance(p[0], p[1]), ReceiptGeometry.distance(p[3], p[2])).roundToInt()
    val h = max(ReceiptGeometry.distance(p[0], p[3]), ReceiptGeometry.distance(p[1], p[2])).roundToInt()
    if (w < 40 || h < 40) return null
    val upright = listOf(DocumentPoint(0.0, 0.0), DocumentPoint(w.toDouble(), 0.0), DocumentPoint(w.toDouble(), h.toDouble()), DocumentPoint(0.0, h.toDouble()))
    val toFrame = homography(upright, p); val fromFrame = homography(p, upright)
    val pixels = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      val s = toFrame(x + .5, y + .5)
      pixels[y * w + x] = bilinear(image, s.x - .5, s.y - .5).toByte()
    }
    val rowScale = DoubleArray(h) { ReceiptGeometry.distance(toFrame(w / 2.0, it.toDouble()), toFrame(w / 2.0, it + 1.0)) }
    val topSeen = ends?.first ?: (!cut(p[0], p[1]) && !continuesPast(image, p[3], p[0], p[2], p[1]))
    val bottomSeen = ends?.second ?: (!cut(p[3], p[2]) && !continuesPast(image, p[0], p[3], p[1], p[2]))
    val print = readUpright(GrayImage(w, h, pixels), topSeen, bottomSeen) ?: return null
    fun scaled(line: PrintLine) = line.copy(scale = rowScale[line.center.toInt().coerceIn(0, h - 1)])
    return print.copy(lines = print.lines.map(::scaled), blocks = print.blocks.map(::scaled), rowScale = rowScale, toFrame = toFrame, fromFrame = fromFrame)
  }

  /**
   * Whether the paper carries on past the outline's edge from [a] to [b], whose sides run from [sideA] to [a] and from
   * [sideB] to [b]: the capture's mask stops at a strong shadow (D41), and there the paper's sides go on, while at the
   * paper's real end they stop. Compares the step across each side just past the edge with the step just before it.
   */
  private fun continuesPast(image: GrayImage, sideA: DocumentPoint, a: DocumentPoint, sideB: DocumentPoint, b: DocumentPoint): Boolean {
    fun step(from: DocumentPoint, to: DocumentPoint, start: Double, end: Double): Double? {
      val length = ReceiptGeometry.distance(from, to); if (length < 1) return null
      val dx = (to.x - from.x) / length; val dy = (to.y - from.y) / length
      val values = mutableListOf<Double>()
      var t = start * length
      while (t <= end * length) {
        val x = to.x + dx * (t - length); val y = to.y + dy * (t - length)
        // The outline comes from a coarse mask and can sit some pixels off the border: take the strongest step near it.
        var best = -1
        for (o in -14..14 step 2) {
          val cx = x - dy * o; val cy = y + dx * o
          val (ax, ay) = cx - dy * 5 to cy + dx * 5; val (bx, by) = cx + dy * 5 to cy - dx * 5
          if (listOf(ax, bx).all { it in 0.0..image.width - 1.0 } && listOf(ay, by).all { it in 0.0..image.height - 1.0 }) {
            best = max(best, abs(bilinear(image, ax, ay) - bilinear(image, bx, by)))
          }
        }
        if (best >= 0) values += best.toDouble()
        t += 2.0
      }
      return if (values.size < 8) null else values.sorted()[values.size / 2]
    }
    val sides = listOf(sideA to a, sideB to b).map { (s, e) -> step(s, e, .85, 1.0) to step(s, e, 1.03, 1.15) }
    // Past the frame's border or too short to tell: the outline is trusted.
    if (sides.any { it.first == null || it.second == null }) return false
    return sides.all { (inside, past) -> inside!! > 15 && past!! >= inside * .35 }
  }

  /** The print of an upright paper filling [image]; [topSeen] and [bottomSeen] say whether its ends are the paper's. */
  private fun readUpright(image: GrayImage, topSeen: Boolean, bottomSeen: Boolean): FramePrint? {
    val w = image.width; val h = image.height
    val p = listOf(DocumentPoint(0.0, 0.0), DocumentPoint(w - 1.0, 0.0), DocumentPoint(w - 1.0, h - 1.0), DocumentPoint(0.0, h - 1.0))
    fun along(a: DocumentPoint, b: DocumentPoint, y: Double) = if (abs(b.y - a.y) < 1e-6) a.x else a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)
    fun left(y: Double) = along(p[0], p[3], y)
    fun right(y: Double) = along(p[1], p[2], y)
    val cx = (p.sumOf { it.x }) / 4
    fun edgeAt(a: DocumentPoint, b: DocumentPoint) = if (abs(b.x - a.x) < 1e-6) a.y else a.y + (b.y - a.y) * (cx - a.x) / (b.x - a.x)
    val paperTopRow = edgeAt(p[0], p[1]); val paperBottomRow = edgeAt(p[3], p[2])
    val y0 = max(0, ceil(paperTopRow).toInt()); val y1 = min(h - 1, floor(paperBottomRow).toInt())
    if (y1 - y0 < 20) return null

    // The paper's own brightness varies (shade, glare, curl), so ink is judged against the paper around it: the 90th
    // percentile in small blocks, interpolated. A small inner margin keeps the paper's edge out.
    val from = IntArray(h); val to = IntArray(h); val paper = DoubleArray(h)
    for (y in y0..y1) {
      val l = left(y.toDouble()); val r = right(y.toDouble()); val margin = (r - l) * .03
      from[y] = max(0, ceil(l + margin).toInt()); to[y] = min(w - 1, floor(r - margin).toInt())
      if (to[y] - from[y] < 10) to[y] = from[y] - 1
    }
    val background = background(image, from, to, y0, y1)
    val ink = BooleanArray(w * h); val glare = BooleanArray(w * h)
    for (y in y0..y1) {
      if (to[y] < from[y]) continue
      // The outline can run a little outside the paper: dark runs touching its sides are table, not print.
      val limit = ((to[y] - from[y]) * .08).toInt()
      var trimmed = 0
      while (trimmed < limit && image[from[y], y] < background(from[y], y) * INK) { from[y]++; trimmed++ }
      trimmed = 0
      while (trimmed < limit && image[to[y], y] < background(to[y], y) * INK) { to[y]--; trimmed++ }
      var sum = 0.0; var dark = 0
      for (x in from[y]..to[y]) {
        val v = image[x, y]; val bg = background(x, y)
        sum += bg; ink[y * w + x] = v < bg * INK; glare[y * w + x] = v >= 250
        if (ink[y * w + x]) dark++
      }
      paper[y] = sum / (to[y] - from[y] + 1)
      // Mostly dark is the table beyond a torn or curled end, not print; no printed row is that dense.
      if (dark > (to[y] - from[y] + 1) * .6) { for (x in from[y]..to[y]) ink[y * w + x] = false; to[y] = from[y] - 1 }
    }

    // Deskew: the shear that makes the row profile of ink sharpest.
    var bestSkew = 0.0; var bestScore = -1.0; var profile = DoubleArray(0)
    var angle = -MAX_SKEW_DEG
    while (angle <= MAX_SKEW_DEG + 1e-9) {
      val t = tan(Math.toRadians(angle))
      val candidate = rowProfile(ink, w, h, y0, y1, from, to, cx, t)
      var score = 0.0
      for (i in 1 until candidate.size) { val d = candidate[i] - candidate[i - 1]; score += d * d }
      if (score > bestScore) { bestScore = score; bestSkew = t; profile = candidate }
      angle += .25
    }

    // Printed runs of rows; gaps of one row are closed.
    val runs = mutableListOf<IntRange>()
    var start = -1; var gap = 0
    for (y in y0..y1 + 1) {
      val printed = y <= y1 && profile[y] >= ROW_INK
      if (printed) { if (start < 0) start = y; gap = 0 }
      else if (start >= 0) {
        gap++
        if (gap > 1 || y > y1) { val end = y - gap; if (end - start + 1 >= 3) runs += start..end; start = -1; gap = 0 }
      }
    }
    if (runs.size < 2) return null
    // Closely spaced lines touch at the threshold (small print, a slight curl): split runs about two lines tall at
    // clear valleys. The local period keeps a single line, whose own profile dips between letters' bars, whole.
    val windows = periods(profile, y0, y1)
    fun period(y: Int) = windows.minByOrNull { abs(it.first - y) }?.second
    val split = runs.flatMap { run -> period((run.first + run.last) / 2)?.let { split(profile, run, it) } ?: listOf(run) }
    runs.clear(); runs += split
    if (runs.size < 4) return null
    val typical = runs.map { it.last - it.first + 1.0 }.filter { it >= 4 }.sorted().let { if (it.isEmpty()) return null else it[it.size / 2] }
    val text = runs.filter { (it.last - it.first + 1) in (typical * .5).toInt()..(typical * 1.8).toInt() }
    val blocks = runs.filter { it.last - it.first + 1 > typical * 1.8 }
    fun measure(run: IntRange) = measure(image, ink, glare, paper, from, to, w, run, cx, bestSkew)
    val lines = text.map(::measure)
    val blockLines = blocks.map(::measure)

    // Pitch against row: from the windows' periods, which don't need the lines to be told apart; outliers (a window
    // over the QR code or the logo) dropped. Spacing between neighbouring lines when too few windows show print.
    val (a, b) = pitchFit(windows) ?: run {
      val spacings = lines.zipWithNext().map { (a, b) -> (a.center + b.center) / 2 to b.center - a.center }
        .filter { it.second <= typical * 2.6 }
      if (spacings.size < 3) return null
      val median = spacings.map { it.second }.sorted()[spacings.size / 2]
      val kept = spacings.filter { it.second in median * .75..median * 1.3 }
      if (kept.size < 3) return null
      fit(kept)
    }

    val bottomVisible = (y0..y1).lastOrNull { to[it] >= from[it] }
    val topVisible = (y0..y1).firstOrNull { to[it] >= from[it] }
    if (topVisible == null || bottomVisible == null) return null
    // A fit that collapses or more than doubles across the frame isn't perspective; the lines weren't found right.
    val ends = listOf(a + b * topVisible, a + b * bottomVisible)
    if (ends.min() < 3 || ends.max() / ends.min() > 2.2) return null
    val middle = (topVisible + bottomVisible) / 2.0
    return FramePrint(
      w, h, lines, blockLines, atan(bestSkew), a, b,
      paperTopRow.takeIf { topSeen }, paperBottomRow.takeIf { bottomSeen },
      if (topSeen) 0.0 else topVisible.toDouble(), if (bottomSeen) h - 1.0 else bottomVisible.toDouble(), right(middle) - left(middle),
    )
  }

  /**
   * The print's period around each row, from the autocorrelation of the ink profile in overlapping windows (perspective
   * changes it along a tilted frame). Null where the rows show no regular print.
   */
  private fun periods(profile: DoubleArray, y0: Int, y1: Int): List<Pair<Int, Double>> {
    val size = max(160, (y1 - y0 + 1) / 4)
    val found = mutableListOf<Pair<Int, Double>>()
    var start = y0
    while (true) {
      val end = min(y1, start + size - 1)
      period(profile, start, end)?.let { found += (start + end) / 2 to it }
      if (end >= y1) break
      start += size / 2
    }
    return found
  }

  private fun pitchFit(windows: List<Pair<Int, Double>>): Pair<Double, Double>? {
    if (windows.size < 2) return null
    val median = windows.map { it.second }.sorted()[windows.size / 2]
    val kept = windows.filter { it.second in median * .8..median * 1.25 }
    return if (kept.size < 2) null else fit(kept.map { it.first.toDouble() to it.second })
  }

  private fun period(profile: DoubleArray, from: Int, to: Int): Double? {
    val n = to - from + 1
    val mean = (from..to).sumOf { profile[it] } / n
    val variance = (from..to).sumOf { (profile[it] - mean).pow(2) }
    if (variance < 1e-9) return null
    val maxLag = min(160, n / 3)
    if (maxLag < 6) return null
    val r = DoubleArray(maxLag + 1)
    for (lag in 4..maxLag) {
      var sum = 0.0
      for (i in from..to - lag) sum += (profile[i] - mean) * (profile[i + lag] - mean)
      r[lag] = sum / variance
    }
    val peaks = (5 until maxLag).filter { r[it] > r[it - 1] && r[it] >= r[it + 1] && r[it] > .15 }
    val strongest = peaks.maxOfOrNull { r[it] } ?: return null
    // The shortest strong period, not one of its multiples, refined between lags by a parabola through its neighbours.
    val lag = peaks.first { r[it] >= strongest * .5 }
    val curve = r[lag - 1] - 2 * r[lag] + r[lag + 1]
    return lag + if (curve < 0) .5 * (r[lag - 1] - r[lag + 1]) / curve else 0.0
  }

  /**
   * Splits [run], when it is at least 1.3 periods tall, at its deepest valley that is clearly lower than the ink on
   * both sides (under 60% of the lower peak) and leaves both parts at least 0.35 periods tall; recursively. A logo or a
   * QR code has no such valleys and stays whole.
   */
  private fun split(profile: DoubleArray, run: IntRange, period: Double): List<IntRange> {
    if (run.last - run.first + 1 < period * 1.3) return listOf(run)
    val part = max(2, (period * .35).toInt()); val reach = max(2, (period * .6).toInt())
    var best = -1; var bestDepth = 0.0
    for (m in run.first + part..run.last - part) {
      val v = (profile[m - 1] + profile[m] + profile[m + 1]) / 3
      val leftPeak = (max(run.first, m - reach) until m).maxOf { profile[it] }
      val rightPeak = (m + 1..min(run.last, m + reach)).maxOf { profile[it] }
      val lower = min(leftPeak, rightPeak)
      if (v < lower * .6 && lower - v > bestDepth) { best = m; bestDepth = lower - v }
    }
    if (best < 0) return listOf(run)
    return listOf(run.first until best, best + 1..run.last).flatMap { split(profile, it, period) }
  }

  /** Paper brightness around each pixel: the 90th percentile of each block of paper, bilinearly interpolated. */
  private fun background(image: GrayImage, from: IntArray, to: IntArray, y0: Int, y1: Int): (Int, Int) -> Double {
    val size = max(16, image.width / 40)
    val cols = (image.width + size - 1) / size; val rows = (image.height + size - 1) / size
    val level = DoubleArray(cols * rows) { Double.NaN }
    val histogram = IntArray(256)
    for (by in 0 until rows) for (bx in 0 until cols) {
      histogram.fill(0); var n = 0
      for (y in max(y0, by * size) until min(y1 + 1, (by + 1) * size)) {
        if (to[y] < from[y]) continue
        for (x in max(from[y], bx * size)..min(to[y], (bx + 1) * size - 1)) { histogram[image[x, y]]++; n++ }
      }
      if (n >= size * size / 4) level[by * cols + bx] = percentile(histogram, n, .9).toDouble()
    }
    // Blocks with too little paper take the nearest measured block in their row, then column.
    for (by in 0 until rows) fillGaps(level, by * cols, 1, cols)
    for (bx in 0 until cols) fillGaps(level, bx, cols, rows)
    val fallback = level.filter { !it.isNaN() }.average().takeIf { !it.isNaN() } ?: 255.0
    for (i in level.indices) if (level[i].isNaN()) level[i] = fallback
    return { x, y ->
      val fx = ((x + .5) / size - .5).coerceIn(0.0, cols - 1.0); val fy = ((y + .5) / size - .5).coerceIn(0.0, rows - 1.0)
      val ix = min(fx.toInt(), cols - 2).coerceAtLeast(0); val iy = min(fy.toInt(), rows - 2).coerceAtLeast(0)
      val tx = if (cols > 1) fx - ix else 0.0; val ty = if (rows > 1) fy - iy else 0.0
      fun at(c: Int, r: Int) = level[min(r, rows - 1) * cols + min(c, cols - 1)]
      (at(ix, iy) * (1 - tx) + at(ix + 1, iy) * tx) * (1 - ty) + (at(ix, iy + 1) * (1 - tx) + at(ix + 1, iy + 1) * tx) * ty
    }
  }

  private fun fillGaps(values: DoubleArray, start: Int, step: Int, count: Int) {
    val known = (0 until count).filter { !values[start + it * step].isNaN() }
    if (known.isEmpty()) return
    for (i in 0 until count) if (values[start + i * step].isNaN()) {
      values[start + i * step] = values[start + known.minBy { abs(it - i) } * step]
    }
  }

  /** Ink share per (sheared) row. Every second column is enough. */
  private fun rowProfile(ink: BooleanArray, w: Int, h: Int, y0: Int, y1: Int, from: IntArray, to: IntArray, cx: Double, t: Double): DoubleArray {
    val sum = DoubleArray(h); val count = DoubleArray(h)
    for (y in y0..y1) {
      var x = from[y]
      while (x <= to[y]) {
        val r = (y + (x - cx) * t).roundToInt()
        if (r in y0..y1) { count[r]++; if (ink[y * w + x]) sum[r]++ }
        x += 2
      }
    }
    return DoubleArray(h) { if (count[it] > 0) sum[it] / count[it] else 0.0 }
  }

  private fun measure(
    image: GrayImage, ink: BooleanArray, glare: BooleanArray, paper: DoubleArray, from: IntArray, to: IntArray,
    w: Int, run: IntRange, cx: Double, t: Double,
  ): PrintLine {
    // Edges are measured across and along the line separately: motion blur smears one direction and leaves the other
    // steep, so the worse of the two decides.
    val values = IntArray(256); val across = IntArray(256); val along = IntArray(256)
    var n = 0; var inked = 0; var gx = 0; var gy = 0; var saturated = 0; var paperSum = 0.0
    for (r in run) {
      paperSum += paper[r]
      for (x in from[r]..to[r]) {
        val y = (r - (x - cx) * t).roundToInt()
        if (y !in to.indices || x < from[y] || x + 1 > to[y]) continue
        val v = image[x, y]
        n++
        if (ink[y * w + x]) { values[v]++; inked++ }
        if (glare[y * w + x]) saturated++
        if (ink[y * w + x] || ink[y * w + x + 1]) { across[abs(image[x + 1, y] - v)]++; gx++ }
        if (y + 1 < image.height && (ink[y * w + x] || ink[(y + 1) * w + x])) { along[abs(image[x, y + 1] - v)]++; gy++ }
      }
    }
    val paperLevel = paperSum / run.count()
    // The ink's own darkness: sparse print (a dashed rule) is as dark as dense print.
    val inkLevel = if (inked == 0) paperLevel else percentile(values, inked, .5).toDouble()
    val contrast = max(0.0, paperLevel - inkLevel)
    val steepest = if (gx == 0 || gy == 0) 0 else min(percentile(across, gx, .95), percentile(along, gy, .95))
    return PrintLine(
      run.first.toDouble(), run.last + 1.0,
      if (steepest == 0) Double.POSITIVE_INFINITY else contrast / steepest,
      contrast, if (n == 0) 0.0 else saturated.toDouble() / n,
    )
  }

  private fun percentile(histogram: IntArray, total: Int, q: Double): Int {
    val target = (total * q).toInt()
    var seen = 0
    for (v in histogram.indices) { seen += histogram[v]; if (seen > target) return v }
    return histogram.lastIndex
  }

  /** The projective map taking the four points [from] to [to]. */
  private fun homography(from: List<DocumentPoint>, to: List<DocumentPoint>): (Double, Double) -> DocumentPoint {
    val a = Array(8) { DoubleArray(9) }
    for (i in 0 until 4) {
      val (x, y) = from[i].x to from[i].y; val (u, v) = to[i].x to to[i].y
      a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
      a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
    }
    for (c in 0 until 8) {
      val pivot = (c until 8).maxBy { abs(a[it][c]) }
      val t = a[c]; a[c] = a[pivot]; a[pivot] = t
      for (r in 0 until 8) if (r != c) {
        val f = a[r][c] / a[c][c]
        for (k in c..8) a[r][k] -= f * a[c][k]
      }
    }
    val m = DoubleArray(8) { a[it][8] / a[it][it] }
    return { x, y ->
      val d = m[6] * x + m[7] * y + 1
      DocumentPoint((m[0] * x + m[1] * y + m[2]) / d, (m[3] * x + m[4] * y + m[5]) / d)
    }
  }

  private fun bilinear(image: GrayImage, x: Double, y: Double): Int {
    val fx = x.coerceIn(0.0, image.width - 1.0); val fy = y.coerceIn(0.0, image.height - 1.0)
    val x0 = min(fx.toInt(), image.width - 2).coerceAtLeast(0); val y0 = min(fy.toInt(), image.height - 2).coerceAtLeast(0)
    val tx = fx - x0; val ty = fy - y0
    val x1 = min(x0 + 1, image.width - 1); val y1 = min(y0 + 1, image.height - 1)
    return ((image[x0, y0] * (1 - tx) + image[x1, y0] * tx) * (1 - ty) + (image[x0, y1] * (1 - tx) + image[x1, y1] * tx) * ty).roundToInt()
  }

  /** Least squares `spacing = a + b·row`. */
  private fun fit(points: List<Pair<Double, Double>>): Pair<Double, Double> {
    val n = points.size.toDouble()
    val mx = points.sumOf { it.first } / n; val my = points.sumOf { it.second } / n
    val sxx = points.sumOf { (it.first - mx).pow(2) }
    if (sxx < 1e-6) return my to 0.0
    val b = points.sumOf { (it.first - mx) * (it.second - my) } / sxx
    return (my - b * mx) to b
  }
}
