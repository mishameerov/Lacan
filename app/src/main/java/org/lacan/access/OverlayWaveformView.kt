package org.lacan.access

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/** Bounded amplitude history: no animator, allocations, or drawing while transcription is visible. */
internal class OverlayWaveformView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val amplitudes = FloatArray(24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 181, 211, 255)
        strokeWidth = 2.1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var lastSampleAt = 0L

    init { contentDescription = "Уровень микрофона"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun addLevel(level: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < 80L) return
        lastSampleAt = now
        amplitudes.copyInto(amplitudes, 0, 1, amplitudes.size)
        amplitudes[amplitudes.lastIndex] = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 11.2f * density
        val usable = (width - padding * 2).coerceAtLeast(0f)
        val count = minOf(amplitudes.size, (usable / (6f * density)).toInt())
        if (count < 1) return
        val step = usable / count
        val middle = height / 2f
        for (index in 0 until count) {
            val amplitude = amplitudes[amplitudes.size - count + index]
            val half = (1.4f + amplitude * 10.5f) * density
            val x = padding + step * (index + .5f)
            canvas.drawLine(x, middle - half, x, middle + half, paint)
        }
    }
}
