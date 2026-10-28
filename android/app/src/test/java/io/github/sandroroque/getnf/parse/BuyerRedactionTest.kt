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

  @Test fun aPageWithoutABuyerIsLeftAsItIs() {
    assertEquals(html, BuyerRedaction.blank(html))
  }

  @Test fun aCpfElsewhereIsBlankedButNotTheStoresCnpjOrTheKey() {
    val text = "Obs: CPF 987.654.321-00 · CNPJ 11.222.333/0001-81 · 42260111222333000181651090000000011123456784"
    assertEquals("Obs: CPF ***.***.***-** · CNPJ 11.222.333/0001-81 · 42260111222333000181651090000000011123456784", BuyerRedaction.blank(text))
  }
}
