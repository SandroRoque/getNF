package io.github.sandroroque.getnf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaveTest {
  // Synthetic: fake CNPJ 11.222.333/0001-81, made-up number and cNF, valid check digit. Never use a real chave here:
  // anyone can look a real one up on the SEFAZ portal.
  private val chave = "42260611222333000181651090000012341123456789"

  @Test
  fun validChave() {
    assertTrue(Chave.isValid(chave))
  }

  @Test
  fun wrongCheckDigit() {
    assertFalse(Chave.isValid(chave.dropLast(1) + "1"))
  }

  @Test
  fun wrongLengthOrDigits() {
    assertFalse(Chave.isValid(chave.drop(1)))
    assertFalse(Chave.isValid(chave.dropLast(1) + "x"))
  }

  @Test
  fun checkDigitRemainderZeroOrOneGivesZero() {
    // 43 zeros: sum 0 → 11 - 0 = 11 → 0.
    assertEquals(0, Chave.checkDigit("0".repeat(43)))
  }

  @Test
  fun fromScQrCode() {
    val qr = "https://sat.sef.sc.gov.br/nfce/consulta?p=$chave|2|1|1|0123456789abcdef0123456789abcdef01234567"
    assertEquals(chave, Chave.fromQr(qr))
  }

  @Test
  fun fromQrWithoutPParam() {
    assertEquals(chave, Chave.fromQr("http://example/nfce?chNFe=$chave&x=1"))
  }

  @Test
  fun notAnNfceQr() {
    assertNull(Chave.fromQr("https://example.com/menu"))
    assertNull(Chave.fromQr("https://sat.sef.sc.gov.br/nfce/consulta?p=${chave.dropLast(1)}1|2|1|1|ab"))
  }

  @Test
  fun pretty() {
    assertEquals("4226 0611 2223 3300 0181 6510 9000 0012 3411 2345 6789", Chave.pretty(chave))
  }
}
