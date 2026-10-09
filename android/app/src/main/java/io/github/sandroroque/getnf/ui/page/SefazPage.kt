package io.github.sandroroque.getnf.ui.page

import java.net.URI
import kotlinx.serialization.json.Json

/** Recognises the pages the SEFAZ-SC portal goes through (docs/sefaz-sc.md). */
object SefazPage {
  enum class Kind {
    /** `NFCe_Detalhes.aspx`: the page we want to save. */
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
      "nfce_detalhes.aspx" in u -> Kind.Details
      "securityverify.aspx" in u -> Kind.Challenge
      "maximorequisicoesexcedidas" in u -> Kind.RateLimited
      else -> Kind.Other
    }
  }

  private val NOT_NEEDED_ANYWHERE = listOf(".png", ".gif", ".jpg", ".jpeg", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".eot", ".otf")

  /**
   * Whether to answer a subresource request with an empty response instead of fetching it, to stay under SEFAZ's
   * per-IP request limit (docs/sefaz-sc.md: one visit was ~59 requests to SEFAZ hosts).
   * - Details page: we only need its HTML, so none of its scripts/styles/images are fetched.
   * - Other SEFAZ pages (the challenge in particular): scripts and styles stay, since Turnstile and the Validar button
   *   need them; images and fonts go.
   * - Never touches non-SEFAZ hosts (Cloudflare's Turnstile).
   */
  fun isSefaz(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "sef.sc.gov.br" || host.endsWith(".sef.sc.gov.br")
  }

  fun shouldBlock(pageKind: Kind, url: String, accept: String?): Boolean {
    if (!isSefaz(url)) return false
    val uri = URI(url)
    if (pageKind == Kind.Details) return true
    val path = uri.path?.lowercase().orEmpty()
    return NOT_NEEDED_ANYWHERE.any { path.endsWith(it) } || accept?.startsWith("image/") == true
  }

  /** JS that returns the whole DOM. `evaluateJavascript` hands back its result as a JSON string literal. */
  const val GET_HTML = "'<!DOCTYPE html>\\n' + document.documentElement.outerHTML"

  fun decodeJsResult(result: String): String? =
    runCatching { Json.decodeFromString<String?>(result) }.getOrNull()?.takeIf { it.isNotEmpty() }
}
