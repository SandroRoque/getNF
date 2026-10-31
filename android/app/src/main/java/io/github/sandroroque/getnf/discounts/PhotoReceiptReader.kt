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

  /** A photo's rows, top to bottom, each the texts of the OCR lines on it, left to right. */
  fun rows(photo: PhotoText): List<String> {
    val lines = photo.alongPrint().lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
    if (lines.isEmpty()) return emptyList()
    val h = lines.map { it.height }.sorted()[lines.size / 2].coerceAtLeast(1)
    val rows = mutableListOf<MutableList<TextLine>>()
    for (l in lines) {
      // On the same row: its middle within half a line of the row's.
      val row = rows.lastOrNull { r -> abs(r.map { it.centerY }.average() - l.centerY) < h * .5 }
      if (row != null) row += l else rows += mutableListOf(l)
    }
    return rows.sortedBy { r -> r.map { it.centerY }.average() }.map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
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
    val t = token.uppercase().replace('0', 'O').replace('8', 'B').let { if (it == "ON" || it == "UM") "UN" else it }
    return t.takeIf { it in UNITS }
  }

  private val TAIL = Regex("""(\S+)\s*([A-Za-z0-9]{1,4})\s*(\d[\d.]*[,.]\d{2,3}|\d+)\s+(\d[\d.]*[,.]\d{2})\s*$""")
  private val HEAD = Regex("""^\W{0,2}(\w{1,3})\s+(\w{3,14})\s+(.*)$""")
  private val DISCOUNT = Regex("""^\W{0,2}desc\w{0,6}\s*[:;.]?\s*(?:R\S?\s*)?(\d{1,4}[.,]\d{2})\s*$""", RegexOption.IGNORE_CASE)

  /** One printed item row, or null if [row] isn't one (or doesn't read as one). */
  fun item(row: String): Item? {
    val head = HEAD.find(row.trim()) ?: return null
    val (numberText, codeText, rest) = head.destructured
    val number = digits(numberText).toIntOrNull()?.takeIf { it in 1..999 } ?: return null
    val code = digits(codeText).takeIf { c -> c.all(Char::isDigit) } ?: return null
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
      for (row in rows) {
        val it = item(row)
        if (it != null) { mine += it; last = it; continue }
        val d = discount(row)
        if (d != null && last != null) {
          // The discount row belongs to the item just above it.
          mine[mine.lastIndex] = last.copy(discountCents = d); last = null; continue
        }
        last = null
        Regex("""(?i)total\s*de\s*itens\D*([\dOoIl]{1,3})\s*$""").find(row)?.let { m -> digits(m.groupValues[1]).toIntOrNull()?.let { count = it } }
        moneyAfter(row, Regex("(?i)total\\s*(de\\s*)?produtos"))?.let { products = it }
        moneyAfter(row, Regex("(?i)valor\\s*total"))?.let { total = it }
        moneyAfter(row, Regex("(?i)valor\\s*a\\s*pagar"))?.let { payable = it }
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
