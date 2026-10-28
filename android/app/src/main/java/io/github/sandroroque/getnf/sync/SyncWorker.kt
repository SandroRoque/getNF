package io.github.sandroroque.getnf.sync

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
 * Pushes the phone's changes to Supabase and pulls the server's (D70), then parses any receipt that arrived with a
 * page. WorkManager runs it when there's a network and retries with backoff when the server can't be reached, also
 * across reboots. Without a signed-in account it does nothing: the phone works on its own.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val app = applicationContext as GetNFApp
    val store = app.receiptStore
    store.load()
    val project = app.accountSettings.project()
    val account = app.accountSettings.account()
    if (!project.isComplete || account == null) return@withContext Result.success()

    val client = SupabaseClient(project.url, project.key)
    try {
      val session = client.refresh(account.refreshToken).also { app.accountSettings.signedIn(it) }
      val open = app.accountSettings.ledgerId()
      val mine = Ledgers.mine(client, session)
      // The phone's files belong to the open caderneta: never push them into another (D104). One the person no longer
      // belongs to (they left, or another person signed in) waits for them to open another in Ajustes.
      val ledger = if (open == null) Ledgers.pick(mine, null, session.userId)?.also { app.accountSettings.openLedger(it) }
        else open.takeIf { mine.any { m -> m.id == open } }
      if (ledger != null) {
        val remote = SupabaseRemote(client, session, ledger)
        val report = app.syncEngine.sync(remote)
        report.errors.forEach { Log.w(TAG, it) }
        // Receipts adopted from the server came with their page: read it on the phone too (D49), and push the result.
        if (app.receiptParser.parsePending(store.receipts.value) > 0) app.syncEngine.sync(remote)
      }
      // Then the other cadernetas this phone holds, each from its own files.
      OtherLedgers(app, app.accountSettings, app.photoArchiver).sync(client, session, mine, app.accountSettings.ledgerId())
      Result.success()
    } catch (e: IOException) {
      Log.w(TAG, "server unreachable, will retry", e)
      Result.retry()
    } catch (e: SupabaseException) {
      // A refresh token that no longer works (signed out elsewhere, or expired): ask for a new sign-in.
      if (e.status == 400 || e.status == 401) app.accountSettings.signOut()
      Log.w(TAG, "sync refused", e)
      Result.failure()
    }
  }

  companion object {
    private const val TAG = "SyncWorker"

    fun enqueue(context: Context) {
      val request =
        OneTimeWorkRequestBuilder<SyncWorker>()
          .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
          .build()
      // REPLACE: start now, even if an earlier run is waiting out a retry backoff. Cancelling a run mid-sync is
      // harmless: pushes are idempotent upserts and the next run picks up everything still dirty.
      WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, request)
    }
  }
}
