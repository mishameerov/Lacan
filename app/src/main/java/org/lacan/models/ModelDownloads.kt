package org.lacan.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Queued IDs includes the active model. A failed model can be enqueued again to resume its bytes. */
data class ModelDownloadState(
    val activeId: String? = null,
    val progress: Map<String, Float> = emptyMap(),
    val queuedIds: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val completionVersion: Long = 0
)

class ModelDownloads private constructor(private val context: Context) {
    companion object {
        private const val WORK = "model-download-queue"
        @Volatile private var instance: ModelDownloads? = null
        fun get(context: Context): ModelDownloads = instance ?: synchronized(this) {
            instance ?: ModelDownloads(context.applicationContext).also { instance = it }
        }
    }
    private val preferences = context.getSharedPreferences("model_download_queue", Context.MODE_PRIVATE)
    private val lock = Any()
    private val queue = readArray(preferences.getString("queue", null)).filter { id -> ModelCatalog.models.any { it.id == id && it.downloadable } }.toMutableList()
    private val progress = readObject(preferences.getString("progress", null)).let { json ->
        json.keys().asSequence().associateWith { json.optDouble(it, 0.0).toFloat().coerceIn(0f, 1f) }.toMutableMap()
    }
    private val errors = readObject(preferences.getString("errors", null)).let { json ->
        json.keys().asSequence().associateWith { json.optString(it) }.toMutableMap()
    }
    private var activeId: String? = null
    private var completionVersion = preferences.getLong("completion", 0)
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<ModelDownloadState> = mutableState.asStateFlow()

    init {
        // Repairs the narrow process-death window between saving the queue and scheduling work.
        if (queue.isNotEmpty()) schedule(ExistingWorkPolicy.KEEP)
    }
    fun enqueue(id: String) {
        ModelCatalog.models.single { it.id == id }.requireDownloadable()
        synchronized(lock) {
            if (id !in queue) { queue += id; progress.putIfAbsent(id, 0f) }
            errors.remove(id)
            save()
            // Appending also covers enqueue racing with an almost-finished drain worker.
            schedule(ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }
    fun cancel(id: String) = synchronized(lock) {
        queue.remove(id)
        progress.remove(id)
        errors.remove(id)
        if (activeId == id) activeId = null
        save()
    }
    internal fun next(): String? = synchronized(lock) {
        queue.firstOrNull()?.also { activeId = it; errors.remove(it); save() }
    }
    internal fun contains(id: String): Boolean = synchronized(lock) { id in queue }
    internal fun progress(id: String, value: Float) = synchronized(lock) {
        if (id !in queue) return@synchronized
        val previous = progress[id] ?: 0f
        progress[id] = value
        if ((previous * 100).toInt() != (value * 100).toInt()) save() else emit()
    }
    internal fun complete(id: String) = synchronized(lock) {
        queue.remove(id)
        progress.remove(id)
        errors.remove(id)
        activeId = null
        completionVersion++
        save()
    }
    internal fun failed(id: String, message: String, retry: Boolean) = synchronized(lock) {
        if (id in queue) {
            errors[id] = message
            if (!retry) queue.remove(id)
        }
        activeId = null
        save()
    }
    internal fun stopped() = synchronized(lock) { activeId = null; save() }
    private fun schedule(policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, work)
    }
    private fun snapshot() = ModelDownloadState(activeId, progress.toMap(), queue.toSet(), errors.toMap(), completionVersion)
    private fun emit() { mutableState.value = snapshot() }
    private fun save() {
        check(preferences.edit().putString("queue", JSONArray(queue).toString())
            .putString("progress", JSONObject(progress.toMap()).toString())
            .putString("errors", JSONObject(errors.toMap()).toString())
            .putLong("completion", completionVersion).commit()) { "Cannot save model download queue" }
        emit()
    }
    private fun readArray(value: String?): List<String> = try {
        val array = JSONArray(value ?: "[]")
        (0 until array.length()).map { array.getString(it) }
    } catch (_: org.json.JSONException) { emptyList() }
    private fun readObject(value: String?): JSONObject = try { JSONObject(value ?: "{}") } catch (_: org.json.JSONException) { JSONObject() }
}
