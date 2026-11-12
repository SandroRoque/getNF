package io.github.sandroroque.getnf.ui.page

import java.net.URI
import kotlinx.serialization.json.Json

/**
 * Recognises the pages the SEFAZ portals go through: SC's (docs/sefaz-sc.md) and RJ's (D155), which shows the same
 * receipt markup after a check of its own that runs without the person.
 */
object SefazPage {
  enum class Kind {
    /** SC's `NFCe_Detalhes.aspx`, RJ's `resultadoQRCode2.faces`: the page we want to save. */
    Details,
    /** `SecurityVerify.aspx`: Cloudflare Turnstile, the user has to solve it. */
    Challenge,
    /** `appMaximoRequisicoesExcedidas.htm`: too many requests from this IP. Only waiting helps. */
    RateLimited,
    /** Redirects and anything else. */
    Other,
  }

  fun classify(url: String?): Kind {
    val u = url?.lowercase() ?: return Kind.Other
    return when {
      "nfce_detalhes.aspx" in u || "/consultanfce/paginas/resultadoqrcode" in u -> Kind.Details
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

  /** A portal whose receipt page the app reads: SC's and RJ's (D155). Any other is another state's (D161). */
  fun isRead(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return isSefaz(url) || host == "fazenda.rj.gov.br" || host.endsWith(".fazenda.rj.gov.br")
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
