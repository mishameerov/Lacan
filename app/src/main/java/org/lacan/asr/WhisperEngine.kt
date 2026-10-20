package org.lacan.asr

import java.io.File

internal class WhisperEngine(model: File) : AsrEngine {
    private val operation = Any()
    private val lifetime = Any()
    private var pointer = nativeLoad(model.absolutePath).also { check(it != 0L) { "Cannot load Whisper model" } }
    override fun transcribe(samples: FloatArray, language: String): String = synchronized(operation) {
        val handle = synchronized(lifetime) { check(pointer != 0L); pointer }
        if (samples.isEmpty()) return@synchronized ""
        nativeTranscribe(handle, samples, language).toString(Charsets.UTF_8).trim()
    }
    override fun cancel() = synchronized(lifetime) { if (pointer != 0L) nativeCancel(pointer) }
    override fun close() = synchronized(operation) {
        synchronized(lifetime) { if (pointer != 0L) { nativeFree(pointer); pointer = 0 } }
    }
    private external fun nativeLoad(path: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    companion object { init { System.loadLibrary("lacan-whisper") } }
}
