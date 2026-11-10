package io.github.sandroroque.getnf.parse

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-11-09, area "parsing". Synthetic pages only: built from the repo's sanitized fixture's markup
 * (android/app/src/test/fixtures/sc-detalhes-server-html.html), nothing from test-media/.
 * Tests named `defect_*` FAIL today and state the expected behaviour; `holds_*` pass and record what was tried.
 */
class Review1109ParsingNfcePageTest {
  private val fixture = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private fun item(n: Int, desc: String, code: String, total: String) = """
      <tr id="Item + $n">
        <td valign="top"><span class="txtTit">$desc</span><span class="RCod">(Código: $code )</span><br><span class="Rqtd"><strong>Qtde.:</strong>1</span><span class="RUN"><strong>UN: </strong>UN</span><span class="RvlUnit"><strong>Vl. Unit.:</strong> $total</span></td>
        <td align="right" valign="top" class="txtTit noWrap">Vl. Total<br><span class="valor">$total</span></td>
      </tr>"""

  private fun page(items: String, totals: List<Pair<String, String>>, payments: List<Pair<String, String>> = emptyList()) = """
    <html><body><table id="tabResult">$items
    </table>
    <div id="totalNota" class="txtRight">
    ${totals.joinToString("\n") { (l, v) -> "<div id=\"linhaTotal\"><label>$l</label><span class=\"totalNumb\">$v</span></div>" }}
    <div id="linhaForma"><label>Forma de pagamento:</label><span class="totalNumb txtTitR">Valor pago R$:</span></div>
    ${payments.joinToString("\n") { (l, v) -> "<div id=\"linhaTotal\"><label class=\"tx\">$l</label><span class=\"totalNumb\">$v</span></div>" }}
    </div><div id="infos"></div></body></html>"""

  // ---- Q1: what gets saved as the receipt ----

  /** A page cut off mid-list (connection dropped while loading) has no totals block: nothing flags it. */
  @Test fun defect_truncatedPageParsesWithoutAnyWarning() {
    val cutAt = fixture.indexOf("<tr id=\"Item + 6\"")
    val truncated = fixture.substring(0, cutAt)
    val p = NfcePage.parse(truncated)
    assertEquals(5, p.items.size)
    // Totals fall back to the items' sum, so the arithmetic check has nothing to compare with.
    assertTrue(
      "a page with no totals block (\"Valor a pagar\", \"Qtd. total de itens\" missing) should carry a warning, " +
        "or be refused, instead of becoming a complete-looking receipt of ${p.items.size} items; warnings=${p.warnings}, " +
        "payable=${p.totals.payableCents}",
      p.warnings.isNotEmpty(),
    )
  }

  // ---- Q2: numbers and codes ----

  /** cProd may be alphanumeric; only the first digit run is kept, so different products share one code. */
  @Test fun defect_storeCodesWithNonDigitsCollide() {
    val p = NfcePage.parse(page(item(1, "PAO FRANCES", "123-4", "1,00") + item(2, "PAO DOCE", "123-5", "2,00"),
      listOf("Valor a pagar R$:" to "3,00")))
    assertNotEquals(
      "two lines with store codes 123-4 and 123-5 should keep distinct codes (got ${p.items.map { it.code }}); " +
        "as is, Derive.codeKey makes them one store item, so one product and one category for both",
      p.items[0].code, p.items[1].code,
    )
  }

  @Test fun defect_storeCodeWithoutDigitsIsLost() {
    val p = NfcePage.parse(page(item(1, "SACOLA", "SAC", "0,10") + item(2, "BANDEJA", "BDJ", "0,20"),
      listOf("Valor a pagar R$:" to "0,30")))
    assertNotNull(
      "a code with no digits (\"SAC\") should be kept; null becomes \"\" in ReceiptParser, and every such line of the " +
        "store shares one store item (codes: ${p.items.map { it.code }})",
      p.items[0].code,
    )
  }

  // ---- Q4: odd receipts ----

  /**
   * A surcharge printed under a label the parser doesn't know (here "Frete", hypothetical wording) isn't spread over
   * the lines (D175): the lines' net is less than what was paid. Only an English warning on the receipt screen says so.
   */
  @Test fun defect_unrecognisedSurchargeLabelMissesTheLines() {
    val p = NfcePage.parse(page(item(1, "ARROZ 5KG", "7891234567895", "10,00"),
      listOf("Qtd. total de itens:" to "1", "Valor total R$:" to "10,00", "Frete R$:" to "2,00", "Valor a pagar R$:" to "12,00")))
    val linesNet = p.totals.grossCents - p.totals.discountCents + (p.totals.surchargeCents ?: 0)
    assertEquals(
      "what the lines add up to (spending) should equal what was paid; surcharge=${p.totals.surchargeCents}, " +
        "warnings=${p.warnings}",
      p.totals.payableCents, linesNet,
    )
  }

  // ---- What held ----

  @Test fun holds_decimals() {
    assertEquals(BigDecimal("1234.56"), NfcePage.decimal("1.234,56"))
    assertEquals(BigDecimal("-1.00"), NfcePage.decimal("-1,00"))
    assertEquals(0L, NfcePage.e4("0,0000"))
    assertEquals(6425L, NfcePage.e4("0,6425"))
    assertEquals(6430L, NfcePage.e4("0,643"))
    assertEquals(123400L, NfcePage.cents("1.234"))
    assertEquals(3064L, NfcePage.cents("R$ 30,64"))
    assertEquals(null, NfcePage.cents(""))
  }

  @Test fun holds_severalPaymentsAndTroco() {
    val p = NfcePage.parse(page(item(1, "X", "1", "50,00"),
      listOf("Valor a pagar R$:" to "50,00"),
      listOf("Dinheiro" to "20,00", "Cartão de Débito" to "40,00", "Troco" to "10,00")))
    assertEquals(listOf(2000L, 4000L), p.payments.map { it.amountCents })
    assertEquals(1000L, p.totals.changeCents)
    assertTrue(p.warnings.toString(), p.warnings.isEmpty())
  }

  @Test fun holds_itemWithoutTotalIsFlaggedWhenPagePrintsATotal() {
    val noValor = item(2, "Y", "2", "5,00").replace("<span class=\"valor\">5,00</span>", "")
    val p = NfcePage.parse(page(item(1, "X", "1", "5,00") + noValor, listOf("Valor a pagar R$:" to "10,00")))
    assertEquals(null, p.items[1].totalCents)
    assertTrue(p.warnings.isNotEmpty())
  }

  @Test fun holds_wrongPayableIsWarned() {
    val p = NfcePage.parse(page(item(1, "X", "1", "5,00"), listOf("Valor total R$:" to "5,00", "Valor a pagar R$:" to "6,00")))
    assertTrue(p.warnings.any { "payable" in it })
  }

  @Test fun holds_sizesAndGtins() {
    assertEquals(Size(250_000, "kg"), Derive.parseSize("CAFE 250G"))
    assertEquals(Size(12_000_000, "l"), Derive.parseSize("LEITE 12X1L"))
    assertEquals(Size(1_500_000, "l"), Derive.parseSize("REFRI 1,5L"))
    assertEquals(null, Derive.parseSize("BANANA PRATA"))
    assertTrue(Derive.isGtin("7891234567895"))
    assertTrue(!Derive.isGtin("2000000000008")) // restricted circulation
    assertEquals(null, Derive.unitKind("UN"))
  }
}
