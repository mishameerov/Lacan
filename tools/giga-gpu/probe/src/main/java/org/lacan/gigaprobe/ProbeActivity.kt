package org.lacan.gigaprobe

import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.io.File

/** Explicit isolated experiment: one model invocation; no microphone or network. */
class ProbeActivity : Activity() {
    private external fun runNative(root: String, backend: String, frames: Int, length: Int): String
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val output = TextView(this).apply { text = "Preparing GigaAM FP32 probe…"; setPadding(24,24,24,24) }
        setContentView(output)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({
            val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GigaGpuProbe:bounded")
            try {
            wakeLock.acquire(180_000L)
            val requestedBackend = intent.getStringExtra("backend") ?: "gpu"
            val backend = if (requestedBackend == "gpu" || requestedBackend == "cpu") requestedBackend else "invalid"
            val root = getExternalFilesDir(null)
            val report = try {
                require(backend != "invalid") { "backend must be gpu or cpu" }
                requireNotNull(root)
                listOf("logits-$backend.f32le", "execution-$backend.tsv", "placement-$backend.tsv", "progress-$backend.txt").forEach { File(root,it).delete() }
                File(root,"progress-$backend.txt").appendText("stage=activity_inputs_begin\n")
                val frames = File(root,"frames.txt").readText().trim().toInt()
                val length = File(root,"length.txt").readText().trim().toInt()
                require(frames in 1..2500 && length in 1..frames)
                runNative(root.absolutePath, backend, frames, length)
            } catch (e: Throwable) { "FAILED ${e.javaClass.simpleName}: ${e.message}" }
            root?.let { File(it,"report-$backend.txt").writeText(report) }
            Log.i("GigaGpuProbe", report)
            runOnUiThread {
                output.text = report
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        },"giga-gpu-probe").start()
    }
    companion object { init { System.loadLibrary("gigaprobe") } }
}
