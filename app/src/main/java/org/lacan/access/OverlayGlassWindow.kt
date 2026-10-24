package org.lacan.access

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.function.Consumer
import kotlin.math.roundToInt

/** A separate rounded window lets Android blur the backdrop of each disjoint overlay surface. */
internal class OverlayGlassWindow(
    private val context: Context,
    private val content: View,
    private var width: Int,
    private val height: Int,
    var x: Int,
    var y: Int,
    private var tint: Int,
    private val canvasMotion: Boolean = false
) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    private val window get() = checkNotNull(dialog.window)
    private var rightAnchorWidth: Int? = null
    private var motionBounds: Pair<Int, Int>? = null
    private var glass: GradientDrawable? = null
    private var drawnOrigin = x
    private val canvasRoot = if (canvasMotion) object : FrameLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            // A resize request is asynchronous. Keep the old buffer's origin until
            // this View has the requested width; only then use the new origin.
            // Same-size settled moves need no relayout and draw at local zero.
            if (this.width == window.attributes.width) drawnOrigin = window.attributes.x
            val left = this@OverlayGlassWindow.x - drawnOrigin
            glass?.let {
                it.setBounds(left, 0, left + this@OverlayGlassWindow.width, this@OverlayGlassWindow.height)
                it.draw(canvas)
            }
            super.dispatchDraw(canvas)
        }
    } else null
    private var listening = false
    private var opacity = 1f
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
        if (canvasRoot != null) {
            canvasRoot.addView(content, FrameLayout.LayoutParams(-1, -1))
            dialog.setContentView(canvasRoot)
        } else dialog.setContentView(content)
        position()
        applyGlass(manager.isCrossWindowBlurEnabled)
    }

    fun show() {
        if (dialog.isShowing) return
        dialog.show()
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Dialog.show may apply theme sizing; enforce exact shape bounds after attachment.
        window.setLayout(width, height)
        position()
        manager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
        listening = true
    }

    fun setTint(value: Int) {
        if (tint == value) return
        tint = value
        applyGlass(manager.isCrossWindowBlurEnabled)
    }

    /** Keep the near edge fixed while Android asynchronously resizes the window buffer. */
    fun setRightAnchor(areaWidth: Int?) {
        if (canvasMotion || rightAnchorWidth == areaWidth) return
        rightAnchorWidth = areaWidth
        position()
    }

    fun position() {
        window.attributes = window.attributes.apply {
            this.width = motionBounds?.second ?: this@OverlayGlassWindow.width
            this.height = this@OverlayGlassWindow.height
            gravity = Gravity.TOP or if (rightAnchorWidth == null) Gravity.LEFT else Gravity.RIGHT
            this.x = motionBounds?.first ?: rightAnchorWidth?.let { it - this@OverlayGlassWindow.x - this@OverlayGlassWindow.width }
                ?: this@OverlayGlassWindow.x
            this.y = this@OverlayGlassWindow.y
        }
    }

    fun setVisible(visible: Boolean) = setOpacity(if (visible) 1f else 0f)

    fun setOpacity(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (opacity == next) return
        opacity = next
        window.attributes = window.attributes.apply {
            alpha = next
            flags = if (next > 0f && motionBounds == null) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        window.decorView.visibility = if (canvasMotion || next > 0f) View.VISIBLE else View.INVISIBLE
    }

    fun setGeometry(left: Int, top: Int, newWidth: Int) {
        val w = newWidth.coerceAtLeast(1)
        if (x == left && y == top && width == w) return
        x = left; y = top; width = w
        if (motionBounds == null) position()
        canvasRoot?.invalidate()
    }

    /** Resize the native surface once; all motion afterwards is a single Canvas draw. */
    fun beginMotion(left: Int, right: Int) {
        if (!canvasMotion) return
        val bounds = left to (right - left).coerceAtLeast(1)
        if (motionBounds == bounds) return
        motionBounds = bounds
        position()
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    fun endMotion() {
        if (motionBounds == null) return
        motionBounds = null
        position()
        if (opacity > 0f) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        canvasRoot?.invalidate()
    }

    fun whenMotionReady(action: () -> Unit) {
        val view = canvasRoot ?: content
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.width != window.attributes.width) return true
                view.viewTreeObserver.removeOnPreDrawListener(this)
                view.postOnAnimation { action() }
                return true
            }
        })
        view.invalidate()
    }

    /** Detach content from layout/drawing work while only the glass bounds animate. */
    fun setContentEnabled(enabled: Boolean) {
        content.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun applyGlass(blurEnabled: Boolean) {
        // This is compositor backdrop blur, not a foreground RenderEffect. Battery saver,
        // developer settings and GPU support can disable it; increase tint opacity then.
        val alpha = if (blurEnabled && !canvasMotion) 112 else 218
        val base = Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint))
        val highlight = Color.argb(alpha, (Color.red(tint) + 20).coerceAtMost(255),
            (Color.green(tint) + 20).coerceAtMost(255), (Color.blue(tint) + 20).coerceAtMost(255))
        val glass = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(highlight, base)).apply {
            cornerRadius = height / 2f
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }
        this.glass = glass
        window.setBackgroundDrawable(if (canvasMotion) ColorDrawable(Color.TRANSPARENT) else glass)
        window.setBackgroundBlurRadius(if (blurEnabled && !canvasMotion) dp(24) else 0)
        canvasRoot?.invalidate()
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
