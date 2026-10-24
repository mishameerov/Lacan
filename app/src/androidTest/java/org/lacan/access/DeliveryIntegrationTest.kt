package org.lacan.access

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
    private fun fixture(password: Boolean) {
        instrument.targetContext.startActivity(Intent().setComponent(ComponentName("org.lacan.fixture", "org.lacan.fixture.TestFieldActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("password", password))
    }
    @Test fun insertsAtCursorInOtherAppButNeverIntoPassword() {
        val oldFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val old = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty()
        val component = "org.lacan/org.lacan.access.LacanAccessibilityService"
        val pregranted = InstrumentationRegistry.getArguments().getString("pregranted") == "true"
        try {
            // Samsung retains a disconnected service after instrumentation restarts the app.
            // Rebind only a service the user has already enabled; never grant new access here.
            if (pregranted) {
                val service = "org.lacan/org.lacan.access.LacanAccessibilityService"
                assertTrue("User must enable Lacan accessibility first", old.split(':').contains(service))
                val others = old.split(':').filter { it.isNotBlank() && it != service }.joinToString(":")
                if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
                else shell("settings put secure enabled_accessibility_services $others")
                shell("settings put secure enabled_accessibility_services $old")
            }
            if (!pregranted) {
            shell("settings put secure enabled_accessibility_services ${(old.split(':').filter(String::isNotBlank) + component).distinct().joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            }
            val deadline = System.currentTimeMillis() + 10000
            while (LacanAccessibilityService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertNotNull("Accessibility service must connect", LacanAccessibilityService.instance)
            fixture(false)
            val device = UiDevice.getInstance(instrument)
            assertNotNull(device.wait(Until.findObject(By.desc("Поле для проверки вставки")), 5000))
            Thread.sleep(1000) // InputConnection is asynchronous; bounded setup, not a performance assertion.
            val result = runBlocking { LacanAccessibilityService.instance!!.deliver("тест ") }
            assertEquals("inserted", result)
            assertTrue(device.wait(Until.hasObject(By.text("начало тест конец")), 3000))
            // Long dictation must still confirm with a bounded surrounding-text read.
            val longText = "длинная диктовка ".repeat(350)
            assertEquals("inserted", runBlocking { LacanAccessibilityService.instance!!.deliver(longText) })
            fixture(true)
            assertNotNull(device.wait(Until.findObject(By.desc("Поле для проверки вставки")), 5000))
            Thread.sleep(700)
            assertEquals("manual", runBlocking { LacanAccessibilityService.instance!!.deliver("секрет") })
        } finally {
            if (!pregranted) {
            if (old.isEmpty()) shell("settings delete secure enabled_accessibility_services") else shell("settings put secure enabled_accessibility_services $old")
            if (old.isEmpty()) shell("settings put secure accessibility_enabled 0")
            }
            Configurator.getInstance().uiAutomationFlags = oldFlags
        }
    }
}
