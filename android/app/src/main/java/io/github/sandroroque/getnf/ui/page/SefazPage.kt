package io.github.sandroroque.getnf.ui.page

import java.net.URI
import kotlinx.serialization.json.Json

/**
 * Recognises the pages the SEFAZ portals go through: SC's (docs/sefaz-sc.md), RJ's (D155), which shows the same
 * receipt markup after a check of its own that runs without the person, and SP's (D206), whose "Consulta Resumida" is
 * RJ's markup served on the QR Code's own address, and SE's (D207), looked up by chave on a form of its own, since its
 * portal refuses its own QR Codes, then shown in RJ's markup too.
 */
object SefazPage {
  enum class Kind {
    /** SC's `NFCe_Detalhes.aspx`, RJ's `resultadoQRCode2.faces`, SP's `ConsultaQRCode.aspx`: the page we want to save. */
    Details,
    /** `SecurityVerify.aspx`: Cloudflare Turnstile, the user has to solve it. */
    Challenge,
    /** SE's lookup form (D207): the app fills in the chave and sends it once Turnstile has passed. */
    LookupForm,
    /** `appMaximoRequisicoesExcedidas.htm`: too many requests from this IP. Only waiting helps. */
    RateLimited,
    /** Redirects and anything else. */
    Other,
  }

  fun classify(url: String?): Kind {
    val u = url?.lowercase() ?: return Kind.Other
    return when {
      "nfce_detalhes.aspx" in u || "/consultanfce/paginas/resultadoqrcode" in u -> Kind.Details
      "/nfceconsultapublica/paginas/consultaqrcode.aspx" in u && isSaoPaulo(url) -> Kind.Details
      "/portal/consultarnfcedca.jsp" in u && isSergipe(url) -> Kind.Details
      "consultadanfenfce.htm" in u && isSergipe(url) -> Kind.LookupForm
      "securityverify.aspx" in u -> Kind.Challenge
      "maximorequisicoesexcedidas" in u -> Kind.RateLimited
      else -> Kind.Other
    }
  }

  private val NOT_NEEDED_ANYWHERE = listOf(".png", ".gif", ".jpg", ".jpeg", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".eot", ".otf")

  /**
   * Whether a details page holds a receipt. RJ answers on the same address with "Não foi possível realizar a consulta"
   * when it has no such receipt (one issued offline and not yet sent, D155).
   */
  fun hasReceipt(html: String): Boolean = "id=\"tabResult\"" in html

  /** The page came whole: the items and, after them, the totals block (T-39); a load cut short has only the first. */
  fun isComplete(html: String): Boolean = hasReceipt(html) && "id=\"totalNota\"" in html

  /**
   * Whether to answer a subresource request with an empty response instead of fetching it, to stay under SEFAZ's
   * per-IP request limit (docs/sefaz-sc.md: one visit was ~59 requests to SEFAZ hosts). SC's portal only: RJ's isn't
   * known to limit, and its check needs its scripts.
   * - Details page: we only need its HTML, so none of its scripts/styles/images are fetched.
   * - Other SEFAZ pages (the challenge in particular): scripts and styles stay, since Turnstile and the Validar button
   *   need them; images and fonts go.
   * - Never touches non-SEFAZ hosts (Cloudflare's Turnstile).
   */
  fun isSefaz(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return host == "sef.sc.gov.br" || host.endsWith(".sef.sc.gov.br")
  }

  /**
   * The URL's host, lowercased. The QR Code's address carries raw `|` in its query (`?p=chave|2|1|1|hash`), as the
   * WebView reports it, and [URI] refuses that character: so only the part before the path goes through it (T-5).
   */
  fun hostOf(url: String): String? {
    val authority = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*").find(url)?.value ?: return null
    return runCatching { URI(authority).host }.getOrNull()?.lowercase()
  }

  /** A portal whose receipt page the app reads: SC's, RJ's (D155), SP's (D206) and SE's (D207). Any other is another state's (D161). */
  fun isRead(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return isSefaz(url) || host == "fazenda.rj.gov.br" || host.endsWith(".fazenda.rj.gov.br") || isSaoPaulo(url) || isSergipe(url)
  }

  private fun isSaoPaulo(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return host == "fazenda.sp.gov.br" || host.endsWith(".fazenda.sp.gov.br")
  }

  private fun isSergipe(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return host == "se.gov.br" || host.endsWith(".se.gov.br")
  }

  /**
   * SE's portal answers its own QR Codes, old and new, with "QR Code INVÁLIDO!" (D207): its receipts are looked up by
   * chave on this form instead.
   */
  const val SERGIPE_FORM = "https://nfce.sefaz.se.gov.br/portal/portalNoticias.jsp?jsp=barra-menu/servicos/consultaDANFENFCe.htm"

  /** Where a receipt's page is looked up: the QR Code's address, except for SE's (D207). */
  fun lookupUrl(chave: String, qrUrl: String?): String? = if (chave.startsWith("28")) SERGIPE_FORM else qrUrl

  /**
   * JS for SE's form: puts the chave in its field, then presses Consultar as soon as Turnstile has left its token
   * (it usually passes by itself; if it asks, the person solves it and the same wait sends it). Gives up after 3 minutes.
   * The form is scrolled into view, so Turnstile is on screen if it asks.
   */
  fun fillLookupForm(chave: String): String {
    require(chave.length == 44 && chave.all { it in '0'..'9' })
    return """(function(){var f=document.getElementById('cdChaveAcesso');if(!f)return 'no field';f.value='$chave';f.scrollIntoView({block:'center'});
      |var n=0,t=setInterval(function(){var r=document.querySelector('[name="cf-turnstile-response"]');
      |if(r&&r.value){clearInterval(t);var b=document.getElementById('btnConsultar');if(b)b.click();}
      |else if(++n>360)clearInterval(t);},500);return 'filled';})()""".trimMargin()
  }

  fun shouldBlock(pageKind: Kind, url: String, accept: String?): Boolean {
    if (!isSefaz(url)) return false
    if (pageKind == Kind.Details) return true
    val path = url.substringBefore('#').substringBefore('?').lowercase()
    return NOT_NEEDED_ANYWHERE.any { path.endsWith(it) } || accept?.startsWith("image/") == true
  }

  /** JS that returns the whole DOM. `evaluateJavascript` hands back its result as a JSON string literal. */
  const val GET_HTML = "'<!DOCTYPE html>\\n' + document.documentElement.outerHTML"

  fun decodeJsResult(result: String): String? =
    runCatching { Json.decodeFromString<String?>(result) }.getOrNull()?.takeIf { it.isNotEmpty() }
}
