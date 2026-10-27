package io.github.sandroroque.getnf.discounts

import io.github.sandroroque.getnf.discounts.PhotoDiscounts.Item
import io.github.sandroroque.getnf.discounts.PhotoDiscounts.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading per-item discounts from OCR lines (D54), on made-up lines shaped like the real receipts' OCR. */
class PhotoDiscountsTest {
  private val items = listOf(
    Item(1, "7898541230498", "LOMBO FAZENDA 1KG DEF", 2998),
    Item(2, "243", "MACA NAC KG", 569),
    Item(3, "7896014400645", "POLPA BRASFRUT 100G", 1036),
    Item(4, "7896014400287", "POLPA BRASFRUT 100G", 1192),
  )

  /** Lines 57 px apart, 45 px tall, like a 3072×4096 photo; [rows] are (text, left). */
  private fun photo(vararg rows: Pair<String, Int>, name: String = "a.jpg", start: Int = 500) =
    PhotoText(name, 3072, 4096, rows.mapIndexed { i, (text, left) -> TextLine(text, left, start + 57 * i, left + 800, start + 57 * i + 45, .8f) })

  private val receipt = photo(
    "01 7898541230498 LOMBO FAZENDA 1KG DEF 1 UN 29,98 29,98" to 740,
    "08 243 MACA NAC KG" to 740,
    "10 7896014400645 P0LPA BRASFRUT 1006" to 740,
    "Desconto: 1,56" to 736,
    "J1 7896014400287 POLPA BRASFRUT 1006" to 745, // the item number misread
    "Desconto: 2,04" to 730,
    "Total produtos" to 698,
    "Desconto R$ 3,60" to 687, // the totals block: not an item's discount
  )

  @Test fun discountLinesGoToTheItemAboveThem() {
    val r = PhotoDiscounts.read(listOf(receipt), items, 360)
    assertEquals(Verdict.COMPLETE, r.verdict)
    assertEquals(listOf(3 to 156L, 4 to 204L), r.discounts.map { it.lineNo to it.cents })
    assertEquals(emptyList<String>(), r.problems)
  }

  @Test fun aReceiptPhotographedSidewaysIsReadAlongItsPrint() {
    // Turned a quarter (reading downwards), with the corners OCR gives: "above" a discount is to its right on the image.
    fun turn(x: Int, y: Int) = listOf(4096 - y, x)
    val sideways = PhotoText("s.jpg", 4096, 3072, receipt.lines.map { l ->
      TextLine(l.text, 4096 - l.bottom, l.left, 4096 - l.top, l.right, l.confidence,
        turn(l.left, l.top) + turn(l.right, l.top) + turn(l.right, l.bottom) + turn(l.left, l.bottom))
    })
    val r = PhotoDiscounts.read(listOf(sideways), items, 360)
    assertEquals(Verdict.COMPLETE, r.verdict)
    assertEquals(listOf(3 to 156L, 4 to 204L), r.discounts.map { it.lineNo to it.cents })
  }

  @Test fun lessThanTheTotalIsPartial() {
    val r = PhotoDiscounts.read(listOf(receipt), items, 500)
    assertEquals(Verdict.PARTIAL, r.verdict)
    assertEquals(360L, r.foundCents)
  }

  @Test fun moreThanTheTotalIsAConflictAndNothingIsApplied() {
    val r = PhotoDiscounts.read(listOf(receipt), items, 300)
    assertEquals(Verdict.CONFLICT, r.verdict)
    assertTrue(r.discounts.isEmpty())
  }

  @Test fun aDiscountUnderAnItemThatWasntRecognizedIsNotGivenToTheOneAbove() {
    // Item 3's code misread beyond repair: its discount must not land on item 2.
    val p = photo(
      "08 243 MACA NAC KG" to 740,
      "10 78960I44OO6A5 P0LPA BRASFRUT 1006" to 740,
      "Desconto: 1,56" to 736,
    )
    val r = PhotoDiscounts.read(listOf(p), items, 156)
    assertEquals(Verdict.NONE, r.verdict)
    assertEquals(1, r.unassigned)
  }

  @Test fun aDiscountTooFarBelowItsItemIsNotTaken() {
    val p = PhotoText("a.jpg", 3072, 4096, listOf(
      TextLine("10 7896014400645 POLPA BRASFRUT 100G", 740, 500, 1500, 545, .8f),
      TextLine("Desconto: 1,56", 736, 700, 1100, 745, .8f),
    ))
    assertEquals(Verdict.NONE, PhotoDiscounts.read(listOf(p), items, 156).verdict)
  }

  @Test fun aShortStoreCodeNeedsTheDescriptionToo() {
    // "243" alone could be anything (a price, a quantity); with the description's first word it's the apple.
    val loose = photo("12 243 SACOLA" to 740, "Desconto: 0,10" to 736)
    assertEquals(Verdict.NONE, PhotoDiscounts.read(listOf(loose), items, 10).verdict)
    val apple = photo("08 243 MACA NAC KG" to 740, "Desconto: 0,10" to 736)
    assertEquals(listOf(2 to 10L), PhotoDiscounts.read(listOf(apple), items, 10).discounts.map { it.lineNo to it.cents })
  }

  @Test fun overlappingPhotosAgreeingCountOnce() {
    val second = photo("10 7896014400645 POLPA BRASFRUT 100G" to 740, "Desconto: 1,56" to 736, name = "b.jpg", start = 90)
    val r = PhotoDiscounts.read(listOf(receipt, second), items, 360)
    assertEquals(Verdict.COMPLETE, r.verdict)
    assertEquals(2, r.discounts.single { it.lineNo == 3 }.evidence.size)
  }

  @Test fun photosThatDisagreeWithoutAClearMajorityAreAConflict() {
    val other = photo("10 7896014400645 POLPA BRASFRUT 100G" to 740, "Desconto: 1,58" to 736, name = "b.jpg")
    assertEquals(Verdict.CONFLICT, PhotoDiscounts.read(listOf(receipt, other), items, 360).verdict)
    // Two readings against one misread: the majority wins.
    val third = photo("10 7896014400645 POLPA BRASFRUT 100G" to 740, "Desconto: 1,56" to 736, name = "c.jpg")
    val r = PhotoDiscounts.read(listOf(receipt, other, third), items, 360)
    assertEquals(Verdict.COMPLETE, r.verdict)
    assertEquals(156L, r.discounts.single { it.lineNo == 3 }.cents)
  }

  @Test fun theSameProductRungUpTwicePairsInPrintOrderOrByItsNumber() {
    val twice = listOf(Item(11, "7898342050325", "ATUM SOLIDO NAUT 170G", 1798), Item(12, "7898342050325", "ATUM SOLIDO NAUT 170G", 2697))
    val both = photo(
      "11 7898342050325 ATUM SOL IDO NAUT 1706" to 584, "Desconto: 0,60" to 572,
      "12 7898342050325 ATUM SOLIDO NAUT 1706" to 581, "Desconto: 0,90" to 570,
    )
    assertEquals(listOf(11 to 60L, 12 to 90L), PhotoDiscounts.read(listOf(both), twice, 150).discounts.map { it.lineNo to it.cents })
    // Only the second in this photo: its printed number says which one it is.
    val one = photo("12 7898342050325 ATUM SOLIDO NAUT 1706" to 581, "Desconto: 0,90" to 570)
    assertEquals(listOf(12 to 90L), PhotoDiscounts.read(listOf(one), twice, 150).discounts.map { it.lineNo to it.cents })
  }

  @Test fun fromToPricesNameTheItemByItsGross() {
    val pharmacy = listOf(Item(1, "1000021204", "REMEDIO A", 25651), Item(2, "1000026121", "VITAMINA B", 8561))
    val p = photo("1 UN De R$ 256,51 pOr R$ 166,73" to 600, "1 UN De RS 85,61 por R$ 41.90" to 600)
    val r = PhotoDiscounts.read(listOf(p), pharmacy, 13349)
    assertEquals(Verdict.COMPLETE, r.verdict)
    assertEquals(listOf(1 to 8978L, 2 to 4371L), r.discounts.map { it.lineNo to it.cents })
  }

  @Test fun anotherReceiptsPhotoFindsNothing() {
    val stranger = photo("05 7891000100103 LEITE COND 395G" to 740, "Desconto: 0,50" to 736)
    val r = PhotoDiscounts.read(listOf(stranger), items, 0)
    assertEquals(Verdict.NONE, r.verdict)
    assertEquals(1, r.unassigned)
  }
}
