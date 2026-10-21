package io.github.sandroroque.getnf.parse

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NfceParityTest on Android's regex engine (D48). Real pages can be checked too: push a directory made by
 * `node tools/nfce-golden.mjs <dir>` to the device and pass `-Pandroid.testInstrumentationRunnerArguments.parityDir=<path>`.
 */
@RunWith(AndroidJUnit4::class)
class NfceParityDeviceTest {
  private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

  private fun check(name: String, html: String, golden: String) {
    val d = NfceParity.differences(html, golden)
    assertTrue("$name differs from the JS parser:\n" + d.joinToString("\n"), d.isEmpty())
  }

  @Test fun committedFixturesMatchTheJsParser() {
    for (name in NfceParity.FIXTURES) check(name, resource("$name.html"), resource("nfce-golden/$name.json"))
  }

  @Test fun localPagesMatchTheJsParser() {
    val dir = InstrumentationRegistry.getArguments().getString("parityDir")?.let(::File)
    assumeTrue("parityDir not given", dir != null)
    val pairs = NfceParity.pairs(dir!!)
    assumeTrue("no page with a golden file in $dir", pairs.isNotEmpty())
    for ((html, json) in pairs) check(html.name, html.readText(), json.readText())
    android.util.Log.i("NfceParity", "${pairs.size} local pages match")
  }
}
