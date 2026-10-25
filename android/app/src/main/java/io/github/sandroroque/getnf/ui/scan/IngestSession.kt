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
 *   least 0.6 (D83, D84): stretches are accepted as a whole, while a row read badly inside one still counts against.
 * - Keyframes: while the view moves along the receipt, the frame reading the most rows not captured yet, and touching
 *   what is captured, is held; a frame reading all it would add and more replaces it, and a frame reading rows it
 *   can't has it committed and is held in turn. The held frame is committed when the view no longer shares rows with
 *   it (the person moved on), when nothing better came for a while, or when it completes the capture.
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
    for ((rows, top, bottom) in pendingEnds) assembly.probeEnds(rows, top, bottom)
    val shows = at.filter { it >= 0 }.map { assembly.rows[it] }.toSet()
    val reads = at.indices.filter { at[it] >= 0 && readable[it] }.map { assembly.rows[at[it]] }.toSet()
    val view = at.filter { it >= 0 }.let { if (it.isEmpty()) null else it.min()..it.max() }

    val commits = mutableListOf<String>()
    candidate?.let { held -> if ((shows intersect held.shows).size < MIN_SHARED || frame.atMs - held.at > PATIENCE_MS) commitHeld()?.let(commits::add) }
    val gain = reads.filter { it !in captured }.toSet()
    val joins = captured.isEmpty() || shows.any { it in captured }
    var hold = false
    if (gain.isNotEmpty() && joins) {
      val held = candidate
      val heldGain = held?.reads?.filter { it !in captured }?.toSet().orEmpty()
      when {
        // The new frame reads everything the held one would add, and more: it takes its place.
        held == null || (gain.containsAll(heldGain) && gain.size > heldGain.size) -> { candidate = Held(frame.id, reads, shows, frame.atMs); hold = true }
        // It reads rows the held one can't: the held one is saved now, and this one held for what it adds.
        !heldGain.containsAll(gain) -> { commitHeld()?.let(commits::add); candidate = Held(frame.id, reads, shows, frame.atMs); hold = true }
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
   * and rows later frames passed over as not there ([RowAssembly.Entry.doubtful]).
   */
  fun required(): List<RowAssembly.Entry> {
    var rows = assembly.rows.filter { it.text.length >= 6 && !it.doubtful }
    if (assembly.topClosed && rows.size > 2 && rows.first().confidence < MIN_ZONE && rows.first() !in captured) rows = rows.drop(1)
    if (assembly.bottomClosed && rows.size > 2 && rows.last().confidence < MIN_ZONE && rows.last() !in captured) rows = rows.dropLast(1)
    return rows
  }

  /** Each row: its own confidence at least 0.5, and the median over three rows either side at least 0.6. */
  private fun readable(rows: List<Row>): BooleanArray = BooleanArray(rows.size) { i ->
    val zone = rows.subList(max(0, i - ZONE), min(rows.size, i + ZONE + 1)).map { it.confidence }.sorted()
    rows[i].confidence >= MIN_ROW && zone[(zone.size - 1) / 2] >= MIN_ZONE
  }

  /**
   * For each row and the next, whether print OCR didn't read lies between them: where they sit further apart than
   * the usual spacing, the paper between them, across the text block, has sparse dark strokes (text). Clean paper is a
   * blank line; dense dark (a QR code, the logo) is not text.
   */
  private fun unreadPrint(image: GrayImage, paper: PaperOutline, rows: List<Row>): BooleanArray {
    val result = BooleanArray(max(0, rows.size - 1))
    if (rows.size < 3 || paper.lines.isEmpty()) return result
    val c = paper.corners.map { DocumentPoint(it.x * image.width, it.y * image.height) }
    fun unit(a: DocumentPoint, b: DocumentPoint) = hypot(b.x - a.x, b.y - a.y).let { n -> DocumentPoint((b.x - a.x) / n, (b.y - a.y) / n) }
    val across = unit(c[0], c[1]); val down = unit(c[0], c[3])
    val spans = paper.lines.flatMap { q -> q.corners.map { it.x * across.x + it.y * across.y } }
    val a0 = spans.min(); val a1 = spans.max()
    val pitch = rows.zipWithNext().map { (p, q) -> q.down - p.down }.filter { it > 0 }.sorted().let { if (it.isEmpty()) return result else it[it.size / 2] }
    for (i in 0 until rows.lastIndex) {
      val top = rows[i].down + rows[i].height / 2; val bottom = rows[i + 1].down - rows[i + 1].height / 2
      if (rows[i + 1].down - rows[i].down < pitch * 1.6 || bottom - top < 4) continue
      // Per line of pixels across the block: the share of dark pixels. Text makes a band of such lines nearly a row
      // tall; a dashed rule between sections is a band of two or three pixels.
      val lines = mutableListOf<DoubleArray>()
      val all = mutableListOf<Int>()
      var d = top + 1
      while (d < bottom - 1) {
        val values = mutableListOf<Int>()
        var a = a0
        while (a < a1) {
          val x = (a * across.x + d * down.x).toInt(); val y = (a * across.y + d * down.y).toInt()
          if (x in 0 until image.width && y in 0 until image.height) values += image[x, y]
          a += 2.0
        }
        all += values
        lines += values.map { it.toDouble() }.toDoubleArray()
        d += 1.0
      }
      if (all.size < 20) continue
      val paperLevel = all.sorted()[all.size * 9 / 10]
      val shares = lines.map { l -> if (l.isEmpty()) 0.0 else l.count { it < paperLevel * .6 }.toDouble() / l.size }
      val total = shares.average()
      // A band touching either edge of the gap is the row above or below spilling past its box (a slight tilt, a
      // tight box); missed print sits inside, clear of both.
      var run = 0; var longest = 0
      for ((k, share) in shares.withIndex()) {
        if (share >= .03) run++
        else { if (run > 0 && k - run > 0) longest = max(longest, run); run = 0 }
      }
      val rowHeight = (rows[i].height + rows[i + 1].height) / 2
      // Letters fill about 60% of OCR's box; a rule, however thick it renders, stays under half of it.
      result[i] = total in UNREAD_PRINT_MIN..UNREAD_PRINT_MAX && longest >= rowHeight * .5
    }
    return result
  }

  private fun closerOrStill(rows: List<Row>): IngestHint =
    if (rows.isEmpty() || rows.map { it.height }.sorted()[rows.size / 2] < CLOSE_ENOUGH_PX) IngestHint.CLOSER else IngestHint.HOLD_STILL

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
    const val CLOSE_ENOUGH_PX = 14.0
    const val MAX_PENDING = 20
    // Dark share of the paper between two rows that means text OCR didn't read (a blank line has almost none; a QR
    // code or the logo is dense).
    const val UNREAD_PRINT_MIN = .02
    const val UNREAD_PRINT_MAX = .25
  }
}
