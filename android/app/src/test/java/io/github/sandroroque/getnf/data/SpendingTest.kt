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

  @Test fun systemOnesReadingsPriceTheLine() = runTest {
    spending()
    val pulpItem = dao.storeItems().first { it.description.startsWith("POLPA") }
    val lineNo = dao.lines().first { it.storeItemId == pulpItem.id }.lineNo
    suspend fun pulp() = db.spending().lines().first().first { it.lineNo == lineNo }
    // A size System-1 is sure of replaces the parsed one (D108): 400 g instead of 100 g.
    dao.updateStoreItem(pulpItem.copy(readSizeE6 = 400_000, readSizeUnit = "kg", readSizeSource = "jev"))
    assertEquals(745L to "kg", pricePoint(pulp())!!.let { it.cents to it.basis })
    // A sure "no size" prices it per unit.
    dao.updateStoreItem(pulpItem.copy(readSizeE6 = null, readSizeUnit = null, readSizeSource = "jev"))
    assertEquals(298L to "un", pricePoint(pulp())!!.let { it.cents to it.basis })
    // Sold by weight under a unit that says nothing: the unit price is per kg.
    dao.updateStoreItem(pulpItem.copy(soldBy = "weight", soldBySource = "jev"))
    assertEquals(298L to "kg", pricePoint(pulp())!!.let { it.cents to it.basis })
    // A size by hand still wins over System-1's.
    dao.updateStoreItem(pulpItem.copy(readSizeE6 = 400_000, readSizeUnit = "kg", readSizeSource = "jev"))
    val product = dao.products().single { it.id == pulpItem.productId }
    dao.updateProduct(product.copy(sizeE6 = 200_000, sizeUnit = "kg", sizeSource = "manual"))
    assertEquals(200_000L, pulp().sizeE6)
  }
}
