package org.lacan.access

import android.Manifest
import android.content.Context
import android.content.Intent
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private var recordButton: Button? = null
    private var controlLossReported = false
    private var anchorX = if (preferences.getFloat("x", 1f) < .5f) 0f else 1f
    private var anchorY = preferences.getFloat("y", .5f).coerceIn(0f, 1f)
    private val size get() = dp(55)
    private val gap get() = dp(10)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
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
            state.recording && !state.readyToRecord -> "record-preparing"
            state.recording -> "recording"
            state.busy && state.preview.isNotBlank() -> "finalizing-preview"
            state.busy -> "preparing"
            state.result.isNotBlank() -> "result"
            else -> "idle"
        }
        if (mode != lastMode || surfaces.isEmpty() || availableArea() != lastArea) show(mode, state)
        updateContent(mode, state)
    }

    private fun show(mode: String, state: ScreenState) {
        hide()
        lastMode = mode
        val (width, height) = availableArea()
        lastArea = width to height
        val ax = (anchorX * (width - size).coerceAtLeast(0)).roundToInt()
        val ay = (anchorY * (height - size).coerceAtLeast(0)).roundToInt()
        val expanded = mode != "idle"
        val startX = ax
        val control = button(
            when (mode) { "recording", "record-preparing" -> "■"; "preparing", "finalizing-preview" -> "…"; else -> "●" },
            when (mode) { "recording", "record-preparing" -> "Остановить диктовку"; "preparing", "finalizing-preview" -> "Отменить диктовку"; else -> "Начать диктовку" },
        ) {
            when (mode) { "recording", "record-preparing" -> runtime.stop(); "preparing", "finalizing-preview" -> runtime.cancel(); else -> start() }
        }
        recordButton = control
        addSurface(control.also { draggable(it) }, size, startX, ay, BLUE)
        if (expanded) {
            val leftSide = ax < (width - size) / 2
            val cancelX = if (mode in listOf("preparing", "record-preparing")) {
                if (leftSide) (ax + size + gap).coerceAtMost(width - size) else (ax - size - gap).coerceAtLeast(0)
            } else if (leftSide) width - size else 0
            addSurface(button("×", if (mode == "result") "Закрыть результат" else "Отменить диктовку") {
                if (mode == "result") runtime.dismissResult() else runtime.cancel()
            }, size, cancelX, ay, RED)
            if (mode !in listOf("preparing", "record-preparing")) {
                val previewStart = (if (leftSide) ax + size else size) + gap
                val previewEnd = (if (leftSide) width - size else ax) - gap
                val previewWidth = (previewEnd - previewStart).coerceAtLeast(0)
                if (previewWidth > 0) addPreview(previewWidth, previewStart, ay, mode == "result")
            }
        }
        updateContent(mode, state)
        try {
            surfaces.forEach { it.show() }
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

    private fun addPreview(width: Int, x: Int, y: Int, result: Boolean) {
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
            setPadding(dp(14), 0, dp(8), 0)
            setSingleLine(true)
            if (result) {
                contentDescription = "Скопировать результат"
                setOnClickListener { runtime.copy(runtime.state.value.result) }
            }
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(text, FrameLayout.LayoutParams(-2, size))
        }
        previewView = text
        previewScroll = scroll
        pill.addView(scroll, box(contentWidth, size))
        waveformView = OverlayWaveformView(context).also { pill.addView(it, box(contentWidth, size)) }
        val timer = TextView(context).apply {
            setTextColor(Color.argb(235, 236, 243, 255))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            if (result) {
                this.text = "⧉"
                contentDescription = "Скопировать результат"
                setOnClickListener { runtime.copy(runtime.state.value.result) }
            }
        }
        if (!result) timerView = timer
        pill.addView(timer, box(timerWidth.coerceAtMost(width), size, contentWidth))
        addSurface(pill, width, x, y, PILL)
        if (result) draggable(pill)
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

    private fun button(label: String, description: String, action: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = description
        textSize = if (label == "×") 30f else 20f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null

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
                        val y = (anchorY * (height - size).coerceAtLeast(0)).roundToInt()
                        surfaces.forEach { surface ->
                            surface.y = y
                            if (lastMode == "idle") surface.x = (anchorX * (width - size).coerceAtLeast(0)).roundToInt()
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
        handler.removeCallbacks(timerTick)
        surfaces.forEach { runCatching { it.close() } }
        surfaces.clear()
        previewView = null; previewScroll = null; waveformView = null; timerView = null; recordButton = null; lastPreview = ""
    }
    override fun close() { hide() }
    private fun box(width: Int, height: Int, x: Int = 0, y: Int = 0) = FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y }
    private fun addSurface(view: View, width: Int, x: Int, y: Int, tint: Int) {
        surfaces += OverlayGlassWindow(context, view, width, size, x, y, tint)
    }

    private companion object {
        val BLUE = Color.rgb(53, 111, 218)
        val RED = Color.rgb(146, 62, 77)
        val PILL = Color.rgb(28, 40, 56)
    }
}
