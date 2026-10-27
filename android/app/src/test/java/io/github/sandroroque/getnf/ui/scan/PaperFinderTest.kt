package io.github.sandroroque.getnf.ui.scan

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import org.junit.Assert.*
import org.junit.Test

/** Finding the paper from its print (D82) on a synthetic receipt, with the drawn lines standing in for OCR. */
class PaperFinderTest {
  private val lines = 40
  private val pitch = 30
  private val margin = 45
  private val paperW = 640
  private val paperH = margin * 2 + lines * pitch
  private val side = 120
  private val top = 100

  /** A frame with the receipt on a dark table, and each printed line's box as OCR would report it. */
  private fun frame(from: Int = 0, to: Int = paperH): Pair<BufferedImage, List<OcrQuad>> {
    val h = (to - from) + (if (from == 0) top else 0) + (if (to == paperH) top else 0)
    val image = BufferedImage(paperW + side * 2, h, BufferedImage.TYPE_BYTE_GRAY)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(40, 40, 40); g.fillRect(0, 0, image.width, image.height)
    val y0 = (if (from == 0) top else 0) - from
    g.color = Color(235, 235, 235); g.fillRect(side, maxOf(0, y0), paperW, minOf(paperH, to) - maxOf(0, from))
    g.color = Color(30, 30, 30); g.font = Font(Font.MONOSPACED, Font.BOLD, 24)
    val quads = mutableListOf<OcrQuad>()
    for (i in 0 until lines) {
      val text = "%02d 789%07d PRODUTO %d UN %d,%02d".format(i + 1, i * 7919, i % 4 + 1, i % 30, i * 7 % 100)
      val baseline = y0 + margin + i * pitch + 22
      if (baseline - 20 < maxOf(0, y0) || baseline + 4 > y0 + minOf(paperH, to)) continue
      g.drawString(text, side + 18, baseline)
      val x1 = side + 18 + g.fontMetrics.stringWidth(text)
      quads += OcrQuad(listOf(DocumentPoint(side + 18.0, baseline - 18.0), DocumentPoint(x1.toDouble(), baseline - 18.0),
        DocumentPoint(x1.toDouble(), baseline + 4.0), DocumentPoint(side + 18.0, baseline + 4.0)), .8, text.length, text)
    }
    g.dispose()
    return image to quads
  }

  private fun gray(image: BufferedImage) = GrayImage(image.width, image.height, (image.raster.dataBuffer as DataBufferByte).data)

  /** The same frame turned a quarter clockwise: the receipt lies sideways, its top to the right. */
  private fun turned(f: Pair<BufferedImage, List<OcrQuad>>): Pair<BufferedImage, List<OcrQuad>> {
    val (image, quads) = f
    val out = BufferedImage(image.height, image.width, BufferedImage.TYPE_BYTE_GRAY)
    for (y in 0 until image.height) for (x in 0 until image.width) out.raster.setSample(image.height - 1 - y, x, 0, image.raster.getSample(x, y, 0))
    return out to quads.map { q -> q.copy(corners = q.corners.map { DocumentPoint(image.height - 1 - it.y, it.x) }) }
  }

  private fun assertNear(expected: DocumentPoint, actual: DocumentPoint, width: Int, height: Int, tolerance: Double = 8.0) {
    assertEquals("x of $actual", expected.x, actual.x * width, tolerance)
    assertEquals("y of $actual", expected.y, actual.y * height, tolerance)
  }

  @Test fun theWholeReceiptIsFoundWithBothEnds() {
    val (image, quads) = frame()
    val paper = PaperFinder.find(gray(image), quads)!!
    assertTrue(paper.topSeen && paper.bottomSeen)
    val corners = listOf(DocumentPoint(side.toDouble(), top.toDouble()), DocumentPoint((side + paperW).toDouble(), top.toDouble()),
      DocumentPoint((side + paperW).toDouble(), (top + paperH).toDouble()), DocumentPoint(side.toDouble(), (top + paperH).toDouble()))
    corners.zip(paper.corners).forEach { (e, a) -> assertNear(e, a, image.width, image.height) }
  }

  @Test fun aSidewaysReceiptKeepsItsTopWhereThePrintStarts() {
    val (image, quads) = turned(frame())
    val paper = PaperFinder.find(gray(image), quads)!!
    assertTrue(paper.topSeen && paper.bottomSeen)
    // Turned clockwise, the receipt's top-left corner is at the frame's top right.
    assertNear(DocumentPoint(image.width - 1.0 - top, side.toDouble()), paper.corners[0], image.width, image.height)
    assertNear(DocumentPoint(image.width - 1.0 - top - paperH, (side + paperW).toDouble()), paper.corners[2], image.width, image.height)
  }

  @Test fun anEndOutsideTheFrameIsACutNotTheEnd() {
    val (image, quads) = frame(to = paperH / 2)
    val paper = PaperFinder.find(gray(image), quads)!!
    assertTrue(paper.topSeen)
    assertFalse(paper.bottomSeen)
  }

  @Test fun aReadableViewOfTheWholeReceiptCompletesTheCaptureAtOnce() {
    val (image, quads) = frame()
    val session = IngestSession()
    val step = session.offer(IngestFrame("whole", gray(image), quads, 0))
    assertTrue(step.complete)
    assertEquals(listOf("whole"), step.commits)
    assertEquals(lines, session.rows.size)
  }

  @Test fun theFinishedCaptureStillFindsThePaperForItsCode() {
    // The QR code is only taken from inside the paper, and may be read after the rows are all captured.
    val (image, quads) = frame()
    val session = IngestSession()
    assertTrue(session.offer(IngestFrame("whole", gray(image), quads, 0)).complete)
    val after = session.offer(IngestFrame("again", gray(image), quads, 400))
    assertTrue(after.complete)
    assertNotNull(after.paper)
    assertTrue(after.commits.isEmpty())
  }

  @Test fun aReceiptReadUnsurelyIsNeverComplete() {
    val (image, quads) = frame()
    val session = IngestSession()
    val step = session.offer(IngestFrame("unsure", gray(image), quads.map { it.copy(confidence = .5) }, 0))
    assertFalse(step.complete)
    assertTrue(step.commits.isEmpty())
  }

  @Test fun twoHalvesJoinByTheirTextAndCompleteTogether() {
    val session = IngestSession()
    val top = frame(to = paperH * 6 / 10); val bottom = frame(from = paperH * 4 / 10)
    val first = session.offer(IngestFrame("top", gray(top.first), top.second, 0))
    assertFalse(first.complete)
    val second = session.offer(IngestFrame("bottom", gray(bottom.first), bottom.second, 2000))
    assertTrue(second.complete)
    assertEquals(listOf("top", "bottom"), session.committed)
    assertEquals(lines, session.rows.size)
  }
}
