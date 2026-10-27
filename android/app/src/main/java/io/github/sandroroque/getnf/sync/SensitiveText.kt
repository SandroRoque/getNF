package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import java.text.Normalizer

/** A box to blank, in the upright photo's pixels. */
data class Blank(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * What a receipt photo shows that shouldn't leave the phone (D98): the card slip (masked card number, the
 * transaction's codes: authorization, NSU, DOC, SiTef, the acquirer), a CPF, and the operator. The phone keeps the
 * full photo; the copy uploaded has these blanked. Read from the photo's OCR; when in doubt, blank more: a slip's
 * lines are blanked as one block from its first to its last line, so a line OCR garbled inside it goes too.
 */
object SensitiveText {
  // OCR reads the masking stars as *, +, x, # or a mix ("559876++e***6759").
  private val MASKED_CARD = Regex("\\d{4,6}\\S{0,3}[*+xX#•]{2,}\\S{0,4}\\d{3,4}")
  private val CPF = Regex("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")
  // The slip's own words, as whole tokens; SEFAZ's "Protocolo de autorização" and "Data de autorização" aren't these.
  private val SLIP = Regex("\\b(CARTAO|CREDITO|DEBITO|DOC|NSU|AUT|AUTORIZ\\w*|SITEF|TEF|CV|REDE|CIELO|STONE|GETNET|PAGSEGURO|SAFRAPAY|MASTERCARD|VISA|ELO|HIPERCARD|AMEX|MAESTRO)\\b")
  // OCR misspells the totals' payment line ("Forna de paganento"): its "DE PAG…" is enough.
  private val NOT_SLIP = Regex("PROTOCOLO|DATA DE AUTORIZ|\\bDE PAG|VALOR PAGO")
  private val OPERATOR = Regex("^OPER(ADOR)?\\b")
  // An item's line (its number, then its code): a product named "REDE …" is not the acquirer.
  private val ITEM = Regex("^\\d{1,3}\\s+\\d{4,}")
  private const val MAX_SLIP_LINES = 14

  fun blanks(text: PhotoText): List<Blank> {
    val lines = text.lines.sortedBy { it.centerY }
    fun plain(l: TextLine) = Normalizer.normalize(l.text.uppercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()
    val result = mutableListOf<Blank>()
    // The card slip: from its first to its last line, the width of everything printed on those lines.
    val slip = lines.indices.filter { i ->
      val t = plain(lines[i])
      !NOT_SLIP.containsMatchIn(t) && !ITEM.containsMatchIn(t) && (MASKED_CARD.containsMatchIn(t) || SLIP.containsMatchIn(t))
    }
    if (slip.isNotEmpty()) {
      val runs = mutableListOf(mutableListOf(slip.first()))
      for (i in slip.drop(1)) {
        // Lines of one slip sit close together; a gap of more than a few lines starts another stretch.
        if (i - runs.last().last() <= 3) runs.last() += i else runs += mutableListOf(i)
      }
      for (run in runs) {
        val span = lines.subList(run.first(), run.last() + 1)
        if (span.size <= MAX_SLIP_LINES) result += box(span) else run.forEach { result += box(listOf(lines[it])) }
      }
    }
    for (l in lines) {
      val t = plain(l)
      if (CPF.containsMatchIn(t) || (t.contains("CPF") && t.count(Char::isDigit) >= 9) || OPERATOR.containsMatchIn(t)) result += box(listOf(l))
    }
    return result
  }

  /** The lines' extent, a little larger: OCR's boxes are tight, and a stroke left showing can still be read. */
  private fun box(lines: List<TextLine>): Blank {
    val pad = (lines.map { it.height }.sorted()[lines.size / 2] * .35).toInt() + 2
    return Blank(lines.minOf { it.left } - pad, lines.minOf { it.top } - pad, lines.maxOf { it.right } + pad, lines.maxOf { it.bottom } + pad)
  }
}
