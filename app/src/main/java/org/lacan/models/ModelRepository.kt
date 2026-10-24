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
import java.util.UUID

class ModelRepository(private val context: Context) {
    companion object { private val downloadLock = Mutex() }
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
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

    private suspend fun publish(spec: ModelSpec, staging: File, checkActive: () -> Unit = {}) {
        val context = currentCoroutineContext()
        ModelIntegrity.publish(staging, directory(spec), spec.files) {
            context.ensureActive()
            checkActive()
            File(staging, ".verified").writeText(manifest(spec))
            context.ensureActive()
            checkActive()
        }
    }
    fun remove(spec: ModelSpec) { check(directory(spec).deleteRecursively()) { "Cannot remove model" } }

    /** Persistent staging belongs to the exact pinned manifest; cancellation keeps resumable bytes. */
    suspend fun download(spec: ModelSpec, checkActive: () -> Unit = {}, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        spec.requireDownloadable()
        downloadLock.withLock {
            if (verifyInstalled(spec)) { onProgress(1f); return@withLock }
            val staging = File(root, ".${spec.id}-download")
            val pinnedManifest = spec.files.joinToString("\n") { "${it.name} ${it.sizeBytes} ${it.sha256} ${it.url}" }
            val marker = File(staging, ".download-manifest")
            val previous = try { marker.readText() } catch (_: java.io.IOException) { null }
            if (previous != pinnedManifest) {
                check(staging.deleteRecursively()) { "Cannot reset model staging directory" }
                check(staging.mkdirs()) { "Cannot create model staging directory" }
                marker.writeText(pinnedManifest)
            }
            val coroutine = currentCoroutineContext()
            val checkDownloadActive = { coroutine.ensureActive(); checkActive() }
            var downloaded = 0L
            for (file in spec.files) {
                checkDownloadActive()
                ResumableModelDownload().download(file, File(staging, file.name), checkDownloadActive) { count ->
                    onProgress(((downloaded + count).toDouble() / spec.totalBytes).toFloat().coerceIn(0f, 1f))
                }
                downloaded += file.sizeBytes
            }
            checkDownloadActive()
            publish(spec, staging, checkDownloadActive)
            onProgress(1f)
        }
    }
}
