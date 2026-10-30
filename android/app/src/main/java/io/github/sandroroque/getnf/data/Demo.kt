package io.github.sandroroque.getnf.data

import android.content.Context
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.RestartActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The demo caderneta (D115): the made-up household of D105, bundled as `assets/demo.zip` (an ordinary backup) and
 * opened in files of its own, so it never mixes with the user's receipts and never syncs. Each visit starts from the
 * bundled copy; leaving deletes it.
 */
object Demo {
  private const val ASSET = "demo.zip"

  /** Stages the bundled backup into the demo's files and restarts into it. Never returns. */
  suspend fun enter(context: Context): Nothing {
    val app = context.applicationContext as GetNFApp
    withContext(Dispatchers.IO) {
      val zip = File(app.cacheDir, ASSET)
      app.assets.open(ASSET).use { input -> zip.outputStream().use { input.copyTo(it) } }
      val pending = pendingRestore(app)
      pending.staged.deleteRecursively()
      BackupArchive.extract(zip, pending.staged, ReceiptDatabase.VERSION)
      pending.markReady()
      zip.delete()
    }
    app.accountSettings.demo = true
    RestartActivity.restart(app)
  }

  /** Back to the user's own caderneta. Never returns. */
  fun leave(context: Context): Nothing {
    val app = context.applicationContext as GetNFApp
    app.accountSettings.demo = false
    RestartActivity.restart(app)
  }

  /** Outside the demo its files have no use: removed at start. */
  fun deleteFiles(app: GetNFApp) {
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(app.getDatabasePath(LocalFiles.DEMO.database).path + suffix).delete()
    File(app.filesDir, LocalFiles.DEMO.receipts).deleteRecursively()
  }

  private fun pendingRestore(app: GetNFApp) =
    PendingRestore(app.filesDir, app.getDatabasePath(LocalFiles.DEMO.database), File(app.filesDir, LocalFiles.DEMO.receipts))
}
