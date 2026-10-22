package org.lacan.access

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lacan.ui.ScreenState

/** Synthetic states only: these tests never open the microphone or change the selected model. */
@RunWith(AndroidJUnit4::class)
class OverlayPresentationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Test fun microphoneAmplitudeChangesWaveformHeight() {
        instrument.runOnMainSync {
            val waveform = OverlayWaveformView(context)
            val density = context.resources.displayMetrics.density
            val width = (240 * density).toInt()
            val height = (55 * density).toInt()
            waveform.layout(0, 0, width, height)
            fun occupiedHeight(): Int {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                waveform.draw(Canvas(bitmap))
                var top = height
                var bottom = -1
                for (y in 0 until height) for (x in 0 until width) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 0) { top = minOf(top, y); bottom = maxOf(bottom, y) }
                }
                bitmap.recycle()
                return (bottom - top + 1).coerceAtLeast(0)
            }
            val silenceHeight = occupiedHeight()
            waveform.addLevel(1f)
            assertTrue("Speech amplitude should visibly extend the bars", occupiedHeight() > silenceHeight)
        }
    }

    @Test fun timerContinuesAcrossUiUpdatesAndFreezesAfterStop() {
        instrument.runOnMainSync {
            val controller = OverlayController(context)
            try {
                val elapsed = OverlayController::class.java.getDeclaredMethod("elapsedMillis", ScreenState::class.java).apply { isAccessible = true }
                val recording = ScreenState(recording = true, recordingStartedAtMillis = SystemClock.elapsedRealtime() - 5000)
                assertTrue((elapsed.invoke(controller, recording) as Long) >= 5000)
                val stopped = recording.copy(recording = false, recordingElapsedMillis = 5123)
                assertEquals(5123L, elapsed.invoke(controller, stopped))
            } finally { controller.close() }
        }
    }

    @Test fun syntheticCompositionTransitionsAndScreenshots() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val device = UiDevice.getInstance(instrument)
        // System settings can suppress overlays. Use a known app surface, not whichever
        // protected page the user happened to leave in the foreground.
        val scenario = androidx.test.core.app.ActivityScenario.launch(org.lacan.MainActivity::class.java)
        lateinit var controller: OverlayController
        val recording = ScreenState(
            recording = true, busy = true, notificationsGranted = true, readyToRecord = true,
            recordingStartedAtMillis = SystemClock.elapsedRealtime() - 4000,
            audioLevel = .8f
        )
        instrument.runOnMainSync { controller = OverlayController(context) }
        try {
            instrument.runOnMainSync { controller.update(recording, true, true, false) }
            val stopVisible = device.wait(Until.hasObject(By.desc("Завершить диктовку")), 3000)
            device.takeScreenshot(File(context.filesDir, "overlay013-initial.png"))
            assertTrue("Stop control must remain reachable", stopVisible)
            assertTrue("Cancel control must remain reachable", device.hasObject(By.desc("Отменить диктовку")))
            assertTrue("Recording timer must be visible", device.hasObject(By.descStartsWith("Длительность записи")))
            instrument.waitForIdleSync()
            assertTrue(device.takeScreenshot(File(context.filesDir, "overlay012-waveform.png")))

            val preview = recording.copy(preview = "Проверка стеклянного оверлея")
            instrument.runOnMainSync { controller.update(preview, true, true, false) }
            assertTrue(device.wait(Until.hasObject(By.text(preview.preview)), 3000))
            assertTrue(device.hasObject(By.descStartsWith("Длительность записи")))
            assertTrue(device.takeScreenshot(File(context.filesDir, "overlay012-preview.png")))

            // A successful delivery clears busy/result and collapses to one microphone.
            instrument.runOnMainSync { controller.update(ScreenState(readyToRecord = true), true, true, false) }
            assertTrue(device.wait(Until.hasObject(By.desc("Начать диктовку")), 3000))
            assertTrue(device.wait(Until.gone(By.desc("Отменить диктовку")), 3000))
            assertFalse(device.hasObject(By.descStartsWith("Длительность записи")))

            instrument.runOnMainSync { controller.update(recording.copy(readyToRecord = false), true, true, false) }
            assertTrue(device.wait(Until.hasObject(By.desc("Завершить диктовку")), 3000))
            assertFalse("Cold model keeps recording controls without a pill", device.hasObject(By.descStartsWith("Длительность записи")))

            val preparing = ScreenState(busy = true, notificationsGranted = true)
            instrument.runOnMainSync { controller.update(preparing, true, true, false) }
            instrument.runOnMainSync {
                val control = OverlayController::class.java.getDeclaredField("recordButton").apply { isAccessible = true }
                    .get(controller) as android.widget.FrameLayout
                val spinner = control.getChildAt(0) as android.widget.ProgressBar
                assertTrue("Preparing must use an animated indeterminate spinner", spinner.isIndeterminate)
            }
            assertTrue(device.wait(Until.hasObject(By.desc("Отменить диктовку")), 3000))
            assertFalse("Preparing has no central pill or timer", device.hasObject(By.descStartsWith("Длительность записи")))
            assertFalse(device.hasObject(By.text(preview.preview)))
            assertTrue(device.takeScreenshot(File(context.filesDir, "overlay012-preparing.png")))

            instrument.runOnMainSync { controller.update(preparing, true, true, true) }
            assertTrue("Lock must hide every synthetic surface", device.wait(Until.gone(By.desc("Отменить диктовку")), 3000))
        } finally {
            instrument.runOnMainSync { controller.close() }
            scenario.close()
        }
    }

    @Test fun independentRoundedWindowsUseExactBoundsAndDismissCleanly() {
        assumeTrue("Overlay permission must already be granted", Settings.canDrawOverlays(context))
        val size = (55 * context.resources.displayMetrics.density).toInt()
        val controlView = View(context)
        val pillView = View(context)
        lateinit var control: OverlayGlassWindow
        lateinit var pill: OverlayGlassWindow
        instrument.runOnMainSync {
            control = OverlayGlassWindow(context, controlView, size, size, 0, 100, Color.BLUE)
            pill = OverlayGlassWindow(context, pillView, size * 3, size, size + 10, 100, Color.DKGRAY)
        }
        try {
            instrument.runOnMainSync { control.show(); pill.show() }
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                assertTrue(controlView.isAttachedToWindow)
                assertTrue(pillView.isAttachedToWindow)
                assertEquals(size, controlView.width)
                assertEquals(size, controlView.height)
                assertEquals(size * 3, pillView.width)
                assertEquals(size, pillView.height)
                control.y += size
                pill.y += size
                control.position()
                pill.position()
            }
        } finally {
            instrument.runOnMainSync { pill.close(); control.close() }
        }
        instrument.runOnMainSync {
            assertFalse(controlView.isAttachedToWindow)
            assertFalse(pillView.isAttachedToWindow)
        }
    }
}
