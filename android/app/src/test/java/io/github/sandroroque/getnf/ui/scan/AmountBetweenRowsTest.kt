package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D198: a Rio supermarket prints an item sold by weight on three rows (code and name, "N/S: …", weight × price) with
 * the line's value in the right-hand column between the last two. Frames saw it a little higher or lower, and it joined
 * one row or the other, or stood alone; each variant became another row, so the count grew at every pass.
 */
class AmountBetweenRowsTest {
  private fun box(text: String, left: Double, top: Double, right: Double, h: Double = 20.0) =
    OcrQuad(listOf(DocumentPoint(left, top), DocumentPoint(right, top), DocumentPoint(right, top + h), DocumentPoint(left, top + h)), .8, text.length, text)

  private fun frame(valueTop: Double, shift: Double = 0.0, value: String = "30,04") = PaperOutline(
    listOf(DocumentPoint(.05, .05), DocumentPoint(.95, .05), DocumentPoint(.95, .95), DocumentPoint(.05, .95)), false, false,
    listOf(
      box("SUPERMERCADOS MUNDIAL LTDA. CNPJ 33.304.981", 40.0, 28.0 + shift, 440.0),
      box("RUA DO RIACHUELO 192 BAIRRO DE FATIMA", 40.0, 52.0 + shift, 440.0),
      box("DOCUMENTO AUXILIAR DA NOTA FISCAL DE CONSUMIDOR", 40.0, 76.0 + shift, 440.0),
      box("015 00000000092562 ALCATRA BOV.NATURAFRIG GRANEL KG", 40.0, 100.0 + shift, 440.0),
      box("N/S: 02009256006024", 70.0, 124.0 + shift, 220.0),
      box(value, 460.0, valueTop + shift, 500.0),
      box("0,602KG X 49,90 T12,00%", 70.0, 148.0 + shift, 300.0),
      box("016 00000000092562 ALCATRA BOV.NATURAFRIG GRANEL KG", 40.0, 172.0 + shift, 440.0),
    ),
  )

  private fun texts(p: PaperOutline) = RowAssembly.rows(p, 1000, 1000).map { it.text }

  @Test fun anAmountBetweenTwoRowsJoinsNeither() {
    // Halfway between N/S (top 124) and the weight (top 148), a little up or down from one frame to the next.
    val expected = texts(frame(136.0))
    for (top in listOf(131.0, 136.0, 141.0)) assertEquals("value at $top", expected, texts(frame(top, shift = 7.0)))
    assertEquals(7, expected.size)
    assertEquals(false, expected.any { "3004" in it })
  }

  @Test fun anAmountLevelWithItsRowStaysInIt() {
    // SC's layout: the value on the weight's own row.
    assertEquals(true, texts(frame(149.0)).any { it.endsWith("3004") })
  }

  @Test fun framesWithTheAmountHigherOrLowerAddNoRows() {
    val assembly = RowAssembly()
    for (top in listOf(131.0, 141.0, 133.0, 139.0)) assembly.add(RowAssembly.rows(frame(top), 1000, 1000), false, false)
    assertEquals(7, assembly.rows.size)
  }
}
