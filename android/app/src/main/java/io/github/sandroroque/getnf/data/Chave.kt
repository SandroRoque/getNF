package io.github.sandroroque.getnf.data

/**
 * The 44-digit chave de acesso that identifies an NFC-e (see docs/sefaz-sc.md).
 *
 * QR payloads look like `https://.../consulta?p=<chave>|2|1|1|<hash>`.
 */
object Chave {
  private val CHAVE_IN_P = Regex("""[?&]p=(\d{44})(?:\||$|&)""")
  private val ANY_44 = Regex("""(?<!\d)\d{44}(?!\d)""")

  /** Extracts a valid chave from a QR payload, or null if this isn't an NFC-e QR code. */
  fun fromQr(payload: String): String? {
    val candidate = CHAVE_IN_P.find(payload)?.groupValues?.get(1) ?: ANY_44.find(payload)?.value
    return candidate?.takeIf(::isValid)
  }

  /** 44 digits with a correct mod-11 check digit. */
  fun isValid(chave: String): Boolean =
    chave.length == 44 && chave.all { it in '0'..'9' } && checkDigit(chave.substring(0, 43)) == chave[43] - '0'

  internal fun checkDigit(first43: String): Int {
    var weight = 2
    var sum = 0
    for (i in first43.indices.reversed()) {
      sum += (first43[i] - '0') * weight
      weight = if (weight == 9) 2 else weight + 1
    }
    val dv = 11 - sum % 11
    return if (dv >= 10) 0 else dv
  }

  /** The issuing state's IBGE code, the chave's first two digits ("42" is Santa Catarina). */
  fun uf(chave: String): String = chave.take(2)

  /** States whose receipt page the app reads (D155, D206): SC, RJ and SP. Another state's portal opens, but nothing is read. */
  val READ_UFS = setOf("42", "33", "35")

  fun isRead(chave: String): Boolean = uf(chave) in READ_UFS

  /** The state's name, with its article ("de Santa Catarina", "do Rio de Janeiro", "da Bahia"), or null for an unknown code. */
  fun stateOf(chave: String): String? = STATES[uf(chave)]

  private val STATES = mapOf(
    "11" to "de Rondônia", "12" to "do Acre", "13" to "do Amazonas", "14" to "de Roraima", "15" to "do Pará",
    "16" to "do Amapá", "17" to "do Tocantins", "21" to "do Maranhão", "22" to "do Piauí", "23" to "do Ceará",
    "24" to "do Rio Grande do Norte", "25" to "da Paraíba", "26" to "de Pernambuco", "27" to "de Alagoas",
    "28" to "de Sergipe", "29" to "da Bahia", "31" to "de Minas Gerais", "32" to "do Espírito Santo",
    "33" to "do Rio de Janeiro", "35" to "de São Paulo", "41" to "do Paraná", "42" to "de Santa Catarina",
    "43" to "do Rio Grande do Sul", "50" to "de Mato Grosso do Sul", "51" to "de Mato Grosso", "52" to "de Goiás",
    "53" to "do Distrito Federal",
  )

  /** "4226 0609 4776 ..." as printed on the receipt. */
  fun pretty(chave: String): String = chave.chunked(4).joinToString(" ")
}
