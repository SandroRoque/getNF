package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/** Absolute bar for a readable print line (D82); provisional values until calibrated on real frames. */
internal data class ReadabilityBar(
  val minHeight: Double = 10.0,
  val maxBlur: Double = .2,
  val minContrast: Double = 60.0,
  val maxGlare: Double = .05,
) {
  fun readable(line: PrintLine): Boolean = line.height >= minHeight && line.edgeWidth / line.height <= maxBlur &&
    line.contrast >= minContrast && line.glare <= maxGlare
}

internal enum class LineState { UNSEEN, SEEN, CAPTURED }

/**
 * A frame placed on the receipt. Cells are one print line tall, counted from the paper's top edge; a cell maps to
 * readable or not, and [text] holds the cells where this frame found a text line (the rest are blank or graphics).
 */
internal data class PlacedFrame(val id: String, val from: Double, val to: Double, val cells: Map<Int, Boolean>, val text: Set<Int>)

/**
 * The receipt as a column of print lines and the best any frame did on each (D82). Frames are placed from the paper's
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
  fun place(id: String, print: FramePrint): PlacedFrame? {
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
    val heightShare = print.lines.map { it.height / print.pitch(it.center) }.sorted().let { if (it.isEmpty()) .7 else it[it.size / 2] }
    val sharpLines = print.lines.filter { bar.readable(it) }.size >= print.lines.size / 2
    val cells = mutableMapOf<Int, Boolean>(); val text = mutableSetOf<Int>()
    val byCell = print.lines.groupBy { floor(u(it.center)).toInt() }
    // A cell counts as in view when at least 40% of it is (the paper's torn ends are fractions of a line).
    for (k in ceil(from - .6).toInt() until floor(to + .6).toInt()) {
      val lines = byCell[k]
      cells[k] = if (lines != null) {
        text += k
        lines.all { bar.readable(it) }
      } else {
        // Blank paper or a graphic: readable if a text line here would have been, at this frame's scale and focus.
        val y = row(k + .5)
        sharpLines && print.pitch(y) * heightShare >= bar.minHeight
      }
    }
    return PlacedFrame(id, from, to, cells, text).also { placed += it }
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
