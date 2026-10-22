package org.lacan.access

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.input.InputManager
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Only the brief morph uses a transparent strip. It never receives input, and its window
 * alpha stays below Android's untrusted-overlay threshold so gaps pass touches through.
 * Settled surfaces retain their separate, tightly bounded compositor-blurred windows.
 */
internal class OverlayPanelAnimation(
    context: Context,
    private val width: Int,
    y: Int,
    private val geometry: OverlayPanelGeometry,
    cancelContent: Bitmap,
    bubbleContent: Bitmap?,
    cancelTint: Int,
    bubbleTint: Int
) : AutoCloseable {
    private val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    val window: Window get() = checkNotNull(dialog.window)
    private val drawing = PanelView(context, geometry, cancelContent, bubbleContent, cancelTint, bubbleTint)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setGravity(Gravity.TOP or Gravity.LEFT)
        window.attributes = window.attributes.apply {
            this.width = width
            this.height = geometry.size
            this.x = 0
            this.y = y
            alpha = context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch.coerceAtMost(.8f)
        }
        dialog.setContentView(drawing)
    }

    fun show() {
        dialog.show()
        window.setLayout(width, geometry.size)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.setPadding(0, 0, 0, 0)
    }

    fun drawFrame(milliseconds: Float) {
        drawing.milliseconds = milliseconds
        drawing.invalidate()
    }

    fun afterFirstLayout(action: () -> Unit) {
        drawing.postOnAnimation { action() }
    }

    override fun close() {
        dialog.dismiss()
    }

    private class PanelView(
        context: Context,
        private val geometry: OverlayPanelGeometry,
        private val cancelContent: Bitmap,
        private val bubbleContent: Bitmap?,
        cancelTint: Int,
        bubbleTint: Int
    ) : View(context) {
        var milliseconds = 0f
        private val bounds = RectF()
        private val path = Path()
        private val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density
            color = Color.argb(70, 255, 255, 255)
        }
        private val cancelPaint = glassPaint(cancelTint)
        private val bubblePaint = glassPaint(bubbleTint)
        private val radius = geometry.size / 2f

        override fun onDraw(canvas: Canvas) {
            val frame = geometry.frame(milliseconds)
            // This window is above the small live main control; never cover that control.
            canvas.clipOutRect(geometry.mainX.toFloat(), 0f, (geometry.mainX + geometry.size).toFloat(), geometry.size.toFloat())
            drawSurface(canvas, frame.cancelX, geometry.size.toFloat(), cancelPaint, cancelContent)
            if (frame.bubbleWidth > 0f) drawSurface(canvas, frame.bubbleX, frame.bubbleWidth, bubblePaint, bubbleContent)
        }

        private fun drawSurface(canvas: Canvas, x: Float, width: Float, paint: Paint, content: Bitmap?) {
            bounds.set(x, 0f, x + width, geometry.size.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, paint)
            val save = canvas.save()
            path.reset()
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
            if (content != null) canvas.drawBitmap(content, x, 0f, contentPaint)
            canvas.restoreToCount(save)
            val inset = rim.strokeWidth / 2f
            bounds.inset(inset, inset)
            canvas.drawRoundRect(bounds, radius, radius, rim)
        }

        private fun glassPaint(tint: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val blurEnabled = context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
            val alpha = if (blurEnabled) 155 else 255
            shader = LinearGradient(0f, 0f, 0f, geometry.size.toFloat(),
                Color.argb(alpha, (Color.red(tint) + 20).coerceAtMost(255), (Color.green(tint) + 20).coerceAtMost(255),
                    (Color.blue(tint) + 20).coerceAtMost(255)),
                Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint)), Shader.TileMode.CLAMP)
        }

    }
}
