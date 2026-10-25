package io.github.sandroroque.getnf.data

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D79's index on hand-checkable cases. */
class IpciTest {
  private val jan = 1_767_268_800_000L // 2026-01-01 12:00 UTC
  private val day = 86_400_000L
  private fun month(n: Int) = jan + n * 31L * day

  /** A line of [qty] units at [cents] each (net, after [discount]). */
  private fun line(product: String, at: Long, cents: Long, qty: Double = 1.0, discount: Long = 0, source: String = "html",
                   unit: String = "UN", sizeE6: Long? = null) =
    SpendingLine("k", 1, at, null, null, Math.round(cents * qty) + discount, discount, source, product, null,
      Math.round(qty * 10_000), unit, cents * 100, sizeE6, sizeE6?.let { "kg" })

  /** Three products, so months clear the minimum; c never changes. */
  private fun basket(n: Int, a: Long, b: Long) = listOf(line("a", month(n), a), line("b", month(n), b), line("c", month(n), 1000))

  @Test fun eachMonthComparesWithTheProductsOwnLastPriceWeightedBySpending() {
    val months = Ipci.compute(basket(0, 1000, 2000) + basket(1, 1100, 2000) + basket(2, 1100, 2200))
    assertEquals(100.0, months[0].index, 1e-9)
    // Feb: a +10% weighing 1100 of 4100 spent on matched products.
    val feb = exp((1100 * ln(1.1)) / 4100)
    assertEquals(100 * feb, months[1].index, 1e-9)
    val mar = exp((2200 * ln(1.1)) / 4300)
    assertEquals(100 * feb * mar, months[2].index, 1e-9)
    assertEquals((feb - 1) * 100, months[1].reading!!, 1e-9)
    // What moved Feb: a, then nothing else.
    assertEquals("a", months[1].contributions().first().first)
  }

  @Test fun thePriceIsWhatWasPaidPerKgWhenTheSizeIsKnown() {
    // 100 g packs: R$ 2,98 then R$ 2,98 with R$ 0,30 off (read from a photo) = cheaper per kg.
    val pack = 100_000L
    val months = Ipci.compute(listOf(line("p", month(0), 298, sizeE6 = pack), line("q", month(0), 500), line("r", month(0), 500),
      line("p", month(1), 268, discount = 30, source = "photo", sizeE6 = pack), line("q", month(1), 500), line("r", month(1), 500)))
    val p = months[1].links.single { it.productId == "p" }
    assertEquals(2980.0, p.previous, 1e-9) // R$ 29,80 a kg
    assertEquals(2680.0, p.current, 1e-9)
    assertEquals(0.0, months[1].estimated, 1e-9) // the discount was read, not prorated
  }

  @Test fun proratedDiscountsAreCountedAsEstimates() {
    val months = Ipci.compute(basket(0, 1000, 1000) + listOf(line("a", month(1), 900, discount = 100, source = "prorated"),
      line("b", month(1), 1000), line("c", month(1), 1000)))
    assertEquals(900.0 / 2900, months[1].estimated, 1e-9)
  }

  @Test fun thinMonthsGiveNoReading() {
    // One repeated product among new ones: too little to say anything.
    val months = Ipci.compute(basket(0, 1000, 1000) + listOf(line("a", month(1), 1200), line("x", month(1), 5000), line("y", month(1), 5000)))
    assertTrue(months[1].coverage < Ipci.MIN_COVERAGE)
    assertNull(months[1].reading)
    assertNull(Ipci.split(months, months[0].period, months[1].period))
  }

  @Test fun spendingSplitsIntoPricesAndTheRest() {
    // Same products, prices +10%, and twice as much bought: spending ×2.2 = prices ×1.1 × the rest ×2.
    val before = basket(0, 1000, 1000)
    val after = listOf(line("a", month(1), 1100, qty = 2.0), line("b", month(1), 1100, qty = 2.0), line("c", month(1), 1100, qty = 2.0))
    val months = Ipci.compute(before + after)
    val split = Ipci.split(months, months[0].period, months[1].period)
    assertNotNull(split); split!!
    assertEquals(2.2, split.spending, 1e-9)
    assertEquals(1.1, split.prices, 1e-9)
    assertEquals(2.0, split.lifestyle, 1e-9)
  }
}
