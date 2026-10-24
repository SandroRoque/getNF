package io.github.sandroroque.getnf.discounts

import java.io.File
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads discounts from real photos' recorded OCR (PhotoOcrDumpTest) and prints what was found per receipt (D54).
 * Local only: GETNF_OCR_EXPORT = an unzipped backup whose receipt directories also hold the `*.ocr.json` dumps.
 */
class PhotoDiscountsEvalTest {
  @Test fun realReceipts() {
    val export = System.getenv("GETNF_OCR_EXPORT")?.let(::File)
    assumeTrue("GETNF_OCR_EXPORT not set", export != null)
    val json = Json { ignoreUnknownKeys = true }
    val db = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(File(export, "db/getnf.db").path)
    fun <T> query(sql: String, row: (androidx.sqlite.SQLiteStatement) -> T): List<T> =
      db.prepare(sql).use { st -> buildList { while (st.step()) add(row(st)) } }
    val receipts = query("SELECT chave, discountCents FROM receipt_parsed ORDER BY chave") { it.getText(0) to it.getLong(1) }
    for ((chave, total) in receipts) {
      val items = query("SELECT lineNo, code, description, grossCents FROM receipt_line WHERE receipt = '$chave' ORDER BY lineNo") {
        PhotoDiscounts.Item(it.getLong(0).toInt(), it.getText(1), it.getText(2), it.getLong(3))
      }
      val photos = File(export, "receipts/$chave").listFiles { f -> f.name.endsWith(".ocr.json") }.orEmpty().sorted()
        .map { json.decodeFromString<PhotoText>(it.readText()) }
      val r = PhotoDiscounts.read(photos, items, total)
      println("${chave.takeLast(6)}: ${photos.size} photos, total $total, ${r.verdict}, found ${r.foundCents} " +
        r.discounts.joinToString(prefix = "[", postfix = "]") { "${it.lineNo}=${it.cents}×${it.evidence.size}" } +
        " unassigned ${r.unassigned}" + if (r.problems.isEmpty()) "" else " problems ${r.problems}")
    }
    db.close()
  }
}
