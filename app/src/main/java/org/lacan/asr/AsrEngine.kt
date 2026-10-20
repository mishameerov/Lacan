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
        "vosk" -> VoskEngine(org.lacan.models.VoskModelArchive.prepare(File(directory, spec.files.single().name), directory))
        "whisper" -> WhisperEngine(File(directory, spec.files.single().name))
        "sherpa-whisper" -> SherpaWhisperEngine(spec, directory, language)
        "sherpa-transducer" -> SherpaTransducerEngine(spec, directory)
        "sherpa" -> SherpaEngine(
            File(directory, spec.files.single { it.name.endsWith(".onnx") }.name),
            File(directory, spec.files.single { it.name.endsWith("_tokens.txt") }.name)
        )
        else -> error("Unknown ASR engine: ${spec.engine}")
    }
}
