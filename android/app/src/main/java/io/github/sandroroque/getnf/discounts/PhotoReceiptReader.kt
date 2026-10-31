package io.github.sandroroque.getnf.discounts

import kotlin.math.abs

/**
 * A receipt read from its photos alone (D106, D147), from the OCR of the photos a capture kept. Not used by the app
 * yet: it's measured against the saved SEFAZ pages first (`PhotoReaderBenchTest`).
 *
 * The printed receipt (DANFE NFC-e) follows a national layout: one row per item, "number code description qty unit
 * unit-price total", a "Desconto: 0,60" row under a discounted item, then the totals ("Qtd. total de itens", "Valor
 * total R$", "Desconto R$", "Valor a pagar R$"). OCR splits a row into pieces (the number, the left part, the right
 * part), so rows are rebuilt from the lines' positions first, per photo; the same item seen in several photos is one
 * item, keyed by its printed number.
 *
 * Everything is checked against the print's own arithmetic, nothing is guessed: an item's total is its quantity times
 * its unit price, less its discount; the items add up to the total; the item count matches the printed one; a GTIN's
 * check digit holds. A reading that passes every check is [Reading.trusted].
 */
object PhotoReceiptReader {
  data class Item(
    val number: Int, val code: String, val description: String, val qtyE4: Long, val unit: String,
    val unitPriceE4: Long, val totalCents: Long, val discountCents: Long = 0,
  ) {
    /** Quantity × unit price, less the discount printed under it, is the line's printed total (to the centavo). */
    val adds: Boolean get() {
      val gross = Math.round(qtyE4.toDouble() * unitPriceE4 / 1_000_000)
      return gross == totalCents || gross - discountCents == totalCents
    }
  }

  /**
   * The printed totals. Stores label them differently: "Total produtos" (the items before discounts) with "Valor total"
   * (after), or "Valor total" (before) with "Valor a pagar" (after).
   */
  data class Totals(val itemsCount: Int?, val products: Long?, val total: Long?, val discountCents: Long?, val payable: Long?)

  data class Reading(val items: List<Item>, val totals: Totals, val problems: List<String>) {
    /** Every check held: no gap in the numbering, every line adds up, the sums match the printed totals. */
    val trusted: Boolean get() = problems.isEmpty()
  }

  /** An OCR line's middle, size and slope (from its corners; a short one's is too unsure, so it counts as level). */
  private class Placed(val line: TextLine) {
    private val c = line.corners?.takeIf { it.size == 8 }
    val cx = c?.let { (it[0] + it[2] + it[4] + it[6]) / 4.0 } ?: ((line.left + line.right) / 2.0)
    val cy = c?.let { (it[1] + it[3] + it[5] + it[7]) / 4.0 } ?: line.centerY.toDouble()
    val w = c?.let { kotlin.math.hypot((it[2] - it[0]).toDouble(), (it[3] - it[1]).toDouble()) } ?: (line.right - line.left).toDouble()
    val h = c?.let { kotlin.math.hypot((it[6] - it[0]).toDouble(), (it[7] - it[1]).toDouble()) } ?: line.height.toDouble()
    private val slope = if (c == null || w < h * 4) 0.0 else ((c[3] + c[5]) - (c[1] + c[7])) / 2.0 / ((c[2] + c[4]) - (c[0] + c[6])).toDouble().let { it / 2 }.coerceAtLeast(1.0)
    fun yAt(x: Double) = cy + slope * (x - cx)
  }

  /** A photo's rows, top to bottom, each the texts of the OCR lines on it, left to right. */
  fun rows(photo: PhotoText): List<String> {
    val lines = photo.alongPrint().lines.filter { it.text.isNotBlank() }.map(::Placed)
    if (lines.isEmpty()) return emptyList()
    val h = lines.map { it.h }.sorted()[lines.size / 2].coerceAtLeast(1.0)
    // Paper isn't flat: rows curve and tilt differently across one photo, so each row follows the slope of its longest
    // line, and a piece (the item number, a price) joins the row whose line, extended to it, passes through its middle.
    val rows = mutableListOf<MutableList<Placed>>()
    for (l in lines.sortedByDescending { it.w }) {
      val row = rows.map { r -> r to abs(r.first().yAt(l.cx) - l.cy) }.filter { it.second < h * .5 }.minByOrNull { it.second }?.first
      if (row != null) row += l else rows += mutableListOf(l)
    }
    val middle = lines.map { it.cx }.average()
    return rows.sortedBy { it.first().yAt(middle) }.map { r -> r.sortedBy { it.line.left }.joinToString(" ") { it.line.text.trim() } }
  }

  private fun digits(token: String) = token.map {
    when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', 'i', '|', 'J', 'L' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z' -> '2'; 'G' -> '6'; else -> it }
  }.joinToString("")

  /** "4,98" → 498 (scale 2), "0,745" → 7450 (scale 4 when [scale] is 4); null when it isn't a number. */
  private fun decimal(text: String, scale: Int): Long? {
    val t = digits(text.trim()).replace(" ", "")
    val m = Regex("""^(\d{1,6})[,.](\d{1,4})$""").find(t) ?: return Regex("""^\d{1,4}$""").find(t)?.let { it.value.toLong() * pow10(scale) }
    val (whole, frac) = m.destructured
    if (frac.length > scale) return null
    return whole.toLong() * pow10(scale) + frac.padEnd(scale, '0').toLong()
  }

  private fun pow10(n: Int) = (1..n).fold(1L) { a, _ -> a * 10 }

  private val UNITS = setOf("UN", "KG", "L", "LT", "G", "ML", "CX", "PC", "PCT", "M", "FD", "DZ", "UND", "UNID")
  private fun unitOf(token: String): String? {
    val t = token.uppercase().replace('0', 'O').replace('8', 'B').let { if (it in setOf("ON", "UM", "IN", "UH", "VN")) "UN" else it }
    return t.takeIf { it in UNITS }
  }

  private val TAIL = Regex("""(\S+)\s*([A-Za-z0-9]{1,4})\s*(\d[\d.]*[,.]\d{2,3}|\d+)\s+(\d[\d.]*[,.]\d{2})\s*$""")
  /** A row that is only an item's right part: "0,485 KG 3,98 1,93". */
  private val ORPHAN = Regex("""^\W{0,2}\d[\d.,]*\s*[A-Za-z0-9]{1,4}\s*\d[\d.]*[,.]\d{2,3}\s+\d[\d.]*[,.]\d{2}$""")
  private val HEAD = Regex("""^\W{0,2}(\w{1,3})\s+(\w{3,14})\s+(.*)$""")
  private val DISCOUNT = Regex("""^\W{0,2}desc\w{0,6}\s*[:;.]?\s*(?:R\S?\s*)?(\d{1,4}[.,]\d{2})\s*$""", RegexOption.IGNORE_CASE)

  /** One printed item row, or null if [row] isn't one (or doesn't read as one). */
  fun item(row: String): Item? {
    val head = HEAD.find(row.trim()) ?: return null
    val (numberText, codeText, rest) = head.destructured
    val number = digits(numberText).toIntOrNull()?.takeIf { it in 1..999 } ?: return null
    val code = code(codeText) ?: return null
    // The right part: quantity, unit, unit price and total, possibly run together ("1 UN2,75 2,75").
    val spaced = rest.replace(Regex("""(?i)(\d)(UN|KG|LT?)(\d)"""), "$1 $2 $3").replace(Regex("""(?i)\b(UN|KG|LT?)(\d)"""), "$1 $2")
    val tail = TAIL.find(spaced) ?: return null
    val (qtyText, unitText, priceText, totalText) = tail.destructured
    val unit = unitOf(unitText) ?: return null
    val qty = decimal(qtyText, 4) ?: return null
    val price = decimal(priceText, 4) ?: return null
    val total = decimal(totalText, 2) ?: return null
    val description = spaced.substring(0, tail.range.first).trim()
    if (description.isEmpty()) return null
    // Pieces of two rows run together (a tilted photo): a second price, a second code, or another item's number.
    if (Regex("""\d,\d{2}\b""").containsMatchIn(description)) return null
    if (Regex("""(?:^|\s)\d{7,14}(?:\s|$)""").containsMatchIn(digits(description))) return null
    if (Regex("""(?:^|\s)0\d(?:\s|$)""").containsMatchIn(description)) return null
    return Item(number, code, description, qty, unit, price, total)
  }

  /**
   * An item's code, its letters read as the digits OCR confuses them with. A GTIN with one character still unread
   * ("78949n4687992") is completed by its check digit, which leaves exactly one possibility.
   */
  private fun code(text: String): String? {
    val c = digits(text)
    if (c.all(Char::isDigit)) return c
    val bad = c.indices.filter { !c[it].isDigit() }
    if (bad.size != 1 || c.length !in setOf(8, 12, 13, 14) || bad[0] == c.lastIndex) return null
    return ('0'..'9').map { d -> c.replaceRange(bad[0], bad[0] + 1, d.toString()) }.singleOrNull(::gtinOk)
  }

  /**
   * An item printed on two rows, without its number (a pharmacy's layout): "code description", then "qty unit R$
   * price R$ total"; a "De R$ 256,51 por R$ 166,73 -R$ 89,78" row after it is its discount.
   */
  private val TWO_ROW_HEAD = Regex("""^\W{0,2}([0-9OoIl]{4,14})\s+([A-Za-z].*)$""")
  private val TWO_ROW_TAIL = Regex("""^\W{0,2}(\S+?)\s*([A-Za-z]{1,4})\s*R\S?\s*(\d[\d.]*[,.]\s?\d{2})\s*\S*\s*R\S?\s*(\d[\d.]*[,.]\s?\d{2})\s*$""")
  private val FROM_TO = Regex("""(?i)\bDe\s*R\S?\s*\d[\d.,\s]*?p\w?r\s*R\S?\s*\d[\d.,\s]*?-\s*R\S?\s*(\d[\d.]*[,.]\s?\d{2})""")

  private fun twoRow(head: String, tail: String, number: Int): Item? {
    val h = TWO_ROW_HEAD.find(head.trim()) ?: return null
    val t = TWO_ROW_TAIL.find(tail.trim()) ?: return null
    if (TAIL.containsMatchIn(head)) return null
    val code = code(h.groupValues[1]) ?: return null
    val (qtyText, unitText, priceText, totalText) = t.destructured
    val unit = unitOf(unitText) ?: return null
    return Item(number, code, h.groupValues[2].trim(), decimal(qtyText, 4) ?: return null, unit,
      decimal(priceText.replace(" ", ""), 4) ?: return null, decimal(totalText.replace(" ", ""), 2) ?: return null)
  }

  private fun discount(row: String) = DISCOUNT.find(row.trim())?.groupValues?.get(1)?.let { decimal(it, 2) }

  private fun moneyAfter(row: String, label: Regex): Long? {
    if (!label.containsMatchIn(row)) return null
    return Regex("""(\d[\d.]*[,.]\d{2})\s*$""").find(digits(row.substringAfterLast("R$", row)))?.groupValues?.get(1)
      ?.replace(".", "")?.let { decimal(it, 2) }
  }

  /** Reads the photos (in any order) into one receipt. */
  fun read(photos: List<PhotoText>): Reading {
    // Each reading with its photo's weight: the share of the photo's item rows that read cleanly and add up, so a
    // tilted photo whose rows ran together counts less than a straight one.
    val readings = mutableMapOf<Int, MutableList<Pair<Item, Double>>>()
    var count: Int? = null; var products: Long? = null; var total: Long? = null; var discount: Long? = null; var payable: Long? = null
    for (photo in photos) {
      val rows = rows(photo)
      val itemLike = rows.count { r -> HEAD.find(r.trim())?.groupValues?.get(2)?.let { c -> digits(c).length >= 4 && digits(c).all(Char::isDigit) } == true }
      val mine = mutableListOf<Item>()
      var last: Item? = null
      // The two-row layout numbers its items by their order, so only from the column header down.
      var headerSeen = false; var nextNumber = 1; var skip = -1
      for ((k, row) in rows.withIndex()) {
        if (k == skip) continue
        if (Regex("(?i)c[oó0]d\\S*\\s+descri").containsMatchIn(row)) headerSeen = true
        // A row's right part on a row of its own, just above or below (weighed items, a curved photo): joined only when
        // the joined row reads as an item and adds up.
        val one = item(row) ?: if (HEAD.containsMatchIn(row.trim()) && !TAIL.containsMatchIn(row)) listOf(k - 1, k + 1).firstNotNullOfOrNull { j ->
          rows.getOrNull(j)?.takeIf { ORPHAN.matches(it.trim()) }?.let { item("$row $it") }?.takeIf { it.adds }?.also { if (j == k + 1) skip = j }
        } else null
        // Every "code description" row under the header is an item, read or not: its number is its place.
        val head = one == null && headerSeen && TWO_ROW_HEAD.containsMatchIn(row.trim()) && !TAIL.containsMatchIn(row)
        val it = one ?: if (head) rows.getOrNull(k + 1)?.let { next -> twoRow(row, next, nextNumber) }?.also { skip = k + 1 } else null
        if (head) nextNumber++
        if (it != null) { mine += it; last = it; continue }
        val d = discount(row) ?: FROM_TO.find(row)?.groupValues?.get(1)?.replace(" ", "")?.let { decimal(it, 2) }
          // A bare amount under an item that doesn't add up without it, and does with it: its discount, unlabelled.
          ?: Regex("""^\W{0,2}(\d{1,4}[,.]\d{2})\s*$""").find(row.trim())?.groupValues?.get(1)?.let { decimal(it, 2) }
            ?.takeIf { v -> last != null && !last!!.adds && last!!.copy(discountCents = v).adds }
        if (d != null && last != null) {
          // The discount row belongs to the item just above it.
          mine[mine.lastIndex] = last.copy(discountCents = d); last = null; continue
        }
        last = null
        Regex("""(?i)total\s*de\s*itens\D*([\dOoIl]{1,3})\s*$""").find(row)?.let { m -> digits(m.groupValues[1]).toIntOrNull()?.let { count = it } }
        moneyAfter(row, Regex("(?i)total\\s*(de\\s*)?produtos"))?.let { products = it }
        moneyAfter(row, Regex("(?i)valor\\s*total|^\\W{0,2}t[eo]tal\\s*R"))?.let { total = it }
        moneyAfter(row, Regex("(?i)(valor\\s*)?a\\s*paga"))?.let { payable = it }
        if (Regex("(?i)^\\W{0,2}desconto\\s*R").containsMatchIn(row)) moneyAfter(row, Regex("(?i)desconto"))?.let { discount = it }
      }
      val weight = if (itemLike == 0) 1.0 else mine.count { it.adds }.toDouble() / itemLike
      for (i in mine) readings.getOrPut(i.number) { mutableListOf() } += i to weight
    }
    // Per item number, among the readings that add up (else all), the one with the most weight behind it.
    val items = readings.toSortedMap().map { (_, rs) ->
      val pool = rs.filter { it.first.adds }.ifEmpty { rs }
      val best = pool.groupBy { it.first.copy(description = "") }.mapValues { (_, v) -> v.sumOf { it.second } }.maxBy { it.value }.key
      best.copy(description = pool.filter { it.first.copy(description = "") == best }.maxBy { it.second }.first.description)
    }
    val totals = Totals(count, products, total, discount, payable)
    val problems = mutableListOf<String>()
    val numbers = items.map { it.number }
    val expected = count ?: numbers.maxOrNull() ?: 0
    val missing = (1..expected).filter { it !in numbers }
    if (missing.isNotEmpty()) problems += "items not read: ${missing.joinToString()}"
    if (count == null) problems += "no item count"
    for (i in items) {
      if (!i.adds) problems += "item ${i.number}: ${i.qtyE4} × ${i.unitPriceE4} ≠ ${i.totalCents}"
      if (i.code.length in setOf(8, 12, 13, 14) && !gtinOk(i.code)) problems += "item ${i.number}: GTIN check digit"
    }
    // Each line before and after its discount (some stores print the total after it, others before).
    val gross = items.sumOf { Math.round(it.qtyE4.toDouble() * it.unitPriceE4 / 1_000_000) }
    val net = gross - (discount ?: items.sumOf { it.discountCents })
    val printed = listOfNotNull(products, total, payable)
    if (printed.isEmpty()) problems += "no total"
    else if (missing.isEmpty() && gross !in listOfNotNull(products, total) && net !in listOfNotNull(total, payable))
      problems += "items add up to $gross ($net after discounts), the receipt says ${printed.joinToString()}"
    return Reading(items, totals, problems)
  }

  private fun gtinOk(code: String): Boolean {
    var sum = 0; var w = 3
    for (i in code.length - 2 downTo 0) { sum += (code[i] - '0') * w; w = 4 - w }
    return (10 - sum % 10) % 10 == code.last() - '0'
  }
}
