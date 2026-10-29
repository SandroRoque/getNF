package io.github.sandroroque.getnf.data

import kotlin.math.exp
import kotlin.math.ln

/**
 * IPCI, Índice de Preços ao Consumidor Individualíssimo (stage 7, D64, D79): the user's own price index.
 *
 * - **Price:** what was paid. A line's net amount (its discount read from a photo, else the prorated share) over its
 *   quantity, per kg or litre when sold by weight or the size is known, else per unit. A product's monthly price is its
 *   month's net over its month's quantity.
 * - **Chained monthly:** each month, every product bought is compared with its own previous price, however long ago;
 *   the log changes are weighted by the month's spending on each and chained. The first month is 100.
 * - **Honest:** a month whose matched spending is below [MIN_COVERAGE] of its spending, or rests on fewer than
 *   [MIN_PRODUCTS] products, has no reading ([Month.reading] is null) though the chain still carries its link.
 */
object Ipci {
  const val MIN_COVERAGE = .3
  const val MIN_PRODUCTS = 3

  /** One product's comparison in a month: what moved, by how much, and how much it weighs. */
  data class Link(val productId: String, val previous: Double, val current: Double, val spentCents: Long, val estimatedCents: Long) {
    val ratio: Double get() = current / previous
  }

  data class Month(
    val period: Period,
    /** The chained level, 100 at the first month with spending. */
    val index: Double,
    /** This month's change against the previous level (1.0 = none), from [links]. */
    val change: Double,
    val links: List<Link>,
    val spentCents: Long,
    /** Share of the month's spending in products that have an earlier price. */
    val coverage: Double,
    /** Share of the matched spending whose discount was prorated, not read: how much rests on estimates. */
    val estimated: Double,
  ) {
    val sufficient: Boolean get() = coverage >= MIN_COVERAGE && links.size >= MIN_PRODUCTS
    /** The month's change in percent, or null when the data can't support a number. */
    val reading: Double? get() = if (sufficient) (change - 1) * 100 else null
    /** What each product added to the month's change, in percentage points, largest first. */
    fun contributions(): List<Pair<String, Double>> {
      val total = links.sumOf { it.spentCents }.toDouble().takeIf { it > 0 } ?: return emptyList()
      return links.map { it.productId to it.spentCents / total * ln(it.ratio) * 100 }.sortedByDescending { kotlin.math.abs(it.second) }
    }
  }

  /** A product's price per basis unit, from lines in that basis. */
  private data class Obs(val net: Long, val qty: Double, val estimated: Long)

  /**
   * The index over [lines] (already narrowed to a scope: everything, or a category's subtree), by store-local month.
   */
  fun compute(lines: List<SpendingLine>): List<Month> {
    val dated = lines.filter { it.issuedAt != null }
    // Each product in its usual basis only: a product bought once by weight and once by unit isn't comparable.
    val basis = dated.groupBy { it.productId }.mapValues { (_, ls) -> ls.groupingBy { basisOf(it) }.eachCount().maxBy { it.value }.key }
    val byMonth = dated.groupBy { Period.month(it.issuedAt!!) }.toSortedMap()
    val last = mutableMapOf<String, Double>()
    val out = mutableListOf<Month>()
    var level = 100.0
    for ((period, ls) in byMonth) {
      val prices = ls.filter { basisOf(it) == basis[it.productId] }.groupBy { it.productId }.mapNotNull { (id, pls) ->
        val o = pls.fold(Obs(0, 0.0, 0)) { a, l -> Obs(a.net + l.netCents, a.qty + quantity(l), a.estimated + if (l.discountSource == "prorated") l.discountCents else 0) }
        if (o.qty > 0 && o.net > 0) id to o else null
      }.toMap()
      val links = prices.mapNotNull { (id, o) ->
        last[id]?.let { prev -> Link(id, prev, o.net / o.qty, o.net, o.estimated) }
      }
      val weight = links.sumOf { it.spentCents }.toDouble()
      val change = if (weight > 0) exp(links.sumOf { it.spentCents * ln(it.ratio) } / weight) else 1.0
      if (out.isNotEmpty()) level *= change
      val spent = ls.sumOf { it.netCents }
      val matched = links.sumOf { it.spentCents }
      out += Month(
        period, level, if (out.isEmpty()) 1.0 else change, if (out.isEmpty()) emptyList() else links, spent,
        coverage = if (spent > 0 && out.isNotEmpty()) matched.toDouble() / spent else 0.0,
        estimated = if (matched > 0) links.filter { it.estimatedCents > 0 }.sumOf { it.spentCents }.toDouble() / matched else 0.0,
      )
      for ((id, o) in prices) last[id] = o.net / o.qty
    }
    return out
  }

  /**
   * D64's split between two months: spending changed by [spending], prices by [prices] (the index), and the rest,
   * [lifestyle], is quantity and the choice of products (trading up shows here). `spending = prices × lifestyle`.
   * Null when either month has no reading.
   */
  data class Split(val spending: Double, val prices: Double, val lifestyle: Double)

  fun split(months: List<Month>, from: Period, to: Period): Split? {
    val a = months.firstOrNull { it.period == from } ?: return null
    val b = months.firstOrNull { it.period == to } ?: return null
    // Every month in between must hold up, or the chain's change over them isn't a reading.
    if (months.filter { it.period > from && it.period <= to }.any { !it.sufficient } || a.spentCents <= 0) return null
    val spending = b.spentCents.toDouble() / a.spentCents
    val prices = b.index / a.index
    return Split(spending, prices, spending / prices)
  }

  /** `kg`, `l` or `un`: how a line's quantity is measured for comparing prices. */
  fun basisOf(l: SpendingLine): String {
    val measure = l.measure
    return when {
      measure != null -> measure
      l.sizeE6 != null && l.sizeE6 > 0 && l.sizeUnit != null -> l.sizeUnit
      else -> "un"
    }
  }

  /** The line's quantity in its basis: kilos or litres, else units. */
  fun quantity(l: SpendingLine): Double {
    val units = l.qtyE4 / 10_000.0
    return when {
      l.measure != null -> units
      l.sizeE6 != null && l.sizeE6 > 0 && l.sizeUnit != null -> units * l.sizeE6 / 1_000_000.0
      else -> units
    }
  }
}
