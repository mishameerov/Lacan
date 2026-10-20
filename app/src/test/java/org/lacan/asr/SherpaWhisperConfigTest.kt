package org.lacan.asr

import org.junit.Assert.*
import org.junit.Test
import org.lacan.models.ModelCatalog
import java.io.File

class SherpaWhisperConfigTest {
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
