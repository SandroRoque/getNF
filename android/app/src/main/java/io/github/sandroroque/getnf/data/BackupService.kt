package io.github.sandroroque.getnf.data

import android.content.Context
import android.net.Uri
import androidx.room.useWriterConnection
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.RestartActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Device side of D47: exports to and restores from a file the user picks (Storage Access Framework). */
class BackupService(
  private val context: Context,
  private val database: ReceiptDatabase,
  private val receiptsRoot: File,
  private val dbFile: File = context.getDatabasePath(ReceiptDatabase.NAME),
  filesDir: File = context.filesDir,
  /** Its lock holds pages still while the snapshot is taken (T-19); null only where nothing else writes. */
  private val store: ReceiptStore? = null,
) {
  private val json = Json { ignoreUnknownKeys = true }
  val pending = PendingRestore(filesDir, dbFile, receiptsRoot)

  /**
   * Database, photos, masks and pages, consistent with each other. Not server credentials or open captures. A file
   * the database names but the phone doesn't have goes in the manifest's `missing` (T-18), never silently left out.
   */
  suspend fun export(target: Uri, progress: (Int, Int) -> Unit): BackupManifest = withContext(Dispatchers.IO) {
    val snapshot = File(context.cacheDir, "backup-db.tmp")
    val pages = File(context.cacheDir, "backup-pages").apply { deleteRecursively() }
    try {
      // Holding the store's lock and the writer connection, nothing can be written between the checkpoint, the copy
      // and the file list. Pages are the one file rewritten in place (a page fetched again): copied here too, so the
      // archive has the page the copied database names (T-19). Photos and masks are never rewritten.
      val (files, counts) = holding { database.useWriterConnection { connection ->
        val busy = connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step(); it.getLong(0) }
        if (busy != 0L) throw BackupException("database busy, try again")
        dbFile.copyTo(snapshot, overwrite = true)
        val receipts = database.receipts().receipts()
        val photos = database.receipts().photos()
        val files = sortedMapOf<String, File>()
        for (r in receipts) r.pageFile?.let { name ->
          val live = File(receiptsRoot, "${r.chave}/$name")
          files["receipts/${r.chave}/$name"] = if (live.isFile) live.copyTo(File(pages, "${r.chave}/$name")) else live
        }
        for (p in photos) {
          files["receipts/${p.receipt}/${p.localName}"] = File(receiptsRoot, "${p.receipt}/${p.localName}")
          p.segment?.let { json.decodeFromString<CaptureSegment>(it).mask }
            ?.let { files["receipts/${p.receipt}/$it"] = File(receiptsRoot, "${p.receipt}/$it") }
        }
        files to listOf(receipts.size, photos.size, database.catalog().lines().size, database.catalog().products().size)
      } }
      val present = linkedMapOf(BackupArchive.DATABASE to snapshot) + files.filterValues { it.isFile }
      val missing = files.filterValues { !it.isFile }.keys.sorted()
      context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
        BackupArchive.write(out, present, progress) { hashes ->
          BackupManifest(
            appVersion = BuildConfig.VERSION_NAME, schemaVersion = ReceiptDatabase.VERSION, createdAt = System.currentTimeMillis(),
            receipts = counts[0], photos = counts[1], lines = counts[2], products = counts[3], files = hashes, missing = missing,
          )
        }
      }
    } finally {
      snapshot.delete()
      pages.deleteRecursively()
    }
  }

  private suspend fun <T> holding(block: suspend () -> T): T = if (store != null) store.locked(block) else block()

  /** Copies the picked file locally and checks all of it. Nothing on the phone changes. */
  suspend fun inspect(source: Uri): BackupManifest = withContext(Dispatchers.IO) {
    val local = File(context.cacheDir, LOCAL_COPY)
    context.contentResolver.openInputStream(source)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
    BackupArchive.inspect(local, ReceiptDatabase.VERSION)
  }

  /** Extracts the inspected backup where the next start swaps it in ([PendingRestore]). */
  suspend fun stage() = withContext(Dispatchers.IO) {
    val local = File(context.cacheDir, LOCAL_COPY)
    pending.clear()
    BackupArchive.extract(local, pending.staged, ReceiptDatabase.VERSION)
    pending.markReady()
    local.delete()
  }

  /** Stages the inspected backup and restarts the app; GetNFApp swaps it in before opening the database. */
  suspend fun restoreAndRestart() {
    stage()
    RestartActivity.restart(context)
  }

  fun discardInspected() { File(context.cacheDir, LOCAL_COPY).delete() }

  private companion object { const val LOCAL_COPY = "restore.zip" }
}
