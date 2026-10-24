package org.lacan.access

import android.animation.ValueAnimator
import android.app.Dialog
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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

    @Test fun canvasKeepsItsOriginUntilResizedBufferIsLaidOut() {
        instrumentation.runOnMainSync {
            val surface = OverlayGlassWindow(context, View(context), 80, 40, 50, 0, 0xff334455.toInt(), canvasMotion = true)
            val root = field(surface, "canvasRoot") as View
            val bitmap = android.graphics.Bitmap.createBitmap(240, 40, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            fun layout(width: Int) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 40)
            }
            fun assertDrawnLeft(left: Int) {
                root.draw(canvas)
                val glass = field(surface, "glass") as android.graphics.drawable.GradientDrawable
                assertEquals(left, glass.bounds.left)
            }
            try {
                layout(80)
                assertDrawnLeft(0)
                surface.beginMotion(10, 250)
                layout(240)
                assertDrawnLeft(40)
                surface.endMotion()
                // Window request is compact, but Android has not supplied its buffer yet.
                assertDrawnLeft(40)
                layout(80)
                assertDrawnLeft(0)
                surface.setGeometry(90, 0, 80)
                assertDrawnLeft(0)
            } finally { bitmap.recycle(); surface.close() }
        }
    }

    @Test fun animationDoesNotResizeOrMoveSystemBubbleWindow() {
        assumeTrue(Settings.canDrawOverlays(context))
        instrumentation.runOnMainSync {
            val instance = OverlayController(context)
            try {
                instance.update(idle, true, true, false)
                instance.update(recording, true, true, false)
                val window = (field(surfaces(instance)[1], "dialog") as Dialog).window!!
                val initialWidth = window.attributes.width
                val initialX = window.attributes.x
                for (time in 0..OverlayPanelGeometry.DURATION_MS step 17) {
                    invoke(instance, "applyPanelProgress", time.toFloat())
                    assertEquals("No asynchronous window resize during motion", initialWidth, window.attributes.width)
                    assertEquals("No asynchronous window movement during motion", initialX, window.attributes.x)
                }
            } finally { instance.close() }
        }
    }

    @Test fun animationUses230MillisecondsWithStableWindowGeometry() {
        assumeTrue(Settings.canDrawOverlays(context))
        instrumentation.runOnMainSync {
            run {
                val instance = OverlayController(context)
                try {
                    instance.update(idle, true, true, false)
                    instance.update(recording, true, true, false)
                    val animator = field(instance, "panelAnimator") as ValueAnimator
                    assertEquals(230L, animator.duration)
                    val layout = field(instance, "panelLayout")!!
                    val geometry = field(layout, "geometry") as OverlayPanelGeometry
                    val bubbleWindow = (field(surfaces(instance)[1], "dialog") as Dialog).window!!
                    var anchor: Int? = null
                    for (time in OverlayPanelGeometry.EXTENSION_MS..OverlayPanelGeometry.DURATION_MS step 13) {
                        invoke(instance, "applyPanelProgress", time.toFloat())
                        val nearEdge = bubbleWindow.attributes.x
                        if (anchor == null) anchor = nearEdge else assertEquals(anchor, nearEdge)
                    }
                } finally { instance.close() }
            }
        }
    }

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
                val size = geometry.size
                val covered = geometry.frame(OverlayPanelGeometry.EXTENSION_MS.toFloat())
                assertEquals(minOf(geometry.bubbleX, geometry.cancelX).toFloat(), covered.bubbleX, 0f)
                assertTrue(covered.bubbleX <= geometry.cancelX)
                assertTrue(covered.bubbleX + covered.bubbleWidth >= geometry.cancelX + size)
                val expanded = geometry.frame(OverlayPanelGeometry.DURATION_MS.toFloat())
                assertTrue(expanded.bubbleWidth > size)
                assertEquals(geometry.cancelX.toFloat(), expanded.cancelX, 0f)
                assertEquals(geometry.bubbleX.toFloat(), expanded.bubbleX, 0f)

                invoke(instance, "applyPanelProgress", OverlayPanelGeometry.DURATION_MS.toFloat())
                invoke(instance, "cancelPanelAnimator")
                instance.update(idle, true, true, false)
                assertEquals("Collapse duration", OverlayPanelGeometry.DURATION_MS.toLong(), (field(instance, "panelAnimator") as ValueAnimator).duration)
                invoke(instance, "applyPanelProgress", OverlayPanelGeometry.SEPARATION_MS.toFloat())
                assertTrue(geometry.frame(OverlayPanelGeometry.SEPARATION_MS.toFloat()).bubbleWidth > 0f)

                instance.update(recording, true, true, false)
                assertEquals(3, surfaces(instance).size)
                val resumedDuration = (field(instance, "panelAnimator") as ValueAnimator).duration
                assertTrue(resumedDuration in 1L..OverlayPanelGeometry.DURATION_MS.toLong())
                instance.hide()
                assertTrue(surfaces(instance).isEmpty())
                assertEquals(null, field(instance, "panelAnimator"))
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            scenario.close()
        }
    }

    @Test fun persistentWindowsResizeWithoutReplacingSurfacesOrUpdatingHiddenContent() {
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
                val persistent = surfaces(instance).toList()
                val dialogs = persistent.map { field(it, "dialog") as Dialog }
                val windows = dialogs.map { it.window!! }
                assertEquals(3, persistent.size)
                assertEquals(0f, windows[0].attributes.alpha, 0f)
                assertEquals(0f, windows[1].attributes.alpha, 0f)
                assertTrue(windows.take(2).all {
                    it.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
                })
                fun assertPersistentWindows() {
                    assertEquals(3, surfaces(instance).size)
                    persistent.forEachIndexed { index, surface ->
                        assertSame(surface, surfaces(instance)[index])
                        assertSame(dialogs[index], field(surface, "dialog"))
                        assertSame(windows[index], (field(surface, "dialog") as Dialog).window)
                    }
                }
                instance.update(recording, true, true, false)
                val bubble = persistent[1]
                val content = field(bubble, "content") as View
                val text = field(instance, "previewView") as TextView
                val frozenText = text.text.toString()
                assertEquals(View.GONE, content.visibility)
                val layout = field(instance, "panelLayout")!!
                val geometry = field(layout, "geometry") as OverlayPanelGeometry
                val motionWindowWidth = windows[1].attributes.width
                val motionWindowX = windows[1].attributes.x
                val phases = listOf(0, OverlayPanelGeometry.EXTENSION_MS / 2,
                    OverlayPanelGeometry.EXTENSION_MS, OverlayPanelGeometry.DURATION_MS)
                phases.forEach { phase ->
                    invoke(instance, "applyPanelProgress", phase.toFloat())
                    val frame = geometry.frame(phase.toFloat())
                    assertEquals(motionWindowWidth, windows[1].attributes.width)
                    assertEquals(motionWindowX, windows[1].attributes.x)
                    assertEquals(frame.bubbleX.roundToInt(), bubble.x)
                    assertEquals((frame.bubbleX + frame.bubbleWidth).roundToInt() - frame.bubbleX.roundToInt(), field(bubble, "width"))
                    assertEquals(View.GONE, content.visibility)
                    assertPersistentWindows()
                }
                val finalizing = recording.copy(recording = false, preview = "Finalizing text")
                instance.update(finalizing, true, true, false)
                assertEquals("Hidden preview must remain unchanged during morph", frozenText, text.text.toString())
                assertEquals(View.GONE, content.visibility)
                assertPersistentWindows()
                invoke(instance, "cancelPanelAnimator")
                assertEquals(View.VISIBLE, content.visibility)
                assertEquals(finalizing.preview, text.text.toString())
                instance.update(idle.copy(result = "Completed text"), true, true, false)
                assertEquals("Completed text", text.text.toString())
                assertPersistentWindows()
                instance.update(idle, true, true, false)
                assertPersistentWindows()
                // End collapse through its real listener, then verify idle retains all windows.
                (field(instance, "panelAnimator") as ValueAnimator).end()
                assertPersistentWindows()
                assertEquals(0f, windows[0].attributes.alpha, 0f)
                assertEquals(0f, windows[1].attributes.alpha, 0f)
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            scenario.close()
        }
    }

    @Test fun realGlassWindowsLeaveGapTouchesToUnderlyingActivity() {
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
                val phase = OverlayPanelGeometry.EXTENSION_MS + OverlayPanelGeometry.EDGE_PAUSE_MS +
                    OverlayPanelGeometry.RETRACTION_MS / 2
                invoke(instance, "applyPanelProgress", phase.toFloat())
                val layout = field(instance, "panelLayout")!!
                val geometry = field(layout, "geometry") as OverlayPanelGeometry
                val main = field(instance, "mainSurface") as OverlayGlassWindow
                val mainWindow = (field(main, "dialog") as Dialog).window!!
                val location = IntArray(2)
                mainWindow.decorView.getLocationOnScreen(location)
                x = location[0] + if (geometry.mainX > geometry.cancelX) -geometry.gap / 2
                    else geometry.size + geometry.gap / 2
                y = location[1] + geometry.size / 2
            }
            instrumentation.waitForIdleSync()
            assertTrue(UiDevice.getInstance(instrumentation).click(x, y))
            instrumentation.waitForIdleSync()
            assertTrue("The gap between real animated windows must deliver touches to the activity", touches.get() > 0)
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
            // The same windows survive every phase; replace listeners to avoid duplicate reports.
            listeners.forEach { (window, listener) -> window.removeOnFrameMetricsAvailableListener(listener) }
            listeners.clear()
            val windows = surfaces(instance).map { field(it, "dialog") as Dialog }
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

    @Suppress("UNCHECKED_CAST")
    private fun surfaces(controller: OverlayController): MutableList<OverlayGlassWindow> =
        field(controller, "surfaces") as MutableList<OverlayGlassWindow>

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
    private fun invoke(target: Any, name: String, argument: Any? = null) {
        val method = if (argument == null) target.javaClass.getDeclaredMethod(name)
            else target.javaClass.getDeclaredMethod(name, Float::class.javaPrimitiveType)
        method.isAccessible = true
        if (argument == null) method.invoke(target) else method.invoke(target, argument)
    }
}
