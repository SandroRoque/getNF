package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/** An 8-bit grayscale image, row-major. Plain Kotlin, so the line model runs on the JVM as well as live (D82). */
internal class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
  init { require(pixels.size == width * height) }
  operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff
}

/** One printed row, in frame pixels measured at the paper's centre column (after deskew). */
internal data class PrintLine(
  val top: Double,
  val bottom: Double,
  /** How many pixels an ink edge takes to go from paper to ink: grows with blur. */
  val edgeWidth: Double,
  /** Paper brightness minus ink brightness, 0..255. */
  val contrast: Double,
  /** Share of the paper on these rows that is saturated (glare). */
  val glare: Double,
) {
  val height: Double get() = bottom - top
  val center: Double get() = (top + bottom) / 2
}

/**
 * What one frame shows of the receipt's print (D82). Thermal printers use one font on a fixed pitch, so text lines are
 * evenly spaced; `pitch(y)` is linear in `y` under perspective, and integrating `1 / pitch` turns frame rows into
 * print lines, the receipt's own coordinate.
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
) {
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
  private const val MAX_AXIS_DEG = 12.0  // the sides of a receipt seen in perspective lean, but not this much

  /**
   * Reads the print inside [polygon] (normalized, top-left, top-right, bottom-right, bottom-left, as in the capture's
   * segment metadata). Null when the frame shows too few text lines to measure a pitch.
   */
  fun read(image: GrayImage, polygon: List<DocumentPoint>): FramePrint? {
    require(polygon.size == 4)
    val w = image.width; val h = image.height
    val p = polygon.map { DocumentPoint(it.x * w, it.y * h) }
    fun along(a: DocumentPoint, b: DocumentPoint, y: Double) = if (abs(b.y - a.y) < 1e-6) a.x else a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)
    fun left(y: Double) = along(p[0], p[3], y)
    fun right(y: Double) = along(p[1], p[2], y)
    val cx = (p.sumOf { it.x }) / 4
    fun edgeAt(a: DocumentPoint, b: DocumentPoint) = if (abs(b.x - a.x) < 1e-6) a.y else a.y + (b.y - a.y) * (cx - a.x) / (b.x - a.x)
    // The paper must run up the frame: a receipt turned further than the deskew search needs rectifying first.
    val axis = listOf(p[0] to p[3], p[1] to p[2]).maxOf { (a, b) -> abs(atan2(b.x - a.x, b.y - a.y)) }
    if (axis > Math.toRadians(MAX_AXIS_DEG)) return null
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
    val period = periods(profile, y0, y1)
    val split = runs.flatMap { run -> period((run.first + run.last) / 2)?.let { split(profile, run, it) } ?: listOf(run) }
    runs.clear(); runs += split
    if (runs.size < 4) return null
    val typical = runs.map { it.last - it.first + 1.0 }.filter { it >= 4 }.sorted().let { if (it.isEmpty()) return null else it[it.size / 2] }
    val text = runs.filter { (it.last - it.first + 1) in (typical * .5).toInt()..(typical * 1.8).toInt() }
    val blocks = runs.filter { it.last - it.first + 1 > typical * 1.8 }
    fun measure(run: IntRange) = measure(image, ink, glare, paper, from, to, w, run, cx, bestSkew)
    val lines = text.map(::measure)
    val blockLines = blocks.map(::measure)

    // Pitch: spacing between neighbouring text lines, fitted against row.
    val spacings = lines.zipWithNext().map { (a, b) -> (a.center + b.center) / 2 to b.center - a.center }
      .filter { it.second <= typical * 2.6 }
    if (spacings.size < 3) return null
    val median = spacings.map { it.second }.sorted()[spacings.size / 2]
    val kept = spacings.filter { it.second in median * .75..median * 1.3 }
    if (kept.size < 3) return null
    val (a, b) = fit(kept)

    val bottomVisible = (y0..y1).lastOrNull { to[it] >= from[it] && left(it.toDouble()) >= 1 && right(it.toDouble()) <= w - 2 }
    val topVisible = (y0..y1).firstOrNull { to[it] >= from[it] && left(it.toDouble()) >= 1 && right(it.toDouble()) <= w - 2 }
    if (topVisible == null || bottomVisible == null) return null
    // A fit that collapses or more than doubles across the frame isn't perspective; the lines weren't found right.
    val ends = listOf(a + b * topVisible, a + b * bottomVisible)
    if (ends.min() < 3 || ends.max() / ends.min() > 2.2) return null
    val middle = (topVisible + bottomVisible) / 2.0
    return FramePrint(
      w, h, lines, blockLines, atan(bestSkew), a, b,
      paperTopRow.takeIf { it > 2 }, paperBottomRow.takeIf { it < h - 3 },
      topVisible.toDouble(), bottomVisible.toDouble(), right(middle) - left(middle),
    )
  }

  /**
   * The print's period around each row, from the autocorrelation of the ink profile in overlapping windows (perspective
   * changes it along a tilted frame). Null where the rows show no regular print.
   */
  private fun periods(profile: DoubleArray, y0: Int, y1: Int): (Int) -> Double? {
    val size = max(160, (y1 - y0 + 1) / 3)
    val found = mutableListOf<Pair<Int, Double>>()
    var start = y0
    while (true) {
      val end = min(y1, start + size - 1)
      period(profile, start, end)?.let { found += (start + end) / 2 to it }
      if (end >= y1) break
      start += size / 2
    }
    return { y -> found.minByOrNull { abs(it.first - y) }?.second }
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
    // The shortest strong period, not one of its multiples.
    return peaks.first { r[it] >= strongest * .8 }.toDouble()
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
    val values = IntArray(256); val gradients = IntArray(256)
    var n = 0; var inked = 0; var g = 0; var saturated = 0; var paperSum = 0.0
    for (r in run) {
      paperSum += paper[r]
      for (x in from[r]..to[r]) {
        val y = (r - (x - cx) * t).roundToInt()
        if (y !in to.indices || x < from[y] || x + 1 > to[y]) continue
        val v = image[x, y]
        n++
        if (ink[y * w + x]) { values[v]++; inked++ }
        if (glare[y * w + x]) saturated++
        if (ink[y * w + x] || ink[y * w + x + 1]) { gradients[abs(image[x + 1, y] - v)]++; g++ }
      }
    }
    val paperLevel = paperSum / run.count()
    // The ink's own darkness: sparse print (a dashed rule) is as dark as dense print.
    val inkLevel = if (inked == 0) paperLevel else percentile(values, inked, .5).toDouble()
    val contrast = max(0.0, paperLevel - inkLevel)
    val steepest = if (g == 0) 0 else percentile(gradients, g, .95)
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
