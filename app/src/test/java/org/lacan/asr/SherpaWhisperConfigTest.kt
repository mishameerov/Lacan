package org.lacan.asr

import org.junit.Assert.*
import org.junit.Test
import org.lacan.models.ModelCatalog
import java.io.File

class SherpaWhisperConfigTest {
    @Test fun frontendAndPathsMatchEachMultilingualWhisperExport() {
        val expectedBins = mapOf("whisper-base-onnx" to 80, "whisper-small-onnx" to 80,
            "whisper-medium-onnx" to 80, "whisper-large-v3-onnx" to 128)
        expectedBins.forEach { (id, bins) ->
            val spec = ModelCatalog.models.single { it.id == id }
            val config = sherpaWhisperConfig(spec, File("/models/$id"), "auto")
            assertEquals(bins, config.featConfig.featureDim)
            assertEquals(16000, config.featConfig.sampleRate)
            assertEquals("greedy_search", config.decodingMethod)
            assertEquals("", config.modelConfig.whisper.language)
            assertTrue(config.modelConfig.whisper.encoder.endsWith(spec.files.single { it.name.endsWith("-encoder.int8.onnx") }.name))
            assertTrue(config.modelConfig.whisper.decoder.endsWith(spec.files.single { it.name.endsWith("-decoder.int8.onnx") }.name))
        }
    }

    @Test fun autoLanguageUsesDetectionAndForcedLanguageCanBeReset() {
        val spec = ModelCatalog.models.single { it.id == "whisper-base-onnx" }
        val config = sherpaWhisperConfig(spec, File("/models/base"), "ru")
        assertEquals("ru", config.modelConfig.whisper.language)
        assertEquals("", sherpaWhisperConfig(spec, File("/models/base"), "auto").modelConfig.whisper.language)
        assertEquals("en", sherpaWhisperConfig(spec, File("/models/base"), "en").modelConfig.whisper.language)
        assertEquals("transcribe", config.modelConfig.whisper.task)
        assertEquals("cpu", config.modelConfig.provider)
        assertEquals("whisper", config.modelConfig.modelType)
        assertEquals("/models/base/base-encoder.int8.onnx", config.modelConfig.whisper.encoder)
        assertEquals("/models/base/base-decoder.int8.onnx", config.modelConfig.whisper.decoder)
        assertEquals("/models/base/base-tokens.txt", config.modelConfig.tokens)
    }
}
