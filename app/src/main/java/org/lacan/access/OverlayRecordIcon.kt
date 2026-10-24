package org.lacan.access

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** One persistent vector view: only its paths change, never the containing glass window. */
internal class OverlayRecordIcon(context: Context) : View(context) {
    enum class Symbol { MICROPHONE, CONFIRM, LOADING }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val bounds = RectF()
    private var morph = 0f
    private var angle = 0f
    private var symbol = Symbol.MICROPHONE
    private var transition: ValueAnimator? = null
    private var spinner: ValueAnimator? = null

    fun setSymbol(next: Symbol, durationMillis: Long = 150L) {
        if (next == symbol) return
        symbol = next
        transition?.cancel()
        spinner?.cancel(); spinner = null
        if (next == Symbol.LOADING) {
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900L; repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            transition = ValueAnimator.ofFloat(morph, if (next == Symbol.CONFIRM) 1f else 0f).apply {
                duration = durationMillis
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.scale(width / 24f, height / 24f)
        paint.style = Paint.Style.STROKE
        if (symbol == Symbol.LOADING) {
            bounds.set(3f, 3f, 21f, 21f)
            for (i in 0 until 12) {
                paint.alpha = 25 + i * 20
                canvas.drawArc(bounds, angle + i * 27f, 20f, false, paint)
            }
        } else {
            paint.alpha = 255
            // The capsule collapses to its centre while the support curve becomes a check.
            val capsule = (1f - morph * 1.6f).coerceAtLeast(0f)
            if (capsule > 0f) {
                paint.style = Paint.Style.FILL
                bounds.set(12f - 3f * capsule, 8f - 6f * capsule, 12f + 3f * capsule, 8f + 6f * capsule)
                canvas.drawRoundRect(bounds, 3f * capsule, 3f * capsule, paint)
            }
            paint.style = Paint.Style.STROKE
            fun mix(a: Float, b: Float) = a + (b - a) * morph
            path.reset()
            path.moveTo(mix(5f, 4f), mix(10f, 12f))
            path.cubicTo(mix(5f, 5.7f), mix(15f, 13.7f), mix(8f, 7.3f), mix(18f, 15.3f), mix(12f, 9f), mix(18f, 17f))
            path.cubicTo(mix(16f, 13f), mix(18f, 13f), mix(19f, 17f), mix(15f, 9f), mix(19f, 21f), mix(10f, 5f))
            canvas.drawPath(path, paint)
            paint.alpha = (255 * (1f - morph)).toInt()
            canvas.drawLine(12f, 18f, 12f, 18f + 4f * (1f - morph), paint)
            canvas.drawLine(12f - 3f * (1f - morph), 22f, 12f + 3f * (1f - morph), 22f, paint)
        }
        paint.alpha = 255
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        transition?.cancel(); spinner?.cancel()
        super.onDetachedFromWindow()
    }
}
