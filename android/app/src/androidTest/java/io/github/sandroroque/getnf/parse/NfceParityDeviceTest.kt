package io.github.sandroroque.getnf.parse

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** NfceParityTest on Android's regex engine (D48). */
@RunWith(AndroidJUnit4::class)
class NfceParityDeviceTest {
  private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

  @Test fun committedFixturesMatchTheirGoldenFiles() {
    for (name in NfceParity.FIXTURES) {
      val d = NfceParity.differences(resource("$name.html"), resource("nfce-golden/$name.json"))
      assertTrue("$name differs from its golden file:\n" + d.joinToString("\n"), d.isEmpty())
    }
  }
}
