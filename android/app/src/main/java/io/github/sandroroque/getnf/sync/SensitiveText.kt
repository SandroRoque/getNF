package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.sin

/** A region to blank: a quadrilateral in the upright photo's pixels, following the print (which may lie at any angle). */
data class Blank(val corners: List<Pair<Double, Double>>) {
  /** Whether (x, y) lies inside. */
  fun contains(x: Double, y: Double): Boolean {
    var inside = false
    for (i in corners.indices) {
      val (ax, ay) = corners[i]; val (bx, by) = corners[(i + 1) % corners.size]
      if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
    }
    return inside
  }
}

/**
 * What a receipt photo shows that shouldn't leave the phone (D98): the card slip (masked card number, the
 * transaction's codes: authorization, NSU, DOC, SiTef, the acquirer), a CPF, and the operator. The phone keeps the
 * full photo; the copy uploaded has these blanked. Read from the photo's OCR; when in doubt, blank more: a slip's
 * lines are blanked as one block from its first to its last line, so a line OCR garbled inside it goes too. Lines are
 * ordered down the print and blocks follow it, whatever the receipt's angle in the photo (D99).
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

  /** A line in the print's axes: across it (u) and down the receipt (v). */
  private class Placed(val line: TextLine, val u0: Double, val u1: Double, val v0: Double, val v1: Double) {
    val v get() = (v0 + v1) / 2
    val height get() = v1 - v0
  }

  fun blanks(text: PhotoText): List<Blank> {
    if (text.lines.isEmpty()) return emptyList()
    // The print's direction: the lines' own, from their corners (left to right as read), the median of them.
    val angle = text.printAngle() ?: 0.0
    val ax = cos(angle); val ay = sin(angle); val dx = -ay; val dy = ax
    fun points(l: TextLine) = l.corners?.takeIf { it.size == 8 }?.chunked(2)?.map { it[0].toDouble() to it[1].toDouble() }
      ?: listOf(l.left.toDouble() to l.top.toDouble(), l.right.toDouble() to l.top.toDouble(), l.right.toDouble() to l.bottom.toDouble(), l.left.toDouble() to l.bottom.toDouble())
    val lines = text.lines.map { l ->
      val p = points(l)
      val us = p.map { (x, y) -> x * ax + y * ay }; val vs = p.map { (x, y) -> x * dx + y * dy }
      Placed(l, us.min(), us.max(), vs.min(), vs.max())
    }.sortedBy { it.v }
    /** The lines' extent in the print's axes, a little larger: a stroke left showing can still be read. */
    fun block(span: List<Placed>): Blank {
      val pad = span.map { it.height }.sorted()[span.size / 2] * .35 + 2
      val u0 = span.minOf { it.u0 } - pad; val u1 = span.maxOf { it.u1 } + pad
      val v0 = span.minOf { it.v0 } - pad; val v1 = span.maxOf { it.v1 } + pad
      fun xy(u: Double, v: Double) = (u * ax + v * dx) to (u * ay + v * dy)
      return Blank(listOf(xy(u0, v0), xy(u1, v0), xy(u1, v1), xy(u0, v1)))
    }
    fun plain(l: TextLine) = Normalizer.normalize(l.text.uppercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()

    val result = mutableListOf<Blank>()
    // The card slip: from its first to its last line down the receipt, the width of everything printed on those lines.
    val slip = lines.indices.filter { i ->
      val t = plain(lines[i].line)
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
        if (span.size <= MAX_SLIP_LINES) result += block(span) else run.forEach { result += block(listOf(lines[it])) }
      }
    }
    for (p in lines) {
      val t = plain(p.line)
      if (CPF.containsMatchIn(t) || (t.contains("CPF") && t.count(Char::isDigit) >= 9) || OPERATOR.containsMatchIn(t)) result += block(listOf(p))
    }
    return result
  }
}
