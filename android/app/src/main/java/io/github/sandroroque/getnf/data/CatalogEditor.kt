package io.github.sandroroque.getnf.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.sandroroque.getnf.parse.Derive
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** A product as the review list and the catalog show it. */
data class ProductSummary(
  val productId: String,
  val name: String?,
  /** The latest wording of one of its store items: what the receipts call it. */
  val description: String?,
  val categoryId: String?,
  val categoryLabel: String?,
  val categoryReview: String?,
  val categorySource: String?,
  val categoryConfidence: Double?,
  val suggestionCategoryId: String?,
  val suggestionLabel: String?,
  val suggestionConfidence: Double?,
  /** Its category got subcategories (a split, D75): one of them has to be picked. */
  val categoryHasChildren: Boolean,
  val spentCents: Long,
  val purchases: Int,
) {
  val title: String get() = name ?: description ?: "?"
  /** Needs the user: no category, only a suggestion, or a category that was split. */
  val toPick: Boolean get() = categoryId == null || categoryHasChildren
  /** Set by a model and not confirmed (D69). */
  val unconfirmed: Boolean get() = categoryId != null && categoryReview == REVIEW_AUTOMATIC
}

data class CategoryNode(
  val id: String, val label: String, val parentId: String?, val sort: Int, val seedPath: String?, val description: String?,
  val products: Int,
)

data class StoreItemDetail(
  val id: String, val codeKind: String, val code: String, val storeCnpj: String, val description: String,
  val parsedSizeE6: Long?, val parsedSizeUnit: String?, val issuerName: String?, val lines: Int,
)

data class Purchase(
  val chave: String, val lineNo: Int, val issuedAt: Long?, val issuerName: String?, val qtyE4: Long, val unit: String?,
  val unitPriceE4: Long, val netCents: Long, val override: Boolean,
)

const val SOURCE_USER = "user"
const val REVIEW_AUTOMATIC = "automatic"
const val REVIEW_CONFIRMED = "confirmed"
const val REVIEW_CORRECTED = "corrected"

@Dao
abstract class EditDao {
  @Query(
    """
    SELECT p.id AS productId, p.name,
      (SELECT s.description FROM store_item s WHERE s.productId = p.id AND s.deleted = 0 ORDER BY s.rowid DESC LIMIT 1) AS description,
      p.categoryId, c.label AS categoryLabel, p.categoryReview, p.categorySource, p.categoryConfidence,
      p.suggestionCategoryId, sc.label AS suggestionLabel, p.suggestionConfidence,
      EXISTS (SELECT 1 FROM category ch WHERE ch.parentId = p.categoryId AND ch.deleted = 0) AS categoryHasChildren,
      COALESCE(u.spent, 0) AS spentCents, COALESCE(u.n, 0) AS purchases
    FROM product p
    LEFT JOIN category c ON c.id = p.categoryId
    LEFT JOIN category sc ON sc.id = p.suggestionCategoryId
    LEFT JOIN (
      SELECT COALESCE(l.productOverrideId, s.productId) AS pid, SUM(l.grossCents - l.discountCents) AS spent, COUNT(*) AS n
      FROM receipt_line l JOIN store_item s ON s.id = l.storeItemId GROUP BY pid
    ) u ON u.pid = p.id
    WHERE p.deleted = 0 AND p.archived = 0 AND u.n > 0
    ORDER BY spentCents DESC
    """,
  )
  abstract fun products(): Flow<List<ProductSummary>>

  @Query(
    """
    SELECT c.id, c.label, c.parentId, c.sort, c.seedPath, c.description,
      (SELECT COUNT(*) FROM product p WHERE p.categoryId = c.id AND p.deleted = 0) AS products
    FROM category c WHERE c.deleted = 0 ORDER BY c.sort, c.label
    """,
  )
  abstract fun categories(): Flow<List<CategoryNode>>

  @Query("SELECT * FROM product WHERE id = :id") abstract fun product(id: String): Flow<ProductRow?>
  @Query("SELECT * FROM tag WHERE deleted = 0 ORDER BY slug") abstract fun tags(): Flow<List<TagRow>>
  @Query("SELECT tagId FROM product_tag WHERE productId = :id AND deleted = 0") abstract fun tagIds(id: String): Flow<List<String>>

  @Query(
    """
    SELECT s.id, s.codeKind, s.code, s.storeCnpj, s.description, s.parsedSizeE6, s.parsedSizeUnit, i.name AS issuerName,
      (SELECT COUNT(*) FROM receipt_line l WHERE l.storeItemId = s.id) AS lines
    FROM store_item s LEFT JOIN issuer i ON i.cnpj = s.storeCnpj
    WHERE s.productId = :id AND s.deleted = 0
    """,
  )
  abstract fun storeItems(id: String): Flow<List<StoreItemDetail>>

  @Query(
    """
    SELECT l.receipt AS chave, l.lineNo, rp.issuedAt, i.name AS issuerName, l.qtyE4, l.unit, l.unitPriceE4,
      l.grossCents - l.discountCents AS netCents, l.productOverrideId IS NOT NULL AS override
    FROM receipt_line l JOIN store_item s ON s.id = l.storeItemId
    LEFT JOIN receipt_parsed rp ON rp.chave = l.receipt LEFT JOIN issuer i ON i.cnpj = rp.issuerCnpj
    WHERE COALESCE(l.productOverrideId, s.productId) = :id
    ORDER BY rp.issuedAt DESC
    """,
  )
  abstract fun purchases(id: String): Flow<List<Purchase>>

  /** The units a product was bought in (KG, UN...), for the warning when joining products (data-model.md). */
  @Query(
    """
    SELECT DISTINCT UPPER(COALESCE(l.unit, '')) FROM receipt_line l JOIN store_item s ON s.id = l.storeItemId
    WHERE COALESCE(l.productOverrideId, s.productId) = :id AND l.unit IS NOT NULL
    """,
  )
  abstract suspend fun lineUnits(id: String): List<String>

  @Query("SELECT * FROM product WHERE id = :id") abstract suspend fun productNow(id: String): ProductRow?
  @Query("SELECT * FROM category WHERE id = :id") abstract suspend fun categoryNow(id: String): CategoryRow?
  @Query("SELECT * FROM category WHERE deleted = 0") abstract suspend fun categoriesNow(): List<CategoryRow>
  @Query("SELECT * FROM store_item WHERE id = :id") abstract suspend fun storeItemNow(id: String): StoreItemRow?
  @Query("SELECT * FROM store_item WHERE productId = :id AND deleted = 0") abstract suspend fun storeItemsOf(id: String): List<StoreItemRow>
  @Query("SELECT * FROM product WHERE (categoryId = :id OR suggestionCategoryId = :id) AND deleted = 0")
  abstract suspend fun productsIn(id: String): List<ProductRow>
  @Query("SELECT COUNT(*) FROM receipt_line WHERE productOverrideId = :id") abstract suspend fun overridesOf(id: String): Int
  @Query("SELECT * FROM receipt_line WHERE receipt = :chave ORDER BY lineNo") abstract suspend fun lines(chave: String): List<ReceiptLineRow>
  @Query("SELECT discountCents FROM receipt_parsed WHERE chave = :chave") abstract suspend fun receiptDiscount(chave: String): Long?
  @Query("SELECT * FROM product_tag WHERE productId = :productId AND tagId = :tagId") abstract suspend fun productTag(productId: String, tagId: String): ProductTagRow?
}

/**
 * Every hand edit of the catalog (stage 4c): products, store-item links, line discounts and the category tree. Each
 * write marks its rows dirty for the next sync and records the user as the source (D51), so no parse or model replaces
 * it. Tree edits also write a [CategoryEventRow] in the same transaction (D75). A refused edit throws
 * IllegalArgumentException with a message for the user, in Portuguese.
 */
class CatalogEditor(
  private val database: ReceiptDatabase,
  private val now: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val dao = database.edit()
  private val sync = database.sync()

  // ---- products ----

  /** Accepts what a model said: the automatic category, or the suggestion when there's no category. */
  suspend fun confirm(productId: String) = write {
    val p = product(productId)
    val category = p.categoryId ?: p.suggestionCategoryId ?: return@write
    save(p.copy(
      categoryId = category, categorySource = SOURCE_USER, categoryReview = REVIEW_CONFIRMED,
      categoryModel = p.categoryModel ?: p.suggestionModel, categoryConfidence = p.categoryConfidence ?: p.suggestionConfidence,
      categoryAt = now(),
    ))
  }

  /** The user's pick. Confirmed if it agrees with what a model said (or nothing was said), corrected if not. */
  suspend fun setCategory(productId: String, categoryId: String?) = write {
    val p = product(productId)
    val proposed = p.categoryId ?: p.suggestionCategoryId
    val review = if (proposed == null || proposed == categoryId) REVIEW_CONFIRMED else REVIEW_CORRECTED
    save(p.copy(categoryId = categoryId, categorySource = SOURCE_USER, categoryReview = review, categoryAt = now(),
      categoryModel = if (review == REVIEW_CONFIRMED) p.categoryModel ?: p.suggestionModel else p.categoryModel))
  }

  suspend fun rename(productId: String, name: String?) = write {
    save(product(productId).copy(name = name?.trim()?.takeIf { it.isNotEmpty() }))
  }

  /** A size set by hand; `null` size with [manual] removes it (the description's size doesn't apply, D49). */
  suspend fun setSize(productId: String, sizeE6: Long?, unit: String?, manual: Boolean = true) = write {
    val p = product(productId)
    save(if (manual) p.copy(sizeE6 = sizeE6, sizeUnit = unit?.takeIf { sizeE6 != null }, sizeSource = "manual")
         else p.copy(sizeE6 = null, sizeUnit = null, sizeSource = null))
  }

  suspend fun setTag(productId: String, tagId: String, on: Boolean) = write {
    val old = dao.productTag(productId, tagId)
    if (old == null && !on) return@write
    sync.upsertProductTag(ProductTagRow(productId, tagId, deleted = !on, dirty = true))
  }

  /**
   * "This store item is also that product" (data-model.md, flow 2). The product it leaves goes away if nothing else
   * uses it and nothing about it was set by hand; otherwise it stays, empty.
   */
  suspend fun link(storeItemId: String, productId: String) = write {
    val item = requireNotNull(dao.storeItemNow(storeItemId)) { "no store item $storeItemId" }
    if (item.productId == productId) return@write
    sync.upsertStoreItem(item.copy(productId = productId, linkSource = SOURCE_USER, linkReview = REVIEW_CONFIRMED, dirty = true))
    dropIfEmpty(item.productId)
  }

  /** Gives a store item a product of its own again. */
  suspend fun unlink(storeItemId: String): String = write {
    val item = requireNotNull(dao.storeItemNow(storeItemId)) { "no store item $storeItemId" }
    val product = ProductRow(newId(), name = null, categoryId = null, sizeE6 = null, sizeUnit = null, sizeSource = null,
      createdAt = now(), dirty = true)
    sync.upsertProduct(product)
    sync.upsertStoreItem(item.copy(productId = product.id, linkSource = SOURCE_USER, linkReview = REVIEW_CONFIRMED, dirty = true))
    dropIfEmpty(item.productId)
    product.id
  }

  private suspend fun dropIfEmpty(productId: String) {
    val p = dao.productNow(productId) ?: return
    val handSet = p.name != null || p.categorySource == SOURCE_USER || p.sizeSource == "manual"
    if (!handSet && dao.storeItemsOf(productId).isEmpty() && dao.overridesOf(productId) == 0) save(p.copy(deleted = true))
  }

  // ---- lines ----

  /** A discount typed by hand; the rest of the receipt's discount is spread again over the lines without one. */
  suspend fun setDiscount(chave: String, lineNo: Int, cents: Long) = write {
    reprorate(chave) { if (it.lineNo == lineNo) it.copy(discountCents = cents, discountSource = "manual", discountReview = REVIEW_CONFIRMED) else it }
  }

  /** Back to a share of the receipt's discount, like a line nobody said anything about. */
  suspend fun prorateAgain(chave: String, lineNo: Int) = write {
    reprorate(chave) { if (it.lineNo == lineNo) it.copy(discountSource = "prorated", discountReview = "unreviewed") else it }
  }

  /** The discount read from a photo, checked against it by the user. */
  suspend fun confirmDiscount(chave: String, lineNo: Int) = write {
    val line = dao.lines(chave).single { it.lineNo == lineNo }
    sync.upsertLine(line.copy(discountReview = REVIEW_CONFIRMED, dirty = true))
  }

  /** This line only is another product (a mis-scanned code, say); `null` goes back to the store item's product. */
  suspend fun overrideProduct(chave: String, lineNo: Int, productId: String?) = write {
    val line = dao.lines(chave).single { it.lineNo == lineNo }
    sync.upsertLine(line.copy(productOverrideId = productId, dirty = true))
  }

  private suspend fun reprorate(chave: String, change: (ReceiptLineRow) -> ReceiptLineRow) {
    val lines = dao.lines(chave).map(change)
    val known = lines.withIndex().filter { it.value.discountSource == "photo" || it.value.discountSource == "manual" }
      .associate { it.index to it.value.discountCents }
    val shares = Derive.lineDiscounts(dao.receiptDiscount(chave) ?: 0, lines.map { it.grossCents }, known)
    lines.forEachIndexed { i, l ->
      val updated = if (i in known) l else l.copy(discountCents = shares[i].cents)
      sync.upsertLine(updated.copy(dirty = true))
    }
  }

  // ---- the category tree (D75) ----

  suspend fun createCategory(parentId: String?, label: String, description: String?): String = write {
    val siblings = dao.categoriesNow().filter { it.parentId == parentId }
    val c = CategoryRow(newId(), seedPath = null, label = label.trim(), parentId = parentId,
      sort = (siblings.maxOfOrNull { it.sort } ?: 0) + 1, userModified = true, dirty = true,
      description = description?.trim()?.takeIf { it.isNotEmpty() })
    sync.upsertCategory(c)
    event("create", c.id, newLabel = c.label, newParentId = parentId)
    c.id
  }

  suspend fun renameCategory(id: String, label: String, description: String?) = write {
    val c = category(id)
    sync.upsertCategory(c.copy(label = label.trim(), description = description?.trim()?.takeIf { it.isNotEmpty() },
      userModified = true, dirty = true))
    event("rename", id, oldLabel = c.label, newLabel = label.trim())
  }

  suspend fun moveCategory(id: String, parentId: String?) = write {
    val c = category(id)
    require(parentId == null || id !in ancestors(parentId) + parentId) { "Uma categoria não pode ir para dentro dela mesma." }
    sync.upsertCategory(c.copy(parentId = parentId, userModified = true, dirty = true))
    event("move", id, oldParentId = c.parentId, newParentId = parentId)
  }

  /** [id] goes into [intoId]: its products follow and keep their review state; its subcategories move there too. */
  suspend fun mergeCategory(id: String, intoId: String) = write {
    require(id != intoId && id !in ancestors(intoId)) { "Não dá para juntar uma categoria com ela mesma ou com uma de dentro dela." }
    val moved = moveContents(id, intoId)
    sync.upsertCategory(category(id).copy(deleted = true, dirty = true))
    event("merge", id, targets = listOf(intoId), products = moved)
  }

  /**
   * Deletes a category with no subcategories. Its products go to [destinationId] (the parent, by default in the app),
   * keeping their review state.
   */
  suspend fun deleteCategory(id: String, destinationId: String?) = write {
    require(dao.categoriesNow().none { it.parentId == id }) { "Primeiro mova ou exclua as subcategorias." }
    val moved = if (destinationId != null) moveContents(id, destinationId)
      else dao.productsIn(id).onEach { save(it.copy(categoryId = it.categoryId.takeIf { c -> c != id },
        suggestionCategoryId = it.suggestionCategoryId.takeIf { c -> c != id })) }.size
    sync.upsertCategory(category(id).copy(deleted = true, dirty = true))
    event("delete", id, targets = listOfNotNull(destinationId), products = moved)
  }

  /**
   * New subcategories under [id]. Its products stay in it, so the reports keep adding up, and go to the review list to
   * pick one of the new ones ([ProductSummary.toPick]); until then their confirmations don't count as right answers
   * (D69's test set reads only confirmations in leaf categories).
   */
  suspend fun splitCategory(id: String, labels: List<String>): List<String> = write {
    val c = category(id)
    val children = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    require(children.size >= 2) { "Dividir pede pelo menos duas partes." }
    val ids = children.mapIndexed { i, label ->
      CategoryRow(newId(), seedPath = null, label = label, parentId = id, sort = i + 1, userModified = true, dirty = true)
        .also { sync.upsertCategory(it) }.id
    }
    event("split", id, targets = ids, oldLabel = c.label, products = dao.productsIn(id).count { it.categoryId == id })
    ids
  }

  private suspend fun moveContents(from: String, to: String): Int {
    val products = dao.productsIn(from)
    for (p in products) save(p.copy(
      categoryId = if (p.categoryId == from) to else p.categoryId,
      suggestionCategoryId = if (p.suggestionCategoryId == from) to else p.suggestionCategoryId,
    ))
    for (child in dao.categoriesNow().filter { it.parentId == from }) {
      sync.upsertCategory(child.copy(parentId = to, userModified = true, dirty = true))
      event("move", child.id, oldParentId = from, newParentId = to)
    }
    return products.size
  }

  private suspend fun ancestors(id: String): List<String> {
    val byId = dao.categoriesNow().associateBy { it.id }
    return generateSequence(byId[id]?.parentId) { byId[it]?.parentId }.toList()
  }

  private suspend fun event(
    op: String, id: String, targets: List<String> = emptyList(), oldLabel: String? = null, newLabel: String? = null,
    oldParentId: String? = null, newParentId: String? = null, products: Int = 0,
  ) = database.categoryEvents().insert(CategoryEventRow(newId(), op, id, targets.joinToString(","), oldLabel, newLabel,
    oldParentId, newParentId, products, now()))

  private suspend fun product(id: String) = requireNotNull(dao.productNow(id)) { "no product $id" }
  private suspend fun category(id: String) = requireNotNull(dao.categoryNow(id)) { "no category $id" }
  private suspend fun save(p: ProductRow) = sync.upsertProduct(p.copy(dirty = true))
  private suspend fun <T> write(block: suspend () -> T): T = database.useWriterConnection { it.immediateTransaction { block() } }
}

@Dao
abstract class CategoryEventDao {
  @androidx.room.Insert abstract suspend fun insert(row: CategoryEventRow)
  @Query("SELECT * FROM category_event WHERE dirty = 1 ORDER BY at") abstract suspend fun dirty(): List<CategoryEventRow>
  @Query("UPDATE category_event SET dirty = 0 WHERE id IN (:ids)") abstract suspend fun pushed(ids: List<String>)
  @Query("SELECT * FROM category_event ORDER BY at") abstract suspend fun all(): List<CategoryEventRow>
}
