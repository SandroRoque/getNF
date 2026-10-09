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

  /** "4226 0609 4776 ..." as printed on the receipt. */
  fun pretty(chave: String): String = chave.chunked(4).joinToString(" ")
}
