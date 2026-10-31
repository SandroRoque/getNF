package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/**
 * One printed row of a receipt as one frame read it: OCR's boxes on the same line merged in reading order. [text] is
 * normalized for matching; [confidence] is the boxes' length-weighted mean; [down] is where it sits down the receipt
 * in frame pixels, [height] its height, [start] where its first box begins along the rows. [cut]: part of the row
 * can't have been read (a box across the frame's border left out, or something over the paper before it), so the text
 * is only part of the row, however well read (D134).
 */
internal data class Row(val text: String, val confidence: Double, val down: Double, val height: Double, val weakest: Double = confidence,
                        val cut: Boolean = false, val start: Double = 0.0)

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
    /**
     * Frames that saw print between this row and the next that OCR didn't read (rows are missing here), and frames that
     * saw clean paper there. The check misfires now and then (a shadow, a fold), so the frames vote.
     */
    var unreadAfter = 0
    var clearAfter = 0
    val printAfter: Boolean get() = unreadAfter > clearAfter
    /** Later frames that matched this row, and that read past it with no row there. */
    var confirmed = 0
    var skipped = 0
    /** Frames that read the rows around this one well but not this one: print that isn't text (a logo, a graphic). */
    var illegible = 0
    /**
     * Passed over by later frames far more often than matched (read once, then passed over twice): an artifact (two
     * lines OCR fused into one box, a piece of a row), not a printed row.
     */
    val doubtful: Boolean get() = skipped >= 2 * (confirmed + 1)
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
      // A row read badly that matches nothing is faint print OCR reads differently every time (a pharmacy's small
      // print came out "VOHSLGOO", "YOTSTPOO"...): it isn't added, or each frame adds its own reading, and the extra
      // rows push the frame's good reads out of line with the assembly's, which then get added again too.
      if (frame[i].confidence < WEAK) continue
      val created = Entry(frame[i].text, frame[i].confidence)
      entries.add(entries.indexOf(previous) + 1, created)
      previous.unreadAfter = 0; previous.clearAfter = 0 // the rows that were missing there
      ref[i] = created; previous = created
    }
    val after = (lastI + 1 until frame.size).toList()
    val before = (0 until firstI).toList()
    val tail = entries.subList(entries.indexOf(lastEntry) + 1, entries.size)
    // Past a closed end, rows read badly are the edge's noise (a logo's strokes, the torn strip), not more receipt.
    if (bottomClosed && after.all { frame[it].confidence < WEAK }) after.forEach { ref[it] = null }
    else if (bottomSeen && after.isNotEmpty() && tail.isNotEmpty() &&
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
    if (topClosed && before.all { frame[it].confidence < WEAK }) before.forEach { ref[it] = null }
    else if (topSeen && before.isNotEmpty() && head.isNotEmpty() &&
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
    closeEnds(frame, ref, topSeen, bottomSeen)
    val at = IntArray(frame.size) { i -> ref[i]?.let(entries::indexOf) ?: -1 }
    gaps(frame, at)
    marks(at, printBetween)
    return at
  }

  /**
   * Where the frame shows two of the assembly's neighbouring rows next to each other, it says whether print lies
   * between them unread ([printBetween], per frame row and the next): rows are missing there, or not.
   */
  private fun marks(at: IntArray, printBetween: BooleanArray) {
    for (i in 0 until at.size - 1) {
      val j = at[i]; if (j < 0 || at[i + 1] != j + 1) continue
      if (printBetween.getOrElse(i) { false }) entries[j].unreadAfter++ else entries[j].clearAfter++
    }
  }

  /**
   * Checks a frame that saw an end of the paper against the assembly without adding anything (its small print may be
   * read too noisily to add): it closes the end it saw if its rows line up with the assembly's first or last.
   */
  fun probeEnds(frame: List<Row>, topSeen: Boolean, bottomSeen: Boolean) {
    if (entries.isEmpty() || frame.isEmpty()) return
    val (pairs, _, _) = align(frame) ?: return
    val ref = arrayOfNulls<Entry>(frame.size)
    for ((i, j) in pairs) ref[i] = entries[j]
    closeEnds(frame, ref, topSeen, bottomSeen)
  }

  /**
   * An end seen right at the frame's first or last row closes the assembly there, if that row is the assembly's first
   * or last. One unmatched row at the frame's end is allowed: the last row in view is often read in part. Rows the
   * assembly has beyond it that were only ever read badly and never again (a logo's strokes read as letters) are
   * dropped: the paper ends before them.
   */
  private fun closeEnds(frame: List<Row>, ref: Array<Entry?>, topSeen: Boolean, bottomSeen: Boolean) {
    val first = ref.indexOfFirst { it != null }; val last = ref.indexOfLast { it != null }
    if (first < 0) return
    fun noise(rows: List<Entry>) = rows.all { it.confidence < WEAK && it.confirmed == 0 && it !in ref }
    if (topSeen && first <= 1) {
      val k = entries.indexOf(ref[first])
      if (k == 0 || noise(entries.subList(0, k))) { entries.subList(0, k).clear(); topClosed = true }
    }
    if (bottomSeen && last >= frame.lastIndex - 1) {
      val k = entries.indexOf(ref[last])
      if (k == entries.lastIndex || noise(entries.subList(k + 1, entries.size))) { entries.subList(k + 1, entries.size).clear(); bottomClosed = true }
    }
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
    private const val WEAK = .5

    /**
     * How strongly two rows match: 0 for unlike rows; otherwise growing with their length (a long code or a product
     * name is strong evidence, "Desconto: 0,56" weak). Alike means few edits for their length, OCR's usual misreads.
     */
    fun similarity(a: String, b: String): Double {
      if (a.isEmpty() || b.isEmpty()) return -1.0
      val shorter = min(a.length, b.length)
      if (shorter < 4) return if (a == b) .5 else -1.0
      // One row read with a piece of the same printed line the other lacks (OCR joins a right-hand column to it in
      // some frames and not others): a weaker match.
      val part = if (shorter >= 8 && sameRow(a, b)) .5 + min(shorter, 30) / 30.0 else -1.0
      if (abs(a.length - b.length) > max(2, shorter / 3)) return part
      val d = distance(a, b)
      // Small print is read noisily: up to one edit in four still counts, the alignment's neighbours vouching for it.
      return if (d <= max(1, shorter / 4)) 1 + min(shorter, 30) / 10.0 - d * .1 else part
    }

    /** A line's text for matching between frames: letters and digits only, upper case, accents off (Ô is O). */
    fun normalize(text: String): String =
      java.text.Normalizer.normalize(text.uppercase(), java.text.Normalizer.Form.NFD).filter { it in 'A'..'Z' || it in '0'..'9' }

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
    /**
     * Unit vectors along the print's rows and down the receipt, at right angles, in frame pixels: across is OCR's
     * lines' typical direction. The outline's sides lean against each other under perspective, and projecting on two
     * axes that aren't square skews rows into each other.
     */
    fun printAxes(paper: PaperOutline, width: Int, height: Int): Pair<DocumentPoint, DocumentPoint> {
      val c = paper.corners.map { DocumentPoint(it.x * width, it.y * height) }
      fun unit(a: DocumentPoint, b: DocumentPoint) = hypot(b.x - a.x, b.y - a.y).let { n -> DocumentPoint((b.x - a.x) / n, (b.y - a.y) / n) }
      val side = unit(c[0], c[1])
      val angles = paper.lines.filter { it.characters >= 8 }.map { q ->
        // Each line's direction, turned to agree with the outline's (OCR reads every line the same way round).
        val a = q.direction
        if (cos(a) * side.x + sin(a) * side.y < 0) a + PI else a
      }.map { a -> atan2(sin(a), cos(a)) }
      val outline = atan2(side.y, side.x)
      // Relative to the outline's angle, so the median doesn't straddle ±180°.
      val angle = angles.map { atan2(sin(it - outline), cos(it - outline)) }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] } + outline
      val across = DocumentPoint(cos(angle), sin(angle))
      val toBottom = unit(c[0], c[3])
      val down = DocumentPoint(-across.y, across.x).let { d -> if (d.x * toBottom.x + d.y * toBottom.y < 0) DocumentPoint(-d.x, -d.y) else d }
      return across to down
    }

    fun rows(paper: PaperOutline, width: Int, height: Int): List<Row> {
      val lines = paper.lines
      if (lines.isEmpty()) return emptyList()
      // A box at the frame's border is text cut by it: read in part, it would match nothing.
      val whole = lines.filter { q -> q.corners.all { it.x > 3 && it.y > 3 && it.x < width - 4 && it.y < height - 4 } }
      val (across, down) = printAxes(paper, width, height)
      fun centre(q: OcrQuad) = DocumentPoint(q.corners.sumOf { it.x } / 4, q.corners.sumOf { it.y } / 4)
      val placed = whole.map { q -> val c = centre(q); Triple(q, c.x * down.x + c.y * down.y, c.x * across.x + c.y * across.y) }.sortedBy { it.second }
      if (placed.isEmpty()) return emptyList()
      val rowHeight = whole.map { it.height }.sorted()[whole.size / 2]
      val groups = mutableListOf<MutableList<Triple<OcrQuad, Double, Double>>>()
      for (p in placed) if (groups.isNotEmpty() && p.second - groups.last().map { it.second }.average() < rowHeight * .5) groups.last() += p else groups += mutableListOf(p)
      // Where the boxes left out at the border sit down the receipt: the rows there are read in part.
      val cutAt = (lines - whole.toSet()).map { q -> val c = centre(q); c.x * down.x + c.y * down.y }
      return groups.map { g ->
        val ordered = g.sortedBy { it.third }
        val text = normalize(ordered.joinToString("") { it.first.text })
        val weight = ordered.sumOf { max(1, it.first.text.length) }.toDouble()
        val at = g.map { it.second }.average()
        Row(text, ordered.sumOf { it.first.confidence * max(1, it.first.text.length) } / weight, at, ordered.maxOf { it.first.height },
          ordered.filter { normalize(it.first.text).length >= 3 }.ifEmpty { ordered }.minOf { it.first.confidence },
          cut = cutAt.any { abs(it - at) < rowHeight * .5 },
          start = ordered.first().first.corners.minOf { it.x * across.x + it.y * across.y })
      }.filter { it.text.isNotEmpty() }
    }
  }
}
