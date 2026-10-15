package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLegibilityTest {
  /** Ten bands of 0.1; [kinds]: T legible text, I illegible text, U unread content, B blank. */
  private fun run(kinds: String, qr: List<DocumentPoint> = emptyList(), ends: List<Double> = emptyList()): List<CaptureBand> {
    val quality = kinds.mapIndexed { i, k -> RegionQuality(CaptureBand(i / 10.0, (i + 1) / 10.0), 1.0, k != 'B', true) }
    val lines = kinds.mapIndexedNotNull { i, k -> when (k) {
      'T' -> OcrLine(DocumentPoint(.5, i / 10.0 + .05), .7)
      'I' -> OcrLine(DocumentPoint(.5, i / 10.0 + .05), .3)
      else -> null
    } }
    return TextLegibility.legibleBands(quality, lines, qr, ends)
  }

  @Test fun shortUnreadGapBetweenTextIsCredited() =
    assertEquals(listOf(CaptureBand(0.0, .5)), run("TUUTB"))

  @Test fun longUnreadStretchIsNotPrint() =
    // A shadow inside a wrong mask: three unread bands between text (D39).
    assertEquals(listOf(CaptureBand(0.0, .1), CaptureBand(.4, .5)), run("TUUUT"))

  @Test fun oneLegibleBandCreditsNothing() = assertTrue(run("BTBUB").isEmpty())

  @Test fun illegibleTextIsNeverCredited() =
    assertEquals(listOf(CaptureBand(0.0, .2), CaptureBand(.3, .5)), run("TTITT"))

  @Test fun qrAndPaperEndPassWithoutText() {
    val qr = listOf(DocumentPoint(.4, .62), DocumentPoint(.6, .62), DocumentPoint(.6, .78), DocumentPoint(.4, .78))
    assertEquals(listOf(CaptureBand(0.0, 1.0)), run("UTTBBBUUBU", qr, ends = listOf(.02, .95)))
  }
}
