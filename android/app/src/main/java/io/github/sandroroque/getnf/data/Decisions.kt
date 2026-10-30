package io.github.sandroroque.getnf.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A receipt as lists show it: where, when, how much (D81). */
data class ReceiptListItem(
  val chave: String, val capturedAt: Long, val issuedAt: Long?, val storeName: String?, val paidCents: Long?, val lines: Int,
  val hasPage: Boolean, val photos: Int,
  /** The issue date the photos show, while the page isn't fetched (D99). */
  val photoIssuedAt: Long? = null,
  /** Who brought it, when only other members scanned it (D104): "Ana", "Ana e Bia". */
  val scannedBy: String? = null,
) {
  /**
   * How lists name it: its store once the page is read; until then "Nota de 16/10/2026" from the photos, or the
   * month the access key carries ("Nota de 10/2026").
   */
  val title: String get() = if (hasPage && storeName != null) storeName else "Nota de " + (photoIssuedAt?.let(::dayOf)
    ?: PhotoRead.keyMonth(chave)?.let { (y, m) -> "%02d/%d".format(m, y) } ?: dayOf(capturedAt))
}

/**
 * The members who scanned receipt `r`, for a receipt this person didn't scan themselves (not on this phone, nor on
 * another of theirs). Members who left aren't in `member`, so their mark goes with them (D104).
 */
internal const val SCANNED_BY = """(CASE WHEN r.scannedHere OR EXISTS (SELECT 1 FROM receipt_scan s JOIN member m ON m.userId = s.userId
      WHERE s.chave = r.chave AND m.you) THEN NULL
      ELSE (SELECT GROUP_CONCAT(m.name, ' e ') FROM receipt_scan s JOIN member m ON m.userId = s.userId WHERE s.chave = r.chave) END)"""

private fun dayOf(millis: Long) = java.time.Instant.ofEpochMilli(millis).atOffset(java.time.ZoneOffset.ofHours(-3)).toLocalDate()
  .let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) }

/** A receipt whose photo-read discounts haven't been checked against the photos yet (D54). */
data class PhotoDiscountCheck(val chave: String, val storeName: String?, val issuedAt: Long?, val lines: Int, val cents: Long)

@Dao
abstract class ListsDao {
  @Query(
    """
    SELECT r.chave, r.capturedAt, COALESCE(rp.issuedAt, pr.issuedAt) AS issuedAt, COALESCE(i.name, ik.name) AS storeName, rp.paidCents,
      (SELECT COUNT(*) FROM receipt_line l WHERE l.receipt = r.chave) AS lines,
      r.pageFile IS NOT NULL AS hasPage,
      (SELECT COUNT(*) FROM receipt_photo ph WHERE ph.receipt = r.chave) AS photos,
      pr.issuedAt AS photoIssuedAt,
      $SCANNED_BY AS scannedBy
    FROM receipt r LEFT JOIN receipt_parsed rp ON rp.chave = r.chave LEFT JOIN issuer i ON i.cnpj = rp.issuerCnpj
      -- Before the page: the store by the CNPJ the access key carries (digits 7-20), when its receipts are known.
      LEFT JOIN issuer ik ON ik.cnpj = substr(r.chave, 7, 14)
      LEFT JOIN receipt_photo_read pr ON pr.chave = r.chave
    ORDER BY COALESCE(rp.issuedAt, pr.issuedAt, r.capturedAt) DESC
    """,
  )
  abstract fun receipts(): Flow<List<ReceiptListItem>>

  @Query("SELECT $SCANNED_BY FROM receipt r WHERE r.chave = :chave")
  abstract fun scannedBy(chave: String): Flow<String?>

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
  /** Jev isn't sure how a store item is sold (D110). */
  data class SoldBy(val item: SoldBySuggestionView, override val cents: Long) : Decision {
    override val key get() = "soldby:${item.storeItemId}"
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
  soldBy: List<SoldBySuggestionView> = emptyList(),
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
  soldBy.mapTo(out) { Decision.SoldBy(it, spent[it.productId] ?: 0) }
  photoChecks.mapTo(out) { Decision.CheckPhotoDiscounts(it) }
  categories.filter { it.description == null && it.descriptionSuggestion != null }.mapTo(out) { Decision.Description(it) }
  return out.sortedByDescending { it.cents }
}

/** A store's share of a period (D81's second lens on spending). */
data class StoreSpend(val cnpj: String?, val name: String?, val netCents: Long, val receipts: Int, val lines: Int)

fun storesIn(lines: List<SpendingLine>): List<StoreSpend> = lines.groupBy { it.storeCnpj }.map { (cnpj, ls) ->
  StoreSpend(cnpj, ls.firstNotNullOfOrNull { it.storeName }, ls.sumOf { it.netCents }, ls.map { it.chave }.distinct().size, ls.size)
}.sortedByDescending { it.netCents }
