package io.github.sandroroque.getnf.parse

/**
 * Blanks who bought from a SEFAZ page before it's saved (D104): the "Consumidor" block's CPF/CNPJ, name and address,
 * and any CPF printed elsewhere on it. The page stays the receipt, shared by everyone in a caderneta; the buyer's
 * identity isn't needed for anything the app reads. The store's CNPJ and the access key are kept.
 *
 * The block is found by its heading's text, not by one HTML shape (T-30): the server sends `<h4>Consumidor</h4><ul>`,
 * but what's saved is the WebView's DOM, where the portal's jQuery Mobile (always on RJ, sometimes on SC) has made it
 * `<h4 …><a …>Consumidor<span …> click to collapse contents</span></a></h4><div …><ul>`. The list after the heading,
 * before the next heading, is the block.
 */
object BuyerRedaction {
  const val REMOVED = "Consumidor removido pelo getNF"
  private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
  private val heading = Regex("""<h4\b[^>]*>(.*?)</h4\s*>""", options)
  private val list = Regex("""(<ul\b[^>]*>)(.*?)(</ul\s*>)""", options)
  /** jQuery Mobile's hidden label for screen readers, inside the heading's link. */
  private val status = Regex("""<span\b[^>]*ui-collapsible-heading-status[^>]*>.*?</span\s*>""", options)
  private val tag = Regex("<[^>]*>")
  private val cpf = Regex("""(?<![\d.])\d{3}\.\d{3}\.\d{3}-\d{2}(?![\d])""")

  fun blank(html: String): String {
    val out = StringBuilder(html.length)
    var copied = 0
    for (h in heading.findAll(html)) {
      if (h.range.first < copied || label(h.groupValues[1]) != "consumidor") continue
      val next = heading.find(html, h.range.last + 1)?.range?.first ?: html.length
      val l = list.find(html, h.range.last + 1)?.takeIf { it.range.last < next } ?: continue
      val inside = l.groupValues[2]
      if (inside.contains("não identificado", ignoreCase = true) || inside.contains(REMOVED)) continue
      out.append(html, copied, l.groups[1]!!.range.last + 1).append("<li><strong>$REMOVED</strong></li>")
      copied = l.groups[3]!!.range.first
    }
    out.append(html, copied, html.length)
    return cpf.replace(out, "***.***.***-**")
  }

  private fun label(inner: String) = tag.replace(status.replace(inner, ""), " ").replace(Regex("\\s+"), " ").trim().lowercase()
}
