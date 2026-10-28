package io.github.sandroroque.getnf.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

/**
 * What SyncEngine needs from the local database (D70): rows waiting to be pushed, single rows for the "unchanged
 * since pushed" check, writes for pulled rows, and the remapping of seeded categories and tags to the server's ids.
 * Writes insert or else update, never REPLACE: a REPLACE deletes first, and the foreign keys would cascade or null
 * out. (Not Room's @Upsert either: it can't run on the JVM tests' bundled SQLite.) An insert ignored for another
 * unique index than the key updates nothing, so callers free a seed path or slug before taking it.
 */
/**
 * A row the server refused (a 4xx, D104), as it was sent. It isn't sent again until it changes, so one bad row
 * neither fails every sync nor is retried forever; the error shows in Ajustes.
 */
@Entity(tableName = "sync_parked", primaryKeys = ["tableName", "rowKey"])
data class SyncParkedRow(val tableName: String, val rowKey: String, val payload: String, val error: String, val at: Long)

/** Who scanned a receipt (D104), pulled from the caderneta; a receipt can have several scanners. */
@Entity(tableName = "receipt_scan", primaryKeys = ["chave", "userId"])
data class ReceiptScanRow(val chave: String, val userId: String)

/** The open caderneta's members as they last synced, for naming who scanned what ("por Ana", D104). */
@Entity(tableName = "member")
data class MemberRow(@androidx.room.PrimaryKey val userId: String, val name: String, val you: Boolean)

@Dao
abstract class SyncDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putScans(rows: List<ReceiptScanRow>)
  @Query("DELETE FROM member") abstract suspend fun clearMembers()
  @Insert abstract suspend fun putMembers(rows: List<MemberRow>)

  @Query("SELECT * FROM sync_parked WHERE tableName = :table") abstract suspend fun parked(table: String): List<SyncParkedRow>
  @Query("SELECT * FROM sync_parked ORDER BY at DESC") abstract suspend fun parked(): List<SyncParkedRow>
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun park(row: SyncParkedRow)
  @Query("DELETE FROM sync_parked WHERE tableName = :table AND rowKey IN (:keys)") abstract suspend fun unpark(table: String, keys: List<String>)

  @Query("SELECT * FROM issuer WHERE dirty = 1") abstract suspend fun dirtyIssuers(): List<IssuerRow>
  @Query("SELECT * FROM receipt_parsed WHERE dirty = 1") abstract suspend fun dirtyParsed(): List<ReceiptParsedRow>
  @Query("SELECT * FROM category WHERE dirty = 1") abstract suspend fun dirtyCategories(): List<CategoryRow>
  @Query("SELECT * FROM tag WHERE dirty = 1") abstract suspend fun dirtyTags(): List<TagRow>
  @Query("SELECT * FROM product WHERE dirty = 1") abstract suspend fun dirtyProducts(): List<ProductRow>
  @Query("SELECT * FROM store_item WHERE dirty = 1") abstract suspend fun dirtyStoreItems(): List<StoreItemRow>
  @Query("SELECT * FROM product_tag WHERE dirty = 1") abstract suspend fun dirtyProductTags(): List<ProductTagRow>
  @Query("SELECT * FROM receipt_line WHERE dirty = 1 ORDER BY receipt, lineNo") abstract suspend fun dirtyLines(): List<ReceiptLineRow>

  @Query("SELECT * FROM issuer WHERE cnpj = :cnpj") abstract suspend fun issuer(cnpj: String): IssuerRow?
  @Query("SELECT * FROM receipt_parsed WHERE chave = :chave") abstract suspend fun parsed(chave: String): ReceiptParsedRow?
  @Query("SELECT * FROM category WHERE id = :id") abstract suspend fun category(id: String): CategoryRow?
  @Query("SELECT * FROM category WHERE seedPath = :path") abstract suspend fun categoryBySeed(path: String): CategoryRow?
  @Query("SELECT * FROM tag WHERE id = :id") abstract suspend fun tag(id: String): TagRow?
  @Query("SELECT * FROM tag WHERE slug = :slug") abstract suspend fun tagBySlug(slug: String): TagRow?
  @Query("SELECT * FROM product WHERE id = :id") abstract suspend fun product(id: String): ProductRow?
  @Query("SELECT * FROM store_item WHERE id = :id") abstract suspend fun storeItem(id: String): StoreItemRow?
  @Query("SELECT * FROM product_tag WHERE productId = :productId AND tagId = :tagId")
  abstract suspend fun productTag(productId: String, tagId: String): ProductTagRow?
  @Query("SELECT * FROM receipt_line WHERE receipt = :chave AND lineNo = :lineNo") abstract suspend fun line(chave: String, lineNo: Int): ReceiptLineRow?
  @Query("SELECT lineNo FROM receipt_line WHERE receipt = :chave") abstract suspend fun lineNumbers(chave: String): List<Int>

  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreIssuer(row: IssuerRow): Long
  @Update protected abstract suspend fun updateIssuer(row: IssuerRow): Int
  open suspend fun upsertIssuer(row: IssuerRow) { if (insertOrIgnoreIssuer(row) == -1L) updateIssuer(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreParsed(row: ReceiptParsedRow): Long
  @Update protected abstract suspend fun updateParsed(row: ReceiptParsedRow): Int
  open suspend fun upsertParsed(row: ReceiptParsedRow) { if (insertOrIgnoreParsed(row) == -1L) updateParsed(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreCategory(row: CategoryRow): Long
  @Update protected abstract suspend fun updateCategory(row: CategoryRow): Int
  open suspend fun upsertCategory(row: CategoryRow) { if (insertOrIgnoreCategory(row) == -1L) updateCategory(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreTag(row: TagRow): Long
  @Update protected abstract suspend fun updateTag(row: TagRow): Int
  open suspend fun upsertTag(row: TagRow) { if (insertOrIgnoreTag(row) == -1L) updateTag(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreProduct(row: ProductRow): Long
  @Update protected abstract suspend fun updateProduct(row: ProductRow): Int
  open suspend fun upsertProduct(row: ProductRow) { if (insertOrIgnoreProduct(row) == -1L) updateProduct(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreStoreItem(row: StoreItemRow): Long
  @Update protected abstract suspend fun updateStoreItem(row: StoreItemRow): Int
  open suspend fun upsertStoreItem(row: StoreItemRow) { if (insertOrIgnoreStoreItem(row) == -1L) updateStoreItem(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreProductTag(row: ProductTagRow): Long
  @Update protected abstract suspend fun updateProductTag(row: ProductTagRow): Int
  open suspend fun upsertProductTag(row: ProductTagRow) { if (insertOrIgnoreProductTag(row) == -1L) updateProductTag(row) }
  @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertOrIgnoreLine(row: ReceiptLineRow): Long
  @Update protected abstract suspend fun updateLine(row: ReceiptLineRow): Int
  open suspend fun upsertLine(row: ReceiptLineRow) { if (insertOrIgnoreLine(row) == -1L) updateLine(row) }

  // A seeded category or tag the phone knew under its own id takes the server's: references move, the old row goes.
  @Query("UPDATE category SET seedPath = NULL WHERE id = :id") abstract suspend fun releaseSeedPath(id: String)
  @Query("UPDATE product SET categoryId = :to WHERE categoryId = :from") abstract suspend fun moveProductsToCategory(from: String, to: String)
  @Query("UPDATE category SET parentId = :to WHERE parentId = :from") abstract suspend fun moveChildren(from: String, to: String)
  @Query("DELETE FROM category WHERE id = :id") abstract suspend fun deleteCategory(id: String)
  @Query("UPDATE tag SET slug = '#' || id WHERE id = :id") abstract suspend fun releaseSlug(id: String)
  @Query("UPDATE product_tag SET tagId = :to WHERE tagId = :from") abstract suspend fun moveProductTags(from: String, to: String)
  @Query("DELETE FROM tag WHERE id = :id") abstract suspend fun deleteTag(id: String)

  @Query("SELECT * FROM link_suggestion WHERE id = :id") abstract suspend fun linkSuggestion(id: String): LinkSuggestionRow?
  @Query("SELECT * FROM link_suggestion WHERE dirty = 1") abstract suspend fun dirtyLinkSuggestions(): List<LinkSuggestionRow>
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putLinkSuggestion(row: LinkSuggestionRow)
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putWorkerReport(row: WorkerReportRow)

  @Query("SELECT value FROM sync_state WHERE name = :name") abstract suspend fun state(name: String): String?
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putState(row: SyncStateRow)
}
