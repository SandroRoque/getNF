package io.github.sandroroque.getnf

import android.app.Application
import io.github.sandroroque.getnf.data.BackupService
import io.github.sandroroque.getnf.data.LookupBudget
import io.github.sandroroque.getnf.data.PendingRestore
import io.github.sandroroque.getnf.data.ReceiptDatabase
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.CaptureSessionStore
import io.github.sandroroque.getnf.data.ServerMirror
import io.github.sandroroque.getnf.data.ServerSettings
import io.github.sandroroque.getnf.upload.PocketBaseClient
import io.github.sandroroque.getnf.upload.UploadWorker
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** App-wide singletons. Small enough that a DI framework isn't worth it yet. */
class GetNFApp : Application() {
  val database by lazy { ReceiptDatabase.open(this) }
  val receiptStore by lazy { ReceiptStore(File(filesDir, "receipts"), database) }
  val backup by lazy { BackupService(this, database, File(filesDir, "receipts")) }
  val serverMirror by lazy { ServerMirror(database) }
  val captureSessions by lazy { CaptureSessionStore(File(filesDir, "capture-sessions")) }
  val serverSettings by lazy { ServerSettings(this) }
  val pocketBase by lazy { PocketBaseClient() }
  val lookupBudget by lazy {
    val prefs = getSharedPreferences("sefaz", MODE_PRIVATE)
    LookupBudget(load = { prefs.getString("lookups", "") ?: "" }, save = { prefs.edit().putString("lookups", it).apply() })
  }

  override fun onCreate() {
    super.onCreate()
    // A restore staged by Settings is swapped in before anything opens the database (D47).
    val restore = PendingRestore(filesDir, getDatabasePath(ReceiptDatabase.NAME))
    // A failure leaves it staged: the next start finishes it (each step skips what's done) instead of crash-looping.
    runCatching { if (restore.applyIfReady()) android.util.Log.i("Backup", "restored a backup") }
      .onFailure { android.util.Log.e("Backup", "restore swap failed; will retry on the next start", it) }
    MainScope().launch {
      val deferred = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captureSessions.recover(receiptStore) }
      if (deferred > 0) android.util.Log.w("ReceiptCapture", "$deferred capture batch(es) await recovery")
      receiptStore.load()
      restore.cleanUp()
      // Catch anything left over from a previous run (e.g. the app was killed mid-upload), and refresh the copy of
      // the server's lines and catalog (D46). Not under instrumented tests: debug builds point at the real server.
      if (!underInstrumentation()) UploadWorker.enqueue(this@GetNFApp)
    }
  }

  private fun underInstrumentation() =
    runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
}
