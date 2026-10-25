package io.github.sandroroque.getnf.ui.scan

import io.github.sandroroque.getnf.discounts.PhotoText
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Plays recorded videos of receipts through [IngestSession] as if live (D82): frames in order, five a second, each with
 * its on-device OCR. Local only: GETNF_INGEST_VIDEOS = a directory with one folder per video of `f0001.jpg`… frames
 * (`ffmpeg -vf fps=5`, scaled to the analysis size) and their `.ocr.json` (PhotoOcrDumpTest on the emulator). Writes
 * `ingest/` in each folder: the frames saved, the map and a timeline, and prints a summary per video.
 */
class VideoIngestTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test fun videos() {
    val root = System.getenv("GETNF_INGEST_VIDEOS")?.let(::File)
    assumeTrue("GETNF_INGEST_VIDEOS not set", root != null)
    val only = System.getenv("GETNF_INGEST_ONLY")
    for (dir in root!!.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
      if (only != null && only !in dir.name) continue
      play(dir)
    }
  }

  private fun play(dir: File) {
    val frames = dir.listFiles { f -> f.name.matches(Regex("f\\d+\\.jpg")) }.orEmpty().sortedBy { it.name }
    if (frames.isEmpty()) return
    val out = File(dir, "ingest").apply { mkdirs() }
    val session = IngestSession()
    val log = StringBuilder()
    val held = mutableMapOf<String, File>()
    val saved = mutableListOf<Pair<String, Long>>()
    val hints = mutableListOf<Pair<Long, IngestHint>>()
    val texts = mutableMapOf<String, List<String>>() // frame id → its confidently read lines, normalized
    var completeAt: Long? = null
    // The whole video's content, read before playing (the session may finish early).
    for (file in frames) File(file.path + ".ocr.json").takeIf { it.exists() }?.let { json.decodeFromString<PhotoText>(it.readText()) }?.let { o ->
      texts[file.nameWithoutExtension] = o.lines.filter { it.confidence >= .7 }.map { RowAssembly.normalize(it.text) }.filter { it.length >= 10 }
    }
    for ((i, file) in frames.withIndex()) {
      val at = i * 200L
      val id = file.nameWithoutExtension
      val image = ImageIO.read(file)
      val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).apply { createGraphics().drawImage(image, 0, 0, null) }
      val ocr = File(file.path + ".ocr.json").takeIf { it.exists() }?.let { json.decodeFromString<PhotoText>(it.readText()) }
      val lines = ocr?.lines.orEmpty().mapNotNull { l ->
        l.corners?.chunked(2)?.map { (x, y) -> DocumentPoint(x.toDouble(), y.toDouble()) }?.let { OcrQuad(it, l.confidence.toDouble(), l.text.length, l.text) }
      }
      val grayImage = GrayImage(gray.width, gray.height, (gray.raster.dataBuffer as DataBufferByte).data, chroma(image))
      val closedBefore = session.topClosed to session.bottomClosed
      val step = session.offer(IngestFrame(id, grayImage, lines, at))
      if ((session.topClosed to session.bottomClosed) != closedBefore) log.appendLine("        ends ${closedBefore} → ${session.topClosed to session.bottomClosed} at $id, ${session.rows.size} rows")
      val why = if (step.paper == null) PaperTrace().also { PaperFinder.find(grayImage, lines, it) }.stopped else null
      if (System.getenv("GETNF_INGEST_ENDS") != null) ends(image, step.paper, File(out, "ends").apply { mkdirs() }, id)
      if (System.getenv("GETNF_INGEST_TRACE")?.split(',')?.contains(id) == true) {
        val trace = PaperTrace().also { PaperFinder.find(grayImage, lines, it) }
        val g = image.createGraphics()
        g.stroke = BasicStroke(3f)
        g.color = Color(255, 60, 60); for (l in trace.lines) g.drawPolygon(l.corners.map { it.x.toInt() }.toIntArray(), l.corners.map { it.y.toInt() }.toIntArray(), 4)
        g.color = Color(60, 140, 255); trace.block?.let { b -> g.drawPolygon(b.map { it.x.toInt() }.toIntArray(), b.map { it.y.toInt() }.toIntArray(), 4) }
        g.color = Color(40, 220, 90); for (e in trace.edges) g.fillOval(e.x.toInt() - 6, e.y.toInt() - 6, 12, 12)
        step.paper?.let { p -> g.color = Color.MAGENTA; g.drawPolygon(p.corners.map { (it.x * image.width).toInt() }.toIntArray(), p.corners.map { (it.y * image.height).toInt() }.toIntArray(), 4)
          g.font = Font(Font.SANS_SERIF, Font.BOLD, 40); g.drawString("top ${p.topSeen} bottom ${p.bottomSeen}", 20, 50) }
        g.dispose()
        ImageIO.write(image, "jpg", File(out, "trace-$id.jpg"))
      }
      step.commits.forEach { saved += it to at }
      if (step.hold) held[id] = file
      hints += at to step.hint
      log.appendLine("%5.1fs %s %-16s %s%s%s%s".format(at / 1000.0, id, step.hint, step.view?.let { "rows %3d–%3d of %3d".format(it.first, it.last, session.rows.size) } ?: "not placed",
        if (step.hold) " HOLD" else "", step.commits.joinToString("") { " COMMIT $it" },
        step.paper?.let { p -> (if (p.topSeen) " ⊤" else "") + (if (p.bottomSeen) " ⊥" else "") } ?: "") + (why?.let { " ($it)" } ?: ""))
      if (step.complete) { completeAt = at; break }
    }
    session.commitHeld()?.let { saved += it to (frames.size * 200L) }
    // The content check, independent of the geometry: lines read confidently in at least two frames are the receipt as
    // the video shows it; each must be read (confidence 0.6) in a saved frame.
    val content = texts.values.flatten().groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
      .fold(mutableListOf<String>()) { kept, t -> if (kept.none { similarText(it, t) }) kept += t; kept }
    val savedTexts = saved.flatMap { (id, _) -> held[id]?.let { f ->
      File(f.path + ".ocr.json").takeIf { it.exists() }?.let { json.decodeFromString<PhotoText>(it.readText()) }?.lines
        ?.filter { it.confidence >= .6 }?.map { RowAssembly.normalize(it.text) }
    }.orEmpty() }
    // OCR splits and joins the same print differently from frame to frame: a line is covered when a saved line matches
    // it, contains it, or makes up most of it.
    val missing = content.filter { t -> savedTexts.none { saved -> similarText(saved, t) || commonRun(saved, t) >= minOf(t.length, maxOf(10, (t.length * .75).toInt())) } }
    val summary = "%s: %d frames over %.1fs; %s; saved %d (%s); %d rows (ends %s), captured %d; content %d of %d lines%s".format(
      dir.name, frames.size, frames.size / 5.0, completeAt?.let { "complete at %.1fs".format(it / 1000.0) } ?: "NOT complete",
      saved.size, saved.joinToString { it.first }, session.rows.size,
      (if (session.topClosed) "⊤" else "-") + (if (session.bottomClosed) "⊥" else "-"), session.rows.count(session::isCaptured),
      content.size - missing.size, content.size,
      if (missing.isEmpty()) "" else ", missing: " + missing.take(6).joinToString(" | "))
    println(summary)
    println("  print unread after: " + session.rows.withIndex().filter { it.value.printAfter }.joinToString(" | ") { (i, r) -> "$i:${r.text.take(20)} → ${session.rows.getOrNull(i + 1)?.text?.take(20)}" })
    println("  not captured: " + session.rows.withIndex().filter { !session.isCaptured(it.value) }.joinToString(" | ") { (i, r) -> "$i:${r.text.take(24)}@%.2f".format(r.confidence) })
    File(out, "log.txt").writeText(summary + "\n" + log)
    drawSaved(saved.mapNotNull { (id, _) -> held[id]?.let { id to it } } , session, File(out, "saved.jpg"))
  }

  /** A small copy of the frame with the outline found: ends seen drawn thick (top red, bottom blue), cut ends thin. */
  private fun ends(image: BufferedImage, paper: PaperOutline?, dir: File, id: String) {
    val w = 216; val h = image.height * w / image.width
    val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = small.createGraphics()
    g.drawImage(image, 0, 0, w, h, null)
    if (paper != null) {
      val c = paper.corners.map { (it.x * w).toInt() to (it.y * h).toInt() }
      g.stroke = BasicStroke(if (paper.topSeen) 4f else 1f); g.color = Color.RED; g.drawLine(c[0].first, c[0].second, c[1].first, c[1].second)
      g.stroke = BasicStroke(if (paper.bottomSeen) 4f else 1f); g.color = Color.BLUE; g.drawLine(c[3].first, c[3].second, c[2].first, c[2].second)
      g.stroke = BasicStroke(1f); g.color = Color.GREEN; g.drawLine(c[0].first, c[0].second, c[3].first, c[3].second); g.drawLine(c[1].first, c[1].second, c[2].first, c[2].second)
    }
    g.color = Color.BLACK; g.fillRect(0, 0, 44, 16); g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 12); g.drawString(id, 2, 12)
    g.dispose()
    ImageIO.write(small, "jpg", File(dir, "$id.jpg"))
  }

  /** The longest run of characters [a] and [b] share. */
  private fun commonRun(a: String, b: String): Int {
    var best = 0
    val previous = IntArray(b.length + 1); val current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      for (j in 1..b.length) { current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else 0; if (current[j] > best) best = current[j] }
      current.copyInto(previous); current.fill(0)
    }
    return best
  }

  private fun similarText(a: String, b: String): Boolean {
    if (a == b) return true
    if (kotlin.math.abs(a.length - b.length) > 2) return false
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      val current = IntArray(b.length + 1); current[0] = i
      for (j in 1..b.length) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
      previous = current
    }
    return previous[b.length] <= maxOf(1, minOf(a.length, b.length) / 8)
  }

  /** The saved frames side by side, and the map: the receipt's rows (blank gaps to scale), green where captured. */
  private fun drawSaved(saved: List<Pair<String, File>>, session: IngestSession, file: File) {
    val thumb = 300
    val cell = 6
    val heights = session.rows.map { 1 + it.gapAfter }
    val mapH = (heights.sum() * cell).toInt()
    val width = 40 + 160 + 30 + saved.size * (thumb + 10)
    val height = maxOf(mapH + 80, thumb * 16 / 9 + 80)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color.WHITE; g.fillRect(0, 0, width, height)
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    val top = 50
    var y = top.toDouble()
    for ((i, row) in session.rows.withIndex()) {
      g.color = if (session.isCaptured(row)) Color(46, 160, 100) else Color(225, 120, 100)
      g.fillRect(40, y.toInt(), 160, cell)
      y += heights[i] * cell
    }
    g.color = Color(80, 80, 80); g.drawRect(40, top, 160, mapH)
    g.drawString("${session.rows.size} rows " + (if (session.topClosed) "top ✓ " else "top ? ") + (if (session.bottomClosed) "bottom ✓" else "bottom ?"), 40, top - 12)
    var x = 40 + 160 + 30
    for ((id, f) in saved) {
      val img = ImageIO.read(f)
      val h = thumb * img.height / img.width
      g.drawImage(img, x, top, thumb, h, null)
      g.color = Color(40, 40, 40); g.drawString(id, x, top - 12)
      x += thumb + 10
    }
    g.dispose()
    ImageIO.write(image, "jpg", file)
  }
}
