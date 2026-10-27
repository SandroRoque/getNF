package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Naming a receipt that waits for its page from its photos (D99). */
class PhotoReadTest {
  // An example access key (synthetic CNPJ): issued in October 2026 ("2610").
  private val chave = "42261011222333000181651090000000011123456784"
  private fun photo(vararg texts: String) = PhotoText("p.jpg", 1000, 2000, texts.map { TextLine(it, 0, 0, 10, 10, .8f) })
  private fun day(millis: Long?) = millis?.let { java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.ofHours(-3)).toLocalDate().toString() }

  @Test fun theDatePrintedMostOftenInTheKeysMonth() {
    val read = PhotoRead.issueDate(chave, listOf(
      photo("NFCe 50.639 Serie 105", "16/10/2026 21:02:05", "Data de autorizacao: 16/10 /2026 21:02:08"),
      photo("Oper. 900427145", "16/10/2026 21:02 Caixa 0105", "Validade da oferta 31/12/2026"),
    ))
    assertEquals("2026-10-16", day(read))
  }

  @Test fun aMisreadDateOutsideTheKeysMonthIsDropped() {
    assertNull(PhotoRead.issueDate(chave, listOf(photo("16/10/2028 21:02:05", "46/10/2026"))))
  }

  @Test fun theTitleUsesThePhotosDateThenTheKeysMonthThenTheStoreOnceThePageIsRead() {
    val pending = ReceiptListItem(chave, capturedAt = 0, issuedAt = null, storeName = "SUPERMERCADO EXEMPLO LTDA", paidCents = null, lines = 0, hasPage = false, photos = 2)
    assertEquals("Nota de 10/2026", pending.title)
    assertEquals("Nota de 16/10/2026", pending.copy(photoIssuedAt = PhotoRead.issueDate(chave, listOf(photo("16/10/2026")))).title)
    assertEquals("SUPERMERCADO EXEMPLO LTDA", pending.copy(hasPage = true).title)
  }
}
