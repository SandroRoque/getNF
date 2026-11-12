package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.parse.Derive
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Review 2026-11-09, money and analytics. Each test states the expected behaviour and FAILS while the defect exists.
 * Synthetic data only (the repo's sanitized fixture). Run: see ../repro.init.gradle, -PreproDir=money.
 */
class Review1109MoneyTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private val editor = CatalogEditor(db)
  private val chave = "42260111222333000181651090000000011123456784"

  private suspend fun receipt() {
    val store = ReceiptStore(File(tmp.root, "r"), db)
    store.open(chave, "qr")
    store.savePage(chave, "https://x", javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText())
    ReceiptParser(db, File(tmp.root, "r"), log = { _, _ -> }).parse(store.get(chave)!!)
  }

  /** M5 (demonstration, not a defect of arithmetic): chained current-month weights drift when a price returns to where it was. */
  @Test fun aPriceThatComesBackMustBringTheIndexBack() {
    fun line(month: Int, product: String, qty: Long, unitCents: Long) = SpendingLine(
      "c$month$product", 1, java.time.LocalDate.of(2026, month, 10).atStartOfDay().toInstant(java.time.ZoneOffset.ofHours(-3)).toEpochMilli(),
      null, null, qty * unitCents, 0, "prorated", product, null, qty * 10_000, "UN", unitCents * 100, null, null)
    // Three products; A goes on sale in month 2 and the household stocks up, then buys little at the old price.
    val lines = (1..3).flatMap { m -> listOf(
      line(m, "A", if (m == 2) 10 else 1, if (m == 2) 500 else 1000),
      line(m, "B", 1, 1000), line(m, "C", 1, 1000), line(m, "D", 1, 1000)) }
    val months = Ipci.compute(lines)
    println("REVIEW index by month: " + months.joinToString { "${it.period.key}=%.2f".format(it.index) })
    assertEquals("every price in month 3 equals month 1, so the level should be 100 again", 100.0, months.last().index, 0.5)
  }
}
