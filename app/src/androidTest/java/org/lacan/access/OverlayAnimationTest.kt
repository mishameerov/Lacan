package org.lacan.access

import android.animation.ValueAnimator
import android.app.Dialog
import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lacan.MainActivity
import org.lacan.ui.ScreenState
import kotlin.math.roundToInt

/** Synthetic state transitions only: no microphone, inference or permission changes. */
@RunWith(AndroidJUnit4::class)
class OverlayAnimationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val recording = ScreenState(recording = true, busy = true, readyToRecord = true, notificationsGranted = true)
    private val idle = ScreenState(readyToRecord = true)

    @Test fun expansionCollapseInterruptionAndHideKeepPanelGeometryConsistent() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        var controller: OverlayController? = null
        try {
            instrumentation.runOnMainSync {
                val instance = OverlayController(context)
                controller = instance
                instance.update(idle, true, true, false)
                instance.update(recording, true, true, false)
                val animator = field(instance, "panelAnimator") as ValueAnimator
                assertEquals("Expansion duration", OverlayPanelGeometry.DURATION_MS.toLong(), animator.duration)
                assertEquals(3, surfaces(instance).size)
                val layout = field(instance, "panelLayout")!!
                val geometry = field(layout, "geometry") as OverlayPanelGeometry
                val size = dp(38.5f)
                val gap = dp(10f)
                val stage = geometry.frame(OverlayPanelGeometry.SEPARATION_MS.toFloat())
                assertEquals(size.toFloat(), stage.bubbleWidth, 0f)
                if (geometry.mainX > geometry.cancelX) {
                    assertEquals(gap.toFloat(), geometry.mainX - stage.bubbleX - size, .01f)
                    assertEquals(gap.toFloat(), stage.bubbleX - stage.cancelX - size, .01f)
                } else {
                    assertEquals(gap.toFloat(), stage.bubbleX - geometry.mainX - size, .01f)
                    assertEquals(gap.toFloat(), stage.cancelX - stage.bubbleX - size, .01f)
                }
                val expanded = geometry.frame(OverlayPanelGeometry.DURATION_MS.toFloat())
                assertTrue(expanded.bubbleWidth > size)
                assertEquals(geometry.cancelX.toFloat(), expanded.cancelX, 0f)
                assertEquals(geometry.bubbleX.toFloat(), expanded.bubbleX, 0f)

                invoke(instance, "applyPanelProgress", OverlayPanelGeometry.DURATION_MS.toFloat())
                invoke(instance, "cancelPanelAnimator")
                instance.update(idle, true, true, false)
                assertEquals("Collapse duration", OverlayPanelGeometry.DURATION_MS.toLong(), (field(instance, "panelAnimator") as ValueAnimator).duration)
                invoke(instance, "applyPanelProgress", OverlayPanelGeometry.SEPARATION_MS.toFloat())
                assertEquals(size.toFloat(), geometry.frame(OverlayPanelGeometry.SEPARATION_MS.toFloat()).bubbleWidth, 0f)

                instance.update(recording, true, true, false)
                assertEquals(3, surfaces(instance).size)
                assertEquals(OverlayPanelGeometry.DURATION_MS.toLong(), (field(instance, "panelAnimator") as ValueAnimator).duration)
                instance.hide()
                assertTrue(surfaces(instance).isEmpty())
                assertEquals(null, field(instance, "panelAnimator"))
                assertEquals(null, field(instance, "panelAnimation"))
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            scenario.close()
        }
    }

    @Test fun animationNeverResizesSettledGlassWindows() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        var controller: OverlayController? = null
        try {
            instrumentation.runOnMainSync {
                val instance = OverlayController(context)
                controller = instance
                instance.update(idle, true, true, false)
                instance.update(recording, true, true, false)
                val bubble = surfaces(instance)[1]
                invoke(instance, "applyPanelProgress", OverlayPanelGeometry.DURATION_MS.toFloat())
                val settledWidth = intField(bubble, "width")
                val settledX = bubble.x
                val animation = field(instance, "panelAnimation") as OverlayPanelAnimation
                val attributes = animation.window.attributes
                val animationWidth = attributes.width
                assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(attributes.alpha <= context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
                for (phase in OverlayPanelGeometry.DURATION_MS downTo 0) {
                    invoke(instance, "applyPanelProgress", phase.toFloat())
                    assertEquals("Animation must not asynchronously resize the glass window", settledWidth, intField(bubble, "width"))
                    assertEquals("Animation must not independently move the glass window", settledX, bubble.x)
                    assertEquals(animationWidth, animation.window.attributes.width)
                }
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            scenario.close()
        }
    }

    @Test fun transparentMorphStripPassesGapTouchesToUnderlyingActivity() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        val touches = AtomicInteger()
        var controller: OverlayController? = null
        var probe: View? = null
        var x = 0
        var y = 0
        try {
            scenario.onActivity { activity ->
                val touchProbe = View(activity).apply { setOnTouchListener { _, _ -> touches.incrementAndGet(); true } }
                probe = touchProbe
                (activity.window.decorView as ViewGroup).addView(touchProbe, ViewGroup.LayoutParams(-1, -1))
                controller = OverlayController(context).also {
                    it.update(idle, true, true, false)
                    it.update(recording, true, true, false)
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val instance = controller!!
                (field(instance, "panelAnimator") as ValueAnimator).pause()
                invoke(instance, "applyPanelProgress", OverlayPanelGeometry.SEPARATION_MS.toFloat())
                val layout = field(instance, "panelLayout")!!
                val geometry = field(layout, "geometry") as OverlayPanelGeometry
                val animation = field(instance, "panelAnimation") as OverlayPanelAnimation
                val location = IntArray(2)
                animation.window.decorView.getLocationOnScreen(location)
                x = location[0] + if (geometry.mainX > geometry.cancelX) geometry.mainX - dp(5f)
                    else geometry.mainX + dp(38.5f) + dp(5f)
                y = location[1] + dp(38.5f) / 2
            }
            assertTrue(UiDevice.getInstance(instrumentation).click(x, y))
            instrumentation.waitForIdleSync()
            assertTrue("A transparent gap in the animation must deliver touches to the activity", touches.get() > 0)
        } finally {
            instrumentation.runOnMainSync {
                controller?.close()
                probe?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
            scenario.close()
        }
    }

    @Test fun syntheticMorphFrameMetrics() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        val records = Collections.synchronizedList(mutableListOf<String>())
        val metricsThread = HandlerThread("overlay-frame-metrics").apply { start() }
        val metricsHandler = Handler(metricsThread.looper)
        var controller: OverlayController? = null
        var pulse: Choreographer.FrameCallback? = null
        val listeners = mutableListOf<Pair<Window, Window.OnFrameMetricsAvailableListener>>()
        fun capture(label: String) {
            val instance = controller!!
            val animation = runCatching { field(instance, "panelAnimation") }.getOrNull()
            val windows = if (animation != null) listOf(field(animation, "dialog") as Dialog)
                else surfaces(instance).map { field(it, "dialog") as Dialog }
            windows.forEach { dialog ->
                val window = dialog.window!!
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                    records += "render,$label,${metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)},${metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)},${metrics.getMetric(FrameMetrics.TOTAL_DURATION)},${metrics.getMetric(FrameMetrics.DEADLINE)},${metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)},$dropped"
                }
                window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
                listeners += window to listener
            }
            pulse = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val animator = field(instance, "panelAnimator") as? ValueAnimator ?: return
                    records += "pulse,$label,$frameTimeNanos,${System.nanoTime()},${animator.animatedValue ?: 0f},0,0,0"
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }.also { Choreographer.getInstance().postFrameCallback(it) }
        }
        try {
            instrumentation.runOnMainSync { controller = OverlayController(context).also { it.update(idle, true, true, false) } }
            repeat(6) { cycle ->
                instrumentation.runOnMainSync {
                    controller!!.update(recording, true, true, false)
                    capture("expand$cycle")
                }
                Thread.sleep(450)
                instrumentation.runOnMainSync {
                    pulse?.let { Choreographer.getInstance().removeFrameCallback(it) }
                    controller!!.update(idle, true, true, false)
                    capture("collapse$cycle")
                }
                Thread.sleep(450)
                instrumentation.runOnMainSync { pulse?.let { Choreographer.getInstance().removeFrameCallback(it) } }
            }
            assertNotNull(controller)
            assertTrue("Morphs must produce actual rendered frames", records.any { it.startsWith("render,") })
        } finally {
            instrumentation.runOnMainSync {
                pulse?.let { Choreographer.getInstance().removeFrameCallback(it) }
                listeners.forEach { (window, listener) -> window.removeOnFrameMetricsAvailableListener(listener) }
                controller?.close()
            }
            metricsThread.quitSafely()
            metricsThread.join(2000)
            File(context.filesDir, "overlay-animation-frames.csv").writeText(
                "kind,phase,intendedVsyncNs,actualVsyncOrCallbackNs,totalDurationOrProgress,deadlineNs,firstDraw,droppedReports\n" +
                    synchronized(records) { records.joinToString("\n") })
            scenario.close()
        }
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    @Suppress("UNCHECKED_CAST")
    private fun surfaces(controller: OverlayController): MutableList<OverlayGlassWindow> =
        field(controller, "surfaces") as MutableList<OverlayGlassWindow>

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
    private fun intField(target: Any, name: String): Int = target.javaClass.getDeclaredField(name).run { isAccessible = true; getInt(target) }
    private fun invoke(target: Any, name: String, argument: Any? = null) {
        val method = if (argument == null) target.javaClass.getDeclaredMethod(name)
            else target.javaClass.getDeclaredMethod(name, Float::class.javaPrimitiveType)
        method.isAccessible = true
        if (argument == null) method.invoke(target) else method.invoke(target, argument)
    }
}
