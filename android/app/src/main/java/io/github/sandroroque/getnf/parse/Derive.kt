package io.github.sandroroque.getnf.parse

/*
 * Turning parsed receipts into store items and line discounts (D14, D44), ported from the retired server's derive.js
 * (D48). Sizes are Long millionths of a kg or litre, money Long cents, as in the database.
 */

/** How a store item is identified: a GTIN anywhere, or a store code together with its store's CNPJ. */
data class CodeKey(val kind: String, val code: String, val storeCnpj: String) {
  /** The server's `products.key`. */
  val serverKey get() = if (kind == GTIN) "gtin:$code" else "store:$storeCnpj:$code"

  companion object {
    const val GTIN = "gtin"
    const val STORE = "store"
  }
}

data class Size(val e6: Long, val unit: String)

enum class DiscountSource(val value: String) { PRORATED("prorated"), PHOTO("photo") }

data class LineDiscount(val cents: Long, val source: DiscountSource)

object Derive {
  /**
   * `kg` or `l` when a line's unit says its quantity is a weight or a volume, else null (UN, PCT, CX… say nothing: some
   * stores print UN for something weighed). Same table as supabase/functions/categorize/reads.ts (D108).
   */
  fun unitKind(unit: String?): String? = when (unit?.trim()?.uppercase()) {
    "KG", "KGS", "KILO", "KILOS", "QUILO", "QUILOS" -> "kg"
    "L", "LT", "LTS", "LITRO", "LITROS" -> "l"
    else -> null
  }

  /** True for a GTIN-8/12/13/14 with a valid check digit that isn't a store-internal (restricted circulation) code. */
  fun isGtin(code: String?): Boolean {
    if (code == null || !Regex("\\d{8}|\\d{12,14}").matches(code)) return false
    // Restricted circulation: GTIN-13 "2..." (in-store, variable weight) and GTIN-8 "0..."/"2...".
    if ((code.length == 13 && code[0] == '2') || (code.length == 8 && (code[0] == '0' || code[0] == '2'))) return false
    var sum = 0
    var w = 3
    for (i in code.length - 2 downTo 0) { sum += (code[i] - '0') * w; w = 4 - w }
    return (10 - sum % 10) % 10 == code.last() - '0'
  }

  /** Packaged goods are the same item at every store; bulk/in-store codes only mean something per store. */
  fun codeKey(code: String, storeCnpj: String) =
    if (isGtin(code)) CodeKey(CodeKey.GTIN, code, "") else CodeKey(CodeKey.STORE, code, storeCnpj)

  private val SIZE = Regex("(?:^|[^\\d.,])(?:(\\d+)\\s?X\\s?)?(\\d+(?:[.,]\\d+)?)\\s?(KGS?|GRS?|G|MG|ML|LTS?|L)(?![A-Z])", RegexOption.IGNORE_CASE)

  /**
   * Net size from a description: "CAFE 3 CORACOES 250G" → 0.25 kg, "LEITE 12X1L" → 12 l.
   * Null when there's no size (bulk items are priced per kg already; odd ones get fixed by hand).
   */
  fun parseSize(description: String?): Size? {
    val m = SIZE.find(description ?: return null) ?: return null
    val count = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 1
    val n = m.groupValues[2].replace(',', '.').toBigDecimal().multiply(count.toBigDecimal())
    val u = m.groupValues[3].uppercase()
    // Millionths of the base unit: kg for weights, l for volumes.
    val (perUnitE6, unit) = when {
      u.startsWith("KG") -> 1_000_000L to "kg"
      u == "MG" -> 1L to "kg"
      u.startsWith("G") -> 1_000L to "kg"
      u == "ML" -> 1_000L to "l"
      else -> 1_000_000L to "l"
    }
    // Same rounding as the JS (to 6 decimals).
    val e6 = n.multiply(perUnitE6.toBigDecimal()).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    return Size(e6, unit)
  }

  /**
   * Splits [totalCents] across lines proportionally to [weights] (line totals in cents), so the parts add up exactly
   * (largest remainder, ties to the earlier line).
   */
  fun allocateCents(totalCents: Long, weights: List<Long>): List<Long> {
    if (weights.isEmpty()) return emptyList()
    val sum = weights.sum()
    if (sum <= 0) return weights.indices.map { if (it == 0) totalCents else 0 }
    // Exact integer arithmetic: part = floor(total * w / sum), remainder ranks the leftover cents.
    val parts = weights.map { Math.floorDiv(totalCents * it, sum) }.toLongArray()
    val remainders = weights.map { Math.floorMod(totalCents * it, sum) }
    var left = totalCents - parts.sum()
    val order = weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
    var k = 0
    while (left > 0) { parts[order[k]]++; k = (k + 1) % order.size; left-- }
    return parts.toList()
  }

  /**
   * Discount per line, in cents. Lines with a known discount (read from the photo) keep it; the rest of the receipt's
   * discount is prorated over the other lines. [known] maps a line's index to its discount.
   */
  fun lineDiscounts(totalDiscountCents: Long, lineCents: List<Long>, known: Map<Int, Long> = emptyMap()): List<LineDiscount> {
    val open = lineCents.indices.filter { it !in known }
    val prorated = allocateCents(maxOf(0, totalDiscountCents - known.values.sum()), open.map { lineCents[it] })
    val byIndex = open.zip(prorated).toMap()
    return lineCents.indices.map { i ->
      known[i]?.let { LineDiscount(it, DiscountSource.PHOTO) } ?: LineDiscount(byIndex.getValue(i), DiscountSource.PRORATED)
    }
  }
}
