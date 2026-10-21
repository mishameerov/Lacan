package org.lacan

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lacan.ui.LacanScreen
import org.lacan.ui.UiActions

class MainActivity : ComponentActivity() {
    private val runtime get() = LacanApp.runtime
    private var importingModelId: String? = null
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importingModelId
        importingModelId = null
        if (uri != null && id != null) runtime.importModel(id, uri)
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { runtime.refreshPermissions() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        importingModelId = savedInstanceState?.getString("importingModelId")
        setContent { LacanScreen(runtime.state.collectAsStateWithLifecycle().value, actions) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        importingModelId?.let { outState.putString("importingModelId", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); runtime.refreshPermissions() }
    private val actions = object : UiActions {
        override fun microphone() = permission.launch(Manifest.permission.RECORD_AUDIO)
        override fun notifications() = permission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        override fun cancelDownload() = runtime.cancelDownload()
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
