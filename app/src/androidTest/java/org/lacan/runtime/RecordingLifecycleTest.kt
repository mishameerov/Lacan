package org.lacan.runtime

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lacan.LacanApp
import org.lacan.LaunchDictationActivity
import org.lacan.models.ModelCatalog
import org.lacan.models.ModelRepository
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingLifecycleTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtime get() = LacanApp.runtime
    private fun await(message: String, timeout: Long = 30000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(message, condition())
    }
    private fun prepare() = runBlocking {
        val spec = ModelCatalog.models.first { it.id == "whisper-tiny" }
        ModelRepository(context).installVerified(spec, File(context.filesDir, "test-fixtures/test-models/${spec.id}"))
        instrumentation.runOnMainSync { runtime.setEnabled(true); runtime.selectModel(spec.id) }
    }
    private fun start() {
        context.startActivity(Intent(context, LaunchDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Visible launch activity must start microphone") { runtime.state.value.recording }
    }
    @Test fun continuesAcrossAppsThenCancelReleasesMicrophoneAndSpool() {
        prepare(); start()
        UiDevice.getInstance(instrumentation).pressHome()
        Thread.sleep(500)
        assertTrue("Home must not stop dictation", runtime.state.value.recording)
        instrumentation.runOnMainSync { runtime.cancel() }
        await("Cancel must settle") { !runtime.state.value.busy }
        assertFalse(runtime.state.value.recording)
        assertTrue("Cancelled audio must be deleted", File(context.cacheDir, "audio").listFiles().orEmpty().isEmpty())
        assertEquals("", runtime.state.value.result)
    }
    @Test fun screenOffStopsCaptureAndDoesNotResumeOnWake() {
        prepare(); start()
        val device = UiDevice.getInstance(instrumentation)
        try {
            device.sleep()
            await("Screen off must stop microphone") { !runtime.state.value.recording }
            await("Silent recording must finish") { !runtime.state.value.busy }
        } finally { device.wakeUp() }
        Thread.sleep(300)
        assertFalse("Unlock must not restart recording", runtime.state.value.recording)
        assertTrue(File(context.cacheDir, "audio").listFiles().orEmpty().isEmpty())
    }
}
