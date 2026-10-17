package io.github.sandroroque.getnf.data

import android.content.Context
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

/**
 * Local database (D45, schema in docs/data-model.md). Stage 2a holds receipts and their photo evidence; photos and
 * pages stay files in `receipts/<chave>/`, rows only name them.
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
)

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

@Database(entities = [ReceiptRow::class, PhotoRow::class, CaptureSessionRow::class], version = 1)
abstract class ReceiptDatabase : RoomDatabase() {
  abstract fun receipts(): ReceiptDao

  companion object {
    const val NAME = "getnf.db"
    fun open(context: Context): ReceiptDatabase = Room.databaseBuilder(context, ReceiptDatabase::class.java, NAME).build()
  }
}
