package org.lacan.access

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.lacan.LacanApp
import org.lacan.R
import org.lacan.LaunchDictationActivity
import org.lacan.ui.ScreenState
import kotlin.math.abs
import kotlin.math.roundToInt

/** Main-thread controller. Android can hide overlays over protected surfaces without notifying us. */
class OverlayController(private val context: Context) : AutoCloseable {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay_position", Context.MODE_PRIVATE)
    private val runtime get() = LacanApp.runtime
    private val handler = Handler(Looper.getMainLooper())
    private val surfaces = mutableListOf<OverlayGlassWindow>()
    private var lastState: ScreenState? = null
    private var lastMode = ""
    private var lastArea = 0 to 0
    private var lastPreview = ""
    private var previewView: TextView? = null
    private var previewScroll: HorizontalScrollView? = null
    private var waveformView: OverlayWaveformView? = null
    private var timerView: TextView? = null
    private var recordButton: View? = null
    private var cancelSurface: OverlayGlassWindow? = null
    private var bubbleSurface: OverlayGlassWindow? = null
    private var panelAnimator: ValueAnimator? = null
    private var panelLayout: PanelLayout? = null
    private var panelAnimation: OverlayPanelAnimation? = null
    private var panelProgress = OverlayPanelGeometry.DURATION_MS.toFloat()
    private var collapsingToIdle = false
    private var controlLossReported = false
    private var anchorX = if (preferences.getFloat("x", 1f) < .5f) 0f else 1f
    private var anchorY = preferences.getFloat("y", .5f).coerceIn(0f, 1f)
    private val size get() = dp(38.5f)
    private val gap get() = dp(10)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    private val timerTick = object : Runnable {
        override fun run() {
            val state = lastState ?: return
            updateTimer(state)
            if (state.recording && surfaces.isNotEmpty()) {
                val elapsed = elapsedMillis(state)
                handler.postDelayed(this, 1000L - elapsed % 1000L)
            }
        }
    }

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
            state.cancelling -> "idle"
            state.modelLoadingVisible && !state.busy -> "model-loading"
            state.recording && !state.readyToRecord -> "record-preparing"
            state.recording -> "recording"
            state.busy && state.preview.isNotBlank() -> "finalizing-preview"
            state.busy -> "preparing"
            state.result.isNotBlank() -> "result"
            else -> "idle"
        }
        if (mode == "idle" && lastMode != "idle" && cancelSurface != null) {
            if (!collapsingToIdle) collapseToIdle()
            return
        }
        if (collapsingToIdle && mode != "idle") {
            collapsingToIdle = false
            cancelPanelAnimator()
            lastMode = "idle"
        } else if (collapsingToIdle) return
        if (mode != lastMode || surfaces.isEmpty() || availableArea() != lastArea) show(mode, state)
        updateContent(mode, state)
    }

    private fun show(mode: String, state: ScreenState) {
        val resumeAt = if (panelAnimator != null && !collapsingToIdle) panelProgress else null
        clearSurfaces()
        cancelPanelAnimator()
        collapsingToIdle = false
        cancelSurface = null; bubbleSurface = null; panelLayout = null
        val previousMode = lastMode
        lastMode = mode
        val (width, height) = availableArea()
        lastArea = width to height
        val ax = gap + (anchorX * (width - size - 2 * gap).coerceAtLeast(0)).roundToInt()
        val ay = (anchorY * (height - size).coerceAtLeast(0)).roundToInt()
        val expanded = mode != "idle"
        val startX = ax
        val control = button(
            when (mode) { "recording", "record-preparing" -> R.drawable.overlay_confirm; "preparing", "finalizing-preview", "model-loading" -> null; else -> R.drawable.overlay_microphone },
            when (mode) { "recording", "record-preparing" -> "Завершить диктовку"; "model-loading" -> "Модель загружается"; "preparing", "finalizing-preview" -> "Отменить диктовку"; else -> "Начать диктовку" },
        ) {
            when (mode) { "recording", "record-preparing" -> runtime.stop(); "preparing", "finalizing-preview" -> runtime.cancel(); "model-loading" -> runtime.requestDictation(); else -> start() }
        }
        recordButton = control
        control.also { draggable(it) }
        var cancelX = ax
        var previewStart = ax
        var previewWidth = 0
        if (expanded) {
            val leftSide = ax < (width - size) / 2
            cancelX = if (mode in listOf("preparing", "record-preparing", "model-loading")) {
                if (leftSide) (ax + size + gap).coerceAtMost(width - size - gap) else (ax - size - gap).coerceAtLeast(gap)
            } else if (leftSide) width - size - gap else gap
            cancelSurface = addSurface(button(R.drawable.overlay_close, if (mode == "result") "Закрыть результат" else "Отменить диктовку") {
                if (mode == "result") runtime.dismissResult() else if (mode == "model-loading") runtime.dismissModelPreparation() else runtime.cancel()
            }, size, cancelX, ay, RED)
            if (mode !in listOf("preparing", "record-preparing", "model-loading")) {
                previewStart = (if (leftSide) ax + size else cancelX + size) + gap
                val previewEnd = (if (leftSide) cancelX else ax) - gap
                previewWidth = (previewEnd - previewStart).coerceAtLeast(0)
                if (previewWidth > 0) bubbleSurface = addPreview(previewWidth, previewStart, ay, mode == "result")
            }
        }
        if (expanded && cancelSurface != null) {
            panelLayout = PanelLayout(OverlayPanelGeometry(ax, cancelX,
                bubbleX = previewStart, bubbleWidth = previewWidth, size = size, gap = gap), ay)
        }
        // Keep the main control above the cancel button and bubble.
        addSurface(control, size, startX, ay, BLUE)
        updateContent(mode, state)
        try {
            surfaces.forEach { it.show() }
            if (bubbleSurface != null && panelLayout != null &&
                (previousMode == "idle" || resumeAt != null || previousMode in listOf("preparing", "record-preparing", "model-loading"))) {
                animatePanel(resumeAt ?: 0f, OverlayPanelGeometry.DURATION_MS.toFloat())
            }
            if (state.recording) handler.post(timerTick)
        } catch (_: RuntimeException) {
            hide()
            if (state.recording && !state.notificationsGranted && !controlLossReported) {
                controlLossReported = true
                runtime.stop("control_lost")
            }
        }
    }

    private fun updateContent(mode: String, state: ScreenState) {
        if (mode == "idle" || mode == "result") {
            recordButton?.alpha = if (state.readyToRecord) 1f else .65f
            recordButton?.contentDescription = if (state.readyToRecord) "Начать диктовку" else "Подготовить диктовку и проверить разрешения"
        }
        val preview = if (mode == "result") state.result else state.preview
        if (preview != lastPreview) {
            lastPreview = preview
            previewView?.text = preview
            previewScroll?.post { previewScroll?.fullScroll(View.FOCUS_RIGHT) }
        }
        val hasText = preview.isNotBlank()
        previewScroll?.visibility = if (hasText) View.VISIBLE else View.INVISIBLE
        waveformView?.visibility = if (hasText) View.GONE else View.VISIBLE
        if (!hasText && state.recording) waveformView?.addLevel(state.audioLevel)
        updateTimer(state)
    }

    private fun addPreview(width: Int, x: Int, y: Int, result: Boolean): OverlayGlassWindow {
        val pill = FrameLayout(context).apply {
            if (result) {
                contentDescription = "Скопировать результат"
                isClickable = true
                setOnClickListener { runtime.copy(runtime.state.value.result) }
            }
        }
        val timerWidth = if (result) dp(34) else dp(57)
        val contentWidth = (width - timerWidth).coerceAtLeast(0)
        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), 0)
            setSingleLine(true)
            if (result) {
                contentDescription = "Скопировать результат"
                setOnClickListener { runtime.copy(runtime.state.value.result) }
            }
        }
        // Keep the leading inset outside the scrollable text so it survives auto-scroll.
        val leadingInset = dp(16).coerceAtMost(contentWidth)
        val scroll = object : HorizontalScrollView(context) {
            override fun getLeftFadingEdgeStrength() = if (canScrollHorizontally(-1)) 1f else 0f
            override fun getRightFadingEdgeStrength() = 0f
        }.apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(12))
            addView(text, FrameLayout.LayoutParams(-2, size))
        }
        previewView = text
        previewScroll = scroll
        pill.addView(scroll, box(contentWidth - leadingInset, size, leadingInset))
        waveformView = OverlayWaveformView(context).also { pill.addView(it, box(contentWidth, size)) }
        val trailingView = if (result) {
            icon(R.drawable.overlay_copy).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Скопировать результат"
                setOnClickListener { runtime.copy(runtime.state.value.result) }
            }
        } else {
            TextView(context).apply {
                setTextColor(Color.argb(235, 236, 243, 255))
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                timerView = this
            }
        }
        pill.addView(trailingView, box(timerWidth.coerceAtMost(width), size, contentWidth))
        val surface = addSurface(pill, width, x, y, PILL)
        if (result) draggable(pill)
        return surface
    }

    private fun elapsedMillis(state: ScreenState): Long = if (state.recording && state.recordingStartedAtMillis > 0L) {
        (SystemClock.elapsedRealtime() - state.recordingStartedAtMillis).coerceAtLeast(0L)
    } else state.recordingElapsedMillis.coerceAtLeast(0L)

    private fun updateTimer(state: ScreenState) {
        val seconds = elapsedMillis(state) / 1000L
        val text = "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        timerView?.let {
            if (it.text.toString() != text) it.text = text
            it.contentDescription = "Длительность записи $text"
        }
    }

    private data class PanelLayout(val geometry: OverlayPanelGeometry, val y: Int)

    private fun animatePanel(from: Float, to: Float) {
        val layout = panelLayout ?: return
        val cancel = cancelSurface ?: return
        val animation = OverlayPanelAnimation(context, lastArea.first, layout.y, layout.geometry,
            cancel.snapshotContent(), bubbleSurface?.snapshotContent(), RED, PILL)
        panelAnimation = animation
        cancel.setVisible(false)
        bubbleSurface?.setVisible(false)
        animation.show()
        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = kotlin.math.abs(to - from).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener { applyPanelProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (panelAnimator !== animator) return
                    panelAnimator = null
                    finishPanelAnimation(revealSettled = !collapsingToIdle)
                    if (collapsingToIdle) {
                        collapsingToIdle = false
                        lastState?.let { show("idle", it) }
                    }
                }
            })
        }
        panelAnimator = animator
        applyPanelProgress(from)
        // Starting before attachment can consume the separation phase while Android
        // creates the surface. Start the animation clock at its first drawable frame.
        animation.afterFirstLayout { if (panelAnimator === animator) animator.start() }
    }

    private fun applyPanelProgress(milliseconds: Float) {
        panelProgress = milliseconds.coerceIn(0f, OverlayPanelGeometry.DURATION_MS.toFloat())
        panelAnimation?.drawFrame(panelProgress)
    }

    private fun collapseToIdle() {
        val current = if (panelAnimator != null) panelProgress else OverlayPanelGeometry.DURATION_MS.toFloat()
        cancelPanelAnimator()
        collapsingToIdle = true
        animatePanel(current, 0f)
    }

    private fun finishPanelAnimation(revealSettled: Boolean) {
        if (revealSettled) {
            cancelSurface?.setVisible(true)
            bubbleSurface?.setVisible(true)
        }
        panelAnimation?.close()
        panelAnimation = null
    }

    private fun cancelPanelAnimator() {
        val old = panelAnimator
        panelAnimator = null
        old?.cancel()
        finishPanelAnimation(revealSettled = true)
    }

    private fun icon(resource: Int) = ImageView(context).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun button(resource: Int?, description: String, action: () -> Unit) =
        object : FrameLayout(context) {
            override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
        }.apply {
            contentDescription = description
            isFocusable = false
            val symbol = if (resource == null) {
                ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            } else icon(resource)
            addView(symbol, FrameLayout.LayoutParams(dp(16.8f), dp(16.8f), Gravity.CENTER))
            setOnClickListener { action() }
        }

    private fun start() {
        runCatching { runtime.requestDictation() }
            .onFailure { runtime.message("Откройте Lacan, чтобы начать диктовку") }
    }

    private fun draggable(view: View) {
        var downX = 0f
        var downY = 0f
        var initialX = 0f
        var initialY = 0f
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            if (collapsingToIdle) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (panelAnimator != null && !collapsingToIdle) {
                        applyPanelProgress(OverlayPanelGeometry.DURATION_MS.toFloat())
                        cancelPanelAnimator()
                    }
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
                        anchorX = (initialX + dx / (width - size - 2 * gap).coerceAtLeast(1)).coerceIn(0f, 1f)
                        anchorY = (initialY + dy / (height - size).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val y = (anchorY * (height - size).coerceAtLeast(0)).roundToInt()
                        surfaces.forEach { surface ->
                            surface.y = y
                            if (lastMode == "idle") surface.x = gap + (anchorX * (width - size - 2 * gap).coerceAtLeast(0)).roundToInt()
                            runCatching { surface.position() }
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
        collapsingToIdle = false
        cancelPanelAnimator()
        panelLayout = null
        cancelSurface = null; bubbleSurface = null
        clearSurfaces()
    }

    private fun clearSurfaces() {
        handler.removeCallbacks(timerTick)
        surfaces.forEach { runCatching { it.close() } }
        surfaces.clear()
        previewView = null; previewScroll = null; waveformView = null; timerView = null; recordButton = null; lastPreview = ""
    }
    override fun close() { hide() }
    private fun box(width: Int, height: Int, x: Int = 0, y: Int = 0) = FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y }
    private fun addSurface(view: View, width: Int, x: Int, y: Int, tint: Int): OverlayGlassWindow {
        return OverlayGlassWindow(context, view, width, size, x, y, tint).also(surfaces::add)
    }

    private companion object {
        val BLUE = Color.rgb(53, 111, 218)
        val RED = Color.rgb(146, 62, 77)
        val PILL = Color.rgb(28, 40, 56)
    }
}
