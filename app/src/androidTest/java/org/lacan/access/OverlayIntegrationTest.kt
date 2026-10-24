package org.lacan.access

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lacan.LacanApp
import org.lacan.models.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class OverlayIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun shell(command: String) = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(message, condition())
    }
    @Test fun tapOverlayStartsCaptureAndCancelCleansIt() {
        val config = Configurator.getInstance()
        val oldFlags = config.uiAutomationFlags
        config.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val oldServices = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty()
        val oldOverlay = shell("appops get org.lacan SYSTEM_ALERT_WINDOW")
        val pregranted = InstrumentationRegistry.getArguments().getString("pregranted") == "true"
        try {
            // Samsung retains a disconnected service after instrumentation restarts the app.
            // Rebind only a service the user has already enabled; never grant new access here.
            if (pregranted) {
                val service = "org.lacan/org.lacan.access.LacanAccessibilityService"
                assertTrue("User must enable Lacan accessibility first", oldServices.split(':').contains(service))
                val others = oldServices.split(':').filter { it.isNotBlank() && it != service }.joinToString(":")
                if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
                else shell("settings put secure enabled_accessibility_services $others")
                shell("settings put secure enabled_accessibility_services $oldServices")
            }
            if (!pregranted) {
            val ui = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.grantRuntimePermission("org.lacan", Manifest.permission.RECORD_AUDIO)
            ui.grantRuntimePermission("org.lacan", Manifest.permission.POST_NOTIFICATIONS)
            shell("appops set org.lacan SYSTEM_ALERT_WINDOW allow")
            shell("settings put secure enabled_accessibility_services ${(oldServices.split(':').filter(String::isNotBlank) + "org.lacan/org.lacan.access.LacanAccessibilityService").distinct().joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            }
            await("Accessibility must connect") { LacanAccessibilityService.instance != null }
            val spec = ModelCatalog.models.first { it.id == "whisper-tiny" }
            runBlocking { ModelRepository(context).installVerified(spec, File(context.filesDir, "test-fixtures/test-models/${spec.id}")) }
            instrument.runOnMainSync { LacanApp.runtime.setEnabled(true); LacanApp.runtime.selectModel(spec.id) }
            context.startActivity(Intent().setComponent(ComponentName("org.lacan.fixture", "org.lacan.fixture.TestFieldActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val device = UiDevice.getInstance(instrument)
            val start = device.wait(Until.findObject(By.desc("Начать диктовку")), 7000)
            assertNotNull("Floating start button must appear over external keyboard", start)
            device.takeScreenshot(File(context.filesDir, "overlay-idle.png"))
            start.click()
            await("Overlay tap must start microphone through visible Activity") { LacanApp.runtime.state.value.recording }
            val cancel = device.wait(Until.findObject(By.desc("Отменить диктовку")), 5000)
            assertNotNull(cancel)
            device.takeScreenshot(File(context.filesDir, "overlay-recording.png"))
            cancel.click()
            await("Overlay cancel must settle") { !LacanApp.runtime.state.value.busy }
            assertFalse(LacanApp.runtime.state.value.recording)
        } finally {
            instrument.runOnMainSync { LacanApp.runtime.cancel() }
            if (!pregranted) {
            if (oldServices.isBlank()) { shell("settings delete secure enabled_accessibility_services"); shell("settings put secure accessibility_enabled 0") }
            else shell("settings put secure enabled_accessibility_services $oldServices")
            shell("appops set org.lacan SYSTEM_ALERT_WINDOW ${if (oldOverlay.contains("allow")) "allow" else "default"}")
            }
            config.uiAutomationFlags = oldFlags
        }
    }
}
