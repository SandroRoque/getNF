package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stage 4c's hand edits: what each one changes, marks for sync, and logs (D51, D75). */
class CatalogEditorTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private var ids = 0
  private val editor = CatalogEditor(db, now = { 50 }, newId = { "n${ids++}" })
  private val chave = "42260111222333000181651090000000011123456784"

  private suspend fun receipt() {
    val store = ReceiptStore(File(tmp.root, "r"), db)
    store.open(chave, "qr")
    store.savePage(chave, "https://x", javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText())
    ReceiptParser(db, File(tmp.root, "r"), log = { _, _ -> }).parse(store.get(chave)!!)
    dao.insertCategory(CategoryRow("food", "food", "Comida", null, 1))
    dao.insertCategory(CategoryRow("fruit", "food.fruit", "Frutas", "food", 1))
    dao.insertCategory(CategoryRow("veg", "food.veg", "Legumes", "food", 2))
  }

  private suspend fun apple() = dao.products().single { p -> dao.storeItems().any { it.productId == p.id && it.code == "243" } }
  private suspend fun events() = db.categoryEvents().all()

  @Test fun confirmingAModelsAnswerMakesItTheUsers() = runTest {
    receipt()
    dao.updateProduct(apple().copy(categoryId = "fruit", categorySource = "jev", categoryReview = "automatic", categoryModel = "jev-latest", dirty = false))
    editor.confirm(apple().id)
    with(apple()) {
      assertEquals(listOf("fruit", "user", "confirmed", "jev-latest"), listOf(categoryId, categorySource, categoryReview, categoryModel))
      assertTrue(dirty)
    }
  }

  @Test fun aSuggestionTakenBecomesTheCategoryAndADifferentPickIsACorrection() = runTest {
    receipt()
    dao.updateProduct(apple().copy(suggestionCategoryId = "veg", suggestionConfidence = .4))
    assertTrue(db.edit().products().first().single { it.productId == apple().id }.toPick)
    editor.setCategory(apple().id, "fruit")
    assertEquals(listOf("fruit", "user", "corrected"), listOf(apple().categoryId, apple().categorySource, apple().categoryReview))
    assertFalse(db.edit().products().first().single { it.productId == apple().id }.toPick)
    editor.confirm(apple().id)
    assertEquals("confirmed", apple().categoryReview)
  }

  @Test fun linkingDropsTheEmptyProductAndUnlinkingGivesANewOne() = runTest {
    receipt()
    val items = dao.storeItems()
    val (a, b) = items[0] to items[1]
    editor.link(b.id, a.productId)
    assertEquals(a.productId, dao.storeItems().single { it.id == b.id }.productId)
    assertEquals("user", dao.storeItems().single { it.id == b.id }.linkSource)
    assertTrue(dao.products().single { it.id == b.productId }.deleted)
    val fresh = editor.unlink(b.id)
    assertEquals(fresh, dao.storeItems().single { it.id == b.id }.productId)
    assertFalse(dao.products().single { it.id == fresh }.deleted)
  }

  @Test fun aHandTypedDiscountSpreadsTheRestAgain() = runTest {
    receipt()
    val total = dao.parsed(chave)!!.discountCents!!
    val first = dao.lines(chave).first()
    editor.setDiscount(chave, first.lineNo, 10)
    val lines = dao.lines(chave)
    assertEquals(listOf(10L, "manual"), listOf(lines.first().discountCents, lines.first().discountSource))
    assertEquals(total, lines.sumOf { it.discountCents })
    assertTrue(lines.all { it.dirty })
    editor.prorateAgain(chave, first.lineNo)
    assertEquals("prorated", dao.lines(chave).first().discountSource)
    assertEquals(total, dao.lines(chave).sumOf { it.discountCents })
  }

  @Test fun everyTreeEditIsLoggedAndMarkedForSync() = runTest {
    receipt()
    val snacks = editor.createCategory("food", "Petiscos", "Snacks between meals")
    editor.renameCategory(snacks, "Lanches", null)
    editor.moveCategory(snacks, null)
    assertEquals(listOf("create", "rename", "move"), events().map { it.op })
    assertTrue(events().all { it.dirty })
    with(dao.categories().single { it.id == snacks }) {
      assertEquals(listOf<Any?>("Lanches", null, true, true), listOf(label, parentId, userModified, dirty))
    }
    try { editor.moveCategory("food", "fruit"); fail("moved into its own child") } catch (_: IllegalArgumentException) {}
  }

  @Test fun mergingMovesProductsAndSubcategoriesKeepingConfirmations() = runTest {
    receipt()
    dao.updateProduct(apple().copy(categoryId = "veg", categorySource = "user", categoryReview = "confirmed"))
    val sub = editor.createCategory("veg", "Raízes", null)
    editor.mergeCategory("veg", "fruit")
    assertEquals(listOf("fruit", "confirmed"), listOf(apple().categoryId, apple().categoryReview))
    assertEquals("fruit", dao.categories().single { it.id == sub }.parentId)
    assertTrue(dao.categories().single { it.id == "veg" }.deleted)
    val merge = events().single { it.op == "merge" }
    assertEquals(listOf("veg", "fruit", "1"), listOf(merge.categoryId, merge.targetIds, merge.products.toString()))
  }

  @Test fun splittingSendsTheProductsToPickOneOfTheNewParts() = runTest {
    receipt()
    dao.updateProduct(apple().copy(categoryId = "fruit", categorySource = "user", categoryReview = "confirmed"))
    val parts = editor.splitCategory("fruit", listOf("Frescas", "Secas"))
    assertEquals(2, parts.size)
    // Still counted under "Frutas" in reports, and on the review list until a part is picked.
    assertEquals("fruit", apple().categoryId)
    assertTrue(db.edit().products().first().single { it.productId == apple().id }.toPick)
    editor.setCategory(apple().id, parts[0])
    assertFalse(db.edit().products().first().single { it.productId == apple().id }.toPick)
    with(events().single { it.op == "split" }) { assertEquals(parts.joinToString(","), targetIds); assertEquals(1, products) }
  }

  @Test fun deletingNeedsNoSubcategoriesAndMovesProductsToTheDestination() = runTest {
    receipt()
    dao.updateProduct(apple().copy(categoryId = "fruit"))
    try { editor.deleteCategory("food", null); fail("deleted a category with subcategories") } catch (_: IllegalArgumentException) {}
    editor.deleteCategory("fruit", "food")
    assertEquals("food", apple().categoryId)
    assertEquals("food", events().single { it.op == "delete" }.targetIds)
    // Without a destination its products are left uncategorized.
    editor.deleteCategory("veg", null)
    assertNull(dao.categories().singleOrNull { it.id == "veg" && !it.deleted })
  }

  @Test fun theLlmsSuggestionsAreAcceptedOrRefusedByTheUser() = runTest {
    receipt()
    val items = dao.storeItems()
    val (a, b) = items[0] to items[1]
    db.sync().putLinkSuggestion(LinkSuggestionRow("s1", b.id, a.productId, .8, "mesmo item", "pending"))
    db.sync().putLinkSuggestion(LinkSuggestionRow("s2", items[2].id, a.productId, .7, null, "pending"))
    assertEquals(2, db.edit().linkSuggestions().first().size)
    editor.acceptLink("s1")
    assertEquals(a.productId, dao.storeItems().single { it.id == b.id }.productId)
    editor.refuseLink("s2")
    assertEquals(listOf("accepted" to true, "refused" to true), listOf("s1", "s2").map { id -> db.sync().linkSuggestion(id)!!.let { it.status to it.dirty } })
    assertTrue(db.edit().linkSuggestions().first().isEmpty())

    // A size the parser missed: accepting makes it the user's.
    dao.updateProduct(apple().copy(sizeSuggestionE6 = 1_000_000, sizeSuggestionUnit = "kg", sizeSuggestionModel = "llm"))
    assertEquals(1, db.edit().sizeSuggestions().first().size)
    editor.acceptSize(apple().id)
    assertEquals(Triple<Long?, String?, String?>(1_000_000, "kg", "manual"), Triple(apple().sizeE6, apple().sizeUnit, apple().sizeSource))
    assertTrue(db.edit().sizeSuggestions().first().isEmpty())
  }

  @Test fun whenTheLlmDisagreesWithJevTheUserPicks() = runTest {
    receipt()
    dao.updateProduct(apple().copy(categoryId = "veg", categorySource = "jev", categoryReview = "automatic", suggestionCategoryId = "fruit", suggestionModel = "llm"))
    val row = db.edit().products().first().single { it.productId == apple().id }
    assertTrue(row.disputed && row.toPick)
    editor.setCategory(apple().id, "fruit")
    assertEquals(listOf("fruit", "corrected"), listOf(apple().categoryId, apple().categoryReview))
    assertFalse(db.edit().products().first().single { it.productId == apple().id }.toPick)
  }

  @Test fun aDraftedDescriptionCanBeTaken() = runTest {
    receipt()
    dao.updateCategory(dao.categories().single { it.id == "veg" }.copy(descriptionSuggestion = "Legumes frescos"))
    editor.acceptDescription("veg")
    assertEquals("Legumes frescos", dao.categories().single { it.id == "veg" }.description)
    assertTrue(events().isEmpty())
  }
}
