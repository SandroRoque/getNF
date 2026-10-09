package io.github.sandroroque.getnf.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sandroroque.getnf.GetNFApp
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads every receipt with local changes. WorkManager runs it when there's a network and retries
 * with backoff if the server can't be reached, also across reboots.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val app = applicationContext as GetNFApp
    val store = app.receiptStore
    val config = app.serverSettings.get()
    store.load()
    val pending = store.receipts.value.filter { it.needsUpload }
    if (pending.isEmpty()) return@withContext Result.success()
    if (!config.isComplete) {
      pending.forEach { store.markUploadError(it.chave, "Server not configured") }
      return@withContext Result.failure()
    }

    val client = app.pocketBase
    try {
      val token = client.login(config)
      for (receipt in pending) {
        try {
          val id = client.upsert(config, token, receipt, store.dir(receipt.chave))
          store.markUploaded(receipt.chave, receipt.version, id)
          Log.i(TAG, "uploaded ${receipt.chave} v${receipt.version} as $id")
        } catch (e: PocketBaseException) {
          // Bad data for this one receipt: record it and carry on with the rest.
          Log.w(TAG, "rejected ${receipt.chave}", e)
          store.markUploadError(receipt.chave, e.message ?: "rejected")
        }
      }
      Result.success()
    } catch (e: IOException) {
      Log.w(TAG, "server unreachable, will retry", e)
      pending.forEach { store.markUploadError(it.chave, "Server unreachable, will retry") }
      Result.retry()
    } catch (e: PocketBaseException) {
      Log.w(TAG, "login failed", e)
      pending.forEach { store.markUploadError(it.chave, "Login failed: ${e.message}") }
      Result.failure()
    }
  }

  companion object {
    private const val TAG = "UploadWorker"

    fun enqueue(context: Context) {
      val request =
        OneTimeWorkRequestBuilder<UploadWorker>()
          .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
          .build()
      // REPLACE: start now, even if an earlier run is waiting out a retry backoff (APPEND would queue behind it for
      // minutes). Cancelling a run mid-upload is harmless: uploads are idempotent and the new run picks up everything.
      WorkManager.getInstance(context).enqueueUniqueWork("upload", ExistingWorkPolicy.REPLACE, request)
    }
  }
}
