package io.github.sandroroque.getnf.ui.scan

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays a capture session pulled from the phone through the print-line model (D82) and draws what it concludes.
 * Local only: GETNF_CAPTURE_REPLAY = a directory with the session's `capture-*.jpg` and a copy of the app's
 * `getnf.db` (for each photo's segment metadata). Writes `replay/` there: one overlay per frame and `map.png`.
 */
class CaptureReplayTest {
  @Serializable private data class Segment(val photo: String, val polygon: List<List<Double>>, val capturedAt: Long)

  @Test fun replay() {
    val dir = System.getenv("GETNF_CAPTURE_REPLAY")?.let(::File)
    assumeTrue("GETNF_CAPTURE_REPLAY not set", dir != null)
    dir!!
    val out = File(dir, "replay").apply { mkdirs() }
    val json = Json { ignoreUnknownKeys = true }
    val db = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(File(dir, "getnf.db").path)
    val photos = dir.listFiles { f -> f.name.startsWith("capture-") && f.name.endsWith(".jpg") }.orEmpty().map { it.name }.toSet()
    val segments = db.prepare("SELECT segment FROM receipt_photo WHERE segment IS NOT NULL").use { st ->
      buildList { while (st.step()) add(json.decodeFromString<Segment>(st.getText(0))) }
    }.filter { it.photo in photos }.sortedBy { it.capturedAt }
    db.close()
    assumeTrue("no photos with metadata in $dir", segments.isNotEmpty())
    val t0 = segments.first().capturedAt
    fun name(s: Segment) = s.photo.substringAfterLast('-').removeSuffix(".jpg")

    val bar = ReadabilityBar()
    val prints = segments.map { s ->
      val image = ImageIO.read(File(dir, s.photo))
      val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).apply { createGraphics().drawImage(image, 0, 0, null) }
      val pixels = (gray.raster.dataBuffer as DataBufferByte).data
      val polygon = s.polygon.map { DocumentPoint(it[0], it[1]) }
      val print = PrintLines.read(GrayImage(gray.width, gray.height, pixels), polygon)
      overlay(image, polygon, print, bar, File(out, "frame-${name(s)}.jpg"))
      s to print
    }

    val map = CoverageMap(bar)
    prints.forEach { (_, p) -> p?.let(map::measure) }
    val report = StringBuilder()
    report.appendLine("length ${map.length?.let { "%.1f".format(it) } ?: "unknown"} print lines, width ${map.widthInLines?.let { "%.1f".format(it) } ?: "?"} lines")
    for ((s, p) in prints) {
      val placed = p?.let { map.place(name(s), it) }
      report.appendLine(
        "frame %3s %5.1fs ".format(name(s), (s.capturedAt - t0) / 1000.0) + when {
          p == null -> lean(s).let { if (it > 12) "not read: paper leans %.0f°, needs rectifying".format(it) else "not read: too little regular print" }
          else -> "%2d lines, pitch %5.1f→%5.1f px, height %4.1f px, blur %.2f, contrast %3.0f, ends %s%s, ".format(
            p.lines.size, p.pitch(p.visibleTop), p.pitch(p.visibleBottom),
            p.lines.map { it.height }.sorted().getOrElse(p.lines.size / 2) { 0.0 },
            p.lines.map { it.edgeWidth / it.height }.sorted().getOrElse(p.lines.size / 2) { 0.0 },
            p.lines.map { it.contrast }.sorted().getOrElse(p.lines.size / 2) { 0.0 },
            if (p.paperTop != null) "top" else "-", if (p.paperBottom != null) "+bottom" else "",
          ) + (placed?.let { f -> "lines %5.1f–%5.1f, readable %d of %d".format(f.from, f.to, f.cells.count { it.value }, f.cells.size) } ?: "not placed: neither end in view")
        },
      )
    }
    for ((s, p) in prints) p?.lines?.filterNot { bar.readable(it) }?.forEach { l ->
      report.appendLine("  frame %s unreadable at %.0f: height %.0f, blur %.2f, contrast %.0f, glare %.3f".format(name(s), l.center, l.height, l.edgeWidth / l.height, l.contrast, l.glare))
    }
    val cells = map.cells
    val states = cells.map { map.state(it) }
    val fewest = map.fewestFrames()
    report.appendLine("cells ${cells.count()}: captured ${states.count { it == LineState.CAPTURED }}, seen ${states.count { it == LineState.SEEN }}, unseen ${states.count { it == LineState.UNSEEN }}")
    report.appendLine("fewest frames: ${fewest.joinToString { it.id }}")
    print(report)
    File(out, "report.txt").writeText(report.toString())
    drawMap(map, prints.map { (s, _) -> name(s) to (s.capturedAt - t0) / 1000.0 }, fewest.map { it.id }.toSet(), File(out, "map.png"))
  }

  /** How far the paper's sides lean from the frame's vertical, in degrees (the larger side). */
  private fun lean(s: Segment): Double {
    val image = ImageIO.createImageInputStream(File(File(System.getenv("GETNF_CAPTURE_REPLAY")), s.photo)).use { input ->
      ImageIO.getImageReaders(input).next().run { setInput(input); getWidth(0) to getHeight(0) }
    }
    val p = s.polygon.map { DocumentPoint(it[0] * image.first, it[1] * image.second) }
    return listOf(p[0] to p[3], p[1] to p[2]).maxOf { (a, b) -> Math.toDegrees(kotlin.math.abs(kotlin.math.atan2(b.x - a.x, b.y - a.y))) }
  }

  private fun overlay(image: BufferedImage, polygon: List<DocumentPoint>, print: FramePrint?, bar: ReadabilityBar, file: File) {
    val scale = 720.0 / maxOf(image.width, image.height)
    val w = (image.width * scale).toInt(); val h = (image.height * scale).toInt()
    val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = small.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(image, 0, 0, w, h, null)
    g.color = Color(0, 160, 255); g.stroke = BasicStroke(2f)
    val xs = polygon.map { (it.x * w).toInt() }.toIntArray(); val ys = polygon.map { (it.y * h).toInt() }.toIntArray()
    g.drawPolygon(xs, ys, 4)
    if (print != null) {
      val left = xs.min(); val right = xs.max()
      for (line in print.lines) {
        g.color = if (bar.readable(line)) Color(0, 170, 80, 110) else Color(230, 60, 40, 140)
        g.fillRect(left, (line.top * scale).toInt(), right - left, maxOf(1, (line.height * scale).toInt()))
      }
      g.color = Color(150, 60, 200, 90)
      for (block in print.blocks) g.fillRect(left, (block.top * scale).toInt(), right - left, (block.height * scale).toInt())
      g.stroke = BasicStroke(3f)
      print.paperTop?.let { g.color = Color.RED; g.drawLine(left, (it * scale).toInt(), right, (it * scale).toInt()) }
      print.paperBottom?.let { g.color = Color.BLUE; g.drawLine(left, (it * scale).toInt(), right, (it * scale).toInt()) }
    }
    g.dispose()
    ImageIO.write(small, "jpg", file)
  }

  /** The receipt to scale, one row per print line, beside each frame's lane in capture order. */
  private fun drawMap(map: CoverageMap, frames: List<Pair<String, Double>>, chosen: Set<String>, file: File) {
    val cell = 9
    val total = map.cells.last + 1
    val receiptWidth = ((map.widthInLines ?: 20.0) * cell).toInt()
    val lane = 22; val top = 70; val left = 30
    val lanesX = left + receiptWidth + 60
    val width = lanesX + frames.size * lane + 30
    val height = top + total * cell + 60
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color.WHITE; g.fillRect(0, 0, width, height)
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    g.color = Color(40, 40, 40)
    g.drawString("Receipt to scale (${total} print lines)", left, 22)
    g.drawString("Frames in capture order (chosen outlined)", lanesX, 22)
    val captured = Color(46, 160, 100); val seen = Color(235, 170, 40); val unseen = Color(225, 225, 225)
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    listOf("captured" to captured, "needs a closer look" to seen, "unseen" to unseen).forEachIndexed { i, (label, c) ->
      g.color = c; g.fillRect(left + i * 150, 34, 12, 12); g.color = Color(60, 60, 60); g.drawString(label, left + i * 150 + 16, 45)
    }
    val text = map.frames.flatMap { it.text }.toSet()
    for (k in 0 until total) {
      val y = top + k * cell
      g.color = when (map.state(k)) { LineState.CAPTURED -> captured; LineState.SEEN -> seen; LineState.UNSEEN -> unseen }
      g.fillRect(left, y, receiptWidth, cell)
      if (k in text) { g.color = Color(255, 255, 255, 150); g.fillRect(left + 6, y + 3, receiptWidth - 12, cell - 6) }
    }
    g.color = Color(90, 90, 90); g.drawRect(left, top, receiptWidth, total * cell)
    frames.forEachIndexed { i, (id, seconds) ->
      val x = lanesX + i * lane
      val f = map.frames.firstOrNull { it.id == id }
      if (f != null) for ((k, ok) in f.cells) {
        g.color = if (ok) captured else seen
        g.fillRect(x + 3, top + k * cell, lane - 6, cell)
      }
      if (id in chosen && f != null) {
        g.color = Color(20, 20, 20); g.stroke = BasicStroke(2f)
        g.drawRect(x + 1, top + ceil(f.from).toInt() * cell - 2, lane - 2, (f.cells.size) * cell + 4)
      }
      g.color = Color(60, 60, 60)
      g.drawString(id, x + 3, top - 6)
      g.drawString("%.0f".format(seconds), x + 1, top + total * cell + 16)
    }
    g.drawString("seconds", lanesX - 50, top + total * cell + 16)
    g.dispose()
    ImageIO.write(image, "png", file)
  }
}
