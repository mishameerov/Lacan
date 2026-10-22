package org.lacan.access

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.function.Consumer
import kotlin.math.roundToInt

/** A separate rounded window lets Android blur the backdrop of each disjoint overlay surface. */
internal class OverlayGlassWindow(
    private val context: Context,
    content: View,
    private var width: Int,
    private val height: Int,
    var x: Int,
    var y: Int,
    private val tint: Int
) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    private val window get() = checkNotNull(dialog.window)
    private var listening = false
    private val blurListener = Consumer<Boolean> { enabled -> applyGlass(enabled) }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setGravity(Gravity.TOP or Gravity.LEFT)
        content.background = null
        dialog.setContentView(content)
        position()
        applyGlass(manager.isCrossWindowBlurEnabled)
    }

    fun show() {
        dialog.show()
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Dialog.show may apply theme sizing; enforce exact shape bounds after attachment.
        window.setLayout(width, height)
        position()
        manager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
        listening = true
    }

    fun position() {
        window.attributes = window.attributes.apply {
            this.width = this@OverlayGlassWindow.width
            this.height = this@OverlayGlassWindow.height
            this.x = this@OverlayGlassWindow.x
            this.y = this@OverlayGlassWindow.y
        }
    }

    fun setBounds(x: Int, y: Int, width: Int) {
        this.x = x
        this.y = y
        this.width = width
        position()
    }

    private fun applyGlass(blurEnabled: Boolean) {
        // This is compositor backdrop blur, not a foreground RenderEffect. Battery saver,
        // developer settings and GPU support can disable it; increase tint opacity then.
        val alpha = if (blurEnabled) 112 else 218
        val base = Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint))
        val highlight = Color.argb(alpha, (Color.red(tint) + 20).coerceAtMost(255),
            (Color.green(tint) + 20).coerceAtMost(255), (Color.blue(tint) + 20).coerceAtMost(255))
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(highlight, base)).apply {
            cornerRadius = height / 2f
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        })
        window.setBackgroundBlurRadius(if (blurEnabled) dp(24) else 0)
        window.decorView.setPadding(0, 0, 0, 0)
    }

    override fun close() {
        try {
            if (listening) manager.removeCrossWindowBlurEnabledListener(blurListener)
        } finally {
            listening = false
            dialog.dismiss()
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
