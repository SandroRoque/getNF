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
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.ServerConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads every receipt with local changes, then refreshes the read-only copy of the server's lines and catalog
 * (D46). WorkManager runs it when there's a network and retries with backoff if the server can't be reached, also
 * across reboots.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val app = applicationContext as GetNFApp
    val store = app.receiptStore
    val config = app.serverSettings.get()
    store.load()
    if (!config.isComplete) {
      val pending = store.receipts.value.filter { it.needsUpload }
      pending.forEach { store.markUploadError(it.chave, "Server not configured") }
      return@withContext if (pending.isEmpty()) Result.success() else Result.failure()
    }

    val client = app.pocketBase
    try {
      val token = client.login(config)
      upload(client, config, token, store)
      try {
        refreshMirror(app, client, config, token)
        // Receipts adopted from the server bring legacy photos still missing from receipt_photos.
        upload(client, config, token, store)
      } catch (e: Exception) {
        // The copy is only for reading during stage 2; it's refreshed again on the next run.
        Log.w(TAG, "server mirror refresh failed", e)
      }
      Result.success()
    } catch (e: IOException) {
      Log.w(TAG, "server unreachable, will retry", e)
      store.receipts.value.filter { it.needsUpload }.forEach { store.markUploadError(it.chave, "Server unreachable, will retry") }
      Result.retry()
    } catch (e: PocketBaseException) {
      Log.w(TAG, "login failed", e)
      store.receipts.value.filter { it.needsUpload }.forEach { store.markUploadError(it.chave, "Login failed: ${e.message}") }
      Result.failure()
    }
  }

  private suspend fun upload(client: PocketBaseClient, config: ServerConfig, token: String, store: ReceiptStore) {
    for (receipt in store.receipts.value.filter { it.needsUpload }) {
      try {
        // Only new photos: the receipt record is already current, so don't PATCH it (and its page) again.
        val id = if (receipt.remoteId != null && receipt.uploadedVersion == receipt.version) receipt.remoteId
          else client.upsert(config, token, receipt, store.dir(receipt.chave)).also { store.markUploaded(receipt.chave, receipt.version, it) }
        // Each photo is confirmed on its own, so an interrupted run resumes where it stopped (D42).
        for (photo in receipt.pendingPhotos) {
          client.uploadPhoto(config, token, id, store.dir(receipt.chave), photo, receipt.captureSegments.find { it.photo == photo })
          store.markPhotoUploaded(receipt.chave, photo)
        }
        Log.i(TAG, "uploaded receipt v${receipt.version} as $id with ${receipt.pendingPhotos.size} new photo(s)")
      } catch (e: PocketBaseException) {
        // Bad data for this one receipt: record it and carry on with the rest.
        Log.w(TAG, "rejected ${receipt.chave}", e)
        store.markUploadError(receipt.chave, e.message ?: "rejected")
      }
    }
  }

  /** Copies the server's lines and catalog; first adopts receipts only the server has, with their page and photos. */
  private suspend fun refreshMirror(app: GetNFApp, client: PocketBaseClient, config: ServerConfig, token: String) {
    val store = app.receiptStore
    val snapshot = client.fetchSnapshot(config, token)
    val known = store.receipts.value.map { it.chave }.toSet()
    for (r in snapshot.receipts.filter { it.chave !in known }) {
      val dir = store.dir(r.chave).apply { mkdirs() }
      r.page?.let { client.download(config, token, "receipts", r.id, it, File(dir, ReceiptStore.PAGE_FILE)) }
      // Legacy photos only for a receipt with no receipt_photos yet: the others' evidence is already there under the
      // phone's names, and sending these too would duplicate it.
      val legacy = if (r.evidencePhotos == 0) r.photos else emptyList()
      for (photo in legacy) File(dir, photo).takeUnless { it.isFile }?.let { client.download(config, token, "receipts", r.id, photo, it) }
      store.adoptRemote(
        Receipt(
          chave = r.chave, qrUrl = r.qrUrl, capturedAt = r.capturedAt ?: System.currentTimeMillis(), pageUrl = r.pageUrl,
          pageFile = r.page?.let { ReceiptStore.PAGE_FILE }, pageFetchedAt = r.pageFetchedAt, version = 1, uploadedVersion = 1,
          remoteId = r.id,
        ),
        legacy,
      )
      Log.i(TAG, "adopted a receipt only the server had, with ${legacy.size} legacy photo(s)")
    }
    val lines = app.serverMirror.apply(snapshot)
    Log.i(TAG, "mirrored ${snapshot.products.size} products, ${snapshot.receipts.size} receipts, $lines lines")
    // Adopted receipts came with their page: read it on the phone too (D49).
    app.receiptParser.parsePending(store.receipts.value)
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
