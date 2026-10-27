package io.github.sandroroque.getnf.ui.scan

import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.parse.NfcePage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The capture benchmark (D89): every recorded session (videos, and the frames phones dump in debug builds) played
 * through [IngestSession] as if live, scored against the receipt's content as SEFAZ has it, and compared with the
 * last accepted run. Local only: GETNF_BENCH = the `test-media/bench` directory, whose `corpus.tsv` lists the
 * sessions (path under test-media, `dev` or `holdout`, frame interval in ms when the session has no `live.tsv`).
 *
 * - Answer key: the receipt's page (`test-media/truth/<chave>/page.html`, saved by the app when it was fetched; no
 *   request is made here). Each item's code and description must be legible (read with confidence 0.6) in a saved
 *   frame. The capture never sees it. A receipt without a page is keyed by the item codes OCR read in its sessions
 *   whose check digit is right ("consensus", weaker).
 * - Scored: completed, seconds from first finding the paper to done, photos, items legible, and a false completion
 *   (done with items not legible).
 * - Holdout sessions are only run with GETNF_BENCH_HOLDOUT=1 and only report totals: they measure, they don't tune.
 * - Each run is written to `runs/`; GETNF_BENCH_ACCEPT=1 makes it the baseline the next runs are compared with.
 *
 * Also writes, per session, `ingest/` beside its frames: the decisions per frame (`log.txt`), the rows assembled
 * (`rows.txt`) and the saved frames with the map (`saved.jpg`). GETNF_BENCH_ONLY limits the run to sessions whose
 * path contains it; GETNF_INGEST_TRACE / GETNF_INGEST_ROWS = frame ids to draw the paper finder's trace / print rows.
 */
class CaptureBenchTest {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  @Serializable
  data class Result(
    val session: String, val split: String, val receipt: String, val key: String, val frames: Int,
    val completed: Boolean, val seconds: Double?, val photos: Int, val items: Int, val legible: Int,
    val falseCompletion: Boolean, val missing: List<String>, val fidelity: String,
  )

  private class Entry(val path: String, val split: String, val stepMs: Long)
  /**
   * An item to find: [text] is its code and description; [numbered] the same after its printed number, which only an
   * item rung up more than once ([repeats] > 1) needs, since its copies differ by it alone (D54 anchors by the code).
   */
  private class Key(val label: String, val text: String, val numbered: String?, val repeats: Int)
  private class Truth(val source: String, val items: List<Key>)

  @Test fun bench() {
    val bench = System.getenv("GETNF_BENCH")?.let(::File)
    assumeTrue("GETNF_BENCH not set", bench != null)
    val media = bench!!.parentFile
    val holdout = System.getenv("GETNF_BENCH_HOLDOUT") != null
    val only = System.getenv("GETNF_BENCH_ONLY")
    val corpus = File(bench, "corpus.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
      val f = line.split('\t'); Entry(f[0], f[1], f.getOrNull(2)?.toLongOrNull() ?: 200)
    }
    val results = corpus.filter { (it.split == "holdout") == holdout && (only == null || only in it.path) }.mapNotNull { e ->
      play(File(media, e.path), e, media)
    }
    report(results, bench, holdout, only == null)
  }

  private fun report(results: List<Result>, bench: File, holdout: Boolean, whole: Boolean) {
    if (holdout) {
      // Totals only: looking into a held-out session turns it into one the capture is tuned on.
      println("HOLDOUT: %d sessions, %d completed, %d false completions, items legible %d of %d, mean %.1f s, %.1f photos".format(
        results.size, results.count { it.completed }, results.count { it.falseCompletion }, results.sumOf { it.legible }, results.sumOf { it.items },
        results.mapNotNull { it.seconds }.average(), results.map { it.photos }.average()))
      return
    }
    println("%-28s %-8s %5s %6s %6s %9s  %s".format("session", "receipt", "done", "secs", "photos", "legible", "fidelity"))
    for (r in results) println("%-28s %-8s %5s %6s %6d %9s  %s%s".format(r.session.takeLast(28), r.receipt.takeLast(6), if (r.completed) "yes" else "no",
      r.seconds?.let { "%.1f".format(it) } ?: "-", r.photos, "${r.legible}/${r.items}${if (r.key == "consensus") "*" else ""}", r.fidelity,
      (if (r.falseCompletion) "  FALSE COMPLETION" else "") + (if (r.missing.isNotEmpty()) "  missing: " + r.missing.take(3).joinToString(" | ") else "")))
    println("(* answer key from the sessions' own reads: no page for that receipt)")
    if (!whole) return // a partial run isn't compared or kept
    val runs = File(bench, "runs").apply { mkdirs() }
    val label = System.getenv("GETNF_BENCH_LABEL") ?: "run"
    val file = File(runs, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-$label.json")
    file.writeText(json.encodeToString(results))
    val baselineFile = File(bench, "baseline.json")
    val baseline = baselineFile.takeIf { it.exists() }?.let { json.decodeFromString<List<Result>>(it.readText()) }.orEmpty().associateBy { it.session }
    var regressions = 0
    for (r in results) {
      val b = baseline[r.session] ?: run { println("NEW       ${r.session}"); null } ?: continue
      val notes = mutableListOf<Pair<Boolean, String>>() // regression?, note
      if (b.completed != r.completed) notes += !r.completed to "completed ${b.completed} → ${r.completed}"
      if (b.legible != r.legible) notes += (r.legible < b.legible) to "legible ${b.legible} → ${r.legible}"
      if (b.falseCompletion != r.falseCompletion) notes += r.falseCompletion to "false completion ${b.falseCompletion} → ${r.falseCompletion}"
      if (b.seconds != null && r.seconds != null && kotlin.math.abs(r.seconds - b.seconds) >= 1.0)
        notes += (r.seconds > b.seconds * 1.25 && r.seconds - b.seconds >= 2) to "seconds %.1f → %.1f".format(b.seconds, r.seconds)
      if (b.photos != r.photos) notes += (r.photos >= b.photos + 2) to "photos ${b.photos} → ${r.photos}"
      regressions += notes.count { it.first }
      for ((bad, note) in notes) println("%-9s %s: %s".format(if (bad) "WORSE" else "changed", r.session, note))
    }
    println("run written to ${file.name}; ${if (baseline.isEmpty()) "no baseline yet" else "$regressions regression(s) against the baseline"}")
    if (System.getenv("GETNF_BENCH_ACCEPT") != null) { file.copyTo(baselineFile, overwrite = true); println("accepted as the baseline") }
  }

  private fun play(dir: File, entry: Entry, media: File): Result? {
    val frames = dir.listFiles { f -> f.name.matches(Regex("f\\d+\\.jpg")) }.orEmpty().sortedBy { it.name }
    if (frames.isEmpty()) return null
    val ocr = frames.associate { f -> f.nameWithoutExtension to File(f.path + ".ocr.json").takeIf { it.exists() }?.let { json.decodeFromString<PhotoText>(it.readText()) } }
    // The live run's timing and decisions, when the phone logged them.
    val live = File(dir, "live.tsv").takeIf { it.exists() }?.readLines()?.map { it.split('\t') }?.associateBy { it[0] }
    val start = live?.values?.minOfOrNull { it[1].toLong() }
    val receipt = receiptOf(ocr.values.filterNotNull())
    val truth = receipt?.let { truth(it, media) }
    val out = File(dir, "ingest").apply { mkdirs() }
    val session = IngestSession()
    val log = StringBuilder()
    val held = mutableMapOf<String, File>()
    val saved = mutableListOf<String>()
    val rowTexts = mutableMapOf<String, List<String>>()
    var engagedAt: Long? = null
    var completeAt: Long? = null
    var completeFrame: String? = null
    var divergence: String? = null
    val endsDiffer = mutableListOf<String>()
    for ((i, file) in frames.withIndex()) {
      val id = file.nameWithoutExtension
      val at = live?.get(id)?.let { it[1].toLong() - start!! } ?: (i * entry.stepMs)
      val image = ImageIO.read(file)
      val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).apply { createGraphics().drawImage(image, 0, 0, null) }
      val lines = ocr[id]?.lines.orEmpty().mapNotNull { l ->
        l.corners?.chunked(2)?.map { (x, y) -> DocumentPoint(x.toDouble(), y.toDouble()) }?.let { OcrQuad(it, l.confidence.toDouble(), l.text.length, l.text) }
      }
      val grayImage = GrayImage(gray.width, gray.height, (gray.raster.dataBuffer as DataBufferByte).data, chroma(image))
      val closedBefore = session.topClosed to session.bottomClosed
      val step = session.offer(IngestFrame(id, grayImage, lines, at))
      // The clock starts when a frame is first placed on the receipt: before that, the camera is still being aimed.
      if (step.view != null && engagedAt == null) engagedAt = at
      if ((session.topClosed to session.bottomClosed) != closedBefore) log.appendLine("        ends $closedBefore → ${session.topClosed to session.bottomClosed} at $id, ${session.rows.size} rows")
      step.paper?.let { p -> rowTexts[id] = RowAssembly.rows(p, grayImage.width, grayImage.height).filter { it.confidence >= .6 }.map { it.text } }
      if (System.getenv("GETNF_INGEST_ROWS")?.split(',')?.contains(id) == true) step.paper?.let { p ->
        println("rows of $id (top ${p.topSeen} bottom ${p.bottomSeen}):")
        RowAssembly.rows(p, grayImage.width, grayImage.height).forEach { println("  %.2f h%.0f %s".format(it.confidence, it.height, it.text)) }
      }
      if (System.getenv("GETNF_INGEST_TRACE")?.split(',')?.contains(id) == true) trace(image, grayImage, lines, step, File(out, "trace-$id.jpg"))
      val why = if (step.paper == null) PaperTrace().also { PaperFinder.find(grayImage, lines, it) }.stopped else null
      saved += step.commits
      if (step.hold) held[id] = file
      live?.get(id)?.let { l ->
        val liveCommits = l[4].split(',').filter { it.isNotEmpty() }
        if (divergence == null && (liveCommits != step.commits || l[5].toBoolean() != step.complete)) divergence = id
        // Where the replay found the paper's ends differently (it finds them on the dumped JPEG, the phone on the camera's frame).
        val ends = step.paper?.let { (if (it.topSeen) "T" else "") + (if (it.bottomSeen) "B" else "") + "-" } ?: ""
        if (l.size > 6 && l[6] != ends) endsDiffer += "$id ${l[6].ifEmpty { "none" }}→${ends.ifEmpty { "none" }}"
      }
      log.appendLine("%5.1fs %s %-16s %s%s%s%s".format(at / 1000.0, id, step.hint, step.view?.let { "rows %3d–%3d of %3d".format(it.first, it.last, session.rows.size) } ?: "not placed",
        if (step.hold) " HOLD" else "", step.commits.joinToString("") { " COMMIT $it" },
        step.paper?.let { p -> (if (p.topSeen) " ⊤" else "") + (if (p.bottomSeen) " ⊥" else "") } ?: "") + (why?.let { " ($it)" } ?: "") +
        session.required().filter { !session.isCaptured(it) }.let { m -> if (m.isEmpty()) "" else " need " + m.take(4).joinToString { "${session.rows.indexOf(it)}" } + if (m.size > 4) "…(${m.size})" else "" } +
        session.rows.withIndex().filter { it.value.printAfter }.let { m -> if (m.isEmpty()) "" else " unread after " + m.joinToString { "${it.index}" } })
      if (step.complete) { completeAt = at; completeFrame = id; break }
    }
    if (completeAt == null) session.commitHeld()?.let { saved += it }
    // Done: only the photos the app keeps (the fewest that read every row) count, and are scored.
    else session.keep().let { kept -> saved.retainAll(kept.toSet()) }
    val liveDone = live?.values?.firstOrNull { it[5].toBoolean() }?.get(0)
    val fidelity = when {
      live == null -> "no live log"
      divergence == null && liveDone == completeFrame -> "matches live"
      else -> "diverges at ${divergence ?: completeFrame}"
    } + if (endsDiffer.isEmpty()) "" else " (paper ends found differently: ${endsDiffer.take(3).joinToString()})"

    // Scored against the answer key: an item is legible when a saved frame read its text (rows and OCR's lines, read
    // with confidence 0.6; a line OCR split into boxes is whole in its row).
    // OCR's letter-for-digit slips ("S133" for 5133), undone on both sides as D54's reader does.
    fun plain(t: String) = t.map { mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')[it] ?: it }.joinToString("")
    val saves = saved.map { id -> (rowTexts[id].orEmpty() + ocr[id]?.lines.orEmpty().filter { it.confidence >= .6 }.map { RowAssembly.normalize(it.text) }).map(::plain) }
    fun found(text: String, inSaved: String) = containedEdits(text, inSaved) <= maxOf(1, text.length / 8)
    val missing = truth?.items?.filter { key ->
      val text = plain(key.text)
      val legible = if (key.repeats <= 1) saves.any { frame -> frame.any { found(text, it) } } else {
        // Rung up more than once: its own number with it ("01" or "1"), or as many readings as copies in one photo.
        val numbered = plain(key.numbered!!).let { if (it.startsWith("0")) listOf(it, it.drop(1)) else listOf(it) }
        saves.any { frame -> frame.any { t -> numbered.any { n -> found(n, t) && n.take(6) in t } } || frame.count { found(text, it) } >= key.repeats }
      }
      !legible
    }?.map { it.label }.orEmpty()
    val items = truth?.items?.size ?: 0
    if (System.getenv("GETNF_BENCH_KEY") != null) truth?.items?.forEach { k -> println("key ${k.label}: ${k.text} x${k.repeats} ${if (k.label in missing) "MISSING" else ""}") }
    val result = Result(dir.relativeTo(media).path, entry.split, receipt ?: "?", truth?.source ?: "none", frames.size, completeAt != null,
      completeAt?.let { (it - (engagedAt ?: 0)) / 1000.0 }, saved.size, items, items - missing.size,
      completeAt != null && missing.isNotEmpty(), missing, fidelity)
    if (entry.split == "holdout") return result // nothing written beside a held-out session
    File(out, "log.txt").writeText("$result\n$log")
    File(out, "rows.txt").writeText(session.rows.withIndex().joinToString("\n") { (i, r) ->
      "%3d %s %.2f c%d s%d i%d%s%s %s".format(i, if (session.isCaptured(r)) "■" else "□", r.confidence, r.confirmed, r.skipped, r.illegible,
        if (r.doubtful) " doubtful" else "", if (r.gapAfter >= .5) " gap%.1f".format(r.gapAfter) else "", r.text)
    })
    drawSaved(saved.mapNotNull { id -> held[id]?.let { id to it } }, session, File(out, "saved.jpg"))
    return result
  }

  /** The receipt a session shows: the access key read most often with a correct check digit. */
  private fun receiptOf(ocr: List<PhotoText>): String? = ocr.flatMap { it.lines }.map { it.text.filter(Char::isDigit) }
    .filter { it.length == 44 && checkDigit(it.take(43), (2..9).toList()) == it.last().digitToInt() }
    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

  private fun truth(chave: String, media: File): Truth {
    val page = File(media, "truth/$chave/page.html")
    if (page.exists()) {
      val parsed = runCatching { NfcePage.parse(page.readText()) }.getOrNull()
      if (parsed != null && parsed.items.isNotEmpty()) {
        val codes = parsed.items.groupingBy { it.code }.eachCount()
        return Truth("page", parsed.items.map { item ->
          val text = RowAssembly.normalize((item.code ?: "") + (item.description ?: "")).take(24)
          Key("${item.line}:${item.description?.take(20)}", text, "%02d".format(item.line) + text, codes[item.code] ?: 1)
        })
      }
    }
    // No page: the item codes (EAN-13, check digit right) read in at least three frames of this receipt's sessions.
    val codes = media.resolve("bench").let { bench -> File(bench, "corpus.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") } }
      .map { File(media, it.split('\t')[0]) }
      .flatMap { d -> d.listFiles { f -> f.name.endsWith(".ocr.json") }.orEmpty().map { json.decodeFromString<PhotoText>(it.readText()) } }
      .filter { o -> o.lines.any { it.text.filter(Char::isDigit) == chave } }
      .flatMap { o -> o.lines.mapNotNull { l -> Regex("^\\d{1,3}\\s*(\\d{13})\\b").find(l.text.trim())?.groupValues?.get(1) } }
      .filter { c -> checkDigit(c.take(12), listOf(1, 3)) == c.last().digitToInt() }
      .groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
    return Truth("consensus", codes.map { Key(it, it, null, 1) })
  }

  /** Mod-10 (EAN, weights alternating from the right) or mod-11 (access key, weights 2–9) check digit. */
  private fun checkDigit(digits: String, weights: List<Int>): Int {
    val sum = digits.reversed().withIndex().sumOf { (i, c) -> c.digitToInt() * weights[(if (weights.size == 2) i + 1 else i) % weights.size] }
    return if (weights.size == 2) (10 - sum % 10) % 10 else (11 - sum % 11).let { if (it >= 10) 0 else it }
  }

  /** The fewest edits that make [part] a substring of [whole]. */
  private fun containedEdits(part: String, whole: String): Int {
    var previous = IntArray(whole.length + 1)
    for (i in 1..part.length) {
      val current = IntArray(whole.length + 1); current[0] = i
      for (j in 1..whole.length) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (part[i - 1] == whole[j - 1]) 0 else 1)
      previous = current
    }
    return previous.min()
  }

  /** The paper finder's trace on the frame: OCR lines (red), the text block (blue), edge points (green), the outline. */
  private fun trace(image: BufferedImage, gray: GrayImage, lines: List<OcrQuad>, step: IngestStep, file: File) {
    val trace = PaperTrace().also { PaperFinder.find(gray, lines, it) }
    val g = image.createGraphics()
    g.stroke = BasicStroke(3f)
    g.color = Color(255, 60, 60); for (l in trace.lines) g.drawPolygon(l.corners.map { it.x.toInt() }.toIntArray(), l.corners.map { it.y.toInt() }.toIntArray(), 4)
    g.color = Color(60, 140, 255); trace.block?.let { b -> g.drawPolygon(b.map { it.x.toInt() }.toIntArray(), b.map { it.y.toInt() }.toIntArray(), 4) }
    g.color = Color(40, 220, 90); for (e in trace.edges) g.fillOval(e.x.toInt() - 6, e.y.toInt() - 6, 12, 12)
    step.paper?.let { p -> g.color = Color.MAGENTA; g.drawPolygon(p.corners.map { (it.x * image.width).toInt() }.toIntArray(), p.corners.map { (it.y * image.height).toInt() }.toIntArray(), 4)
      g.font = Font(Font.SANS_SERIF, Font.BOLD, 40); g.drawString("top ${p.topSeen} bottom ${p.bottomSeen}", 20, 50) }
    g.dispose()
    ImageIO.write(image, "jpg", file)
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
