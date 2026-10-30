package io.github.sandroroque.getnf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
  // The JVM's pt-BR currency format puts a no-break space after "R$".
  private fun plain(s: String) = s.replace(' ', ' ')

  @Test fun reais() {
    assertEquals("R$ 30,64", plain(Format.brl(3064)))
    assertEquals("R$ 1.234,56", plain(Format.brl(123456)))
    assertEquals("-R$ 0,50", plain(Format.brl(-50)))
  }

  @Test fun quantities() {
    assertEquals("0,64", Format.e4(6400))
    assertEquals("1", Format.e4(10000))
    assertEquals("1,355", Format.e4(13550))
  }

  @Test fun momentsOnThePhoneInPortuguese() {
    val zone = java.time.ZoneId.of("America/Sao_Paulo")
    // 2025-10-27 07:00 UTC is 04:00 in São Paulo: the backup dialog once said "Oct 27, 2025 4:00:00 AM".
    val at = java.time.Instant.parse("2025-10-27T07:00:00Z").toEpochMilli()
    assertEquals("27 out 2025, 04:00", Format.dateTime(at, zone))
    assertEquals("04:00", Format.time(at, zone))
  }
}
