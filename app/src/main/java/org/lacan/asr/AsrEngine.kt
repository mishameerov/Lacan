package org.lacan.asr

import org.lacan.models.ModelSpec
import java.io.File

interface AsrEngine : AutoCloseable {
    /** Rolling previews repeat inference on audio that will be decoded again in final segments. */
    val allowsSpeculativePreviews: Boolean get() = true
    fun beginSession(language: String) {}
    fun transcribe(samples: FloatArray, language: String): String
    fun cancel()
}
object EngineFactory {
    fun create(spec: ModelSpec, directory: File, language: String): AsrEngine = when (spec.engine) {
        "mnn-gigaam-gpu" -> MnnGigaAmEngine(spec, directory)
        "litert-parakeet-gpu" -> LiteRtParakeetEngine(spec, directory)
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
