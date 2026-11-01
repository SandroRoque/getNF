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
 * Sends the reports waiting in the outbox (D152) when there's a network, retrying with backoff, across reboots too.
 * No account needed: the table takes reports from anyone with the app. The demo's reports go too (testers outside
 * Santa Catarina see only the demo, D146).
 */
class FeedbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val app = applicationContext as GetNFApp
    val project = app.accountSettings.project()
    // A build without a server keeps them; the share sheet is still there.
    if (!project.isComplete) return@withContext Result.success()
    val client = SupabaseClient(project.url, project.key)
    try {
      val sent = app.feedback.send(onRefused = { Log.w(TAG, "report refused, dropped: $it") }) { client.insertAnonymous("feedback", it) }
      if (sent > 0) Log.i(TAG, "sent $sent report(s)")
      Result.success()
    } catch (e: IOException) {
      Log.w(TAG, "server unreachable, will retry", e)
      Result.retry()
    }
  }

  companion object {
    private const val TAG = "Feedback"

    fun enqueue(context: Context) {
      val request = OneTimeWorkRequestBuilder<FeedbackWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      // A report added while a run is sending waits for the next run, which APPEND_OR_REPLACE queues after it.
      WorkManager.getInstance(context).enqueueUniqueWork("feedback", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
  }
}
