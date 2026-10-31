package io.github.sandroroque.getnf.discounts

import io.github.sandroroque.getnf.parse.NfcePage
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The photo reader (D106, D147) against the saved SEFAZ pages, local only, like the capture benchmark (D89):
 * GETNF_BENCH = `test-media/bench`. For each receipt session of the corpus whose receipt has a page in
 * `test-media/truth/<chave>/`, it reads the photos the capture kept (the frames its last run committed, from the
 * session's `ingest/log.txt`, so run the capture benchmark first) and compares item by item with the page.
 *
 * Scored per session: items on the page, items read exactly (code, quantity, unit, unit price, line total), the
 * description (same letters and digits, ignoring OCR's usual confusions), and whether the reading checked itself as
 * trusted. The one number that must stay at zero: a trusted reading that is wrong. GETNF_READER_ONLY limits it to
 * sessions whose path contains it, and prints each item.
 */
class PhotoReaderBenchTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test fun bench() {
    val bench = System.getenv("GETNF_BENCH")?.let(::File)
    assumeTrue("GETNF_BENCH not set", bench != null)
    val media = bench!!.parentFile
    val only = System.getenv("GETNF_READER_ONLY")
    val sessions = File(bench, "corpus.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
      .filter { it[1] == "dev" && (only == null || only in it[0]) }.map { File(media, it[0]) }
    println("%-28s %-6s %6s %6s %6s %8s  %s".format("session", "receipt", "items", "exact", "desc", "trusted", "problems / wrong"))
    var trustedWrong = 0; var trustedRight = 0; var exactReceipts = 0; var n = 0; var itemsAll = 0; var exactAll = 0; var unseen = 0
    for (dir in sessions) {
      val log = File(dir, "ingest/log.txt").takeIf { it.exists() } ?: continue
      val committed = Regex("COMMIT (f\\d+)").findAll(log.readText()).map { it.groupValues[1] }.toSet()
      val photos = committed.mapNotNull { id -> File(dir, "$id.jpg.ocr.json").takeIf { it.exists() }?.let { json.decodeFromString<PhotoText>(it.readText()) } }
      if (photos.isEmpty()) continue
      val chave = dir.listFiles { f -> f.name.endsWith(".ocr.json") }.orEmpty().asSequence()
        .flatMap { json.decodeFromString<PhotoText>(it.readText()).lines.asSequence() }.map { it.text.filter(Char::isDigit) }
        .filter { it.length == 44 && File(media, "truth/$it/page.html").exists() }.firstOrNull() ?: continue
      val page = NfcePage.parse(File(media, "truth/$chave/page.html").readText())
      if (System.getenv("GETNF_READER_ROWS") != null) for (ph in photos) { println("--- ${ph.photo}"); PhotoReceiptReader.rows(ph).forEach { println("    $it") } }
      val reading = PhotoReceiptReader.read(photos)
      val byNumber = reading.items.associateBy { it.number }
      val wrong = mutableListOf<String>()
      var exact = 0; var desc = 0
      for (p in page.items) {
        val r = byNumber[p.line]
        if (r == null) {
          wrong += "${p.line} not read"
          // Not in the photos at all (the capture's coverage), as opposed to there but not read.
          if (photos.none { ph -> ph.lines.any { p.code != null && p.code!!.length >= 4 && p.code!! in it.text.filter(Char::isDigit) } }) unseen++
          continue
        }
        val same = r.code == p.code && r.qtyE4 == p.qtyE4 && r.unit.equals(p.unit, true) && r.unitPriceE4 == p.unitPriceE4 &&
          (r.totalCents == p.totalCents || r.totalCents + r.discountCents == p.totalCents)
        if (same) exact++ else wrong += "${p.line}: read ${r.code} ${r.qtyE4} ${r.unit} ${r.unitPriceE4} ${r.totalCents}, page ${p.code} ${p.qtyE4} ${p.unit} ${p.unitPriceE4} ${p.totalCents}"
        if (fold(r.description) == fold(p.description.orEmpty())) desc++
        if (only != null) println("  ${if (same) "ok " else "BAD"} ${p.line} ${p.description} | ${r.description}")
      }
      val extra = reading.items.count { r -> page.items.none { it.line == r.number } }
      if (extra > 0) wrong += "$extra extra"
      val allExact = wrong.isEmpty()
      if (allExact) exactReceipts++
      if (reading.trusted) { if (allExact) trustedRight++ else trustedWrong++ }
      n++; itemsAll += page.items.size; exactAll += exact
      println("%-28s %-6s %6d %6d %6d %8s  %s".format(dir.name.takeLast(28), chave.takeLast(6), page.items.size, exact, desc,
        if (reading.trusted) (if (allExact) "yes" else "WRONG") else "no",
        (reading.problems.take(2) + wrong.take(2)).joinToString(" | ").take(160)))
    }
    println("$n sessions: $exactReceipts read exactly; items exact $exactAll of $itemsAll ($unseen of the rest not in the kept photos at all); " +
      "trusted and right $trustedRight, trusted and WRONG $trustedWrong")
  }

  /** Letters and digits, with OCR's usual confusions folded. */
  private fun fold(s: String) = s.uppercase().replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('6', 'G').replace('8', 'B')
    .filter { it.isLetterOrDigit() }
}
