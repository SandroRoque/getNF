package io.github.sandroroque.getnf.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/*
 * Lines and catalog (docs/data-model.md, D46). Money is Long cents, quantities and unit prices Long at scale 4, sizes
 * Long millionths of a kg or litre. During stage 2 these tables mirror the server (ServerMirror); nothing edits them
 * on the phone yet.
 */

/** What the server's parser read from a receipt's page. Its own table, so ReceiptStore's row writes never touch it. */
@Entity(
  tableName = "receipt_parsed",
  foreignKeys = [ForeignKey(ReceiptRow::class, ["chave"], ["chave"], onDelete = ForeignKey.CASCADE)],
)
data class ReceiptParsedRow(
  @PrimaryKey val chave: String,
  val issuerCnpj: String?,
  /** Epoch millis. */
  val issuedAt: Long?,
  val grossCents: Long?,
  val discountCents: Long?,
  val paidCents: Long?,
  val parserVersion: Int?,
  val parseError: String?,
)

@Entity(tableName = "issuer")
data class IssuerRow(@PrimaryKey val cnpj: String, val name: String)

@Entity(
  tableName = "category",
  foreignKeys = [ForeignKey(CategoryRow::class, ["id"], ["parentId"], onDelete = ForeignKey.SET_NULL)],
  indices = [Index("seedPath", unique = true), Index("parentId")],
)
data class CategoryRow(
  @PrimaryKey val id: String,
  /** Path in taxonomy/categories.txt (`food.produce.fruit`); null for a category the user created. */
  val seedPath: String?,
  val label: String,
  val parentId: String?,
  val sort: Int,
  /** The taxonomy seed no longer overwrites label, parent or order. */
  val userModified: Boolean = false,
  /** Tombstone: the seed doesn't recreate it. */
  val deleted: Boolean = false,
)

@Entity(tableName = "tag", indices = [Index("slug", unique = true)])
data class TagRow(@PrimaryKey val id: String, val slug: String, val description: String, val seed: Boolean = true, val deleted: Boolean = false)

/** What the thing is. Store items of several stores can point to the same product (D44). */
@Entity(
  tableName = "product",
  foreignKeys = [ForeignKey(CategoryRow::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL)],
  indices = [Index("categoryId")],
)
data class ProductRow(
  @PrimaryKey val id: String,
  /** Given by the user; null shows the latest store item description. */
  val name: String?,
  val categoryId: String?,
  /** Set only by hand (`sizeSource = manual`); parsed sizes live on the store item. */
  val sizeE6: Long?,
  val sizeUnit: String?,
  val sizeSource: String?,
  val archived: Boolean = false,
  val createdAt: Long,
)

/** One per code as receipts show it: a GTIN anywhere, or a store code together with its store's CNPJ. */
@Entity(
  tableName = "store_item",
  foreignKeys = [ForeignKey(ProductRow::class, ["id"], ["productId"])],
  indices = [Index("codeKind", "code", "storeCnpj", unique = true), Index("productId")],
)
data class StoreItemRow(
  @PrimaryKey val id: String,
  /** `gtin` or `store`. */
  val codeKind: String,
  val code: String,
  /** Empty for `gtin`. */
  val storeCnpj: String,
  val description: String,
  val parsedSizeE6: Long?,
  val parsedSizeUnit: String?,
  val productId: String,
)

@Entity(
  tableName = "product_tag",
  primaryKeys = ["productId", "tagId"],
  foreignKeys = [
    ForeignKey(ProductRow::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(TagRow::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("tagId")],
)
data class ProductTagRow(val productId: String, val tagId: String)

/** One printed line of a receipt. Extracted fields are evidence; corrections have their own columns. */
@Entity(
  tableName = "receipt_line",
  primaryKeys = ["receipt", "lineNo"],
  foreignKeys = [
    ForeignKey(ReceiptRow::class, ["chave"], ["receipt"], onDelete = ForeignKey.CASCADE),
    ForeignKey(StoreItemRow::class, ["id"], ["storeItemId"]),
    ForeignKey(ProductRow::class, ["id"], ["productOverrideId"]),
  ],
  indices = [Index("storeItemId"), Index("productOverrideId")],
)
data class ReceiptLineRow(
  val receipt: String,
  /** The receipt's own item number (nItem): stable across re-parses. */
  val lineNo: Int,
  val description: String,
  val code: String,
  val qtyE4: Long,
  val unit: String?,
  val unitPriceE4: Long,
  val grossCents: Long,
  val storeItemId: String,
  /** A correction for this line only. */
  val productOverrideId: String? = null,
  val discountCents: Long,
  /** `html`, `photo`, `manual` or `prorated`. */
  val discountSource: String,
  /** `unreviewed` or `confirmed`. */
  val discountReview: String = "unreviewed",
  val orphaned: Boolean = false,
)

/** Net spending per store-local month and category path (empty for unclassified). */
data class MonthlyCategoryTotal(val month: String, val category: String, val netCents: Long)

@Dao
abstract class CatalogDao {
  @Query("SELECT * FROM category") abstract suspend fun categories(): List<CategoryRow>
  @Query("SELECT * FROM tag") abstract suspend fun tags(): List<TagRow>
  @Query("SELECT * FROM product") abstract suspend fun products(): List<ProductRow>
  @Query("SELECT * FROM store_item") abstract suspend fun storeItems(): List<StoreItemRow>
  @Query("SELECT * FROM product_tag") abstract suspend fun productTags(): List<ProductTagRow>
  @Query("SELECT * FROM receipt_line ORDER BY receipt, lineNo") abstract suspend fun lines(): List<ReceiptLineRow>
  @Query("SELECT * FROM receipt_parsed") abstract suspend fun parsed(): List<ReceiptParsedRow>
  @Query("SELECT * FROM issuer") abstract suspend fun issuers(): List<IssuerRow>

  @Insert abstract suspend fun insertCategory(row: CategoryRow)
  @Update abstract suspend fun updateCategory(row: CategoryRow): Int
  @Insert abstract suspend fun insertTag(row: TagRow)
  @Update abstract suspend fun updateTag(row: TagRow): Int
  @Insert abstract suspend fun insertProduct(row: ProductRow)
  @Update abstract suspend fun updateProduct(row: ProductRow): Int
  @Insert abstract suspend fun insertStoreItem(row: StoreItemRow)
  @Update abstract suspend fun updateStoreItem(row: StoreItemRow): Int
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putIssuer(row: IssuerRow)
  // REPLACE is safe here: nothing references these rows.
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putParsed(row: ReceiptParsedRow)
  @Insert abstract suspend fun insertLines(rows: List<ReceiptLineRow>)
  @Insert abstract suspend fun insertProductTags(rows: List<ProductTagRow>)

  @Query("DELETE FROM receipt_line WHERE receipt = :chave") abstract suspend fun deleteLines(chave: String)
  @Query("DELETE FROM receipt_parsed WHERE chave = :chave") abstract suspend fun deleteParsed(chave: String)
  @Query("DELETE FROM product_tag") abstract suspend fun deleteProductTags()
  @Query("DELETE FROM store_item WHERE id IN (:ids)") abstract suspend fun deleteStoreItems(ids: List<String>)
  @Query("DELETE FROM product WHERE id IN (:ids)") abstract suspend fun deleteProducts(ids: List<String>)
  @Query("DELETE FROM category WHERE id IN (:ids)") abstract suspend fun deleteCategories(ids: List<String>)
  @Query("DELETE FROM tag WHERE id IN (:ids)") abstract suspend fun deleteTags(ids: List<String>)
  @Query("DELETE FROM issuer WHERE cnpj NOT IN (SELECT issuerCnpj FROM receipt_parsed WHERE issuerCnpj IS NOT NULL)")
  abstract suspend fun deleteUnusedIssuers()

  /**
   * Net spending per month (store-local time, UTC-3 like the server's spending_lines) and category path. A line's
   * product is its own correction, else its store item's product.
   */
  @Query(
    """
    SELECT strftime('%Y-%m', rp.issuedAt / 1000, 'unixepoch', '-3 hours') AS month,
           COALESCE(c.seedPath, '') AS category,
           SUM(l.grossCents - l.discountCents) AS netCents
    FROM receipt_line l
    JOIN receipt_parsed rp ON rp.chave = l.receipt
    JOIN store_item s ON s.id = l.storeItemId
    JOIN product p ON p.id = COALESCE(l.productOverrideId, s.productId)
    LEFT JOIN category c ON c.id = p.categoryId
    GROUP BY month, category
    ORDER BY month, category
    """,
  )
  abstract suspend fun monthlyTotals(): List<MonthlyCategoryTotal>
}
