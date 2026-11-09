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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends the closed test's photos and pages (D161) on Wi-Fi, so a tester's mobile data isn't spent on them, retrying
 * with backoff, across reboots too. Taking them back (the person turned it off) needs any network. No account needed.
 */
class ContributionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val app = applicationContext as GetNFApp
    val project = app.accountSettings.project()
    if (!project.isComplete) return@withContext Result.success()
    val client = SupabaseClient(project.url, project.key)
    val contribution = app.testContribution
    try {
      if (contribution.withdrawPending) {
        client.contribute(mapOf("install" to contribution.installId(), "withdraw" to "1"), ByteArray(0).toRequestBody())
        contribution.withdrawn()
        Log.i(TAG, "took back what was sent")
      }
      if (contribution.active) {
        val sent = contribution.send(app.photoArchiver, onRefused = { Log.w(TAG, "refused, dropped: $it") }) { query, file, type ->
          client.contribute(query, file.asRequestBody(type.toMediaType()))
        }
        if (sent > 0) Log.i(TAG, "sent $sent file(s)")
      }
      Result.success()
    } catch (e: IOException) {
      Log.w(TAG, "server unreachable or over a limit, will retry", e)
      Result.retry()
    } catch (e: SupabaseException) {
      Log.w(TAG, "withdrawal refused", e)
      Result.success()
    }
  }

  companion object {
    private const val TAG = "Contribution"

    fun enqueue(context: Context) {
      val app = context.applicationContext as GetNFApp
      val withdrawing = app.testContribution.withdrawPending
      val request = OneTimeWorkRequestBuilder<ContributionWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (withdrawing) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
      // Taking back replaces a run waiting for Wi-Fi; otherwise a new item waits for the run that's sending.
      val policy = if (withdrawing) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
      WorkManager.getInstance(context).enqueueUniqueWork("contribution", policy, request)
    }
  }
}
