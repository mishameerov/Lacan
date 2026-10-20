package org.lacan.access

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.lacan.LacanApp
import org.lacan.LaunchDictationActivity
import org.lacan.ui.ScreenState
import kotlin.math.abs
import kotlin.math.roundToInt

/** Main-thread controller. Android can hide overlays over protected surfaces without notifying us. */
class OverlayController(private val context: Context) : AutoCloseable {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay_position", Context.MODE_PRIVATE)
    private val runtime get() = LacanApp.runtime
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastState: ScreenState? = null
    private var lastMode = ""
    private var lastArea = 0 to 0
    private var lastPreview = ""
    private var previewView: TextView? = null
    private var previewScroll: HorizontalScrollView? = null
    private var expandedFinal = false
    private var controlLossReported = false
    private var anchorX = if (preferences.getFloat("x", 1f) < .5f) 0f else 1f
    private var anchorY = preferences.getFloat("y", .5f).coerceIn(0f, 1f)
    private val size get() = dp(55)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun update(state: ScreenState, targetEligible: Boolean, keyboardVisible: Boolean, locked: Boolean) {
        lastState = state
        val permission = Settings.canDrawOverlays(context)
        if (!permission && state.recording &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!controlLossReported) {
                controlLossReported = true
                runtime.stop("control_lost")
            }
        } else controlLossReported = false
        if (locked || !permission || (!state.busy && state.result.isBlank() &&
                !(state.enabled && targetEligible && keyboardVisible))) {
            hide()
            return
        }
        val mode = when {
            state.recording -> "recording"
            state.busy -> if (expandedFinal) "final-cancel" else "finalizing"
            state.result.isNotBlank() -> "result"
            else -> "idle"
        }
        if (!state.busy) expandedFinal = false
        if (mode != lastMode || root == null || availableArea() != lastArea) show(mode, state)
        val preview = if (mode == "result") state.result else state.preview.ifBlank { state.status }
        if (preview != lastPreview) {
            lastPreview = preview
            previewView?.text = preview
            previewScroll?.post { previewScroll?.fullScroll(View.FOCUS_RIGHT) }
        }
    }

    private fun show(mode: String, state: ScreenState) {
        hide()
        lastMode = mode
        val (width, height) = availableArea()
        lastArea = width to height
        val ax = (anchorX * (width - size).coerceAtLeast(0)).roundToInt()
        val ay = (anchorY * (height - size).coerceAtLeast(0)).roundToInt()
        val full = mode == "recording" || mode == "result" || mode == "final-cancel"
        val panel = FrameLayout(context).apply {
            background = shape(Color.rgb(27, 39, 48))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root = panel
        val layout = WindowManager.LayoutParams(if (full) width else size,
            if (mode == "result") dp(110) else size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (full) 0 else ax
            y = ay.coerceAtMost((height - if (mode == "result") dp(110) else size).coerceAtLeast(0))
        }
        params = layout
        when (mode) {
            "idle" -> panel.addView(button("●", "Начать диктовку") { start() }.also { draggable(it) }, box(size, size))
            "finalizing" -> panel.addView(button("…", "Распознавание: показать отмену") {
                expandedFinal = true
                lastState?.let { show("final-cancel", it) }
            }.also { draggable(it) }, box(size, size))
            "recording", "final-cancel" -> {
                val leftSide = ax < (width - size) / 2
                val stop = button(if (mode == "recording") "Стоп" else "…", "Остановить диктовку") {
                    if (mode == "recording") runtime.stop() else runtime.cancel()
                }
                panel.addView(stop.also { draggable(it) }, box(size, size, ax))
                val cancelX = if (leftSide) width - size else 0
                panel.addView(button("×", "Отменить диктовку") { runtime.cancel() }, box(size, size, cancelX))
                val previewStart = if (leftSide) ax + size else size
                val previewEnd = if (leftSide) width - size else ax
                addPreview(panel, (previewEnd - previewStart).coerceAtLeast(0), previewStart, 0)
            }
            "result" -> {
                addPreview(panel, width, 0, 0)
                val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(button("●", "Начать новую диктовку") { start() }, LinearLayout.LayoutParams(size, size))
                actions.addView(button("Копировать", "Скопировать результат") { runtime.copy(runtime.state.value.result) }, LinearLayout.LayoutParams(0, size, 1f))
                actions.addView(button("×", "Закрыть результат") { runtime.dismissResult() }, LinearLayout.LayoutParams(size, size))
                panel.addView(actions, box(width, size, 0, size))
                draggable(previewView!!)
            }
        }
        val preview = if (mode == "result") state.result else state.preview.ifBlank { state.status }
        lastPreview = preview
        previewView?.text = preview
        previewScroll?.post { previewScroll?.fullScroll(View.FOCUS_RIGHT) }
        try { windowManager.addView(panel, layout) }
        catch (_: RuntimeException) {
            root = null
            params = null
            if (state.recording && !state.notificationsGranted && !controlLossReported) {
                controlLossReported = true
                runtime.stop("control_lost")
            }
        }
    }

    private fun addPreview(panel: FrameLayout, width: Int, x: Int, y: Int) {
        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            setSingleLine(true)
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(text, FrameLayout.LayoutParams(-2, size))
        }
        previewView = text
        previewScroll = scroll
        panel.addView(scroll, box(width, size, x, y))
    }

    private fun button(label: String, description: String, action: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = description
        textSize = 12f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        background = shape(Color.rgb(45, 81, 86))
        setOnClickListener { action() }
    }

    private fun start() {
        runCatching {
            context.startActivity(Intent(context, LaunchDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { runtime.message("Откройте Lacan, чтобы начать диктовку") }
    }

    private fun draggable(view: View) {
        var downX = 0f
        var downY = 0f
        var initialX = 0f
        var initialY = 0f
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initialX = anchorX; initialY = anchorY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        val (width, height) = availableArea()
                        anchorX = (initialX + dx / (width - size).coerceAtLeast(1)).coerceIn(0f, 1f)
                        anchorY = (initialY + dy / (height - size).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val lp = params
                        val panel = root
                        if (lp != null && panel != null) {
                            lp.y = (anchorY * (height - lp.height).coerceAtLeast(0)).roundToInt()
                            if (lp.width == size) lp.x = (anchorX * (width - size).coerceAtLeast(0)).roundToInt()
                            runCatching { windowManager.updateViewLayout(panel, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // Settle on the closest edge; the expanded stop button keeps this anchor.
                        anchorX = if (anchorX < .5f) 0f else 1f
                        preferences.edit().putFloat("x", anchorX).putFloat("y", anchorY).apply()
                        lastState?.let { show(lastMode, it) }
                    } else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun availableArea(): Pair<Int, Int> {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(size) to
            (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(size)
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null; params = null
        previewView = null; previewScroll = null; lastPreview = ""
    }
    override fun close() { hide() }
    private fun box(width: Int, height: Int, x: Int = 0, y: Int = 0) = FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
}
