package io.github.sandroroque.getnf.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.room.useWriterConnection
import io.github.sandroroque.getnf.BuildConfig
import java.io.File
import kotlin.system.exitProcess
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
) {
  private val json = Json { ignoreUnknownKeys = true }
  val pending = PendingRestore(filesDir, dbFile)

  /** Database, photos, masks and pages, consistent with each other. Not server credentials or open captures. */
  suspend fun export(target: Uri, progress: (Int, Int) -> Unit): BackupManifest = withContext(Dispatchers.IO) {
    val snapshot = File(context.cacheDir, "backup-db.tmp")
    try {
      // Holding the writer connection, nothing can be written between the checkpoint, the copy and the file list.
      val (files, counts) = database.useWriterConnection { connection ->
        val busy = connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step(); it.getLong(0) }
        if (busy != 0L) throw BackupException("database busy, try again")
        dbFile.copyTo(snapshot, overwrite = true)
        val receipts = database.receipts().receipts()
        val photos = database.receipts().photos()
        val files = sortedMapOf<String, File>()
        for (r in receipts) r.pageFile?.let { files["receipts/${r.chave}/$it"] = File(receiptsRoot, "${r.chave}/$it") }
        for (p in photos) {
          files["receipts/${p.receipt}/${p.localName}"] = File(receiptsRoot, "${p.receipt}/${p.localName}")
          p.segment?.let { json.decodeFromString<CaptureSegment>(it).mask }
            ?.let { files["receipts/${p.receipt}/$it"] = File(receiptsRoot, "${p.receipt}/$it") }
        }
        files to listOf(receipts.size, photos.size, database.catalog().lines().size, database.catalog().products().size)
      }
      val present = linkedMapOf(BackupArchive.DATABASE to snapshot) + files.filterValues { it.isFile }
      context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
        BackupArchive.write(out, present, progress) { hashes ->
          BackupManifest(
            appVersion = BuildConfig.VERSION_NAME, schemaVersion = ReceiptDatabase.VERSION, createdAt = System.currentTimeMillis(),
            receipts = counts[0], photos = counts[1], lines = counts[2], products = counts[3], files = hashes,
          )
        }
      }
    } finally {
      snapshot.delete()
    }
  }

  /** Copies the picked file locally and checks all of it. Nothing on the phone changes. */
  suspend fun inspect(source: Uri): BackupManifest = withContext(Dispatchers.IO) {
    val local = File(context.cacheDir, LOCAL_COPY)
    context.contentResolver.openInputStream(source)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
    BackupArchive.inspect(local, ReceiptDatabase.VERSION)
  }

  /** Extracts the inspected backup where the next start swaps it in ([PendingRestore]). */
  suspend fun stage() = withContext(Dispatchers.IO) {
    val local = File(context.cacheDir, LOCAL_COPY)
    BackupArchive.extract(local, pending.staged, ReceiptDatabase.VERSION)
    pending.markReady()
    local.delete()
  }

  /** Stages the inspected backup and restarts the app; GetNFApp swaps it in before opening the database. */
  suspend fun restoreAndRestart() {
    stage()
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    val intent = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
    context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 500, intent)
    exitProcess(0)
  }

  fun discardInspected() { File(context.cacheDir, LOCAL_COPY).delete() }

  private companion object { const val LOCAL_COPY = "restore.zip" }
}
