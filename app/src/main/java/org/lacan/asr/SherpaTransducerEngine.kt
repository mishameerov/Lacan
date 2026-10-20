package org.lacan.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.lacan.models.ModelSpec
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal fun sherpaTransducerConfig(spec: ModelSpec, directory: File): OfflineRecognizerConfig {
    fun path(name: String) = File(directory, spec.files.single { it.name == name }.name).absolutePath
    return OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = spec.featureDim),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = path("encoder.int8.onnx"),
                decoder = path("decoder.int8.onnx"),
                joiner = path("joiner.int8.onnx")
            ),
            tokens = path("tokens.txt"),
            modelType = "nemo_transducer", numThreads = 4, debug = false, provider = "cpu"
        ),
        decodingMethod = "greedy_search"
    )
}

internal class SherpaTransducerEngine(spec: ModelSpec, directory: File) : AsrEngine {
    private val recognizer = OfflineRecognizer(assetManager = null, config = sherpaTransducerConfig(spec, directory))
    private val cancelled = AtomicBoolean(false)
    private var closed = false

    // Parakeet v3 selects the language from the audio; it has no forced-language option.
    @Synchronized override fun transcribe(samples: FloatArray, language: String): String {
        check(!closed)
        cancelled.set(false)
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            if (cancelled.get()) "" else recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    // Synchronous native decode cannot abort; discard its result and wait before release.
    override fun cancel() { cancelled.set(true) }
    @Synchronized override fun close() { if (!closed) { closed = true; recognizer.release() } }
}
