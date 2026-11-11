package io.github.sandroroque.getnf.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A saved page keeps the receipt and loses the buyer (D104). Values below are made up. */
class BuyerRedactionTest {
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()
  private val unidentified = "<li><strong>Consumidor não identificado</strong></li>"
  private val identified = "<li><strong>CPF: </strong>123.456.789-09</li><li><strong>Nome: </strong>FULANA DE TAL</li>" +
    "<li><strong>Logradouro: </strong>RUA DAS FLORES, 10</li>"

  @Test fun anIdentifiedBuyerIsBlankedAndTheReceiptReadsTheSame() {
    assertTrue(unidentified in html)
    val withBuyer = html.replace(unidentified, identified)
    val blanked = BuyerRedaction.blank(withBuyer)
    for (gone in listOf("123.456.789-09", "FULANA", "FLORES")) assertFalse(gone, gone in blanked)
    assertTrue(BuyerRedaction.REMOVED in blanked)
    // Everything the app reads is untouched.
    assertEquals(NfcePage.parse(html), NfcePage.parse(blanked))
  }

  /**
   * What's saved is the WebView's DOM (SefazPage.GET_HTML), where the portal's jQuery Mobile wraps the heading in a
   * link with a hidden status span and the list in a div (T-30). The buyer is made up, in the shape SC prints one.
   */
  @Test fun theBuyerIsBlankedInTheWebViewsDomToo() {
    val dom = javaClass.classLoader!!.getResource("sc-detalhes-webview-dom.html")!!.readText()
    val unidentified = "<strong>Consumidor não identificado</strong></li>"
    val identified = "<strong>CPF: </strong>123.456.789-09</li><li class=\"ui-li-static ui-body-inherit\"><strong>Nome: </strong>FULANA DE TAL</li>" +
      "<li class=\"ui-li-static ui-body-inherit ui-last-child\"><strong>Logradouro: </strong>RUA DAS FLORES , 10 , , CENTRO , FLORIANOPOLIS , SC</li>"
    assertTrue(unidentified in dom)
    val withBuyer = dom.replace(unidentified, identified)
    val blanked = BuyerRedaction.blank(withBuyer)
    for (gone in listOf("123.456.789-09", "FULANA", "FLORES")) assertFalse(gone, gone in blanked)
    assertTrue(BuyerRedaction.REMOVED in blanked)
    assertEquals(NfcePage.parse(dom), NfcePage.parse(blanked))
    assertEquals(dom, BuyerRedaction.blank(dom))
  }

  /** RJ's page: the same heading, each item on its own lines; a CNPJ buyer instead of a CPF. */
  @Test fun anRjBuyerIsBlanked() {
    val rj = javaClass.classLoader!!.getResource("rj-qrcode-server-html.html")!!.readText()
    val unidentified = "<li>\n<strong>Consumidor não identificado</strong>\n</li>"
    val identified = "<li>\n<strong>CNPJ Consumidor: </strong>99.888.777/0001-66\n</li>\n<li>\n<strong>Nome: </strong>EMPRESA FICTICIA LTDA\n</li>\n" +
      "<li>\n<strong>Logradouro: </strong>RUA DAS PALMEIRAS, 20, RIO DE JANEIRO, RJ\n</li>"
    assertTrue(unidentified in rj)
    val blanked = BuyerRedaction.blank(rj.replace(unidentified, identified))
    for (gone in listOf("99.888.777/0001-66", "FICTICIA", "PALMEIRAS")) assertFalse(gone, gone in blanked)
    assertEquals(NfcePage.parse(rj), NfcePage.parse(blanked))
    // Its heading as the WebView hands it over, too.
    val enhanced = rj.replace("<h4>Consumidor</h4>", "<h4 class=\"ui-collapsible-heading\"><a href=\"#\" class=\"ui-btn\">Consumidor" +
      "<span class=\"ui-collapsible-heading-status\"> click to collapse contents</span></a></h4><div class=\"ui-collapsible-content\">")
    val blankedEnhanced = BuyerRedaction.blank(enhanced.replace(unidentified, identified))
    for (gone in listOf("99.888.777/0001-66", "FICTICIA", "PALMEIRAS")) assertFalse(gone, gone in blankedEnhanced)
  }

  @Test fun aPageWithoutABuyerIsLeftAsItIs() {
    assertEquals(html, BuyerRedaction.blank(html))
  }

  @Test fun aCpfElsewhereIsBlankedButNotTheStoresCnpjOrTheKey() {
    val text = "Obs: CPF 987.654.321-00 · CNPJ 11.222.333/0001-81 · 42260111222333000181651090000000011123456784"
    assertEquals("Obs: CPF ***.***.***-** · CNPJ 11.222.333/0001-81 · 42260111222333000181651090000000011123456784", BuyerRedaction.blank(text))
  }
}
