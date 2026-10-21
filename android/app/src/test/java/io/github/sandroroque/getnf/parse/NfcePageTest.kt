package io.github.sandroroque.getnf.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** server/test/nfce_page.test.js, ported. */
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
