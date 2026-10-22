package org.lacan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import org.lacan.audio.DictationService
import org.lacan.ui.setupBlockReason

/** Visible user-initiated transition; checks configuration before creating a microphone service. */
class LaunchDictationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var polls = 0
    private val awaitStart = object : Runnable {
        override fun run() {
            val state = LacanApp.runtime.state.value
            when {
                state.recording -> finish()
                polls++ >= 20 -> {
                    if (!state.busy) openSetup(state.setupBlockReason() ?: "Не удалось начать запись. Проверьте настройки Lacan.")
                    else finish()
                }
                else -> handler.postDelayed(this, 100)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LacanApp.runtime.refreshPermissions()
        val reason = LacanApp.runtime.state.value.setupBlockReason()
        if (reason != null) { started = true; openSetup(reason); return }
        setContentView(TextView(this).apply { text = "Lacan\nЗапускаю локальную диктовку…"; textSize = 20f; setPadding(40, 40, 40, 40) })
    }
    private fun openSetup(reason: String) {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true).putExtra(MainActivity.EXTRA_SETUP_REASON, reason))
        finish()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            LacanApp.runtime.refreshPermissions()
            val state = LacanApp.runtime.state.value
            val reason = state.setupBlockReason()
            if (reason != null) { openSetup(reason); return }
            if (state.busy) { finish(); return }
            try {
                startForegroundService(Intent(this, DictationService::class.java).setAction(DictationService.START))
                handler.postDelayed(awaitStart, 150)
            } catch (_: Exception) {
                openSetup("Android не разрешил запись. Проверьте разрешения Lacan.")
            }
        }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
