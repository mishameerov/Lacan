package org.lacan.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lacan.MainActivity

/** Synthetic UI states and no-op actions: never requests permissions or downloads models. */
@RunWith(AndroidJUnit4::class)
class SetupUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val device get() = UiDevice.getInstance(instrument)
    private val installedModels = listOf(
        ModelCard("gigaam", "GigaAM CPU", "Русская речь", 250, installed = true, selected = true),
        ModelCard("parakeet", "Parakeet CPU", "Русский и английский", 600, installed = false, selected = false)
    )
    private val configured = ScreenState(
        microphoneGranted = true, notificationsGranted = true, overlayGranted = true,
        accessibilityEnabled = true, enabled = true, models = installedModels
    )

    @Test fun modelIsFirstAndCompletedStepsAdvanceWithoutPermissionActions() = withScreen(showWizard = true) { scenario ->
        val snapshot = mutableStateOf(ScreenState(models = installedModels.map { it.copy(installed = false) }))
        scenario.onActivity { activity ->
            activity.setContent { key("synthetic-setup") { LacanScreen(snapshot.value, NoOpActions) } }
        }
        visible("Выберите модель")
        assertFalse("Microphone must follow model installation", device.hasObject(By.text("Разрешите микрофон")))

        scenario.onActivity { snapshot.value = snapshot.value.copy(models = installedModels) }
        visible("Разрешите микрофон")
        assertFalse(device.hasObject(By.text("Выберите модель")))

        scenario.onActivity { snapshot.value = snapshot.value.copy(microphoneGranted = true) }
        visible("Включите уведомления")

        // Simulates returning from system settings with several grants already completed.
        scenario.onActivity { snapshot.value = snapshot.value.copy(notificationsGranted = true, overlayGranted = true) }
        visible("Включите ввод в поле")
        assertFalse("A completed overlay step must be skipped", device.hasObject(By.text("Разрешите плавающую панель")))

        scenario.onActivity { snapshot.value = snapshot.value.copy(accessibilityEnabled = true, enabled = false) }
        visible("Включите Lacan")
        scenario.onActivity { snapshot.value = snapshot.value.copy(enabled = true) }
        gone("Настройка Lacan")
        assertFalse("No permission dialog should be launched by a synthetic state change", device.hasObject(By.text("Разрешить микрофон")))
    }

    @Test fun pendingDownloadOffersNextAndQueuedCancellationWithoutGrantingPermissions() = withScreen(showWizard = true) { scenario ->
        val snapshot = mutableStateOf(ScreenState(
            models = installedModels.map { it.copy(installed = false, selected = false) },
            setupModelId = "gigaam", downloadingId = "gigaam", downloadProgress = 0.25f,
            queuedDownloadIds = setOf("parakeet"), status = "Загрузка модели…"
        ))
        var cancelledId: String? = null
        val actions = object : UiActions by NoOpActions {
            override fun cancelDownload(id: String) { cancelledId = id }
        }
        scenario.onActivity { activity ->
            activity.setContent { key("synthetic-downloading-setup") { LacanScreen(snapshot.value, actions) } }
        }
        visible("Далее")
        assertFalse(device.hasObject(By.text("Позже")))
        val queue = device.wait(Until.findObject(By.text("В очереди · 0%")), 5000)
        assertTrue("Queued model must show zero progress", queue != null)
        val cancelButtons = device.findObjects(By.text("Отменить"))
        assertTrue("Both active and queued models can be cancelled", cancelButtons.size == 2)
        cancelButtons.last().click()
        instrument.waitForIdleSync()
        assertTrue("Cancellation must target the queued model", cancelledId == "parakeet")
        device.findObject(By.text("Далее")).click()
        visible("Разрешите микрофон")
        assertFalse("Continuing setup must not grant microphone access", snapshot.value.microphoneGranted)
        assertTrue("Dictation remains blocked until installation", snapshot.value.setupBlockReason() != null)
    }

    @Test fun settingsRequestHidesWizardAndShowsReasonInSnackbar() = withScreen(showWizard = true) { scenario ->
        val request = mutableStateOf(0)
        val message = mutableStateOf<String?>(null)
        val reason = "Для диктовки разрешите микрофон — тест"
        scenario.onActivity { activity ->
            activity.setContent {
                key("synthetic-settings-request") {
                    LacanScreen(
                        ScreenState(models = installedModels), NoOpActions,
                        openSettingsRequest = request.value, setupMessage = message.value,
                        onSetupMessageConsumed = { message.value = null }
                    )
                }
            }
        }
        visible("Разрешите микрофон")
        scenario.onActivity { request.value = 1; message.value = reason }
        visible(reason)
        visible("Открыть мастер настройки")
        visible("Настройки")
        gone("Разрешите микрофон")
        assertFalse(device.hasObject(By.text("Позже")))
    }

    @Test fun configuredUsersSeeThreeTabsWithoutDictationOrAutomaticWizard() = withScreen(showWizard = true) { scenario ->
        scenario.onActivity { activity ->
            activity.setContent { key("synthetic-configured") { LacanScreen(configured, NoOpActions) } }
        }
        visible("Модели")
        visible("История")
        visible("Настройки")
        assertFalse("Removed demo tab must not be present", device.hasObject(By.text("Диктовка")))
        assertFalse("Configured users must not get an automatic wizard", device.hasObject(By.text("Настройка Lacan")))
        assertFalse(device.hasObject(By.text("Начать диктовку")))
        assertFalse(device.hasObject(By.text("Предварительный текст")))
        assertFalse(device.hasObject(By.text("Результат")))
    }

    private fun visible(text: String) {
        assertTrue("Expected visible UI text: $text", device.wait(Until.hasObject(By.text(text)), 5000))
    }

    private fun gone(text: String) {
        assertTrue("Expected UI text to disappear: $text", device.wait(Until.gone(By.text(text)), 5000))
    }

    private fun withScreen(showWizard: Boolean, test: (ActivityScenario<MainActivity>) -> Unit) {
        val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
        val hadPresented = prefs.contains("presented")
        val oldPresented = prefs.getBoolean("presented", false)
        // Suppress the real activity composition before replacing it with a synthetic one.
        prefs.edit().putBoolean("presented", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Detach MainActivity's real ComposeView before changing setup preferences.
                // Replacing only its content can race the original composition's effects;
                // a fresh view also guarantees fresh rememberSaveable state for this fixture.
                scenario.onActivity { activity ->
                    activity.setContentView(android.widget.FrameLayout(activity))
                }
                instrument.waitForIdleSync()
                scenario.onActivity { prefs.edit().putBoolean("presented", !showWizard).commit() }
                test(scenario)
            }
        } finally {
            prefs.edit().apply {
                if (hadPresented) putBoolean("presented", oldPresented) else remove("presented")
            }.commit()
        }
    }

    private object NoOpActions : UiActions {
        override fun microphone() = Unit
        override fun notifications() = Unit
        override fun overlay() = Unit
        override fun accessibility() = Unit
        override fun setEnabled(value: Boolean) = Unit
        override fun setLanguage(value: String) = Unit
        override fun importModel(id: String) = Unit
        override fun download(id: String) = Unit
        override fun cancelDownload() = Unit
        override fun selectModel(id: String) = Unit
        override fun removeModel(id: String) = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun copy(text: String) = Unit
        override fun deleteHistory(id: String) = Unit
        override fun clearHistory() = Unit
        override fun dismissResult() = Unit
    }
}
