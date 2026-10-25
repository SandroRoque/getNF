package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stage 5's numbers: every level adds up to the one above and to the receipts; prices compare like with like. */
class SpendingTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private val chave = "42260111222333000181651090000000011123456784"

  private suspend fun spending(yearly: Boolean = false): Spending {
    val store = ReceiptStore(File(tmp.root, "r"), db)
    store.open(chave, "qr")
    store.savePage(chave, "https://x", javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText())
    ReceiptParser(db, File(tmp.root, "r"), log = { _, _ -> }).parse(store.get(chave)!!)
    dao.insertCategory(CategoryRow("food", "food", "Comida", null, 1))
    dao.insertCategory(CategoryRow("fruit", "food.fruit", "Frutas", "food", 1))
    dao.insertCategory(CategoryRow("veg", "food.veg", "Legumes", "food", 2))
    dao.insertCategory(CategoryRow("home", "home", "Casa", null, 2))
    val items = dao.storeItems()
    fun categorize(code: String, category: String) {
      val p = items.single { it.code == code }.productId
      kotlinx.coroutines.runBlocking { dao.updateProduct(dao.products().single { it.id == p }.copy(categoryId = category)) }
    }
    categorize("243", "fruit") // MACA NAC KG
    categorize(items.first { it.description.startsWith("BROCOLIS") }.code, "veg")
    categorize(items.first { it.description.startsWith("POLPA") }.code, "food") // only at the top level
    val parents = db.edit().categories().first().associate { it.id to it.parentId }
    return Spending(db.spending().lines().first(), parents, yearly)
  }

  @Test fun everyLevelAddsUpToTheReceipt() = runTest {
    val s = spending()
    val month = s.periods.single()
    assertEquals(3064L, s.total(month)) // the receipt's amount paid
    val top = s.slices(month, null)
    assertEquals(s.total(month), top.sumOf { it.netCents })
    assertTrue("uncategorized lines show as their own slice", top.any { it.categoryId == null })
    val food = top.single { it.categoryId == "food" }
    val inFood = s.slices(month, "food")
    assertEquals(food.netCents, inFood.sumOf { it.netCents })
    // What's in "food" itself, not in a subcategory, is a slice of its own.
    assertEquals(setOf("fruit", "veg", "food"), inFood.map { it.categoryId }.toSet())
    assertEquals(food.netCents, s.linesUnder(month, "food").sumOf { it.netCents })
    assertEquals(s.total(month), productsIn(s.linesIn(month)).sumOf { it.netCents })
  }

  @Test fun caveatsSayWhatIsEstimatedOrMissing() = runTest {
    val s = spending()
    val c = s.caveats(s.periods.single())
    // The fixture's R$ 0,50 discount is spread over the lines, not read per item.
    assertEquals(50L, c.proratedDiscountCents)
    assertEquals(s.slices(s.periods.single(), null).single { it.categoryId == null }.netCents, c.uncategorizedCents)
  }

  @Test fun aYearAddsUpItsMonths() = runTest {
    val year = spending(yearly = true)
    assertEquals(4, year.periods.single().key.length)
    assertEquals(3064L, year.total(year.periods.single()))
  }

  @Test fun pricesCompareByKgWhenTheSizeIsKnown() = runTest {
    spending()
    val lines = db.spending().lines().first()
    val apple = pricePoint(lines.first { it.unit == "KG" })!!
    assertEquals("kg", apple.basis)
    // POLPA BRASFRUT 100G at R$ 2,98 a unit is R$ 29,80 a kg.
    val pulp = pricePoint(lines.first { it.sizeE6 == 100_000L })!!
    assertEquals(2980L to "kg", pulp.cents to pulp.basis)
  }

  @Test fun theCategoryTrendFollowsEachProductAgainstItsOwnFirstPrice() {
    fun line(product: String, at: Long, priceE4: Long) = SpendingLine("k", 1, at, null, null, 100, 0, "html", product, "c", 10_000, "UN", priceE4, null, null)
    val oct = 1_790_000_000_000L; val nov = oct + 31L * 86_400_000
    val trend = categoryTrend(listOf(line("a", oct, 10_000), line("a", nov, 11_000), line("b", oct, 50_000), line("b", nov, 50_000), line("c", nov, 99_000)))
    assertEquals(listOf(100.0, 105.0), trend.map { it.second }) // a +10%, b flat; c bought once says nothing
  }
}
