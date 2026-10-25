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
 * says so for each print line's height along it (the map's resolution, not a line-by-line test). [texts] are its
 * readable lines of text, normalized, with where they sit; [bottom] is where the paper's bottom edge is, when in view.
 */
internal data class PlacedFrame(
  val id: String,
  val from: Double,
  val to: Double,
  val cells: Map<Int, Boolean>,
  val texts: List<Pair<String, Double>> = emptyList(),
  val bottom: Double? = null,
) {
  val readable: Set<Int> get() = cells.filterValues { it }.keys
}

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

  /** A length measured where the print was readable; no estimate overrides it. */
  var confirmed = false; private set

  /** Learns the length from a frame that shows both ends. Returns whether it did. */
  fun measure(print: FramePrint): Boolean {
    val top = print.paperTop ?: return false
    val bottom = print.paperBottom ?: return false
    widths += print.paperWidth / print.pitch((print.visibleTop + print.visibleBottom) / 2)
    widthInLines = widths.sorted()[widths.size / 2]
    if (confirmed) return true
    lengths += print.linesBetween(top, bottom)
    length = lengths.sorted()[lengths.size / 2]
    return true
  }

  /** Sets the length from a readable frame placed along the chain from the top that shows the bottom edge. */
  fun confirmLength(value: Double) { length = value; confirmed = true }

  /** Places [print] from an end of the paper in view and adds it; null when it shows neither (see [locate]). */
  fun place(id: String, print: FramePrint, ocr: List<OcrQuad>? = null): PlacedFrame? =
    endOffset(print)?.let { locate(id, print, ocr, it) }?.also(::add)

  /**
   * Where row 0 of [print]'s upright paper sits on the receipt, in print lines, from an end of the paper in view: the
   * top edge directly, the bottom edge once the length is known. Null when neither applies.
   */
  fun endOffset(print: FramePrint): Double? {
    print.paperTop?.let { return -print.linesBetween(0.0, it) }
    val bottom = print.paperBottom ?: return null
    return length?.let { it - print.linesBetween(0.0, bottom) }
  }

  /**
   * [print] placed with row 0 of its upright paper at [offset] print lines from the receipt's top edge, its own print
   * lines stretched by [scale] (fitted to text placed before: a frame's pitch estimate is a little off, and the error
   * grows with the distance from where it was matched), without adding it. [ocr], when given, is what OCR read in the same frame; it then decides readability instead of the pixel
   * measures.
   */
  fun locate(id: String, print: FramePrint, ocr: List<OcrQuad>?, offset: Double, scale: Double = 1.0): PlacedFrame? {
    val total = length
    val u: (Double) -> Double = { offset + scale * print.linesBetween(0.0, it) }
    val row: (Double) -> Double = { print.rowAfter(0.0, (it - offset) / scale) }
    val top = print.paperTop; val bottom = print.paperBottom
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
    val texts = ocr.orEmpty().filter { it.confidence >= bar.minConfidence }.mapNotNull { line ->
      val text = normalize(line.text).takeIf { it.length >= 8 } ?: return@mapNotNull null
      val centre = line.corners.let { c -> DocumentPoint(c.sumOf { it.x } / 4, c.sumOf { it.y } / 4) }
      text to u(print.fromFrame(centre.x, centre.y).y)
    }
    return PlacedFrame(id, from, to, cells, texts, bottom?.let(u))
  }

  fun add(frame: PlacedFrame) { placed += frame }



  companion object {
    private const val ZONE = 3.0
    private fun median(values: List<Double>) = values.sorted().let { it[(it.size - 1) / 2] }
    /** A line's text for matching between frames: letters and digits only, upper case. */
    fun normalize(text: String): String = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
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
