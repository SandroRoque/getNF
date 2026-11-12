package io.github.sandroroque.getnf.discounts

/**
 * Per-item discounts read from receipt photos (D54, D199). The page says what the items are, each one's gross and the
 * receipt's total discount; the printed receipt says which item had which discount, in whatever layout the store
 * uses: `Desconto: 1,56` under the item, `desconto item 013` with `-1,26` in the value column, "De R$ 256,51 por
 * R$ 166,73". So this reads no particular layout. Pure, so it's tested on the JVM with recorded OCR.
 *
 * - **Each item's block.** An item is found by its code (the page's, which OCR reads far more reliably than the printed
 *   item number), with OCR's letter-for-digit slips undone; a short in-store code must also come with the first word of
 *   the description. An item whose gross no other item shares is also found by that amount printed in the photo. Its
 *   block is the print from there down to the next item's start, the totals, or six lines, whichever comes first,
 *   wherever across the receipt its text sits.
 * - **What a block says.** An amount printed negative, or on the same row as a word like "desconto", is that item's
 *   discount. An amount the block shows besides the item's gross that is smaller than it (a "from" and a "to" price)
 *   gives a discount of the difference, used only when that makes the readings add up to the page's discount exactly.
 * - **The page decides.** Readings that add up to the page's discount are complete; less is partial; more, or photos
 *   that disagree, is a conflict and nothing is applied. An explicit discount in no item's block (under an item OCR
 *   couldn't place) is left unassigned rather than guessed.
 */
object PhotoDiscounts {
  data class Item(val lineNo: Int, val code: String, val description: String, val grossCents: Long)

  /** Where a reading came from, to show the user later. */
  data class Evidence(val photo: String, val itemTop: Int, val discountBottom: Int)

  data class Found(val lineNo: Int, val cents: Long, val evidence: List<Evidence>)

  enum class Verdict {
    /** Readings add up to the page's total discount: every discount was found. */
    COMPLETE,
    /** Fewer than the total: the rest stays prorated over the other lines. */
    PARTIAL,
    /** Nothing was read. */
    NONE,
    /** Readings exceed the total, or photos disagree, or a reading exceeds its line: nothing is applied. */
    CONFLICT,
  }

  data class Result(
    val verdict: Verdict,
    /** Applicable discounts by line number; empty on [Verdict.CONFLICT]. */
    val discounts: List<Found>,
    val foundCents: Long,
    val totalCents: Long,
    /** Discount lines that couldn't be tied to an item, per photo. */
    val unassigned: Int,
    val problems: List<String>,
  )

  /** A block ends this many lines below its item at most. */
  private const val MAX_BLOCK = 6.0
  // An amount: "1,26", "-1,26", "1,26-", "R$ 1,26"; not part of a longer number, not a percentage.
  private val AMOUNT = Regex("""(?<![\d,.])([-~–—]\s?)?(?:R\S?\s?)?(\d{1,5}[.,]\s?\d{2})(?![\d%])(\s?-)?""")
  /**
   * An item's first line, found or not: a short token (the item number) and a code, or a long code alone; the code
   * mostly digits once OCR's slips are undone ("78960I44OO6A5"). An "N/S:" or a weight line is no start.
   */
  private fun itemStart(text: String): Boolean {
    val ts = tokens(text.trim().trimStart { !it.isLetterOrDigit() })
    fun codeLike(t: String, min: Int) = t.length >= min && t.all(Char::isLetterOrDigit) && digits(t).count(Char::isDigit) >= t.length * .75
    return ts.size >= 2 && ((ts[0].length <= 3 && ts[0].all(Char::isLetterOrDigit) && codeLike(ts[1], 3)) || codeLike(ts[0], 7))
  }

  /** OCR's usual confusions inside a number. */
  private fun digits(token: String) = token.map {
    when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', 'i', '|', 'J' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it }
  }.joinToString("")

  private fun tokens(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

  private fun fold(s: String) = s.uppercase().replace('0', 'O').replace('1', 'I').replace('5', 'S').filter { it.isLetterOrDigit() }

  /** Does [line] carry [item]'s code (and, for a short code, its first word)? */
  private fun anchors(line: TextLine, item: Item): Boolean {
    val ts = tokens(line.text)
    // The code is the 1st or 2nd token (the item number may be read as its own token, or not at all).
    // Printed padded or not ("07891000100103", "00000000005214"): leading zeros don't count.
    val code = item.code.trimStart('0')
    val hit = ts.take(3).indexOfFirst { digits(it).trimStart('0') == code && code.isNotEmpty() }
    if (hit < 0) return false
    if (item.code.length >= 8) return true
    val firstWord = item.description.split(' ').firstOrNull { it.length >= 3 } ?: return true
    return ts.drop(hit + 1).take(2).any { fold(it).startsWith(fold(firstWord).take(4)) }
  }

  private fun cents(value: String) = value.replace(" ", "").replace(',', '.').toBigDecimal().movePointRight(2).toLong()

  private class Amount(val line: TextLine, val cents: Long, val negative: Boolean)

  private fun amounts(line: TextLine) = AMOUNT.findAll(line.text).map {
    Amount(line, cents(it.groupValues[2]), it.groupValues[1].isNotBlank() || it.groupValues[3].isNotBlank())
  }.toList()

  /** A word like "desconto" or "desc." (misread as "0esconto", "descanto", "DESCUNTO"). */
  private fun saysDiscount(text: String) = DISCOUNT_WORD.containsMatchIn(text.uppercase().replace('0', 'O').replace(Regex("[^A-Z0-9]+"), " "))
  // A word starting "DESCON" ("0ESCONTO", "DESCUNTO", "DESCONLO") or "DESC" alone (abbreviated); not "DESCRICAO", a
  // column header, nor "VISCONTI", a bread.
  private val DISCOUNT_WORD = Regex("""\b[DO]ES[CG][OU]N|\bDESC\b""")

  /** The receipt's totals ("Total", "Qtd. total de itens", "Valor a pagar"): no item's block runs into them. */
  private fun totals(text: String) = fold(text).let { it.contains("TOTAL") || it.contains("PAGAR") || it.startsWith("QTD") }

  /** Readings of one photo: line number → (cents, evidence); implicit ones apart; how many discounts found no item. */
  private class PhotoReading(
    val byLine: Map<Int, Pair<Long, Evidence>>, val implicit: Map<Int, Pair<Long, Evidence>>, val unassigned: Int, val conflicts: List<String>,
  )

  private fun readPhoto(photo: PhotoText, items: List<Item>): PhotoReading {
    val lines = photo.lines.sortedBy { it.centerY }
    val height = lines.map { it.height }.sorted().let { if (it.isEmpty()) return PhotoReading(emptyMap(), emptyMap(), 0, emptyList()) else it[it.size / 2] }
    // Each item is anchored at most once per photo. The same product rung up twice has one code on two lines: those
    // pair up in print order. Any other count is ambiguous, and those items go unanchored here.
    val anchorOf = HashMap<TextLine, Item>()
    for (sameCode in items.groupBy { it.code }.values) {
      val candidates = lines.filter { line -> anchors(line, sameCode.first()) }
      if (candidates.size == sameCode.size) {
        candidates.zip(sameCode.sortedBy { it.lineNo }).forEach { (line, item) -> anchorOf[line] = item }
      } else if (sameCode.size > 1) {
        // Only some of them are in this photo: a readable item number still tells them apart.
        for (line in candidates) {
          val number = tokens(line.text).firstOrNull()?.let(::digits)?.toIntOrNull()
          sameCode.singleOrNull { it.lineNo == number }?.let { anchorOf[line] = it }
        }
      }
    }
    // An item not found by its code (some stores print a barcode where the page has their own code) is found by its
    // printed number with its description's first word: "013 07896003740226 BISC.MARILAN".
    for (line in lines) {
      if (line in anchorOf || !itemStart(line.text)) continue
      val number = tokens(line.text.trim().trimStart { !it.isLetterOrDigit() }).first().let(::digits).toIntOrNull() ?: continue
      val item = items.firstOrNull { it.lineNo == number } ?: continue
      if (item in anchorOf.values) continue
      val word = item.description.split(' ', '.').firstOrNull { it.length >= 3 }?.let(::fold)?.take(4) ?: continue
      if (fold(line.text).contains(word)) anchorOf[line] = item
    }
    // An item not found by its code may be found by its gross, when no other item has the same and it's on one line.
    val byGross = items.groupBy { it.grossCents }
    val anchored = anchorOf.values.toSet()
    for (item in items) {
      if (item in anchored || byGross[item.grossCents]!!.size > 1 || item.grossCents < 100) continue
      lines.filter { l -> amounts(l).any { it.cents == item.grossCents } }.singleOrNull()?.let { anchorOf.putIfAbsent(it, item) }
    }
    // Where blocks end: any item's start (found or not) and the totals.
    val starts = lines.filter { it in anchorOf || itemStart(it.text) || totals(it.text) }
    fun blockOf(line: TextLine): Item? {
      // Box tops say little on a tilted photo (a long line's box grows with the tilt): positions are centres, and the
      // margin is the typical line's height.
      val anchor = lines.filter { it in anchorOf && it.centerY - height * .5 <= line.centerY }.maxByOrNull { it.centerY } ?: return null
      if (line.centerY - anchor.centerY > MAX_BLOCK * height) return null
      // A start between the anchor and this line (another item, the totals) closes the block before it.
      val closed = starts.any { it !== anchor && it.centerY > anchor.centerY + height * .5 && it.centerY - height * .5 <= line.centerY && (it !in anchorOf || anchorOf[it] != anchorOf[anchor]) }
      return if (closed) null else anchorOf[anchor]
    }
    fun inTotals(line: TextLine) = starts.filter { it.centerY - height * .5 <= line.centerY }.maxByOrNull { it.centerY }?.let { totals(it.text) } == true
    // Level: within half the smaller box's height (a long line's box grows with any tilt left).
    fun level(a: TextLine, b: TextLine) = kotlin.math.abs(a.centerY - b.centerY) < .5 * minOf(a.height, b.height)
    fun sameRow(a: TextLine, b: TextLine) = kotlin.math.abs(a.centerY - b.centerY) < .5 * maxOf(a.height, b.height)

    val explicit = HashMap<Int, MutableList<Amount>>()
    val other = HashMap<Int, MutableList<Amount>>()
    var unassigned = 0
    for (line in lines) for (a in amounts(line)) {
      // Its own line says "desconto", or it stands alone (only amounts on its box) level with a line that does.
      val alone = AMOUNT.replace(line.text, "").none(Char::isLetterOrDigit)
      val isDiscount = a.negative || (saysDiscount(line.text) && !totals(line.text)) ||
        (alone && lines.any { it !== line && level(it, line) && saysDiscount(it.text) && !totals(it.text) })
      val item = blockOf(line)
      when {
        // Past the totals' first line, a discount is the receipt's ("Desconto R$ 3,60"), not an unplaced item's.
        item == null -> if (isDiscount && !inTotals(line)) unassigned++
        isDiscount -> explicit.getOrPut(item.lineNo) { mutableListOf() } += a
        else -> other.getOrPut(item.lineNo) { mutableListOf() } += a
      }
    }
    val anchorLine = anchorOf.entries.groupBy({ it.value.lineNo }, { it.key }).mapValues { (_, ls) -> ls.minBy { it.centerY } }
    val byLine = HashMap<Int, Pair<Long, Evidence>>()
    val conflicts = mutableListOf<String>()
    for ((lineNo, found) in explicit) {
      val values = found.map { it.cents }.distinct()
      // Two different discounts in one item's block: this photo doesn't say which; other photos may.
      if (values.size > 1) { unassigned += values.size; continue }
      byLine[lineNo] = values.single() to Evidence(photo.photo, anchorLine[lineNo]?.top ?: found[0].line.top, found.maxOf { it.line.bottom })
    }
    // "De R$ <gross> por R$ <net>": the gross, then a smaller amount after it on its row (a unit price comes before).
    val implicit = HashMap<Int, Pair<Long, Evidence>>()
    for ((lineNo, found) in other) {
      if (lineNo in byLine) continue
      val gross = items.first { it.lineNo == lineNo }.grossCents
      val nets = found.filter { it.cents == gross }.flatMap { g ->
        val onLine = amounts(g.line).let { all -> all.drop(all.indexOfFirst { it.cents == gross } + 1) }
        val beside = found.filter { it.line !== g.line && level(it.line, g.line) && it.line.left > g.line.right }
        (onLine + beside).filter { it.cents in 1 until gross }.map { it.cents to g.line }
      }
      val net = nets.map { it.first }.distinct().singleOrNull() ?: continue
      val at = nets.first().second
      implicit[lineNo] = (gross - net) to Evidence(photo.photo, anchorLine[lineNo]?.top ?: at.top, at.bottom)
    }
    return PhotoReading(byLine, implicit, unassigned, conflicts)
  }

  /**
   * [typed] maps lines whose discount a person typed to it (D185, T-34): their readings are dropped, since a typed
   * value is never replaced, and the readings must fit in what the typed ones leave of the receipt's discount.
   */
  fun read(photos: List<PhotoText>, items: List<Item>, totalDiscountCents: Long, typed: Map<Int, Long> = emptyMap()): Result {
    // Read along the print: a photo taken sideways has its lines' "above" across the image (D101).
    val readings = photos.map { readPhoto(it.alongPrint(), items) }
    val problems = mutableListOf<String>()
    fun agreed(report: Boolean = true, pick: (PhotoReading) -> Map<Int, Pair<Long, Evidence>>): List<Found> {
      val values = HashMap<Int, MutableList<Pair<Long, Evidence>>>()
      for (r in readings) for ((line, v) in pick(r)) values.getOrPut(line) { mutableListOf() } += v
      // Overlapping photos read the same discount more than once: that's agreement, not a second discount. When they
      // differ (a misread digit), the value read most often wins if it has at least twice the readings of any other.
      return values.toSortedMap().mapNotNull { (line, vs) ->
        val counts = vs.groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }
        if (counts.size > 1 && counts[0].value < 2 * counts[1].value) {
          if (report) problems += "line $line: photos disagree (${counts.joinToString { "${it.key}×${it.value}" }})"
          null
        } else Found(line, counts[0].key, vs.filter { it.first == counts[0].key }.map { it.second })
      }.filter { it.lineNo !in typed }
    }
    problems += readings.flatMap { it.conflicts }
    var found = agreed { it.byLine }
    val typedSum = typed.values.sum()
    // Implicit readings ("from" and "to" prices) count only when they make the readings exact.
    val extra = agreed(report = false) { it.implicit }.filter { f -> found.none { it.lineNo == f.lineNo } }
    if (extra.isNotEmpty() && found.sumOf { it.cents } + extra.sumOf { it.cents } + typedSum == totalDiscountCents) found = (found + extra).sortedBy { it.lineNo }
    val gross = items.associate { it.lineNo to it.grossCents }
    for (f in found) if (f.cents > (gross[f.lineNo] ?: 0)) problems += "line ${f.lineNo}: discount ${f.cents} exceeds the line's ${gross[f.lineNo]}"
    val sum = found.sumOf { it.cents }
    if (sum + typedSum > totalDiscountCents) problems += "discounts read add up to $sum" +
      (if (typedSum > 0) " and typed ones to $typedSum" else "") + ", the page's total is $totalDiscountCents"
    val unassigned = readings.sumOf { it.unassigned }
    val verdict = when {
      problems.isNotEmpty() -> Verdict.CONFLICT
      found.isEmpty() -> Verdict.NONE
      sum + typedSum == totalDiscountCents -> Verdict.COMPLETE
      else -> Verdict.PARTIAL
    }
    return Result(verdict, if (verdict == Verdict.CONFLICT) emptyList() else found, sum, totalDiscountCents, unassigned, problems)
  }
}
