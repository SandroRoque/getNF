package io.github.sandroroque.getnf.ui.page

import io.github.sandroroque.getnf.ui.page.SefazPage.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SefazPageTest {
  @Test
  fun classifiesPortalUrls() {
    // URLs as seen in docs/sefaz-sc.md (note the inconsistent casing).
    assertEquals(Kind.Other, SefazPage.classify("https://sat.sef.sc.gov.br/nfce/consulta?p=1|2|1|1|ab"))
    assertEquals(Kind.Other, SefazPage.classify("https://sat.sef.sc.gov.br/tax.net/Sat.Dfe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p=1"))
    assertEquals(Kind.Details, SefazPage.classify("https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/NFCe_Detalhes.aspx?rq=abc"))
    assertEquals(Kind.Challenge, SefazPage.classify("https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc"))
    assertEquals(Kind.RateLimited, SefazPage.classify("https://sat.sef.sc.gov.br/static/appMaximoRequisicoesExcedidas.htm"))
    assertEquals(Kind.Other, SefazPage.classify(null))
    // RJ (D155): the QR address, then the result page once its check has run.
    assertEquals(Kind.Other, SefazPage.classify("https://consultadfe.fazenda.rj.gov.br/consultaNFCe/QRCode?p=1|2|1|3|AB"))
    assertEquals(Kind.Details, SefazPage.classify("https://consultadfe.fazenda.rj.gov.br/consultaNFCe/paginas/resultadoQRCode2.faces?cid=1"))
  }

  @Test
  fun qrAddressesWithRawPipesAreRecognised() {
    // The WebView reports the QR address with raw `|`, which java.net.URI refuses (T-5).
    val sc = "https://sat.sef.sc.gov.br/nfce/consulta?p=42000000000000000000650010000000011000000010|2|1|1|ab12"
    val rj = "https://consultadfe.fazenda.rj.gov.br/consultaNFCe/QRCode?p=33000000000000000000650010000000011000000010|2|1|3|AB12"
    assertTrue(SefazPage.isSefaz(sc))
    assertTrue(SefazPage.isRead(sc))
    assertTrue(SefazPage.isRead(rj))
    assertFalse(SefazPage.isSefaz(rj))
    assertFalse(SefazPage.isRead("https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=43000|2|1|1|AB"))
    assertEquals("sat.sef.sc.gov.br", SefazPage.hostOf(sc))
    assertNull(SefazPage.hostOf("not a url|x"))
    // A subresource with a pipe is judged by its path, not refused.
    assertTrue(SefazPage.shouldBlock(Kind.Other, "https://sat.sef.sc.gov.br/img/logo.png?v=1|2", null))
    assertFalse(SefazPage.shouldBlock(Kind.Other, "https://sat.sef.sc.gov.br/js/app.js?v=1|2", "*/*"))
  }

  @Test
  fun rjsNotFoundIsNoReceipt() {
    assertFalse(SefazPage.hasReceipt("<body><div>Não foi possível realizar a consulta. Por favor, entre em contato.</div></body>"))
    assertTrue(SefazPage.hasReceipt(javaClass.classLoader!!.getResource("rj-qrcode-server-html.html")!!.readText()))
    assertTrue(SefazPage.hasReceipt(javaClass.classLoader!!.getResource("sc-detalhes-webview-dom.html")!!.readText()))
  }

  @Test
  fun rjsScriptsAreNeverBlocked() {
    assertFalse(SefazPage.shouldBlock(Kind.Details, "https://consultadfe.fazenda.rj.gov.br/TSPD/?type=18", null))
    assertFalse(SefazPage.isSefaz("https://consultadfe.fazenda.rj.gov.br/consultaNFCe/QRCode?p=1"))
  }

  @Test
  fun blocksOnlyWhatWeDontNeed() {
    val fwkJs = "https://app.sat.sef.sc.gov.br/fwk-res/ResourceCatalogs/NET48/JavaScripts/CaptchaField.js?t=XJX8N15n"
    val fwkCss = "https://app.sat.sef.sc.gov.br/fwk-res/ResourceCatalogs/NET48/StyleSheets/Page.css?t=6szoF7g3"
    val logo = "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/imagens/logoNFCe.png"
    val turnstile = "https://challenges.cloudflare.com/turnstile/v0/api.js?onload=x&render=explicit"
    val axd = "https://sat.sef.sc.gov.br/tax.NET/ScriptResource.axd?d=abc&t=1"

    // Challenge: keep scripts and styles (Turnstile + Validar), drop images/fonts.
    assertFalse(SefazPage.shouldBlock(Kind.Challenge, fwkJs, "*/*"))
    assertFalse(SefazPage.shouldBlock(Kind.Challenge, fwkCss, "text/css,*/*;q=0.1"))
    assertFalse(SefazPage.shouldBlock(Kind.Challenge, axd, "*/*"))
    assertTrue(SefazPage.shouldBlock(Kind.Challenge, logo, null))
    assertTrue(SefazPage.shouldBlock(Kind.Other, "https://sat.sef.sc.gov.br/x/font.woff2?v=1", null))
    assertTrue(SefazPage.shouldBlock(Kind.Other, "https://sat.sef.sc.gov.br/Img.ashx?id=1", "image/avif,image/webp,*/*"))
    // Details: we only need the HTML.
    assertTrue(SefazPage.shouldBlock(Kind.Details, fwkJs, "*/*"))
    assertTrue(SefazPage.shouldBlock(Kind.Details, fwkCss, null))
    assertTrue(SefazPage.shouldBlock(Kind.Details, axd, null))
    // Never Cloudflare, never other sites.
    assertFalse(SefazPage.shouldBlock(Kind.Details, turnstile, "*/*"))
    assertFalse(SefazPage.shouldBlock(Kind.Challenge, "https://challenges.cloudflare.com/x/logo.png", "image/png"))
    assertFalse(SefazPage.shouldBlock(Kind.Details, "https://evilsef.sc.gov.br.example.com/a.js", null))
    assertFalse(SefazPage.shouldBlock(Kind.Details, "not a url", null))
  }

  @Test
  fun decodesEvaluateJavascriptResult() {
    assertEquals("<html lang=\"pt\">\nção</html>", SefazPage.decodeJsResult("\"<html lang=\\\"pt\\\">\\nção</html>\""))
    assertEquals("é", SefazPage.decodeJsResult("\"\\u00e9\""))
    assertNull(SefazPage.decodeJsResult("null"))
    assertNull(SefazPage.decodeJsResult("\"\""))
  }
}
