package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/**
 * Absolute bar for readable print (D82). With OCR, a zone is readable when the median confidence of its lines reaches
 * [minConfidence] (sharp frames read 0.66–0.80, smeared or soft ones 0.51–0.54 on the replayed sessions). Without
 * it, the pixel measures of a [PrintLine] stand in; they misjudge faint print.
 */
internal data class ReadabilityBar(
  val minHeight: Double = 10.0,
  val maxBlur: Double = .2,
  val minContrast: Double = 60.0,
  val maxGlare: Double = .05,
  val minConfidence: Double = .6,
) {
  fun readable(line: PrintLine): Boolean = line.sourceHeight >= minHeight && line.edgeWidth / line.height <= maxBlur &&
    line.contrast >= minContrast && line.glare <= maxGlare
}

internal enum class LineState { UNSEEN, SEEN, CAPTURED }

/**
 * A frame placed on the receipt: the stretch [from]–[to], in print lines from the paper's top edge, where both sides
 * of the paper are in view. The stretch is accepted as a whole where its print is in focus and large enough; [cells]
 * says so for each print line's height along it (the map's resolution, not a line-by-line test).
 */
internal data class PlacedFrame(val id: String, val from: Double, val to: Double, val cells: Map<Int, Boolean>)

/**
 * The receipt along its length, at the resolution of one print line, and the best any frame did on each stretch (D82). Frames are placed from the paper's
 * top edge, or from its bottom edge once some frame has shown both and so the length is known.
 */
internal class CoverageMap(private val bar: ReadabilityBar = ReadabilityBar()) {
  private val placed = mutableListOf<PlacedFrame>()
  val frames: List<PlacedFrame> get() = placed
  /** Print lines from the paper's top edge to its bottom edge. */
  var length: Double? = null; private set
  /** Paper width in print lines, for drawing the receipt to scale. */
  var widthInLines: Double? = null; private set
  private val lengths = mutableListOf<Double>()
  private val widths = mutableListOf<Double>()

  /** Learns the length from a frame that shows both ends. Returns whether it did. */
  fun measure(print: FramePrint): Boolean {
    val top = print.paperTop ?: return false
    val bottom = print.paperBottom ?: return false
    lengths += print.linesBetween(top, bottom)
    widths += print.paperWidth / print.pitch((print.visibleTop + print.visibleBottom) / 2)
    length = lengths.sorted()[lengths.size / 2]
    widthInLines = widths.sorted()[widths.size / 2]
    return true
  }

  /** Places [print] on the receipt; null when it shows neither end (content registration is a later step). */
  /** [ocr], when given, is what OCR read in the same frame; it then decides readability instead of the pixel measures. */
  fun place(id: String, print: FramePrint, ocr: List<OcrQuad>? = null): PlacedFrame? {
    val total = length
    val u: (Double) -> Double
    val row: (Double) -> Double
    val top = print.paperTop; val bottom = print.paperBottom
    when {
      top != null -> { u = { print.linesBetween(top, it) }; row = { print.rowAfter(top, it) } }
      bottom != null && total != null -> { u = { total - print.linesBetween(it, bottom) }; row = { print.rowAfter(bottom, it - total) } }
      else -> return null
    }
    // Where the paper's end is in view, the frame covers the paper to that end (a torn end is narrower than the paper).
    val from = if (top != null && u(print.visibleTop) < 1) 0.0 else max(0.0, u(print.visibleTop))
    val to = u(print.visibleBottom).let { if (total != null && (bottom != null && total - it < 1 || it > total)) total else it }
    if (to <= from) return null
    // Focus and scale are judged over zones a few print lines tall, so a dashed rule, the logo or one odd line doesn't
    // punch a hole in an accepted stretch, while a frame that goes soft towards one end (a tilted view) still does.
    val heightShare = print.lines.map { it.height / print.pitch(it.center) }.sorted().let { if (it.isEmpty()) .7 else it[it.size / 2] }
    // Readability is judged on lines of text, not on a logo's few stylized words, which OCR reads less surely.
    val read = ocr?.filter { it.characters >= 8 }?.map { line ->
      val centre = line.corners.let { c -> DocumentPoint(c.sumOf { it.x } / 4, c.sumOf { it.y } / 4) }
      u(print.fromFrame(centre.x, centre.y).y) to line.confidence
    }
    val sharpFrame = if (read != null) read.isNotEmpty() && median(read.map { it.second }) >= bar.minConfidence
      else print.lines.count { bar.readable(it) } >= print.lines.size / 2
    val positions = read ?: print.lines.map { u(it.center) to if (bar.readable(it)) 1.0 else 0.0 }
    val zoneReadable: (List<Double>) -> Boolean =
      if (read != null) { values -> median(values) >= bar.minConfidence } else { values -> values.count { it > 0 } >= values.size * .7 }
    val cells = mutableMapOf<Int, Boolean>()
    // A cell counts as in view when at least 40% of it is (the paper's torn ends are fractions of a line).
    for (k in ceil(from - .6).toInt() until floor(to + .6).toInt()) {
      val near = positions.filter { abs(it.first - (k + .5)) <= ZONE }
      cells[k] = if (near.size >= 2) zoneReadable(near.map { it.second })
      else sharpFrame && row(k + .5).let { print.pitch(it) * heightShare * print.scaleAt(it) } >= bar.minHeight
    }
    return PlacedFrame(id, from, to, cells).also { placed += it }
  }

  private companion object {
    const val ZONE = 3.0
    fun median(values: List<Double>) = values.sorted().let { it[(it.size - 1) / 2] }
  }

  fun state(cell: Int): LineState = when {
    placed.any { it.cells[cell] == true } -> LineState.CAPTURED
    placed.any { it.cells.containsKey(cell) } -> LineState.SEEN
    else -> LineState.UNSEEN
  }

  /** Cells from the top edge to the bottom edge (or to the furthest seen while the length is unknown). */
  val cells: IntRange get() = 0 until (length ?: placed.maxOfOrNull { it.to } ?: 0.0).let { (it + .4).toInt() }

  /** The fewest frames that capture every capturable cell (greedy set cover), in receipt order. */
  fun fewestFrames(): List<PlacedFrame> {
    val wanted = cells.filter { state(it) == LineState.CAPTURED }.toMutableSet()
    val chosen = mutableListOf<PlacedFrame>()
    while (wanted.isNotEmpty()) {
      val best = placed.maxBy { f -> f.cells.count { (k, ok) -> ok && k in wanted } }
      val gain = best.cells.filter { (k, ok) -> ok && k in wanted }.keys
      if (gain.isEmpty()) break
      chosen += best; wanted -= gain
    }
    return chosen.sortedBy { it.from }
  }
}
