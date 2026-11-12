package io.github.sandroroque.getnf.data

import android.content.Context
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.RestartActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The demo caderneta (D115): the made-up household of D105, bundled as `assets/demo.zip` (an ordinary backup) and
 * opened in files of its own, so it never mixes with the user's receipts and never syncs. Each visit starts from the
 * bundled copy; leaving deletes it.
 */
object Demo {
  private const val ASSET = "demo.zip"

  /**
   * Stages the bundled backup into the demo's files and restarts into it. Never returns. Not cancellable (D184): the
   * caller's scope is a screen's, which rotation or Back cancels, and stopping between the marker and the flag left a
   * restore the next start would apply. The marker names the demo's database, so even then it can't land elsewhere.
   */
  suspend fun enter(context: Context): Nothing {
    val app = context.applicationContext as GetNFApp
    withContext(NonCancellable + Dispatchers.IO) {
      val zip = File(app.cacheDir, ASSET)
      app.assets.open(ASSET).use { input -> zip.outputStream().use { input.copyTo(it) } }
      val pending = pendingRestore(app)
      pending.clear()
      BackupArchive.extract(zip, pending.staged, ReceiptDatabase.VERSION)
      zip.delete()
      // The flag first: a start in the demo with nothing staged opens an empty demo, never the user's files.
      app.accountSettings.demo = true
      pending.markReady()
    }
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
