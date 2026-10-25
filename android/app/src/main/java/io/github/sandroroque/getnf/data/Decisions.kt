package io.github.sandroroque.getnf.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A receipt as lists show it: where, when, how much (D81). */
data class ReceiptListItem(
  val chave: String, val capturedAt: Long, val issuedAt: Long?, val storeName: String?, val paidCents: Long?, val lines: Int,
  val hasPage: Boolean, val photos: Int,
)

/** A receipt whose photo-read discounts haven't been checked against the photos yet (D54). */
data class PhotoDiscountCheck(val chave: String, val storeName: String?, val issuedAt: Long?, val lines: Int, val cents: Long)

@Dao
abstract class ListsDao {
  @Query(
    """
    SELECT r.chave, r.capturedAt, rp.issuedAt, i.name AS storeName, rp.paidCents,
      (SELECT COUNT(*) FROM receipt_line l WHERE l.receipt = r.chave) AS lines,
      r.pageFile IS NOT NULL AS hasPage,
      (SELECT COUNT(*) FROM receipt_photo ph WHERE ph.receipt = r.chave) AS photos
    FROM receipt r LEFT JOIN receipt_parsed rp ON rp.chave = r.chave LEFT JOIN issuer i ON i.cnpj = rp.issuerCnpj
    ORDER BY COALESCE(rp.issuedAt, r.capturedAt) DESC
    """,
  )
  abstract fun receipts(): Flow<List<ReceiptListItem>>

  @Query(
    """
    SELECT l.receipt AS chave, i.name AS storeName, rp.issuedAt, COUNT(*) AS lines, SUM(l.discountCents) AS cents
    FROM receipt_line l LEFT JOIN receipt_parsed rp ON rp.chave = l.receipt LEFT JOIN issuer i ON i.cnpj = rp.issuerCnpj
    WHERE l.discountSource = 'photo' AND l.discountReview = 'unreviewed'
    GROUP BY l.receipt
    """,
  )
  abstract fun photoDiscountChecks(): Flow<List<PhotoDiscountCheck>>
}

/**
 * One thing only the user can decide (D81's "Para você"): every kind the app asks about, in one queue. [cents] is the
 * money involved, which orders the queue; receipts without their page come first, since nothing else can happen to
 * them until the page is fetched.
 */
sealed interface Decision {
  val key: String
  val cents: Long

  data class FetchPage(val receipt: ReceiptListItem) : Decision {
    override val key get() = "page:${receipt.chave}"
    override val cents get() = Long.MAX_VALUE
  }
  /** No category yet; [suggestion] is a model's below-threshold answer, if any. */
  data class PickCategory(val product: ProductSummary) : Decision {
    override val key get() = "pick:${product.productId}"
    override val cents get() = product.spentCents
  }
  /** Its category was split (D75): pick one of [parts]. */
  data class PickPart(val product: ProductSummary, val parts: List<CategoryNode>) : Decision {
    override val key get() = "part:${product.productId}"
    override val cents get() = product.spentCents
  }
  /** Jev (or an import) set the category and the LLM disagrees (D80). */
  data class Dispute(val product: ProductSummary) : Decision {
    override val key get() = "dispute:${product.productId}"
    override val cents get() = product.spentCents
  }
  data class SameProduct(val link: LinkSuggestionView, override val cents: Long) : Decision {
    override val key get() = "link:${link.id}"
  }
  data class Size(val size: SizeSuggestionView, override val cents: Long) : Decision {
    override val key get() = "size:${size.productId}"
  }
  data class CheckPhotoDiscounts(val check: PhotoDiscountCheck) : Decision {
    override val key get() = "photo:${check.chave}"
    override val cents get() = check.cents
  }
  data class Description(val category: CategoryNode) : Decision {
    override val key get() = "describe:${category.id}"
    override val cents get() = 0L
  }
}

/** The queue, most money first (D81). */
fun decisions(
  receipts: List<ReceiptListItem>, products: List<ProductSummary>, categories: List<CategoryNode>,
  links: List<LinkSuggestionView>, sizes: List<SizeSuggestionView>, photoChecks: List<PhotoDiscountCheck>,
): List<Decision> {
  val spent = products.associate { it.productId to it.spentCents }
  val children = categories.groupBy { it.parentId }
  val out = mutableListOf<Decision>()
  receipts.filter { !it.hasPage }.sortedBy { it.capturedAt }.mapTo(out) { Decision.FetchPage(it) }
  for (p in products) when {
    p.categoryId != null && p.categoryHasChildren -> out += Decision.PickPart(p, children[p.categoryId].orEmpty())
    p.categoryId == null -> out += Decision.PickCategory(p)
    p.disputed -> out += Decision.Dispute(p)
  }
  links.mapTo(out) { Decision.SameProduct(it, spent[it.productId] ?: 0) }
  sizes.mapTo(out) { Decision.Size(it, spent[it.productId] ?: 0) }
  photoChecks.mapTo(out) { Decision.CheckPhotoDiscounts(it) }
  categories.filter { it.description == null && it.descriptionSuggestion != null }.mapTo(out) { Decision.Description(it) }
  return out.sortedByDescending { it.cents }
}

/** A store's share of a period (D81's second lens on spending). */
data class StoreSpend(val cnpj: String?, val name: String?, val netCents: Long, val receipts: Int, val lines: Int)

fun storesIn(lines: List<SpendingLine>): List<StoreSpend> = lines.groupBy { it.storeCnpj }.map { (cnpj, ls) ->
  StoreSpend(cnpj, ls.firstNotNullOfOrNull { it.storeName }, ls.sumOf { it.netCents }, ls.map { it.chave }.distinct().size, ls.size)
}.sortedByDescending { it.netCents }
