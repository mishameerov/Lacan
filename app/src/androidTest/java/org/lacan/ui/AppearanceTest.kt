package org.lacan.ui

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.lacan.MainActivity
import java.io.File

class AppearanceTest {
    @Test fun lightAndDarkAreReadableAndChoiceSurvivesRecreation() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val old = prefs.getString("theme", null)
        val device = UiDevice.getInstance(instrument)
        fun tap(label: String) {
            val target = device.wait(Until.findObject(By.text(label)), 5000)
            assertNotNull("Missing UI control: $label", target)
            target.click(); device.waitForIdle(); Thread.sleep(350) // Let Material transitions finish before screenshots.
        }
        fun luminance(): Float {
            val bitmap = instrument.uiAutomation.takeScreenshot()!!
            return try { Color.luminance(bitmap.getPixel(3, bitmap.height / 2)) } finally { bitmap.recycle() }
        }
        try {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 256, 256)
            icon.draw(android.graphics.Canvas(bitmap))
            File(context.filesDir, "adaptive-icon.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                device.findObject(By.text("Позже"))?.click()
                tap("Настройки"); tap("Светлая"); tap("Модели")
                assertTrue("Light theme must have a light surface", luminance() > 0.5f)
                device.takeScreenshot(File(context.filesDir, "material-light.png"))
                tap("Настройки"); tap("Тёмная"); tap("Модели")
                assertTrue("Dark theme must have a dark surface", luminance() < 0.15f)
                device.takeScreenshot(File(context.filesDir, "material-dark.png"))
                scenario.recreate(); device.waitForIdle()
                assertEquals("Dark", prefs.getString("theme", null))
                assertTrue("Dark preference must survive recreation", luminance() < 0.15f)
                tap("Модели")
                device.takeScreenshot(File(context.filesDir, "material-models-dark.png"))
            }
        } finally { prefs.edit().apply { if (old == null) remove("theme") else putString("theme", old) }.commit() }
    }
}
