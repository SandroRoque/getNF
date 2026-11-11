package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lines the OCR read correctly that the uploaded copy must not show (the 2026-11-09 audit, T-31, D181), not T-7/T-8's
 * partial OCR. Synthetic lines, laid out as SensitiveTextTest does; each failure names the line left visible.
 */
class SensitiveTextReadLinesTest {
  private fun receipt(vararg texts: String) = PhotoText("p.jpg", 1000, 2000, texts.mapIndexed { i, t -> TextLine(t, 100, 100 + i * 30, 100 + t.length * 12, 125 + i * 30, .9f) })
  private fun blanked(p: PhotoText) = SensitiveText.blanks(p).flatMap { b ->
    p.lines.filter { l -> listOf(l.left to l.top, l.right to l.top, l.right to l.bottom, l.left to l.bottom).all { (x, y) -> b.contains(x.toDouble(), y.toDouble()) } }.map { it.text }
  }.toSet()
  private fun mustBlank(lines: List<String>, private_: List<String>) {
    val gone = blanked(receipt(*lines.toTypedArray()))
    val left = private_.filter { it !in gone }
    assertTrue("expected these lines blanked in the uploaded copy, left visible: $left", left.isEmpty())
  }
  private val items = listOf("01 7891000100103 MACA NAC KG 0,875 KG 6,49 5,68", "Valor a pagar R$ 5,68", "Forma de pagamento Dinheiro 5,68")

  /** An identified buyer's line wraps: the DANFE prints "CONSUMIDOR - CPF … - name - address" over two or three rows. */
  @Test fun theBuyersNameAndAddressOnTheRowsAfterTheCpfAreBlanked() =
    mustBlank(items + listOf("CONSUMIDOR - CPF 123.456.789-09 - FULANA DE", "TAL - RUA DAS FLORES, 10, CENTRO,", "FLORIANOPOLIS - SC", "Tributos Totais Incidentes R$ 1,00"),
      listOf("CONSUMIDOR - CPF 123.456.789-09 - FULANA DE", "TAL - RUA DAS FLORES, 10, CENTRO,", "FLORIANOPOLIS - SC"))

  /** The label and the number as two OCR lines (a wide gap between them), the number printed without dots. */
  @Test fun aCpfWhoseDigitsAreTheirOwnOcrLineIsBlanked() =
    mustBlank(items + listOf("CPF/CNPJ consumidor:", "12345678909", "Tributos Totais Incidentes R$ 1,00"), listOf("12345678909"))

  /** Pix slips and some PDVs print the payer: a half-masked CPF (6 real digits) and the name. No card word on them. */
  @Test fun aPixSlipsPayerIsBlanked() =
    mustBlank(items + listOf("COMPROVANTE PIX", "PAGADOR: FULANA DE TAL", "CPF: ***.456.789-**", "BANCO: 260 NU PAGAMENTOS", "Tributos Totais Incidentes R$ 1,00"),
      listOf("PAGADOR: FULANA DE TAL", "CPF: ***.456.789-**"))

  /** A customer named by the store's own programme (pharmacies, loyalty clubs). */
  @Test fun aNamedCustomerAndTheirPhoneAreBlanked() =
    mustBlank(items + listOf("CLIENTE: FULANA DE TAL", "TELEFONE: (48) 99999-0000", "Tributos Totais Incidentes R$ 1,00"),
      listOf("CLIENTE: FULANA DE TAL", "TELEFONE: (48) 99999-0000"))

  /** A masked card number printed in groups, or with no leading digits, standing first or last in the slip. */
  @Test fun aMaskedCardNumberInGroupsIsBlanked() =
    mustBlank(items + listOf("5598 76** **** 6759", "Tributos Totais Incidentes R$ 1,00", "Volte sempre", "Obrigado", "Ate logo", "************6759"),
      listOf("5598 76** **** 6759", "************6759"))

  /** What stays: an unidentified consumer, the authorization block's "Via Consumidor", and the items after them. */
  @Test fun anUnidentifiedConsumerAndTheReceiptAroundItStay() {
    val lines = listOf("CONSUMIDOR NAO IDENTIFICADO", "01 7891000100103 MACA NAC KG 0,875 KG 6,49 5,68",
      "NFC-e n 000001 Serie 109 15/01/2026 10:00:00 - Via Consumidor", "Valor a pagar R$ 5,68", "Forma de pagamento Dinheiro 5,68")
    assertTrue(blanked(receipt(*lines.toTypedArray())).isEmpty())
    // An identified one's block ends at the next section: the receipt's number and the taxes stay.
    val gone = blanked(receipt(*(items + listOf("CONSUMIDOR CPF: 123.456.789-09 FULANA DE TAL", "RUA DAS FLORES, 10",
      "NFC-e n 000001 Serie 109 15/01/2026 10:00:00", "Tributos Totais Incidentes R$ 1,00")).toTypedArray()))
    assertTrue("RUA DAS FLORES, 10" in gone)
    for (kept in items + listOf("NFC-e n 000001 Serie 109 15/01/2026 10:00:00", "Tributos Totais Incidentes R$ 1,00"))
      assertTrue("$kept should stay", kept !in gone)
  }
}
