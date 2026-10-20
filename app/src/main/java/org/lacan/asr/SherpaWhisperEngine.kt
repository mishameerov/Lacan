package org.lacan.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.lacan.models.ModelSpec
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal fun sherpaWhisperConfig(spec: ModelSpec, directory: File, language: String): OfflineRecognizerConfig =
    OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = File(directory, spec.files.single { it.name.endsWith("-encoder.int8.onnx") }.name).absolutePath,
                decoder = File(directory, spec.files.single { it.name.endsWith("-decoder.int8.onnx") }.name).absolutePath,
                language = if (language == "auto") "" else language,
                task = "transcribe"
            ),
            tokens = File(directory, spec.files.single { it.name.endsWith("-tokens.txt") }.name).absolutePath,
            modelType = "whisper", numThreads = 4, debug = false, provider = "cpu"
        ),
        decodingMethod = "greedy_search"
    )

internal class SherpaWhisperEngine(spec: ModelSpec, directory: File, language: String) : AsrEngine {
    private val config = sherpaWhisperConfig(spec, directory, language)
    private val recognizer = OfflineRecognizer(assetManager = null, config = config)
    private val cancelled = AtomicBoolean(false)
    private var closed = false

    @Synchronized override fun transcribe(samples: FloatArray, language: String): String {
        check(!closed)
        cancelled.set(false)
        if (samples.isEmpty()) return ""
        // Empty language requests fresh detection for each chunk, with no cached result.
        config.modelConfig.whisper.language = if (language == "auto") "" else language
        recognizer.setConfig(config)
        val parts = mutableListOf<String>()
        // Sherpa Whisper drops input beyond 29.5 seconds. Runtime VAD normally
        // sends shorter segments; retain all audio for other callers as well.
        val window = 25 * 16000
        var offset = 0
        while (offset < samples.size && !cancelled.get()) {
            val end = minOf(offset + window, samples.size)
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(if (offset == 0 && end == samples.size) samples else samples.copyOfRange(offset, end), 16000)
                recognizer.decode(stream)
                if (!cancelled.get()) parts.add(recognizer.getResult(stream).text.trim())
            } finally { stream.release() }
            offset = end
        }
        return if (cancelled.get()) "" else parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    // Sherpa's synchronous decode has no abort API. Drop its result and skip
    // subsequent windows; close waits until the native call has returned.
    override fun cancel() { cancelled.set(true) }
    @Synchronized override fun close() { if (!closed) { closed = true; recognizer.release() } }
}
