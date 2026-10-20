package org.lacan.models

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun multilingualWhisperOnnxChoicesHaveEncoderDecoderAndTokens() {
        listOf("whisper-base-onnx", "whisper-small-onnx").forEach { id ->
            val model = ModelCatalog.models.single { it.id == id }
            assertEquals("sherpa-whisper", model.engine)
            assertEquals(3, model.files.size)
            assertEquals(1, model.files.count { it.name.endsWith("-encoder.int8.onnx") })
            assertEquals(1, model.files.count { it.name.endsWith("-decoder.int8.onnx") })
            assertEquals(1, model.files.count { it.name.endsWith("-tokens.txt") })
            assertFalse(model.files.any { it.name.contains(".en-") })
        }
    }

    @Test fun downloadsArePinnedAndModelIdentifiersRemainUnique() {
        val models = ModelCatalog.models
        assertEquals(models.size, models.map { it.id }.distinct().size)
        assertTrue(models.map { it.id }.containsAll(listOf("whisper-tiny", "whisper-base", "gigaam-v3")))
        models.flatMap { it.files }.forEach { file ->
            assertTrue("Unpinned artifact: ${file.url}", file.url.matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[a-f0-9]{40}/.+")))
        }
    }

    @Test fun sherpaChoicesHaveExactlyOneCtcModelAndTokenFile() {
        ModelCatalog.models.filter { it.engine == "sherpa" }.forEach { model ->
            assertEquals(1, model.files.count { it.name.endsWith(".onnx") })
            assertEquals(1, model.files.count { it.name.endsWith("_tokens.txt") })
            assertEquals(2, model.files.size)
        }
        assertEquals("gigaam_v3_e2e_ctc.onnx", ModelCatalog.models.single { it.id == "gigaam-v3-fp32" }.files.first().name)
    }
}
