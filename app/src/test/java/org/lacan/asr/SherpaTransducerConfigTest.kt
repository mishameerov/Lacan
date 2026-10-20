package org.lacan.asr

import org.junit.Assert.*
import org.junit.Test
import org.lacan.models.ModelCatalog
import java.io.File

class SherpaTransducerConfigTest {
    @Test fun parakeetUsesNemoTdtTransducerWithAllThreeWeightsAnd128BinFrontend() {
        val spec = ModelCatalog.models.single { it.id == "parakeet-tdt-0-6b-v3" }
        val config = sherpaTransducerConfig(spec, File("/models/parakeet"))
        assertEquals("sherpa-transducer", spec.engine)
        assertEquals(128, config.featConfig.featureDim)
        assertEquals(16000, config.featConfig.sampleRate)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals("cpu", config.modelConfig.provider)
        assertEquals("/models/parakeet/encoder.int8.onnx", config.modelConfig.transducer.encoder)
        assertEquals("/models/parakeet/decoder.int8.onnx", config.modelConfig.transducer.decoder)
        assertEquals("/models/parakeet/joiner.int8.onnx", config.modelConfig.transducer.joiner)
        assertEquals("/models/parakeet/tokens.txt", config.modelConfig.tokens)
        assertEquals("", config.modelConfig.nemo.model)
        assertEquals("", config.modelConfig.whisper.encoder)
    }
}
