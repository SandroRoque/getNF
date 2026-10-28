package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Local database (D45, D46; schema in docs/data-model.md). Receipts and their photo evidence (ReceiptStore), plus the
 * parsed lines and catalog (Catalog.kt). Photos and pages stay files in `receipts/<chave>/`; rows only name them.
 */
@Entity(tableName = "receipt")
data class ReceiptRow(
  @PrimaryKey val chave: String,
  val qrUrl: String?,
  val capturedAt: Long,
  val pageUrl: String?,
  val pageFile: String?,
  val pageFetchedAt: Long?,
  val version: Long,
  val uploadedVersion: Long?,
  val remoteId: String?,
  val uploadError: String?,
  /** Scanned on this phone rather than only adopted from the caderneta (D104). */
  @ColumnInfo(defaultValue = "1") val scannedHere: Boolean = true,
)

/**
 * What a receipt's photos say before its page is fetched (D99): its issue date, read by OCR (local midnight, epoch
 * millis), so a receipt waiting for its page is named by it. Not synced: the page replaces it.
 */
@Entity(tableName = "receipt_photo_read", foreignKeys = [ForeignKey(ReceiptRow::class, ["chave"], ["chave"], onDelete = ForeignKey.CASCADE)])
data class PhotoReadRow(@PrimaryKey val chave: String, val issuedAt: Long?)

@Entity(
  tableName = "receipt_photo",
  primaryKeys = ["receipt", "localName"],
  foreignKeys = [ForeignKey(ReceiptRow::class, ["chave"], ["receipt"], onDelete = ForeignKey.CASCADE)],
)
data class PhotoRow(
  val receipt: String,
  val localName: String,
  /** Order taken, within the receipt. */
  val position: Int,
  /** Confirmed in the server's append-only receipt_photos (D42). */
  val uploaded: Boolean,
  /** [CaptureSegment] JSON for continuous-capture photos (mask name, polygon, bands, session); null for older ones. */
  val segment: String?,
)

@Entity(
  tableName = "capture_session",
  primaryKeys = ["receipt", "sessionId"],
  foreignKeys = [ForeignKey(ReceiptRow::class, ["chave"], ["receipt"], onDelete = ForeignKey.CASCADE)],
  indices = [Index("sessionId")],
)
data class CaptureSessionRow(val receipt: String, val sessionId: String, val position: Int)

@Dao
abstract class ReceiptDao {
  @Query("SELECT * FROM receipt") abstract suspend fun receipts(): List<ReceiptRow>
  @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putPhotoRead(row: PhotoReadRow)
  @Query("SELECT chave FROM receipt_photo_read") abstract suspend fun photoReadChaves(): List<String>
  @Query("SELECT * FROM receipt_photo ORDER BY receipt, position") abstract suspend fun photos(): List<PhotoRow>
  @Query("SELECT * FROM capture_session ORDER BY receipt, position") abstract suspend fun sessions(): List<CaptureSessionRow>

  @Query("SELECT * FROM receipt WHERE chave = :chave") abstract suspend fun receipt(chave: String): ReceiptRow?
  @Query("SELECT * FROM receipt_photo WHERE receipt = :chave ORDER BY position") abstract suspend fun photos(chave: String): List<PhotoRow>
  @Query("SELECT * FROM capture_session WHERE receipt = :chave ORDER BY position") abstract suspend fun sessions(chave: String): List<CaptureSessionRow>

  // Not @Upsert or REPLACE: a REPLACE deletes the row first, and the cascade would take its photos with it.
  @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insert(receipt: ReceiptRow)
  @Update abstract suspend fun update(receipt: ReceiptRow): Int
  @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertPhotos(photos: List<PhotoRow>)
  @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSessions(sessions: List<CaptureSessionRow>)
  @Query("UPDATE receipt_photo SET uploaded = 1 WHERE receipt = :chave AND localName = :photo")
  abstract suspend fun markPhotoUploaded(chave: String, photo: String)

  /** One receipt with new photos and sessions, all or nothing. */
  @Transaction
  open suspend fun save(receipt: ReceiptRow, photos: List<PhotoRow> = emptyList(), sessions: List<CaptureSessionRow> = emptyList()) {
    if (update(receipt) == 0) insert(receipt)
    if (photos.isNotEmpty()) insertPhotos(photos)
    if (sessions.isNotEmpty()) insertSessions(sessions)
  }
}

@Database(
  entities = [
    ReceiptRow::class, PhotoRow::class, CaptureSessionRow::class, PhotoReadRow::class,
    ReceiptParsedRow::class, IssuerRow::class, CategoryRow::class, TagRow::class, ProductRow::class, StoreItemRow::class,
    ProductTagRow::class, ReceiptLineRow::class, SyncStateRow::class, CategoryEventRow::class, LinkSuggestionRow::class, WorkerReportRow::class,
    SyncParkedRow::class, ReceiptScanRow::class, MemberRow::class,
  ],
  version = ReceiptDatabase.VERSION,
  // Every version ships a migration from the one before; schemas are exported to android/app/schemas.
  autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
    AutoMigration(from = 7, to = 8, spec = ReceiptDatabase.ReuploadBlanked::class), AutoMigration(from = 8, to = 9, spec = ReceiptDatabase.ReuploadBlanked::class),
    AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11)],
)
abstract class ReceiptDatabase : RoomDatabase() {
  abstract fun receipts(): ReceiptDao
  abstract fun catalog(): CatalogDao
  abstract fun sync(): SyncDao
  abstract fun edit(): EditDao
  abstract fun categoryEvents(): CategoryEventDao
  abstract fun spending(): SpendingDao
  abstract fun lists(): ListsDao

  /**
   * 7 → 8 (D96, D98): payments and taxes on the parsed receipt; and every photo goes up again, its online copy now with
   * the card slip, a CPF and the operator blanked (the upload replaces the copy at the same path). 8 → 9 (D99): again,
   * since 8's blanks were upright boxes that, on receipts photographed sideways, covered far more than the slip.
   */
  class ReuploadBlanked : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) = db.execSQL(REUPLOAD)
    override fun onPostMigrate(connection: SQLiteConnection) = connection.execSQL(REUPLOAD)
  }

  companion object {
    private const val REUPLOAD = "UPDATE `receipt_photo` SET `uploaded` = 0"
    const val NAME = "getnf.db"
    const val VERSION = 11
    /** [name]: each caderneta has its own database on the phone (D104, [LocalFiles]). */
    fun open(context: Context, name: String = NAME): ReceiptDatabase =
      Room.databaseBuilder(context, ReceiptDatabase::class.java, name).addMigrations(MIGRATION_3_4).build()

    /**
     * Supabase replaces PocketBase (D70): everything goes up again, to the new server. Sync columns; receipts and
     * photos count as not uploaded; every row the phone holds is dirty, except categories and tags, which the server
     * seeds itself and the first pull matches by path and slug. Categories that came from PocketBase were set by hand
     * or by an agent: `import`, `automatic` (not confirmed by the user in the app, D69).
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
      // Both entry points: the app's framework SQLite calls the first, JVM tests on the bundled driver the second.
      override fun migrate(db: SupportSQLiteDatabase) = statements3to4().forEach(db::execSQL)
      override fun migrate(connection: SQLiteConnection) = statements3to4().forEach(connection::execSQL)
    }

    private fun statements3to4(): List<String> = buildList {
      for (table in listOf("receipt_parsed", "issuer", "category", "tag", "product", "store_item", "product_tag", "receipt_line"))
        add("ALTER TABLE `$table` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0")
      for (table in listOf("product", "store_item", "product_tag"))
        add("ALTER TABLE `$table` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
      add("ALTER TABLE `product` ADD COLUMN `categorySource` TEXT")
      add("ALTER TABLE `product` ADD COLUMN `categoryReview` TEXT")
      add("ALTER TABLE `product` ADD COLUMN `categoryModel` TEXT")
      add("ALTER TABLE `product` ADD COLUMN `categoryConfidence` REAL")
      add("ALTER TABLE `product` ADD COLUMN `categoryAt` INTEGER")
      add("ALTER TABLE `store_item` ADD COLUMN `linkSource` TEXT NOT NULL DEFAULT 'regex'")
      add("ALTER TABLE `store_item` ADD COLUMN `linkReview` TEXT")
      add("CREATE TABLE IF NOT EXISTS `sync_state` (`name` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`name`))")

      add("UPDATE receipt SET uploadedVersion = NULL, remoteId = NULL, uploadError = NULL")
      add("UPDATE receipt_photo SET uploaded = 0")
      for (table in listOf("receipt_parsed", "issuer", "product", "store_item", "product_tag", "receipt_line"))
        add("UPDATE `$table` SET dirty = 1")
      add("UPDATE product SET categorySource = 'import', categoryReview = 'automatic' WHERE categoryId IS NOT NULL")
    }
  }
}
