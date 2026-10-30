package io.github.sandroroque.getnf

import android.app.Application
import io.github.sandroroque.getnf.data.BackupService
import io.github.sandroroque.getnf.data.LookupBudget
import io.github.sandroroque.getnf.data.PendingRestore
import io.github.sandroroque.getnf.data.ReceiptDatabase
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.PhotoDiscountReader
import io.github.sandroroque.getnf.discounts.PhotoOcr
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.CaptureSessionStore
import io.github.sandroroque.getnf.data.AccountSettings
import io.github.sandroroque.getnf.sync.SyncEngine
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.sync.WebpArchiver
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** App-wide singletons. Small enough that a DI framework isn't worth it yet. */
class GetNFApp : Application() {
  /** The open caderneta's files (D104); another caderneta opens with a restart. */
  val localFiles by lazy { accountSettings.localFiles() }
  val receiptsDir by lazy { File(filesDir, localFiles.receipts) }
  val database by lazy { ReceiptDatabase.open(this, localFiles.database) }
  val receiptStore by lazy { ReceiptStore(receiptsDir, database) }
  val backup by lazy { BackupService(this, database, receiptsDir, getDatabasePath(localFiles.database)) }
  val receiptParser by lazy { ReceiptParser(database, receiptsDir) }
  private val photoOcr by lazy { PhotoOcr(this) }
  val photoDiscounts by lazy { PhotoDiscountReader(database, receiptsDir) { photoOcr.read(it) } }
  val captureSessions by lazy { CaptureSessionStore(File(filesDir, "capture-sessions")) }
  val accountSettings by lazy { AccountSettings(this) }
  val onboarding by lazy { io.github.sandroroque.getnf.data.Onboarding(this) }
  val devSettings by lazy { io.github.sandroroque.getnf.data.DevSettings(this) }
  val catalogEditor by lazy { io.github.sandroroque.getnf.data.CatalogEditor(database) }
  val photoContribution by lazy { io.github.sandroroque.getnf.data.PhotoContribution(this, database) }
  /** Photos' online copies, with what's private blanked (D61, D98). */
  val photoArchiver by lazy { WebpArchiver { io.github.sandroroque.getnf.sync.SensitiveText.blanks(photoOcr.read(it)) } }
  val syncEngine by lazy { SyncEngine(database, receiptStore, photoArchiver, contribute = photoContribution::wants) }
  val lookupBudget by lazy {
    val prefs = getSharedPreferences("sefaz", MODE_PRIVATE)
    LookupBudget(load = { prefs.getString("lookups", "") ?: "" }, save = { prefs.edit().putString("lookups", it).apply() })
  }

  override fun onCreate() {
    super.onCreate()
    // RestartActivity's process only relaunches the app: it must not swap a restore or open the database.
    if (processName().endsWith(RestartActivity.PROCESS_SUFFIX)) return
    // Back from the demo (D115): its files go.
    if (!accountSettings.demo) io.github.sandroroque.getnf.data.Demo.deleteFiles(this)
    // A restore staged by Settings is swapped in before anything opens the database (D47).
    val restore = PendingRestore(filesDir, getDatabasePath(localFiles.database), receiptsDir)
    // A failure leaves it staged: the next start finishes it (each step skips what's done) instead of crash-looping.
    runCatching { if (restore.applyIfReady()) android.util.Log.i("Backup", "restored a backup") }
      .onFailure { android.util.Log.e("Backup", "restore swap failed; will retry on the next start", it) }
    MainScope().launch {
      val deferred = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captureSessions.recover(receiptStore) }
      if (deferred > 0) android.util.Log.w("ReceiptCapture", "$deferred capture batch(es) await recovery")
      receiptStore.load()
      restore.cleanUp()
      // Pages saved before D49, or read by an older parser (D49).
      receiptParser.parsePending(receiptStore.receipts.value).let { if (it > 0) android.util.Log.i("ReceiptParser", "parsed $it saved page(s)") }
      // Receipts waiting for their page are named by what their photos say (D99); older ones are read once here.
      runCatching { receiptStore.readPendingPhotos { photoOcr.read(it) } }.onFailure { android.util.Log.w("ReceiptStore", "reading pending photos", it) }
      // Discounts the photos show, not yet read from them (D101).
      for (r in receiptStore.receipts.value) runCatching { photoDiscounts.readIfPending(r) }
        .onSuccess { o -> if (o != null) android.util.Log.i("PhotoDiscounts", "${r.chave}: ${o.result.verdict}, ${o.result.discounts.size} found") }
        .onFailure { android.util.Log.w("PhotoDiscounts", "reading ${r.chave}", it) }
      // Push anything left over from a previous run (e.g. the app was killed mid-sync) and pull the server's changes
      // (D70). Not under instrumented tests: debug builds point at the real project.
      if (!underInstrumentation() && !accountSettings.demo) SyncWorker.enqueue(this@GetNFApp)
    }
  }

  private fun processName() =
    if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
    else File("/proc/self/cmdline").readText().substringBefore('\u0000')

  private fun underInstrumentation() =
    runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
}
