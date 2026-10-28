package io.github.sandroroque.getnf.parse

/**
 * Blanks who bought from a SEFAZ page before it's saved (D104): the "Consumidor" block's CPF/CNPJ, name and address,
 * and any CPF printed elsewhere on it. The page stays the receipt, shared by everyone in a caderneta; the buyer's
 * identity isn't needed for anything the app reads. The store's CNPJ and the access key are kept.
 */
object BuyerRedaction {
  const val REMOVED = "Consumidor removido pelo getNF"
  private val consumer = Regex("""(<h4[^>]*>\s*Consumidor\s*</h4>\s*<ul[^>]*>)(.*?)(</ul>)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
  private val cpf = Regex("""(?<![\d.])\d{3}\.\d{3}\.\d{3}-\d{2}(?![\d])""")

  fun blank(html: String): String = consumer.replace(html) { m ->
    val inside = m.groupValues[2]
    if (inside.contains("não identificado", ignoreCase = true) || inside.contains(REMOVED)) m.value
    else m.groupValues[1] + "<li><strong>$REMOVED</strong></li>" + m.groupValues[3]
  }.let { cpf.replace(it, "***.***.***-**") }
}
