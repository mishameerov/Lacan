package org.lacan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import org.lacan.audio.DictationService
import org.lacan.ui.setupBlockReason

/** Transparent non-focusable bridge for Android's foreground microphone service rules. */
class LaunchDictationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var polls = 0
    private val awaitStart = object : Runnable {
        override fun run() {
            val state = LacanApp.runtime.state.value
            if (state.recording || polls++ >= 20) finish()
            else handler.postDelayed(this, 50)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(1, 1)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }
    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        val runtime = LacanApp.runtime
        runtime.refreshPermissions()
        val reason = runtime.state.value.setupBlockReason()
        if (reason != null) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true).putExtra(MainActivity.EXTRA_SETUP_REASON, reason))
            finish(); return
        }
        if (runtime.state.value.busy) { finish(); return }
        if (!runtime.state.value.readyToRecord) { runtime.requestDictation(); finish(); return }
        try {
            startForegroundService(Intent(this, DictationService::class.java).setAction(DictationService.START))
            handler.post(awaitStart)
        } catch (_: Exception) {
            runtime.message("Android не разрешил запись. Проверьте разрешения Lacan.")
            finish()
        }
    }
    override fun finish() { super.finish(); overridePendingTransition(0, 0) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
