package io.github.sandroroque.getnf.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.MainActivity
import io.github.sandroroque.getnf.R
import java.util.concurrent.TimeUnit

/**
 * A reminder, some hours after a scan whose page was left for later (D99): "1 nota esperando a página da SEFAZ".
 * Nothing if every page has been fetched meanwhile, the person is testing the capture, or notifications aren't allowed.
 */
class PageReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as GetNFApp
    if (app.devSettings.testingCapture) return Result.success()
    app.receiptStore.load()
    val waiting = app.receiptStore.receipts.value.count { it.pageFile == null }
    if (waiting == 0) return Result.success()
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
    val manager = app.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Páginas da SEFAZ", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE)
    val title = if (waiting == 1) "1 nota esperando a página da SEFAZ" else "$waiting notas esperando a página da SEFAZ"
    val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title)
      .setContentText("Toque para buscar. As fotos já estão salvas.").setContentIntent(open).setAutoCancel(true).build()
    NotificationManagerCompat.from(app).notify(NOTIFICATION, notification)
    return Result.success()
  }

  companion object {
    private const val CHANNEL = "pages"
    private const val NOTIFICATION = 1
    private const val NAME = "page-reminder"
    const val DELAY_HOURS = 3L

    /** In [DELAY_HOURS]; a later scan left for later moves it on. */
    fun schedule(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<PageReminderWorker>().setInitialDelay(DELAY_HOURS, TimeUnit.HOURS).build())
    }
  }
}
