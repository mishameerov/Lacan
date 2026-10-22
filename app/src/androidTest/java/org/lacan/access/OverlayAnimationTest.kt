package org.lacan.access

import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lacan.MainActivity
import org.lacan.ui.ScreenState
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class OverlayAnimationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun expansionCollapseInterruptionAndHideKeepPanelGeometryConsistent() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val prefs = context.getSharedPreferences("overlay_position", 0)
        val oldX = prefs.getFloat("x", 1f)
        val oldY = prefs.getFloat("y", .5f)
        var controller: OverlayController? = null
        try {
            instrumentation.runOnMainSync {
                context.getSharedPreferences("overlay_position", 0).edit()
                    .putFloat("x", 1f).putFloat("y", .5f).commit()
                val instance = OverlayController(context)
                controller = instance
                instance.update(ScreenState(readyToRecord = true), true, true, false)
                instance.update(ScreenState(
                    recording = true, busy = true, readyToRecord = true,
                    notificationsGranted = true, recordingElapsedMillis = 1200
                ), true, true, false)

                val animator = field(instance, "panelAnimator") as android.animation.ValueAnimator
                assertEquals("Expansion duration", 300L, animator.duration)
                val size = dp(38.5f)
                val gap = dp(10f)
                val surfaces = surfaces(instance)
                assertEquals(3, surfaces.size)
                val cancel = surfaces[0]
                val bubble = surfaces[1]
                val main = surfaces[2]
                val mainX = main.x

                invoke(instance, "applyPanelProgress", 120f)
                assertEquals(size, intField(bubble, "width"))
                assertEquals(gap, mainX - (bubble.x + size))
                assertEquals(gap, bubble.x - (cancel.x + size))

                invoke(instance, "applyPanelProgress", 300f)
                assertEquals(gap, cancel.x)
                assertTrue(intField(bubble, "width") > size)
                assertEquals(gap, mainX - (bubble.x + intField(bubble, "width")))
                assertEquals(gap, bubble.x - (cancel.x + size))

                invoke(instance, "cancelPanelAnimator")
                instance.update(ScreenState(busy = true, cancelling = true), true, true, false)
                val collapse = field(instance, "panelAnimator") as android.animation.ValueAnimator
                assertEquals("Collapse duration", 300L, collapse.duration)
                invoke(instance, "applyPanelProgress", 120f)
                assertEquals(size, intField(bubble, "width"))
                assertEquals(gap, mainX - (bubble.x + size))

                // A fresh recording update interrupts collapse and opens a new panel.
                instance.update(ScreenState(recording = true, busy = true, readyToRecord = true), true, true, false)
                assertEquals(3, surfaces(instance).size)
                assertEquals(300L, (field(instance, "panelAnimator") as android.animation.ValueAnimator).duration)

                instance.hide()
                assertTrue(surfaces(instance).isEmpty())
                assertEquals(null, field(instance, "panelAnimator"))
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            prefs.edit().putFloat("x", oldX).putFloat("y", oldY).commit()
            scenario.close()
        }
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun surfaces(controller: OverlayController): MutableList<OverlayGlassWindow> =
        field(controller, "surfaces") as MutableList<OverlayGlassWindow>

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }

    private fun intField(target: Any, name: String): Int = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        getInt(target)
    }

    private fun invoke(target: Any, name: String, argument: Any? = null) {
        val method = if (argument == null) target.javaClass.getDeclaredMethod(name)
            else target.javaClass.getDeclaredMethod(name, Float::class.javaPrimitiveType)
        method.isAccessible = true
        if (argument == null) method.invoke(target) else method.invoke(target, argument)
    }
}
