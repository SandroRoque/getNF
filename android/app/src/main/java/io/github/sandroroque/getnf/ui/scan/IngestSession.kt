package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/** One analysed camera frame: its pixels and what OCR read in it. */
internal class IngestFrame(val id: String, val image: GrayImage, val ocr: List<OcrQuad>, val atMs: Long)

/** What the person should do next, one at a time. */
internal enum class IngestHint { FIND_RECEIPT, BACK_TO_CAPTURED, CLOSER, HOLD_STILL, MOVE_UP, MOVE_DOWN, DONE }

/**
 * What the session decided about a frame.
 *
 * [hold]: keep this frame's image; it is now the candidate to save (drop the one held before, unless [commits] names
 * it). [commits]: save these held frames now, in order; they are part of the capture (at most the one held before and
 * this frame). [view]: the rows of the receipt this frame shows, as indices into [IngestSession.rows] at the time.
 * [down]: the direction down the receipt in the frame, for an arrow.
 */
internal data class IngestStep(
  val paper: PaperOutline?,
  val view: IntRange?,
  val hold: Boolean,
  val commits: List<String>,
  val hint: IngestHint,
  val complete: Boolean,
  val down: DocumentPoint?,
)

/**
 * The capture planned from the receipt's print (D82–D85): every frame's rows are assembled into the receipt
 * ([RowAssembly]), and only the frames that read rows not captured yet, joined to what is captured, are kept.
 *
 * - Readable: a row whose own OCR confidence is at least 0.5 in a stretch (three rows either side) whose median is at
 *   least 0.6 (D83, D84), printed at least 20 pixels tall (D87): stretches are accepted as a whole, while a row read
 *   badly inside one still counts against.
 * - Keyframes: the frame reading the most rows not captured yet, and touching what is captured, is held; the same
 *   view read better replaces it, and a view moved along the receipt that reads rows past it has it committed and is
 *   held in turn (D87). The held frame is committed when the view no longer shares rows with it (the person moved
 *   on), when nothing better came for a while, or when it completes the capture.
 * - Done: both ends of the paper seen right at the receipt's first and last rows, and every row captured.
 */
internal class IngestSession {
  private val assembly = RowAssembly()
  val rows: List<RowAssembly.Entry> get() = assembly.rows
  private val captured = mutableSetOf<RowAssembly.Entry>()
  private class Held(val id: String, val reads: Set<RowAssembly.Entry>, val shows: Set<RowAssembly.Entry>, val at: Long)
  private var candidate: Held? = null
  private val pendingEnds = mutableListOf<Triple<List<Row>, Boolean, Boolean>>()
  private val committedIds = mutableListOf<String>()
  val committed: List<String> get() = committedIds
  private var done = false
  val topClosed: Boolean get() = assembly.topClosed
  val bottomClosed: Boolean get() = assembly.bottomClosed
  fun isCaptured(row: RowAssembly.Entry) = row in captured

  fun offer(frame: IngestFrame): IngestStep {
    if (done) return IngestStep(null, null, false, emptyList(), IngestHint.DONE, true, null)
    val paper = PaperFinder.find(frame.image, frame.ocr) ?: return skip(null, IngestHint.FIND_RECEIPT, frame)
    val down = down(paper, frame.image)
    val frameRows = RowAssembly.rows(paper, frame.image.width, frame.image.height)
    val readable = readable(frameRows)
    val at = assembly.add(frameRows, paper.topSeen, paper.bottomSeen, unreadPrint(frame.image, paper, frameRows))
    if (at == null) {
      // A frame that saw an end but can't be placed now (an overview before the assembly started, small print) may
      // close that end once the assembly has grown: it is kept a while and tried again.
      if (paper.topSeen || paper.bottomSeen) { pendingEnds += Triple(frameRows, paper.topSeen, paper.bottomSeen); if (pendingEnds.size > MAX_PENDING) pendingEnds.removeAt(0) }
      return skip(paper, if (assembly.rows.isEmpty()) closerOrStill(frameRows) else IngestHint.BACK_TO_CAPTURED, frame, down)
    }
    // By reference: closing an end on a pending frame may drop rows and shift the indices.
    val placed = at.map { j -> if (j >= 0) assembly.rows[j] else null }
    for ((rows, top, bottom) in pendingEnds) assembly.probeEnds(rows, top, bottom)
    // A row unreadable among neighbours read well, at a size OCR reads, is print that isn't text (a logo).
    for (i in placed.indices) if (!readable[i] && frameRows[i].height >= MIN_TEXT_PX && neighboursReadable(readable, i)) placed[i]?.let { it.illegible++ }
    val shows = placed.filterNotNull().filter { it in assembly.rows }.toSet()
    val reads = placed.indices.filter { readable[it] }.mapNotNull { placed[it] }.filter { it in assembly.rows }.toSet()
    val view = shows.map(assembly.rows::indexOf).let { if (it.isEmpty()) null else it.min()..it.max() }

    val commits = mutableListOf<String>()
    candidate?.let { held -> if ((shows intersect held.shows).size < MIN_SHARED || frame.atMs - held.at > PATIENCE_MS) commitHeld()?.let(commits::add) }
    val gain = reads.filter { it !in captured }.toSet()
    val joins = captured.isEmpty() || shows.any { it in captured }
    var hold = false
    if (gain.isNotEmpty() && joins) {
      val held = candidate
      val heldGain = held?.reads?.filter { it !in captured }?.toSet().orEmpty()
      val moved = held != null && shows.count { it !in held.shows } >= max(MIN_SHARED, held.shows.size / 3)
      when {
        held == null -> { candidate = Held(frame.id, reads, shows, frame.atMs); hold = true }
        // The view moved along the receipt and reads rows past the held one's: the held one is saved now (its rows
        // may not be in view again), and this one held for what it adds.
        moved && gain.count { it !in heldGain } >= MIN_SHARED -> { commitHeld()?.let(commits::add); candidate = Held(frame.id, reads, shows, frame.atMs); hold = true }
        // The same view read better (focus, a steadier hand): it takes the held one's place. What the frames read
        // differs a little from one to the next; a row it loses is read again while the view stays.
        gain.size > heldGain.size -> { candidate = Held(frame.id, reads, shows, frame.atMs); hold = true }
      }
    }
    // The frame that completes the capture is committed at once.
    candidate?.let { held -> if (completeWith(held.reads)) commitHeld()?.let(commits::add) }
    if (isComplete()) done = true

    val hint = when {
      done -> IngestHint.DONE
      frameRows.isNotEmpty() && readable.count { it } < frameRows.size / 2 -> closerOrStill(frameRows)
      else -> direction(view)
    }
    return IngestStep(paper, view, hold, commits, hint, done, down)
  }

  /** Commits the held frame, if any, and returns its id. */
  fun commitHeld(): String? {
    val held = candidate ?: return null
    candidate = null
    captured += held.reads
    committedIds += held.id
    return held.id
  }

  fun isComplete(): Boolean = completeWith(emptySet())

  private fun completeWith(extra: Set<RowAssembly.Entry>): Boolean =
    assembly.topClosed && assembly.bottomClosed && assembly.rows.isNotEmpty() && required().all { it in captured || it in extra } &&
      assembly.rows.none { it.printAfter }

  /**
   * The rows that must be captured: all, except one row right at a closed end that no frame read well, which is the
   * paper's torn edge (a strip of the previous receipt, or a last line the cut went through); rows under six
   * characters, which are OCR's splinters of other rows (an amount read apart), their print in the stretch around;
   * rows later frames passed over as not there ([RowAssembly.Entry.doubtful]); and rows three frames failed to read
   * while reading the rows around them well, which are print that isn't text (a logo), unless more frames read them.
   */
  fun required(): List<RowAssembly.Entry> {
    var rows = assembly.rows.filter { it.text.length >= 6 && !it.doubtful && (it.illegible < ILLEGIBLE || it.illegible * 2 <= it.confirmed || it in captured) }
    if (assembly.topClosed && rows.size > 2 && rows.first().confidence < MIN_ZONE && rows.first() !in captured) rows = rows.drop(1)
    if (assembly.bottomClosed && rows.size > 2 && rows.last().confidence < MIN_ZONE && rows.last() !in captured) rows = rows.dropLast(1)
    return rows
  }

  /** Each row: its own confidence at least 0.5, and the median over three rows either side at least 0.6. */
  private fun readable(rows: List<Row>): BooleanArray = BooleanArray(rows.size) { i -> rows[i].confidence >= MIN_ROW && zoneMedian(rows, i) >= MIN_ZONE && rows[i].height >= MIN_TEXT_PX }

  private fun neighboursReadable(readable: BooleanArray, i: Int): Boolean {
    val around = (max(0, i - ZONE) until min(readable.size, i + ZONE + 1)).filter { it != i }
    return around.isNotEmpty() && around.count { readable[it] } * 2 >= around.size
  }

  private fun zoneMedian(rows: List<Row>, i: Int): Double =
    rows.subList(max(0, i - ZONE), min(rows.size, i + ZONE + 1)).map { it.confidence }.sorted().let { it[(it.size - 1) / 2] }

  /**
   * For each row and the next, whether print OCR didn't read lies between them: where they sit further apart than
   * the usual spacing, the paper between them, across the text block, has sparse dark strokes (text). Clean paper is a
   * blank line; dense dark (a QR code, the logo) is not text.
   */
  private fun unreadPrint(image: GrayImage, paper: PaperOutline, rows: List<Row>): BooleanArray {
    val result = BooleanArray(max(0, rows.size - 1))
    if (rows.size < 3 || paper.lines.isEmpty()) return result
    val (across, down) = RowAssembly.printAxes(paper, image.width, image.height)
    val spans = paper.lines.flatMap { q -> q.corners.map { it.x * across.x + it.y * across.y } }
    val a0 = spans.min(); val a1 = spans.max()
    val pitch = rows.zipWithNext().map { (p, q) -> q.down - p.down }.filter { it > 0 }.sorted().let { if (it.isEmpty()) return result else it[it.size / 2] }
    for (i in 0 until rows.lastIndex) {
      val top = rows[i].down + rows[i].height / 2; val bottom = rows[i + 1].down - rows[i + 1].height / 2
      if (rows[i + 1].down - rows[i].down < pitch * 1.6 || bottom - top < 4) continue
      val rowHeight = (rows[i].height + rows[i + 1].height) / 2
      val (total, longest) = band(image, across, down, a0, a1, top, bottom)
      // Letters fill about 60% of OCR's box; a rule, however thick it renders, stays under half of it.
      result[i] = total in UNREAD_PRINT_MIN..UNREAD_PRINT_MAX && longest >= rowHeight * .5
    }
    return result
  }

  /**
   * The paper between [top] and [bottom] down the block, read line by line across it: the mean share of dark pixels
   * per line, and the longest band of lines with dark pixels clear of both edges (a band touching an edge is the row
   * above or below spilling past its box). Dark is against the paper in the same column: a shadow darkens a whole
   * column, ink only its strokes.
   */
  private fun band(image: GrayImage, across: DocumentPoint, down: DocumentPoint, a0: Double, a1: Double, top: Double, bottom: Double): Pair<Double, Int> {
    val columns = ceil((a1 - a0) / 2.0).toInt()
    val lines = mutableListOf<IntArray>()
    var d = top + 1
    while (d < bottom - 1) {
      lines += IntArray(columns) { n ->
        val a = a0 + n * 2.0
        val x = (a * across.x + d * down.x).toInt(); val y = (a * across.y + d * down.y).toInt()
        if (x in 0 until image.width && y in 0 until image.height) image[x, y] else -1
      }
      d += 1.0
    }
    if (lines.size < 2 || columns < 10) return 0.0 to 0
    val paperLevel = IntArray(columns) { n -> lines.maxOf { it[n] } }
    val shares = lines.map { l ->
      val inside = l.indices.filter { l[it] >= 0 && paperLevel[it] > 0 }
      if (inside.isEmpty()) 0.0 else inside.count { l[it] < paperLevel[it] * .6 }.toDouble() / inside.size
    }
    var run = 0; var longest = 0
    for ((k, share) in shares.withIndex()) {
      if (share >= .03) run++
      else { if (run > 0 && k - run > 0) longest = max(longest, run); run = 0 }
    }
    return shares.average() to longest
  }

  private fun closerOrStill(rows: List<Row>): IngestHint =
    if (rows.isEmpty() || rows.map { it.height }.sorted()[rows.size / 2] < MIN_TEXT_PX) IngestHint.CLOSER else IngestHint.HOLD_STILL

  /** Toward the nearest row not captured yet, or toward an end not seen yet. */
  private fun direction(view: IntRange?): IngestHint {
    if (view == null) return IngestHint.BACK_TO_CAPTURED
    val middle = (view.first + view.last) / 2.0
    val required = required().toSet()
    val missing = assembly.rows.indices.filter { assembly.rows[it] in required && assembly.rows[it] !in captured && candidate?.reads?.contains(assembly.rows[it]) != true }
    val nearest = missing.minByOrNull { abs(it - middle) }
    return when {
      nearest != null -> if (nearest < middle) IngestHint.MOVE_UP else IngestHint.MOVE_DOWN
      !assembly.bottomClosed -> IngestHint.MOVE_DOWN
      !assembly.topClosed -> IngestHint.MOVE_UP
      else -> IngestHint.HOLD_STILL
    }
  }

  private fun down(paper: PaperOutline, image: GrayImage): DocumentPoint {
    val c = paper.corners
    val dx = ((c[3].x + c[2].x) - (c[0].x + c[1].x)) * image.width; val dy = ((c[3].y + c[2].y) - (c[0].y + c[1].y)) * image.height
    val n = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
    return DocumentPoint(dx / n, dy / n)
  }

  private fun skip(paper: PaperOutline?, hint: IngestHint, frame: IngestFrame, down: DocumentPoint? = null): IngestStep {
    // A frame that can't be placed doesn't hold back a candidate for long.
    val commits = listOfNotNull(candidate?.takeIf { frame.atMs - it.at > PATIENCE_MS }?.let { commitHeld() })
    return IngestStep(paper, null, false, commits, hint, false, down)
  }

  private companion object {
    const val PATIENCE_MS = 1500L
    const val MIN_SHARED = 3
    const val ZONE = 3
    const val MIN_ROW = .5
    const val MIN_ZONE = .6
    // Text smaller than this (OCR box height, pixels) is read with errors at any confidence OCR reports.
    const val MIN_TEXT_PX = 20.0
    const val MAX_PENDING = 20
    const val ILLEGIBLE = 3
    // Dark share of the paper between two rows that means text OCR didn't read (a blank line has almost none; a QR
    // code or the logo is dense).
    const val UNREAD_PRINT_MIN = .02
    const val UNREAD_PRINT_MAX = .25
  }
}
