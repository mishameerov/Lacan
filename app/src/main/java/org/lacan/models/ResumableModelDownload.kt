package org.lacan.models

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class ModelDownloadException(message: String, val retryable: Boolean = false) : IOException(message)

internal interface ModelDownloadResponse : Closeable {
    val status: Int
    fun header(name: String): String?
    fun body(): InputStream
}

/** The pinned SHA is the authority, including when a server ignores Range or changes its ETag. */
internal class ResumableModelDownload(
    private val request: (URL, Long) -> ModelDownloadResponse = { url, offset ->
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            object : ModelDownloadResponse {
                override val status = code
                override fun header(name: String): String? = connection.getHeaderField(name)
                override fun body(): InputStream = connection.inputStream
                override fun close() = connection.disconnect()
            }
        } catch (failure: Throwable) { connection.disconnect(); throw failure }
    }
) {
    fun download(spec: ModelFile, destination: File, checkActive: () -> Unit = {}, onProgress: (Long) -> Unit = {}) {
        checkActive()
        if (ModelIntegrity.verify(destination, spec)) { onProgress(spec.sizeBytes); return }
        if (destination.length() >= spec.sizeBytes) check(destination.delete()) { "Cannot reset invalid model file" }
        var resetAfter416 = false
        while (true) {
            checkActive()
            val offset = destination.length()
            val response = openFollowingRedirects(URL(requireNotNull(spec.url)), offset, checkActive)
            var restart = false
            response.use { active ->
                if (active.status == 416 && !resetAfter416) {
                    // A full file was checked above. A shorter partial cannot satisfy the pinned artifact.
                    if (destination.exists()) check(destination.delete()) { "Cannot reset partial model" }
                    resetAfter416 = true
                    restart = true
                    return@use
                }
                val append = when (active.status) {
                    200 -> false
                    206 -> {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(active.header("Content-Range").orEmpty())
                            ?: throw ModelDownloadException("Invalid model Content-Range")
                        val (start, end, total) = range.destructured
                        if (start.toLongOrNull() != offset || total.toLongOrNull() != spec.sizeBytes ||
                            end.toLongOrNull() != spec.sizeBytes - 1) throw ModelDownloadException("Model Content-Range does not match pinned file")
                        true
                    }
                    else -> throw ModelDownloadException("Model download HTTP ${active.status}", active.status >= 500 || active.status == 408 || active.status == 429)
                }
                val expected = spec.sizeBytes - if (append) offset else 0L
                active.header("Content-Length")?.toLongOrNull()?.let {
                    if (it != expected) throw ModelDownloadException("Model response size does not match pinned file")
                }
                var received = if (append) offset else 0L
                onProgress(received)
                active.body().buffered().use { input ->
                    FileOutputStream(destination, append).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (received + count > spec.sizeBytes) throw ModelDownloadException("Model exceeds expected size")
                            output.write(buffer, 0, count)
                            received += count
                            onProgress(received)
                        }
                        output.fd.sync()
                    }
                }
                if (received != spec.sizeBytes) throw IOException("Model transfer interrupted; partial download saved")
                checkActive()
                if (!ModelIntegrity.verify(destination, spec)) {
                    destination.delete()
                    throw ModelDownloadException("Model integrity check failed")
                }
            }
            if (!restart) return
        }
    }

    private fun openFollowingRedirects(initial: URL, offset: Long, checkActive: () -> Unit): ModelDownloadResponse {
        var url = initial
        repeat(6) { redirect ->
            checkActive()
            require(url.protocol == "https") { "HTTPS required for model download" }
            val response = request(url, offset)
            if (response.status !in listOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            response.close()
            if (location == null || redirect == 5) throw ModelDownloadException("Invalid model redirect")
            url = URL(url, location)
        }
        throw ModelDownloadException("Too many model redirects")
    }
}
