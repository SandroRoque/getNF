package io.github.sandroroque.getnf.ui.scan

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-6: a short receipt seen whole in two frames of the same view keeps both
 * photos, because keep() credits a row only to frames that read it within KEEP_MARGIN (0.05) of the best confidence,
 * and OCR's confidence for the same row moves more than that from frame to frame. Synthetic receipt drawn as in
 * PaperFinderTest (40 rows, the whole paper in view).
 */
class IngestKeepTest {
  private val lines = 40
  private val pitch = 30
  private val margin = 45
  private val paperW = 640
  private val paperH = margin * 2 + lines * pitch
  private val side = 120
  private val top = 100

  private fun frame(): Pair<GrayImage, List<OcrQuad>> {
    val image = BufferedImage(paperW + side * 2, paperH + top * 2, BufferedImage.TYPE_BYTE_GRAY)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(40, 40, 40); g.fillRect(0, 0, image.width, image.height)
    g.color = Color(235, 235, 235); g.fillRect(side, top, paperW, paperH)
    g.color = Color(30, 30, 30); g.font = Font(Font.MONOSPACED, Font.BOLD, 24)
    val quads = mutableListOf<OcrQuad>()
    for (i in 0 until lines) {
      val text = "%02d 789%07d PRODUTO %d UN %d,%02d".format(i + 1, i * 7919, i % 4 + 1, i % 30, i * 7 % 100)
      val baseline = top + margin + i * pitch + 22
      g.drawString(text, side + 18, baseline)
      val x1 = side + 18 + g.fontMetrics.stringWidth(text)
      quads += OcrQuad(listOf(DocumentPoint(side + 18.0, baseline - 18.0), DocumentPoint(x1.toDouble(), baseline - 18.0),
        DocumentPoint(x1.toDouble(), baseline + 4.0), DocumentPoint(side + 18.0, baseline + 4.0)), .8, text.length, text)
    }
    g.dispose()
    return GrayImage(image.width, image.height, (image.raster.dataBuffer as DataBufferByte).data) to quads
  }

  @Test fun twoFramesOfTheSameWholeViewKeepOnePhoto() {
    val (image, quads) = frame()
    val session = IngestSession()
    // Frame 1: every row read well (0.8) but four in the middle (0.5: unreadable, a glare). Held, not complete.
    val first = session.offer(IngestFrame("f1", image, quads.mapIndexed { i, q -> q.copy(confidence = if (i in 18..21) .5 else .8) }, 0))
    assertTrue("setup: the first frame is held, not complete: hold=${first.hold} complete=${first.complete} commits=${first.commits} paper=${first.paper != null} view=${first.view} rows=${session.rows.size} hint=${first.hint}", first.hold && !first.complete)
    // Frame 2, same view 2 s later (the glare gone): every row readable, the four at 0.85 and the rest at 0.72
    // (OCR's ordinary frame-to-frame wobble, well above the 0.6 readability bar). It alone reads the whole receipt.
    val second = session.offer(IngestFrame("f2", image, quads.mapIndexed { i, q -> q.copy(confidence = if (i in 18..21) .85 else .72) }, 2000))
    assertTrue("setup: the second frame completes the capture", second.complete)
    assertEquals(
      "expected only the frame that reads the whole receipt to be kept (the other is the same view); committed " +
        "${session.committed}, kept",
      listOf("f2"), session.keep(),
    )
  }
}
