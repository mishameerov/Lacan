package org.lacan.audio

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import org.lacan.LacanApp
import org.lacan.MainActivity
import org.lacan.R

class DictationService : Service() {
    private val runtime get() = LacanApp.runtime
    private val handler = Handler(Looper.getMainLooper())
    private val checkControls = object : Runnable {
        override fun run() {
            runtime.refreshPermissions()
            if (runtime.state.value.recording && !runtime.state.value.notificationsGranted) runtime.stop("control_lost")
            handler.postDelayed(this, 1000)
        }
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) runtime.stop("screen_off")
        }
    }
    override fun onCreate() {
        super.onCreate()
        handler.postDelayed(checkControls, 1000)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Диктовка", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Управление локальной записью"; setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> runtime.stop()
            CANCEL -> runtime.cancel()
            START -> {
                if (runtime.state.value.busy) return START_NOT_STICKY
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runtime.message("Предоставьте доступ к микрофону в настройках Lacan"); stopSelf(); return START_NOT_STICKY
                }
                try {
                    startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    runtime.startCapture { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                } catch (e: Exception) {
                    runtime.message("Android не разрешил запись. Откройте Lacan и проверьте разрешения.")
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        fun action(code: Int, value: String) = PendingIntent.getService(this, code,
            Intent(this, DictationService::class.java).setAction(value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle("Lacan · диктовка")
            .setContentText("Запись или завершение обработки на устройстве")
            .setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open).addAction(0, "Стоп", action(1, STOP)).addAction(0, "Отмена", action(2, CANCEL)).build()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(lockReceiver); runtime.stop("service_stopped"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "org.lacan.START"; const val STOP = "org.lacan.STOP"; const val CANCEL = "org.lacan.CANCEL"
        private const val CHANNEL = "dictation"; private const val NOTIFICATION = 1
    }
}
