package org.lacan.asr

import org.lacan.models.ModelSpec
import java.io.File

interface AsrEngine : AutoCloseable {
    fun beginSession(language: String) {}
    fun transcribe(samples: FloatArray, language: String): String
    fun cancel()
}
object EngineFactory {
    fun create(spec: ModelSpec, directory: File, language: String): AsrEngine = when (spec.engine) {
        "whisper" -> WhisperEngine(File(directory, spec.files.single().name))
        "sherpa" -> SherpaEngine(directory)
        else -> error("Unknown ASR engine: ${spec.engine}")
    }
}
