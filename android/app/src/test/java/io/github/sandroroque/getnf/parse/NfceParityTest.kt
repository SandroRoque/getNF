package io.github.sandroroque.getnf.parse

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin parser and derivation give what the server's JS gives (D48). Set GETNF_PARITY_DIR to also check a local
 * directory of real pages, each with a golden file from `node tools/nfce-golden.mjs <dir>`.
 */
class NfceParityTest {
  private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

  private fun check(name: String, html: String, golden: String) {
    val d = NfceParity.differences(html, golden)
    assertTrue("$name differs from the JS parser:\n" + d.joinToString("\n"), d.isEmpty())
  }

  @Test fun committedFixturesMatchTheJsParser() {
    for (name in NfceParity.FIXTURES) check(name, resource("$name.html"), resource("nfce-golden/$name.json"))
  }

  @Test fun localPagesMatchTheJsParser() {
    val dir = System.getenv("GETNF_PARITY_DIR")?.let(::File)
    assumeTrue("GETNF_PARITY_DIR not set", dir != null)
    val pairs = NfceParity.pairs(dir!!)
    assumeTrue("no page with a golden file in $dir", pairs.isNotEmpty())
    for ((html, json) in pairs) check(html.name, html.readText(), json.readText())
    println("parity: ${pairs.size} local pages match")
  }
}
