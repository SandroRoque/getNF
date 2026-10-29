package io.github.sandroroque.getnf.parse

import org.junit.Assert.assertTrue
import org.junit.Test

/** The parser and derivation still give what the golden files pin (D48, D106). */
class NfceParityTest {
  private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

  @Test fun committedFixturesMatchTheirGoldenFiles() {
    for (name in NfceParity.FIXTURES) {
      val d = NfceParity.differences(resource("$name.html"), resource("nfce-golden/$name.json"))
      assertTrue("$name differs from its golden file:\n" + d.joinToString("\n"), d.isEmpty())
    }
  }
}
