package io.github.sandroroque.getnf.discounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo reader (D147, D148) on made-up OCR: rows rebuilt from pieces, the print's arithmetic, and its limits. */
class PhotoReceiptReaderTest {
  private var y = 100

  /** A row of OCR pieces at the next height: (left x, text), each piece its own line, the row tilted by [tilt] px per 100. */
  private fun row(vararg pieces: Pair<Int, String>, tilt: Int = 0): List<TextLine> {
    val top = y; y += 40
    return pieces.map { (x, text) ->
      val w = text.length * 18
      val dy = tilt * x / 100; val dy2 = tilt * (x + w) / 100
      TextLine(text, x, top + minOf(dy, dy2), x + w, top + 30 + maxOf(dy, dy2), .9f, listOf(x, top + dy, x + w, top + dy2, x + w, top + 30 + dy2, x, top + 30 + dy))
    }
  }

  private fun photo(vararg rows: List<TextLine>) = PhotoText("p.jpg", 1600, 1200, rows.flatMap { it })

  // Made-up codes with the right check digits, and a made-up store.
  private val receipt = listOf(
    row(0 to "# Cod Descricao Qt Un Vlr Tot"),
    row(0 to "01", 60 to "7890000000017 CAFE TORRADO 500G", 760 to "1 UN 18,90 18,90"),
    row(0 to "02 7890000000024 ATUM SOLIDO 170G", 760 to "2 UN 8,99 17,38", tilt = 4),
    row(0 to "Desconto: 0,60"),
    row(0 to "03 243 BANANA KG", 760 to "0,815 KG 5,98 4,87"),
    row(0 to "Qtd. total de itens 3"),
    row(0 to "Total produtos R$ 41,75"),
    row(0 to "Desconto R$ 0,60"),
    row(0 to "Valor total R$ 41,15"),
  )

  @Test fun piecesOfARowAreOneItemAndEveryCheckHolds() {
    val r = PhotoReceiptReader.read(listOf(photo(*receipt.toTypedArray())))
    assertEquals(listOf(1, 2, 3), r.items.map { it.number })
    with(r.items[1]) {
      assertEquals("7890000000024" to "ATUM SOLIDO 170G", code to description)
      assertEquals(listOf(20_000L, 89_900L, 1738L, 60L), listOf(qtyE4, unitPriceE4, totalCents, discountCents))
    }
    assertEquals(8150L to "KG", r.items[2].qtyE4 to r.items[2].unit)
    assertTrue(r.problems.toString(), r.trusted)
  }

  @Test fun aWrongDigitOrAMissingItemIsNeverTrusted() {
    val misread = receipt.toMutableList().apply { set(1, row(0 to "01 7890000000017 CAFE TORRADO 500G", 760 to "1 UN 18,90 19,90")) }
    assertFalse(PhotoReceiptReader.read(listOf(photo(*misread.toTypedArray()))).trusted)
    val gap = receipt.filterIndexed { i, _ -> i != 4 }
    assertFalse(PhotoReceiptReader.read(listOf(photo(*gap.toTypedArray()))).trusted)
  }

  @Test fun aGtinsOneUnreadCharacterComesFromItsCheckDigit() {
    assertEquals("7890000000017", PhotoReceiptReader.item("01 78900000n0017 CAFE TORRADO 500G 1 UN 18,90 18,90")?.code)
    // Two unread: nothing is guessed.
    assertEquals(null, PhotoReceiptReader.item("01 78900n00n0017 CAFE TORRADO 500G 1 UN 18,90 18,90"))
  }

  @Test fun twoRowsRunTogetherAreDropped() {
    assertEquals(null, PhotoReceiptReader.item("02 7890000000024 ATUM SOLIDO 170G 1 UN 3,99 3,99 1 UN 4,98 4,98"))
    assertEquals(null, PhotoReceiptReader.item("08 7890000000024 ATUM 170G 7890000000017 CAFE 1 UN 4,98 4,98"))
  }
}
