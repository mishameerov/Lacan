package org.lacan.models

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class ModelRepository(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val downloadLock = Mutex()
    fun directory(spec: ModelSpec): File = File(root, spec.id)
    private fun manifest(spec: ModelSpec) = spec.files.joinToString("\n") { "${it.name} ${it.sizeBytes} ${it.sha256}" }
    fun isInstalled(spec: ModelSpec): Boolean = try {
        File(directory(spec), ".verified").readText() == manifest(spec) &&
            spec.files.all { File(directory(spec), it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }
    } catch (_: java.io.IOException) { false }
    suspend fun verifyInstalled(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        isInstalled(spec) && spec.files.all {
            currentCoroutineContext().ensureActive()
            ModelIntegrity.verify(File(directory(spec), it.name), it)
        }
    }
    /** Imports user/test supplied data through the same hash-checked atomic publication path. */
    suspend fun installVerified(spec: ModelSpec, source: File) = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val staging = File(root, ".${spec.id}-${UUID.randomUUID()}")
            check(staging.mkdirs())
            try {
                spec.files.forEach {
                    currentCoroutineContext().ensureActive()
                    val coroutine = currentCoroutineContext()
                    File(source, it.name).inputStream().buffered().use { input ->
                        File(staging, it.name).outputStream().buffered().use { output ->
                            copyModelFile(input, output, it.sizeBytes, checkActive = { coroutine.ensureActive() })
                        }
                    }
                }
                publish(spec, staging)
            } finally { staging.deleteRecursively() }
        }
    }
    /** SAF supplies only the pinned model; auxiliary token bytes come from licensed app assets. */
    suspend fun importLocal(spec: ModelSpec, uri: Uri, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val importedFile = requireNotNull(spec.localImportFile) { "This model does not support local import" }
        require(!spec.downloadable && spec.files.all { it.url == null })
        downloadLock.withLock {
            val staging = File(root, ".${spec.id}-${UUID.randomUUID()}")
            check(staging.mkdirs()) { "Cannot create model staging directory" }
            try {
                val coroutine = currentCoroutineContext()
                var copied = 0L
                for (file in spec.files) {
                    coroutine.ensureActive()
                    val source = if (file.name == importedFile.name) {
                        requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open model document" }
                    } else context.assets.open("models/${file.name}")
                    source.buffered().use { input ->
                        File(staging, file.name).outputStream().buffered().use { output ->
                            copyModelFile(input, output, file.sizeBytes,
                                onProgress = { count -> onProgress(((copied + count).toDouble() / spec.totalBytes).toFloat().coerceIn(0f, 1f)) },
                                checkActive = { coroutine.ensureActive() })
                        }
                    }
                    copied += file.sizeBytes
                }
                coroutine.ensureActive()
                publish(spec, staging)
                onProgress(1f)
            } finally { staging.deleteRecursively() }
        }
    }

    private suspend fun publish(spec: ModelSpec, staging: File) {
        val context = currentCoroutineContext()
        ModelIntegrity.publish(staging, directory(spec), spec.files) {
            context.ensureActive()
            File(staging, ".verified").writeText(manifest(spec))
            context.ensureActive()
        }
    }
    fun remove(spec: ModelSpec) { check(directory(spec).deleteRecursively()) { "Cannot remove model" } }

    suspend fun download(spec: ModelSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        spec.requireDownloadable()
        downloadLock.withLock {
            if (isInstalled(spec)) { onProgress(1f); return@withLock }
            val staging = File(root, ".${spec.id}-${UUID.randomUUID()}")
            check(staging.mkdirs()) { "Cannot create model staging directory" }
            try {
                var downloaded = 0L
                for (file in spec.files) {
                    currentCoroutineContext().ensureActive()
                    var url = URL(requireNotNull(file.url))
                    var connection: HttpsURLConnection? = null
                    // HF redirects to its HTTPS content store; never follow a downgrade.
                    for (redirect in 0..5) {
                        require(url.protocol == "https") { "HTTPS required" }
                        val candidate = (url.openConnection() as HttpsURLConnection).apply {
                            instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 15000
                        }
                        val status = candidate.responseCode
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            val location = candidate.getHeaderField("Location")
                            candidate.disconnect()
                            check(location != null && redirect < 5) { "Invalid model redirect" }
                            url = URL(url, location)
                        } else { connection = candidate; break }
                    }
                    val active = checkNotNull(connection)
                    try {
                        check(active.responseCode == 200) { "Model download HTTP ${active.responseCode}" }
                        active.inputStream.buffered().use { input ->
                            File(staging, file.name).outputStream().buffered().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var fileBytes = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    fileBytes += count
                                    check(fileBytes <= file.sizeBytes) { "Model exceeds expected size" }
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    onProgress((downloaded.toDouble() / spec.totalBytes).toFloat().coerceIn(0f, 1f))
                                }
                            }
                        }
                    } finally { active.disconnect() }
                }
                currentCoroutineContext().ensureActive()
                publish(spec, staging)
                onProgress(1f)
            } finally { staging.deleteRecursively() }
        }
    }
}
