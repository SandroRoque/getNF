package io.github.sandroroque.getnf.discounts

/**
 * Per-item discounts read from receipt photos (D54). The HTML says what the items are and the total discount; the
 * printed receipt (DANFE NFC-e) puts a `Desconto: 1,56` line under each discounted item. This finds those lines in
 * OCR text and ties each to the item printed just above it. Pure, so it's tested on the JVM with recorded OCR.
 *
 * Items are found by their code (the HTML's, which OCR reads far more reliably than the printed item number), with
 * the usual letter-for-digit confusions undone. A short in-store code ("243") must also come with the first word of
 * the description, since such numbers turn up elsewhere. A discount is only taken when the nearest item-looking line
 * above it is a found item, close enough to be its own line; anything else is left unassigned rather than guessed.
 *
 * Some stores (a pharmacy) print "1 UN De R$ 256,51 por R$ 166,73" instead: the "from" price is the line's gross,
 * which identifies the item when no other line has the same gross, and the discount is the difference.
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

  private const val MAX_GAP = 2.2
  private val FROM_TO = Regex("""\bDe\s*R\S?\s*(\d{1,5}[.,]\s?\d{2})\s*p\w?r\s*R\S?\s*(\d{1,5}[.,]\s?\d{2})""", RegexOption.IGNORE_CASE)
  private val DISCOUNT = Regex("""^\W{0,2}desc\w{0,6}\s*[:;.]?\s*(\d{1,4}[.,]\d{2})\s*$""", RegexOption.IGNORE_CASE)
  // An item's first line: a short token (the item number, often misread) and a code, or a long code alone (the
  // number read as a line of its own).
  private val ITEM_START = Regex("""^\W{0,2}(\w{1,3}\s+[0-9OoIl]{3,14}|[0-9OoIl]{7,14})\s+\S""")

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
    val hit = ts.take(3).indexOfFirst { digits(it) == item.code }
    if (hit < 0) return false
    if (item.code.length >= 8) return true
    val firstWord = item.description.split(' ').firstOrNull { it.length >= 3 } ?: return true
    return ts.drop(hit + 1).take(2).any { fold(it).startsWith(fold(firstWord).take(4)) }
  }

  private fun cents(value: String) = value.replace(" ", "").replace(',', '.').toBigDecimal().movePointRight(2).toLong()

  /** Readings of one photo: line number → (cents, evidence); plus how many discount lines found no item. */
  private class PhotoReading(val byLine: Map<Int, Pair<Long, Evidence>>, val unassigned: Int, val conflicts: List<String>)

  private fun readPhoto(photo: PhotoText, items: List<Item>): PhotoReading {
    val lines = photo.lines.sortedBy { it.centerY }
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
    val byLine = HashMap<Int, Pair<Long, Evidence>>()
    val conflicts = mutableListOf<String>()
    var unassigned = 0
    for ((i, line) in lines.withIndex()) {
      val m = DISCOUNT.find(line.text.trim()) ?: continue
      // The nearest item-looking line above, by vertical order.
      val above = (i - 1 downTo 0).map { lines[it] }.firstOrNull { it in anchorOf || ITEM_START.containsMatchIn(it.text) }
      val item = above?.let { anchorOf[it] }
      // Its own line, right under the item: closer than about two line heights (the next item is further).
      if (item == null || line.centerY - above.centerY > MAX_GAP * maxOf(above.height, line.height)) { unassigned++; continue }
      val value = cents(m.groupValues[1])
      val previous = byLine[item.lineNo]
      if (previous != null && previous.first != value) conflicts += "line ${item.lineNo}: ${previous.first} and $value in ${photo.photo}"
      byLine[item.lineNo] = value to Evidence(photo.photo, above.top, line.bottom)
    }
    // "De R$ <gross> por R$ <net>": the gross names the item when it's unique on the receipt.
    val byGross = items.groupBy { it.grossCents }
    for (line in lines) {
      val m = FROM_TO.find(line.text) ?: continue
      val from = cents(m.groupValues[1]); val to = cents(m.groupValues[2])
      val item = byGross[from]?.singleOrNull()
      if (item == null || to > from) { unassigned++; continue }
      val previous = byLine[item.lineNo]
      if (previous != null && previous.first != from - to) conflicts += "line ${item.lineNo}: ${previous.first} and ${from - to} in ${photo.photo}"
      byLine[item.lineNo] = (from - to) to Evidence(photo.photo, line.top, line.bottom)
    }
    return PhotoReading(byLine, unassigned, conflicts)
  }

  fun read(photos: List<PhotoText>, items: List<Item>, totalDiscountCents: Long): Result {
    val readings = photos.map { readPhoto(it, items) }
    val problems = readings.flatMap { it.conflicts }.toMutableList()
    val values = HashMap<Int, MutableList<Pair<Long, Evidence>>>()
    for (r in readings) for ((line, v) in r.byLine) values.getOrPut(line) { mutableListOf() } += v
    // Overlapping photos read the same discount more than once: that's agreement, not a second discount. When they
    // differ (a misread digit), the value read most often wins if it has at least twice the readings of any other.
    val found = values.toSortedMap().mapNotNull { (line, vs) ->
      val counts = vs.groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }
      if (counts.size > 1 && counts[0].value < 2 * counts[1].value) {
        problems += "line $line: photos disagree (${counts.joinToString { "${it.key}×${it.value}" }})"
        null
      } else Found(line, counts[0].key, vs.filter { it.first == counts[0].key }.map { it.second })
    }
    val gross = items.associate { it.lineNo to it.grossCents }
    for (f in found) if (f.cents > (gross[f.lineNo] ?: 0)) problems += "line ${f.lineNo}: discount ${f.cents} exceeds the line's ${gross[f.lineNo]}"
    val sum = found.sumOf { it.cents }
    if (sum > totalDiscountCents) problems += "discounts read add up to $sum, the page's total is $totalDiscountCents"
    val unassigned = readings.sumOf { it.unassigned }
    val verdict = when {
      problems.isNotEmpty() -> Verdict.CONFLICT
      found.isEmpty() -> Verdict.NONE
      sum == totalDiscountCents -> Verdict.COMPLETE
      else -> Verdict.PARTIAL
    }
    return Result(verdict, if (verdict == Verdict.CONFLICT) emptyList() else found, sum, totalDiscountCents, unassigned, problems)
  }
}
