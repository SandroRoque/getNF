package io.github.sandroroque.getnf.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-11-09, privacy. The app saves `document.documentElement.outerHTML` of the WebView (SefazPage.GET_HTML),
 * not the server's HTML. When the portal's jQuery Mobile runs (always on RJ, whose scripts aren't blocked; sometimes on
 * SC), the "Consumidor" heading becomes `<h4 class=…><a …>Consumidor<span>…</span></a></h4><div …><ul>`, which
 * BuyerRedaction's pattern (written for the server's `<h4>Consumidor</h4><ul>`) doesn't match. The fixture below is the
 * repo's own synthetic WebView DOM; the buyer is made up, in the shape SC prints one (CPF, Nome, Logradouro).
 */
class Review1109PrivacyBuyerRedactionTest {
  private val dom = javaClass.classLoader!!.getResource("sc-detalhes-webview-dom.html")!!.readText()
  private val unidentified = "<strong>Consumidor não identificado</strong></li>"
  private val identified = "<strong>CPF: </strong>123.456.789-09</li><li class=\"ui-li-static ui-body-inherit\"><strong>Nome: </strong>FULANA DE TAL</li>" +
    "<li class=\"ui-li-static ui-body-inherit ui-last-child\"><strong>Logradouro: </strong>RUA DAS FLORES , 10 , , CENTRO , FLORIANOPOLIS , SC</li>"

  @Test fun theBuyerIsRemovedFromThePageAsTheWebViewHandsItOver() {
    assertTrue("fixture has the unidentified buyer", unidentified in dom)
    val blanked = BuyerRedaction.blank(dom.replace(unidentified, identified))
    assertFalse("the buyer's CPF must not be saved", "123.456.789-09" in blanked)
    assertFalse("expected: the buyer's NAME is removed from a page in the WebView's (jQuery-Mobile-enhanced) DOM, as D104 and the policy promise; it is still there", "FULANA DE TAL" in blanked)
    assertFalse("expected: the buyer's ADDRESS is removed too; it is still there", "RUA DAS FLORES" in blanked)
  }
  // (Removed by the 2026-11-10 reviewer: a test of a CPF printed without dots. Every identified buyer in the real saved
  // pages prints it dotted, which the global CPF pattern already removes; the claim had no evidence behind it.)
}
