package org.lacan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import org.lacan.audio.DictationService

/** Visible user-initiated transition; does not assume overlay permission grants microphone access. */
class LaunchDictationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var polls = 0
    private val awaitStart = object : Runnable {
        override fun run() {
            if (LacanApp.runtime.state.value.recording || polls++ >= 20) finish()
            else handler.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Lacan\nЗапускаю локальную диктовку…"; textSize = 20f; setPadding(40, 40, 40, 40) })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            try {
                startForegroundService(Intent(this, DictationService::class.java).setAction(DictationService.START))
                handler.postDelayed(awaitStart, 150)
            } catch (_: Exception) {
                LacanApp.runtime.message("Не удалось начать запись. Откройте Lacan и проверьте разрешения."); finish()
            }
        }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
