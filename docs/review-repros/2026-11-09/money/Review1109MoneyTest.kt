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

  /** M1: T-14's fix caps a typed discount from above only. Once every line is typed, less than the receipt's discount stays. */
  @Test fun handTypedDiscountsOnEveryLineMustStillAddUpToTheReceipts() = runTest {
    receipt()
    val header = dao.parsed(chave)!!
    assertEquals("fixture: the lines start out adding up to what was paid", header.paidCents, dao.lines(chave).sumOf { it.grossCents - it.discountCents + it.surchargeCents })
    // The person clears the estimated discount line by line ("this item had no discount"): each edit is accepted.
    for (l in dao.lines(chave)) editor.setDiscount(chave, l.lineNo, 0)
    val lines = dao.lines(chave)
    assertEquals("the lines' discounts must still add up to the receipt's discount", header.discountCents, lines.sumOf { it.discountCents })
    assertEquals("spending for the receipt must stay what was paid", header.paidCents, lines.sumOf { it.grossCents - it.discountCents + it.surchargeCents })
  }

  /** M2: the rest of a discount is prorated without a cap, so a line can get more discount than it cost. */
  @Test fun aProratedShareMustNotExceedItsLine() {
    // A R$ 10,00 discount on a receipt of a R$ 100,00 item and a R$ 2,00 item; the person types "0" on the first
    // (CatalogEditor.setDiscount accepts 0 for any line), so the whole R$ 10,00 lands on the R$ 2,00 line.
    val gross = listOf(10_000L, 200L)
    val shares = Derive.lineDiscounts(1_000, gross, known = mapOf(0 to 0L))
    for ((i, s) in shares.withIndex()) assertTrue("line $i: discount ${s.cents} > its price ${gross[i]} (net ${gross[i] - s.cents})", s.cents <= gross[i])
  }

  /** M3: two valid moves on two phones make a parent cycle (nothing on the server refuses it); the reports must survive it. */
  @Test fun aCategoryCycleMustNotHangTheReports() {
    val parents = mapOf<String, String?>("a" to "b", "b" to "a")
    val line = SpendingLine("c", 1, 1_760_000_000_000, null, null, 100, 0, "prorated", "p", "a", 10_000, "UN", 10_000, null, null)
    var outcome = "still running after 1.5 s (generateSequence never ends)"
    val t = Thread {
      outcome = try { Spending(listOf(line), parents).slices(Period.month(line.issuedAt!!), null); "finished" }
        catch (e: Throwable) { "died: ${e.javaClass.simpleName}" }
    }.apply { isDaemon = true; start() }
    t.join(1500)
    @Suppress("DEPRECATION") if (t.isAlive) runCatching { t.stop() }
    assertEquals("slices() over a cyclic tree", "finished", outcome)
  }

  /** M4: a product left in a category that another phone merged away counts as "sem categoria" but asks for nothing. */
  @Test fun aProductInADeletedCategoryMustAskForACategory() = runTest {
    receipt()
    dao.insertCategory(CategoryRow("gone", null, "Antiga", null, 1, deleted = true))
    val p = dao.products().first()
    dao.updateProduct(p.copy(categoryId = "gone", categorySource = "user", categoryReview = "confirmed"))
    val parents = db.edit().categories().first().associate { it.id to it.parentId }
    val s = Spending(db.spending().lines().first(), parents)
    val uncategorized = s.caveats(s.periods.last()).uncategorizedCents
    assertTrue("fixture: the reports count it as uncategorized", uncategorized > 0)
    val row = db.edit().products().first().single { it.productId == p.id }
    assertTrue("reports say 'sem categoria' for it, so the catalog must ask (toPick), not show '${row.categoryLabel}'", row.toPick)
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
