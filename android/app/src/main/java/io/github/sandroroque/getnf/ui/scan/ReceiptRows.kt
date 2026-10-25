package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/**
 * One printed row of a receipt as one frame read it: OCR's boxes on the same line merged in reading order. [text] is
 * normalized for matching; [confidence] is the boxes' length-weighted mean; [down] is where it sits down the receipt
 * in frame pixels, [height] its height.
 */
internal data class Row(val text: String, val confidence: Double, val down: Double, val height: Double)

/**
 * The receipt as the sequence of its printed rows, assembled from frames that each see part of it (D82): every frame's
 * rows are aligned with the rows known so far by their text, the way overlapping reads are assembled, so a view of the
 * middle joins without seeing an end, and nothing depends on measuring distances. Repeated lines ("Desconto: 0,56")
 * are told apart by their neighbours, and a row OCR missed or split in one frame is filled in by another.
 */
internal class RowAssembly {
  class Entry(var text: String, var confidence: Double) {
    /** Blank rows after this one, as frames saw them (the QR code or a blank line). */
    var gapAfter = 0.0
    /** A frame saw print between this row and the next that OCR didn't read: rows are missing here. */
    var printAfter = false
    /** Later frames that matched this row, and that read past it with no row there. */
    var confirmed = 0
    var skipped = 0
    /** Read once, then passed over twice: an artifact (two lines OCR fused into one box), not a printed row. */
    val doubtful: Boolean get() = confirmed == 0 && skipped >= 2
  }

  private val entries = mutableListOf<Entry>()
  val rows: List<Entry> get() = entries
  /** The paper's own end was seen right at the first or last row: nothing comes before, or after. */
  var topClosed = false; private set
  var bottomClosed = false; private set

  /**
   * Where each of [frame]'s rows sits in the assembly (index per row, or -1 for none), adding the rows it shows that
   * weren't known; null when the frame can't be placed (too few rows agree with what is known). The first frame read
   * well enough starts the assembly.
   */
  fun add(frame: List<Row>, topSeen: Boolean, bottomSeen: Boolean, printBetween: BooleanArray = BooleanArray(0)): IntArray? {
    if (frame.isEmpty()) return null
    if (entries.isEmpty()) {
      // Only a frame read well starts it: misread rows would hold back every frame after.
      if (frame.count { it.confidence >= START_CONFIDENCE } < max(MIN_MATCHES + 2, (frame.size * .7).toInt())) return null
      frame.forEach { entries += Entry(it.text, it.confidence) }
      gaps(frame, IntArray(frame.size) { it })
      marks(IntArray(frame.size) { it }, printBetween)
      if (topSeen) topClosed = true
      if (bottomSeen) bottomClosed = true
      return IntArray(frame.size) { it }
    }
    val (pairs, substitutions, skips) = align(frame) ?: return null
    skips.forEach { entries[it].skipped++ }
    // Rows are tracked by reference while the assembly changes; indices are worked out at the end.
    val ref = arrayOfNulls<Entry>(frame.size)
    fun reread(e: Entry, i: Int, margin: Double) { if (frame[i].confidence > e.confidence + margin) { e.text = frame[i].text; e.confidence = frame[i].confidence } }
    for ((i, j) in pairs) { ref[i] = entries[j]; entries[j].confirmed++; reread(entries[j], i, 0.0) }
    // Between matched rows, a row paired with a different text is the same row read differently: the surer read wins,
    // and a row no later frame has confirmed (a misread, two lines fused into one box) loses even at equal confidence.
    for ((i, j) in substitutions) { ref[i] = entries[j]; reread(entries[j], i, if (entries[j].confirmed == 0) -1e-9 else .1) }
    val (firstI, firstJ) = pairs.first(); val (lastI, lastJ) = pairs.last()
    val firstEntry = entries[firstJ]; val lastEntry = entries[lastJ]
    // Between the first and last match, a frame row paired with nothing is a row the assembly lacks: it goes right after
    // the row before it, unless its text is mostly in a neighbour's (that row read in part: OCR split it).
    var previous = firstEntry
    for (i in firstI + 1 until lastI) {
      ref[i]?.let { previous = it; continue }
      val next = (i + 1..lastI).firstNotNullOf { ref[it] }
      val twin = listOf(previous, next).firstOrNull { sameRow(it.text, frame[i].text) }
      if (twin != null) { ref[i] = twin; continue }
      val created = Entry(frame[i].text, frame[i].confidence)
      entries.add(entries.indexOf(previous) + 1, created)
      previous.printAfter = false // the rows that were missing there
      ref[i] = created; previous = created
    }
    val after = (lastI + 1 until frame.size).toList()
    val before = (0 until firstI).toList()
    val tail = entries.subList(entries.indexOf(lastEntry) + 1, entries.size)
    if (bottomSeen && after.isNotEmpty() && tail.isNotEmpty() &&
      after.map { frame[it].confidence }.average() > tail.map { it.confidence }.average()) {
      // A frame that sees the paper's bottom edge right after its rows sees the receipt's real end: where the
      // assembly's rows after the last match were read worse (earlier frames' partial reads at their edge), its rows
      // replace them.
      if (bottomClosed) bottomClosed = false
      tail.clear()
      after.forEach { i -> Entry(frame[i].text, frame[i].confidence).also { entries += it; ref[i] = it } }
    } else if (after.isNotEmpty() && tail.size <= EDGE_ROWS && after.size > tail.size) {
      // The frame goes on past the assembly's end: its last rows there were read by an earlier frame at its edge,
      // often in part, so a row or two left unmatched at the end are this frame's next rows, read better.
      if (bottomClosed) bottomClosed = false // print past a closed end: the end was false
      val reused = tail.toList()
      after.forEachIndexed { n, i ->
        when {
          n < reused.size -> { reused[n].text = frame[i].text; reused[n].confidence = frame[i].confidence; ref[i] = reused[n] }
          sameRow(entries.last().text, frame[i].text) -> ref[i] = entries.last() // a piece of the row before
          else -> Entry(frame[i].text, frame[i].confidence).also { entries += it; ref[i] = it }
        }
      }
    }
    val head = entries.subList(0, entries.indexOf(firstEntry))
    if (topSeen && before.isNotEmpty() && head.isNotEmpty() &&
      before.map { frame[it].confidence }.average() > head.map { it.confidence }.average()) {
      if (topClosed) topClosed = false
      head.clear()
      before.forEachIndexed { n, i -> Entry(frame[i].text, frame[i].confidence).also { entries.add(n, it); ref[i] = it } }
    } else if (before.isNotEmpty() && head.size <= EDGE_ROWS && before.size > head.size) {
      // The assembly's first rows left unmatched are this frame's rows just before the match, read better.
      if (topClosed) topClosed = false
      val reused = head.toList()
      val fresh = before.size - reused.size
      before.takeLast(reused.size).forEachIndexed { n, i -> reused[n].text = frame[i].text; reused[n].confidence = frame[i].confidence; ref[i] = reused[n] }
      before.take(fresh).forEachIndexed { n, i -> Entry(frame[i].text, frame[i].confidence).also { entries.add(n, it); ref[i] = it } }
    }
    val at = IntArray(frame.size) { i -> ref[i]?.let(entries::indexOf) ?: -1 }
    gaps(frame, at)
    marks(at, printBetween)
    closeEnds(frame, at, topSeen, bottomSeen)
    return at
  }

  /**
   * Where the frame shows two of the assembly's neighbouring rows next to each other, it says whether print lies
   * between them unread ([printBetween], per frame row and the next): rows are missing there, or not.
   */
  private fun marks(at: IntArray, printBetween: BooleanArray) {
    for (i in 0 until at.size - 1) {
      val j = at[i]; if (j < 0 || at[i + 1] != j + 1) continue
      entries[j].printAfter = printBetween.getOrElse(i) { false }
    }
  }

  /**
   * Checks a frame that saw an end of the paper against the assembly without adding anything (its small print may be
   * read too noisily to add): it closes the end it saw if its rows line up with the assembly's first or last.
   */
  fun probeEnds(frame: List<Row>, topSeen: Boolean, bottomSeen: Boolean) {
    if (entries.isEmpty() || frame.isEmpty()) return
    val (pairs, _, _) = align(frame) ?: return
    val at = IntArray(frame.size) { -1 }
    for ((i, j) in pairs) at[i] = j
    closeEnds(frame, at, topSeen, bottomSeen)
  }

  /**
   * An end seen right at the frame's first or last row closes the assembly there, if that row is the assembly's first
   * or last. One unmatched row at the frame's end is allowed: the last row in view is often read in part.
   */
  private fun closeEnds(frame: List<Row>, at: IntArray, topSeen: Boolean, bottomSeen: Boolean) {
    val first = at.indexOfFirst { it >= 0 }; val last = at.indexOfLast { it >= 0 }
    if (first < 0) return
    if (topSeen && first <= 1 && at[first] == 0) topClosed = true
    if (bottomSeen && last >= frame.lastIndex - 1 && at[last] == entries.lastIndex) bottomClosed = true
  }

  /**
   * Overlap alignment of the frame's rows with the assembly: matches score by how alike the rows are and how long,
   * skipping a row on either side costs a little, and leading or trailing rows on either side are free (the frame
   * and the assembly each extend beyond their overlap). Matched pairs in order, the substitutions between the first
   * and last of them, and the assembly rows skipped between them; null under [MIN_MATCHES] matches.
   */
  private fun align(frame: List<Row>): Triple<List<Pair<Int, Int>>, List<Pair<Int, Int>>, List<Int>>? {
    val n = frame.size; val m = entries.size
    val score = Array(n + 1) { DoubleArray(m + 1) }
    val move = Array(n + 1) { ByteArray(m + 1) } // 1 diagonal, 2 skip frame row, 3 skip assembly row
    for (i in 1..n) for (j in 1..m) {
      val diagonal = score[i - 1][j - 1] + similarity(frame[i - 1].text, entries[j - 1].text)
      val up = score[i - 1][j] - SKIP
      val left = score[i][j - 1] - SKIP
      val best = maxOf(diagonal, up, left)
      score[i][j] = best
      move[i][j] = when (best) { diagonal -> 1; up -> 2; else -> 3 }
    }
    // The overlap ends at the frame's last row or the assembly's last row, wherever the score is highest.
    var bi = n; var bj = (0..m).maxBy { score[n][it] }
    val alongBottom = (0..n).maxBy { score[it][m] }
    if (score[alongBottom][m] > score[n][bj]) { bi = alongBottom; bj = m }
    val pairs = mutableListOf<Pair<Int, Int>>(); val substitutions = mutableListOf<Pair<Int, Int>>(); val skipped = mutableListOf<Int>()
    var i = bi; var j = bj
    while (i > 0 && j > 0) {
      when (move[i][j].toInt()) {
        1 -> { (if (similarity(frame[i - 1].text, entries[j - 1].text) > 0) pairs else substitutions) += (i - 1) to (j - 1); i--; j-- }
        2 -> i--
        else -> { skipped += j - 1; j-- }
      }
    }
    if (pairs.size < MIN_MATCHES) return null
    pairs.reverse()
    val inside = pairs.first().first..pairs.last().first
    val span = pairs.first().second..pairs.last().second
    return Triple(pairs, substitutions.filter { it.first in inside }.reversed(), skipped.filter { it in span })
  }

  /** Blank space after each row, in rows, from the frame's spacing between neighbouring rows it placed. */
  private fun gaps(frame: List<Row>, at: IntArray) {
    val pitch = frame.zipWithNext().map { (a, b) -> b.down - a.down }.filter { it > 0 }.sorted().let { if (it.isEmpty()) return else it[it.size / 2] }
    for (i in 0 until frame.lastIndex) {
      val j = at[i]; if (j < 0 || at[i + 1] != j + 1) continue
      entries[j].gapAfter = max(entries[j].gapAfter, ((frame[i + 1].down - frame[i].down) / pitch - 1).coerceAtLeast(0.0))
    }
  }

  companion object {
    const val MIN_MATCHES = 3
    private const val SKIP = .6
    private const val EDGE_ROWS = 2
    private const val START_CONFIDENCE = .6

    /**
     * How strongly two rows match: 0 for unlike rows; otherwise growing with their length (a long code or a product
     * name is strong evidence, "Desconto: 0,56" weak). Alike means few edits for their length, OCR's usual misreads.
     */
    fun similarity(a: String, b: String): Double {
      if (a.isEmpty() || b.isEmpty()) return -1.0
      val shorter = min(a.length, b.length)
      if (shorter < 4) return if (a == b) .5 else -1.0
      if (abs(a.length - b.length) > max(2, shorter / 3)) return -1.0
      val d = distance(a, b)
      // Small print is read noisily: up to one edit in four still counts, the alignment's neighbours vouching for it.
      return if (d <= max(1, shorter / 4)) 1 + min(shorter, 30) / 10.0 - d * .1 else -1.0
    }

    /** One text mostly contained in the other: a row and a piece of it. */
    fun sameRow(a: String, b: String): Boolean {
      val shorter = min(a.length, b.length)
      if (shorter < 6) return false
      var best = 0
      val previous = IntArray(b.length + 1); val current = IntArray(b.length + 1)
      for (i in 1..a.length) {
        for (j in 1..b.length) { current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else 0; if (current[j] > best) best = current[j] }
        current.copyInto(previous); current.fill(0)
      }
      return best >= shorter * .7
    }

    fun distance(a: String, b: String): Int {
      var previous = IntArray(b.length + 1) { it }
      for (i in 1..a.length) {
        val current = IntArray(b.length + 1); current[0] = i
        for (j in 1..b.length) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        previous = current
      }
      return previous[b.length]
    }

    /**
     * The frame's printed rows, from the OCR boxes in [paper]'s text block, read along its rows and down the receipt:
     * boxes whose centres lie within half a row of each other down the receipt are one row, read across in order.
     */
    fun rows(paper: PaperOutline, width: Int, height: Int): List<Row> {
      val lines = paper.lines
      if (lines.isEmpty()) return emptyList()
      val c = paper.corners.map { DocumentPoint(it.x * width, it.y * height) }
      // A box at the frame's border is text cut by it: read in part, it would match nothing.
      val whole = lines.filter { q -> q.corners.all { it.x > 3 && it.y > 3 && it.x < width - 4 && it.y < height - 4 } }
      fun unit(a: DocumentPoint, b: DocumentPoint) = hypot(b.x - a.x, b.y - a.y).let { n -> DocumentPoint((b.x - a.x) / n, (b.y - a.y) / n) }
      val across = unit(c[0], c[1]); val down = unit(c[0], c[3])
      fun centre(q: OcrQuad) = DocumentPoint(q.corners.sumOf { it.x } / 4, q.corners.sumOf { it.y } / 4)
      val placed = whole.map { q -> val c = centre(q); Triple(q, c.x * down.x + c.y * down.y, c.x * across.x + c.y * across.y) }.sortedBy { it.second }
      if (placed.isEmpty()) return emptyList()
      val rowHeight = whole.map { it.height }.sorted()[whole.size / 2]
      val groups = mutableListOf<MutableList<Triple<OcrQuad, Double, Double>>>()
      for (p in placed) if (groups.isNotEmpty() && p.second - groups.last().map { it.second }.average() < rowHeight * .5) groups.last() += p else groups += mutableListOf(p)
      return groups.map { g ->
        val ordered = g.sortedBy { it.third }
        val text = CoverageMap.normalize(ordered.joinToString("") { it.first.text })
        val weight = ordered.sumOf { max(1, it.first.text.length) }.toDouble()
        Row(text, ordered.sumOf { it.first.confidence * max(1, it.first.text.length) } / weight, g.map { it.second }.average(), ordered.maxOf { it.first.height })
      }.filter { it.text.isNotEmpty() }
    }
  }
}
