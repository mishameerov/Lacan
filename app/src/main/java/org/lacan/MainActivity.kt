package org.lacan

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.lacan.ui.LacanScreen
import org.lacan.ui.UiActions

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val EXTRA_SETUP_REASON = "setup_reason"
    }

    private val runtime get() = LacanApp.runtime
    private var importingModelId: String? = null
    private var pendingSetupModelId: String? = null
    private var openSettingsRequest by mutableIntStateOf(0)
    private var setupMessage by mutableStateOf<String?>(null)
    private val permissionPreferences by lazy { getSharedPreferences("permission_requests", MODE_PRIVATE) }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importingModelId
        importingModelId = null
        if (uri != null && id != null) runtime.importModel(id, uri)
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runtime.refreshPermissions()
        if (!granted) explainDenied(Manifest.permission.RECORD_AUDIO)
    }
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runtime.refreshPermissions()
        if (!granted) explainDenied(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        importingModelId = savedInstanceState?.getString("importingModelId")
        pendingSetupModelId = savedInstanceState?.getString("pendingSetupModelId")
        handleIntent(intent)
        lifecycleScope.launch {
            runtime.state.collect { state ->
                val id = pendingSetupModelId
                if (id != null && state.downloadingId == null && state.models.any { it.id == id && it.installed }) {
                    pendingSetupModelId = null
                    runtime.selectModel(id)
                } else if (id != null && state.downloadingId == null &&
                    (state.status.startsWith("Не удалось") || state.status.contains("отменена"))) {
                    pendingSetupModelId = null
                }
            }
        }
        setContent {
            LacanScreen(
                runtime.state.collectAsStateWithLifecycle().value, actions,
                openSettingsRequest = openSettingsRequest, setupMessage = setupMessage,
                onSetupMessageConsumed = { setupMessage = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openSettingsRequest++
        intent.getStringExtra(EXTRA_SETUP_REASON)?.let { setupMessage = it }
        intent.removeExtra(EXTRA_OPEN_SETTINGS)
        intent.removeExtra(EXTRA_SETUP_REASON)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        importingModelId?.let { outState.putString("importingModelId", it) }
        pendingSetupModelId?.let { outState.putString("pendingSetupModelId", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); runtime.refreshPermissions() }

    private fun appSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun notificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun requestPermission(name: String) {
        if (checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) {
            if (name == Manifest.permission.POST_NOTIFICATIONS) notificationSettings() else appSettings()
            return
        }
        if (permissionPreferences.getBoolean(name, false) && !shouldShowRequestPermissionRationale(name)) {
            explainDenied(name)
        } else if (shouldShowRequestPermissionRationale(name)) {
            explainDenied(name)
        } else launchPermission(name)
    }

    private fun launchPermission(name: String) {
        permissionPreferences.edit().putBoolean(name, true).apply()
        if (name == Manifest.permission.RECORD_AUDIO) microphonePermission.launch(name)
        else notificationsPermission.launch(name)
    }

    private fun explainDenied(name: String) {
        val microphone = name == Manifest.permission.RECORD_AUDIO
        val canRetry = shouldShowRequestPermissionRationale(name)
        AlertDialog.Builder(this)
            .setTitle(if (microphone) "Доступ к микрофону" else "Уведомления Lacan")
            .setMessage(if (microphone) "Для диктовки нужен микрофон. Разрешите запись звука. Если Android больше не показывает запрос, откройте «Разрешения» → «Микрофон» в настройках приложения."
                else "Уведомления нужны для управления записью из другого приложения. Разрешите уведомления и включите канал диктовки в системных настройках.")
            .setPositiveButton(if (canRetry) "Повторить запрос" else "Открыть настройки") { _, _ ->
                if (canRetry) launchPermission(name) else if (microphone) appSettings() else notificationSettings()
            }
            .setNeutralButton("Настройки") { _, _ -> if (microphone) appSettings() else notificationSettings() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private val actions = object : UiActions {
        override fun microphone() = requestPermission(Manifest.permission.RECORD_AUDIO)
        override fun notifications() = requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        override fun overlay() { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        override fun accessibility() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        override fun setEnabled(value: Boolean) = runtime.setEnabled(value)
        override fun setLanguage(value: String) = runtime.setLanguage(value)
        override fun importModel(id: String) {
            if (runtime.state.value.busy || runtime.state.value.downloadingId != null) return
            importingModelId = id
            importDocument.launch(arrayOf("*/*"))
        }
        override fun download(id: String) = runtime.download(id)
        override fun downloadForSetup(id: String) {
            if (runtime.state.value.busy || runtime.state.value.recording || runtime.state.value.downloadingId != null) return
            pendingSetupModelId = id
            runtime.download(id)
        }
        override fun cancelDownload() { pendingSetupModelId = null; runtime.cancelDownload() }
        override fun selectModel(id: String) = runtime.selectModel(id)
        override fun removeModel(id: String) = runtime.removeModel(id)
        override fun start() { startActivity(Intent(this@MainActivity, LaunchDictationActivity::class.java)) }
        override fun stop() = runtime.stop()
        override fun cancel() = runtime.cancel()
        override fun copy(text: String) = runtime.copy(text)
        override fun deleteHistory(id: String) = runtime.deleteHistory(id)
        override fun clearHistory() = runtime.clearHistory()
        override fun dismissResult() = runtime.dismissResult()
    }
}
