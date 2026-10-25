package io.github.sandroroque.getnf.ui.scan

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.DataBufferByte
import java.awt.image.Kernel
import org.junit.Assert.*
import org.junit.Test

/** The print-line model (D82) on a synthetic receipt: monospaced lines on a fixed pitch, framed like the camera would. */
class PrintLinesTest {
  private val lines = 60
  private val pitch = 30
  private val margin = 45
  private val paper: BufferedImage = BufferedImage(640, margin * 2 + lines * pitch, BufferedImage.TYPE_BYTE_GRAY).apply {
    val g = createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(235, 235, 235); g.fillRect(0, 0, width, height)
    g.color = Color(30, 30, 30); g.font = Font(Font.MONOSPACED, Font.BOLD, 24)
    for (i in 0 until lines) g.drawString("%02d 789%07d PRODUTO %d UN %d,%02d".format(i + 1, i * 7919, i % 4 + 1, i % 30, i * 7 % 100), 18, margin + i * pitch + 22)
    g.dispose()
  }

  /** Paper rows [from, to) scaled by [scale] on a dark table; the paper runs off the frame wherever it is cut. */
  private fun frame(scale: Double, from: Int = 0, to: Int = paper.height, blur: Int = 0): Pair<GrayImage, List<DocumentPoint>> {
    val w = (paper.width * scale).toInt(); val h = ((to - from) * scale).toInt()
    val side = 60; val top = if (from == 0) 50 else 0; val bottom = if (to == paper.height) 50 else 0
    var image = BufferedImage(w + side * 2, h + top + bottom, BufferedImage.TYPE_BYTE_GRAY)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = Color(40, 40, 40); g.fillRect(0, 0, image.width, image.height)
    g.drawImage(paper.getSubimage(0, from, paper.width, to - from), side, top, w, h, null)
    g.dispose()
    if (blur > 0) image = ConvolveOp(Kernel(blur, blur, FloatArray(blur * blur) { 1f / (blur * blur) }), ConvolveOp.EDGE_NO_OP, null).filter(image, null)
    val nx = { x: Int -> x / image.width.toDouble() }; val ny = { y: Int -> y / image.height.toDouble() }
    val polygon = listOf(
      DocumentPoint(nx(side), ny(top)), DocumentPoint(nx(side + w), ny(top)),
      DocumentPoint(nx(side + w), ny(top + h)), DocumentPoint(nx(side), ny(top + h)),
    )
    return GrayImage(image.width, image.height, (image.raster.dataBuffer as DataBufferByte).data) to polygon
  }

  private fun read(f: Pair<GrayImage, List<DocumentPoint>>) = PrintLines.read(f.first, f.second)!!

  @Test fun evenlySpacedLinesGiveThePitchAndTheLengthInLines() {
    val p = read(frame(1.0))
    assertTrue("found ${p.lines.size}", p.lines.size >= lines * .95)
    assertEquals(pitch.toDouble(), p.pitch(p.height / 2.0), 1.5)
    assertNotNull(p.paperTop); assertNotNull(p.paperBottom)
    assertEquals(paper.height / pitch.toDouble(), p.linesBetween(p.paperTop!!, p.paperBottom!!), 1.5)
  }

  @Test fun theSameLinesAtHalfScaleKeepTheirCountInPrintLines() {
    val p = read(frame(.5))
    assertEquals(pitch * .5, p.pitch(p.height / 2.0), 1.0)
    assertEquals(paper.height / pitch.toDouble(), p.linesBetween(p.paperTop!!, p.paperBottom!!), 1.5)
  }

  @Test fun blurredPrintIsNotReadable() {
    val bar = ReadabilityBar()
    assertTrue(read(frame(1.0)).lines.all(bar::readable))
    val blurred = read(frame(1.0, blur = 9))
    assertTrue(blurred.lines.count(bar::readable) < blurred.lines.size / 4)
  }

  @Test fun oneReadableViewOfTheWholeReceiptIsEnough() {
    val map = CoverageMap()
    val whole = read(frame(1.0)); val top = read(frame(1.0, to = paper.height / 2))
    assertTrue(map.measure(whole))
    map.place("top", top); map.place("whole", whole)
    assertTrue(map.cells.all { map.state(it) == LineState.CAPTURED })
    assertEquals(listOf("whole"), map.fewestFrames().map { it.id })
  }

  @Test fun aSmallOverviewPlacesTwoCloseUpsThatTogetherCaptureEverything() {
    // The overview is too small to read but shows both ends; each close-up shows one end and most of the receipt.
    val bar = ReadabilityBar(minHeight = 14.0)
    val map = CoverageMap(bar)
    val overview = read(frame(.4))
    assertTrue(overview.lines.none(bar::readable))
    assertTrue(map.measure(overview))
    map.place("overview", overview)
    assertTrue(map.cells.none { map.state(it) == LineState.CAPTURED })
    assertTrue(map.cells.all { map.state(it) == LineState.SEEN })

    map.place("top", read(frame(1.0, to = paper.height * 6 / 10)))
    val missing = map.cells.filter { map.state(it) != LineState.CAPTURED }
    assertTrue(missing.first() >= lines * 5 / 10 && missing.last() == map.cells.last)
    map.place("bottom", read(frame(1.0, from = paper.height * 5 / 10)))
    assertTrue(map.cells.all { map.state(it) == LineState.CAPTURED })
    assertEquals(listOf("top", "bottom"), map.fewestFrames().map { it.id })
  }
}
