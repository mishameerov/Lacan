package org.lacan.asr

import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class SherpaEngine(model: File, tokens: File) : AsrEngine {
    private val cancelled = AtomicBoolean(false)
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            // ONNX input is [batch, 64, time]. Sherpa also enforces 64 from
            // is_giga_am metadata and configures its GigaAM-specific frontend.
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = 4, debug = false, provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
    )
    private var closed = false
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
    override fun cancel() { cancelled.set(true) }
    @Synchronized override fun close() { if (!closed) { closed = true; recognizer.release() } }
}
