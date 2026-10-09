package io.github.sandroroque.getnf

import android.app.Application
import io.github.sandroroque.getnf.data.LookupBudget
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.ServerSettings
import io.github.sandroroque.getnf.upload.PocketBaseClient
import io.github.sandroroque.getnf.upload.UploadWorker
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** App-wide singletons. Small enough that a DI framework isn't worth it yet. */
class GetNFApp : Application() {
  val receiptStore by lazy { ReceiptStore(File(filesDir, "receipts")) }
  val serverSettings by lazy { ServerSettings(this) }
  val pocketBase by lazy { PocketBaseClient() }
  val lookupBudget by lazy {
    val prefs = getSharedPreferences("sefaz", MODE_PRIVATE)
    LookupBudget(load = { prefs.getString("lookups", "") ?: "" }, save = { prefs.edit().putString("lookups", it).apply() })
  }

  override fun onCreate() {
    super.onCreate()
    MainScope().launch {
      receiptStore.load()
      // Catch anything left over from a previous run (e.g. the app was killed mid-upload).
      if (receiptStore.receipts.value.any { it.needsUpload }) UploadWorker.enqueue(this@GetNFApp)
    }
  }
}
