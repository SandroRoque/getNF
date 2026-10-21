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
}
