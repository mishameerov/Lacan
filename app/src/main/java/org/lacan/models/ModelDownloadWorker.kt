package org.lacan.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.lacan.MainActivity
import java.io.IOException

/** WorkManager owns the wake lock, connectivity recovery, and process/reboot rescheduling. */
class ModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val downloads = ModelDownloads.get(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    override suspend fun doWork(): Result {
        try {
            while (true) {
                val id = downloads.next() ?: return Result.success()
                val spec = ModelCatalog.models.single { it.id == id }
                try {
                    setForeground(ForegroundInfo(NOTIFICATION, notification(spec, downloads.state.value.progress[id] ?: 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                    var notifiedPercent = -1
                    ModelRepository(applicationContext).download(spec, onProgress = { progress ->
                        downloads.progress(id, progress)
                        val percent = (progress * 100).toInt()
                        if (percent != notifiedPercent) {
                            notifications.notify(NOTIFICATION, notification(spec, progress))
                            notifiedPercent = percent
                        }
                    }, checkActive = { if (!downloads.contains(id)) throw DownloadRemoved() })
                    downloads.complete(id)
                } catch (_: DownloadRemoved) {
                    // Cancellation removes only this queue item; keep its staging bytes for a later retry.
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    val retry = failure is IOException && (failure !is ModelDownloadException || failure.retryable)
                    downloads.failed(id, failure.message ?: "Не удалось скачать модель", retry)
                    if (retry) return Result.retry()
                }
            }
        } finally { downloads.stopped() }
    }
    private fun notification(spec: ModelSpec, progress: Float): Notification {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Скачивание моделей для распознавания на устройстве"; setSound(null, null)
        })
        val open = PendingIntent.getActivity(applicationContext, 20, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Lacan · загрузка модели")
            .setContentText("${spec.title} · $percent%")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(100, percent, false).build()
    }
    private class DownloadRemoved : RuntimeException()
    companion object { private const val CHANNEL = "model_downloads"; private const val NOTIFICATION = 2 }
}
