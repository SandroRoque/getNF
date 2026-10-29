package io.github.sandroroque.getnf.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the retired server's derive.test.js (D48). */
class DeriveTest {
  @Test fun gtinCheckDigitsAndStoreInternalCodes() {
    assertTrue(Derive.isGtin("7891079000229"))
    assertFalse(Derive.isGtin("7891079000228")) // wrong check digit
    assertFalse(Derive.isGtin("243"))
    assertFalse(Derive.isGtin("1043570")) // 7 digits: store code
    assertFalse(Derive.isGtin("2000123000005")) // restricted circulation (in-store, variable weight)
    assertTrue(Derive.isGtin("96385074")) // GTIN-8
  }

  @Test fun codeKeys() {
    assertEquals(CodeKey("gtin", "7891079000229", ""), Derive.codeKey("7891079000229", "11222333000181"))
    assertEquals("gtin:7891079000229", Derive.codeKey("7891079000229", "11222333000181").serverKey)
    assertEquals("store:11222333000181:243", Derive.codeKey("243", "11222333000181").serverKey)
  }

  @Test fun sizesFromDescriptions() {
    assertEquals(Size(250_000, "kg"), Derive.parseSize("CAFE 3 CORACOES 250G MOG"))
    assertEquals(Size(1_000_000, "l"), Derive.parseSize("LEITE ITALAC INTEG 1L"))
    assertEquals(Size(85_000, "kg"), Derive.parseSize("MAC INST NISSIN 85G"))
    assertEquals(Size(300_000, "kg"), Derive.parseSize("PAO HAMB VITAL 300G C G"))
    assertEquals(Size(9_000_000, "l"), Derive.parseSize("AGUA MINERAL 6X1,5L"))
    assertEquals(Size(350_000, "l"), Derive.parseSize("REFRIG 350ML"))
    assertEquals(Size(5_000_000, "kg"), Derive.parseSize("ARROZ 5KG"))
    assertNull(Derive.parseSize("MACA NAC KG")) // sold by weight: no pack size
    assertNull(Derive.parseSize("QJO MUSS CASA QUEIJO 300")) // no unit: fix by hand
    assertNull(Derive.parseSize("FILTRO PAP BRIGITTA 102"))
  }

  @Test fun allocationAddsUpExactlyAndFollowsTheWeights() {
    val parts = Derive.allocateCents(50, listOf(562, 298, 298, 298, 298, 298, 698, 364, 599, 375, 498, 249, 249, 249, 249, 249, 2098, 2098, 349, 349))
    assertEquals(50L, parts.sum())
    assertEquals(listOf(10L, 10L), parts.subList(16, 18)) // the two coffees: ~19.6% of the total each
    assertEquals(listOf(1L, 0L, 0L), Derive.allocateCents(1, listOf(100, 100, 100)))
    assertEquals(listOf(0L, 0L), Derive.allocateCents(0, listOf(100, 200)))
    assertEquals(listOf(7L, 0L), Derive.allocateCents(7, listOf(0, 0)))
    assertEquals(emptyList<Long>(), Derive.allocateCents(5, emptyList()))
  }

  @Test fun photoDiscountsAreKeptTheRestIsProrated() {
    assertEquals(
      listOf(LineDiscount(15, DiscountSource.PRORATED), LineDiscount(15, DiscountSource.PRORATED), LineDiscount(10, DiscountSource.PHOTO), LineDiscount(10, DiscountSource.PHOTO)),
      Derive.lineDiscounts(50, listOf(1000, 1000, 249, 249), mapOf(2 to 10L, 3 to 10L)),
    )
    // Everything known from the photo: nothing left to prorate.
    assertEquals(listOf(20L, 0L), Derive.lineDiscounts(20, listOf(500, 500), mapOf(0 to 20L, 1 to 0L)).map { it.cents })
  }
}
