package io.github.sandroroque.getnf.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ported from the retired server's nfce_page.test.js (D48). */
class NfcePageTest {
  private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

  @Test fun serverHtmlHeaderItemsAndTotalsWithDiscount() {
    val p = NfcePage.parse(fixture("sc-detalhes-server-html.html"))
    assertEquals("42260111222333000181651090000000011123456784", p.chave)
    assertEquals(ParsedStore("SUPERMERCADO EXEMPLO LTDA", "11222333000181", "RUA EXEMPLO, 100 , , CENTRO , CIDADE EXEMPLO , SC"), p.store)
    // 2026-01-15T10:00:00-03:00
    assertEquals(1768482000000, p.issuedAt)
    assertEquals("109", p.series)
    assertEquals(8, p.items.size)
    assertEquals(ParsedItem(1, "MACA NAC KG", "243", 6400, "KG", 87800, 562), p.items[0])
    assertEquals("7896014400287", p.items[1].code)
    assertEquals(8L, p.totals.itemsCount)
    assertEquals(3114L, p.totals.grossCents)
    assertEquals(50L, p.totals.discountCents)
    assertEquals(3064L, p.totals.payableCents)
    assertEquals(listOf(ParsedPayment("Cartão de Crédito", 3064)), p.payments)
    assertEquals(emptyList<String>(), p.warnings)
  }

  @Test fun webViewDomWithoutDiscountLines() {
    val p = NfcePage.parse(fixture("sc-detalhes-webview-dom.html"))
    assertEquals(6, p.items.size)
    assertEquals(ParsedItem(1, "PAO LTE SEVEN BOYS 450G", "7891193010081", 10000, "UN", 67900, 679), p.items[0])
    // "Valor total"/"Descontos" are absent when there's no discount; the parser fills them in.
    assertEquals(4443L, p.totals.grossCents)
    assertEquals(0L, p.totals.discountCents)
    assertEquals(4443L, p.totals.payableCents)
    assertTrue(p.taxesEstimate!!.federalCents!! > 0)
    assertEquals(emptyList<String>(), p.warnings)
  }

  @Test fun twoPaymentMethodsAndChange() {
    // No real receipt paid two ways yet: the committed page with cash and a card, and change from the cash.
    val card = "<div id=\"linhaTotal\"><label class=\"tx\">\n\t\t\t\t\t\t\t\t\t\t\tCartão de Crédito\n\t\t\t\t\t\t\t\t\t\t</label><span class=\"totalNumb\">30,64</span></div>"
    val html = fixture("sc-detalhes-server-html.html")
    assertTrue("the fixture's payment line changed", card in html)
    val p = NfcePage.parse(html.replace(card,
      "<div id=\"linhaTotal\"><label class=\"tx\">Dinheiro</label><span class=\"totalNumb\">20,00</span></div>" +
        "<div id=\"linhaTotal\"><label class=\"tx\">Cartão de Débito</label><span class=\"totalNumb\">15,00</span></div>" +
        "<div id=\"linhaTotal\"><label>Troco R$:</label><span class=\"totalNumb\">4,36</span></div>"))
    assertEquals(listOf(ParsedPayment("Dinheiro", 2000), ParsedPayment("Cartão de Débito", 1500)), p.payments)
    assertEquals(436L, p.totals.changeCents)
    assertEquals(3064L, p.totals.payableCents)
  }

  @Test fun taxesInEachStoresWording() {
    fun taxes(text: String) = NfcePage.parseTaxes(text)
    assertEquals(ParsedTaxes(3770, 4263, 0, 8033),
      taxes("[##TRIBUTOS TOTAIS INCIDENTES (LEI FEDERAL 12.7412012) - FEDERAL R$ 37,70 ESTADUAL R$ 42,63 MUNICIPAL R$ 0,00 ; COO: 22063, PDV: 105]"))
    assertEquals(ParsedTaxes(2807, 3546, null, 6353),
      taxes("Informação dos Tributos Totais Incidentes (Lei Federal 12.741/2012) R$ 63,53 Informações gerais da Nota " +
        "PDV: 9 VALOR APROXIMADO TRIBUTOS (Lei 12.741/2012) - FEDERAIS: R$ 28,07 - ESTADUAIS: R$ 35,46. Fonte: IBPT."))
    assertEquals(ParsedTaxes(4795, 2888, null, 7683),
      taxes("Consumidor nao identificado #DAV 0001|Trib aprox R$: 47,95 Federal e 28,88 Estadual|Fonte: IBPT 09/10/2026"))
    // Only SEFAZ's total; and "Lei Federal" outside the tax text isn't an amount.
    assertEquals(ParsedTaxes(null, null, null, 6353), taxes("Tributos Totais Incidentes (Lei Federal 12.741/2012) R$ 63,53"))
    assertNull(taxes("Lei Federal 8.078, de 11/09/1990, R$ 10,00 de desconto"))
  }

  @Test fun warnsWhenItemsDontAddUpToTheTotal() {
    val html = fixture("sc-detalhes-server-html.html").replace("<span class=\"valor\">5,62</span>", "<span class=\"valor\">5,63</span>")
    assertTrue(NfcePage.parse(html).warnings.joinToString(), NfcePage.parse(html).warnings.any { "items sum to 31.15" in it })
  }

  @Test fun rejectsPagesWithoutItems() {
    try { NfcePage.parse("<html><div class='cf-turnstile'></div></html>"); fail() } catch (e: NfceParseException) {
      assertTrue(e.message!!.contains("no items"))
    }
  }

  @Test fun brazilianNumbersAreReadExactly() {
    assertEquals(123456L, NfcePage.cents("1.234,56"))
    assertEquals(6400L, NfcePage.e4(" 0,64"))
    assertEquals(2000L, NfcePage.cents("20"))
    assertNull(NfcePage.cents(""))
    // Floats would make this 1.0049999…
    assertEquals(10050L, NfcePage.e4("1,005"))
  }

  @Test fun entitiesAndWhitespace() {
    assertEquals("A & B ç", NfcePage.text("<b>A</b>&nbsp;&amp;\n B &#231;"))
  }
}
