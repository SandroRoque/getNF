package io.github.sandroroque.getnf.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Before the phone parses its own pages (D49): reparses every receipt of a real backup with ReceiptParser and compares
 * the result with the server's lines mirrored into it. Local only, since a backup holds real receipts: set
 * GETNF_EXPORT_DIR to an unzipped backup (`db/getnf.db`, `receipts/`). Works on a copy; the backup isn't changed.
 */
class ExportReparseTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun open(file: File): ReceiptDatabase {
    val context = object : ContextWrapper(null) {
      override fun getDatabasePath(name: String) = File(name)
      override fun getApplicationContext(): Context = this
    }
    return Room.databaseBuilder(context, ReceiptDatabase::class.java, file.path).setDriver(BundledSQLiteDriver())
      .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
  }

  /** What the comparison looks at in a line: everything but the review state. */
  private fun ReceiptLineRow.key() =
    listOf(lineNo, description, code, qtyE4, unit, unitPriceE4, grossCents, storeItemId, productOverrideId, discountCents, discountSource)

  private fun ReceiptParsedRow.key() = listOf(issuerCnpj, issuedAt, grossCents, discountCents, paidCents, parserVersion, parseError)

  @Test fun phoneParseMatchesTheServersLines() = runTest {
    val export = System.getenv("GETNF_EXPORT_DIR")?.let(::File)
    assumeTrue("GETNF_EXPORT_DIR not set", export != null)
    val dbFile = File(tmp.root, "getnf.db")
    File(export, "db/getnf.db").copyTo(dbFile)
    val db = open(dbFile)
    val dao = db.catalog()
    val receipts = ReceiptStore(File(export, "receipts"), db).apply { load() }.receipts.value

    val linesBefore = receipts.associate { it.chave to dao.lines(it.chave).map { l -> l.key() } }
    val parsedBefore = receipts.associate { it.chave to dao.parsed(it.chave)?.key() }
    val storeItems = dao.storeItems().size
    val products = dao.products().size
    val totals = dao.monthlyTotals()

    val parser = ReceiptParser(db, File(export, "receipts"), log = { _, _ -> })
    val problems = mutableListOf<String>()
    var lines = 0
    for (r in receipts) {
      val outcome = parser.parse(r)
      if (outcome !is ReceiptParser.Outcome.Parsed) { problems += "${r.chave}: $outcome"; continue }
      val after = dao.lines(r.chave).map { it.key() }
      lines += after.size
      if (after != linesBefore[r.chave]) {
        val diffs = after.zip(linesBefore.getValue(r.chave)).filter { (a, b) -> a != b }
        problems += "${r.chave}: ${after.size} lines vs ${linesBefore.getValue(r.chave).size} from the server; first difference ${diffs.firstOrNull()}"
      }
      val p = dao.parsed(r.chave)!!.key()
      if (p != parsedBefore[r.chave]) problems += "${r.chave}: header $p vs ${parsedBefore[r.chave]}"
    }
    if (dao.storeItems().size != storeItems) problems += "store items ${dao.storeItems().size} vs $storeItems"
    if (dao.products().size != products) problems += "products ${dao.products().size} vs $products"
    if (dao.monthlyTotals() != totals) problems += "monthly totals per category changed"
    db.close()

    println("reparse: ${receipts.size} receipts, $lines lines, $storeItems store items, ${totals.size} month×category cells")
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }
}
