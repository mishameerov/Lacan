package org.lacan.models

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun multilingualWhisperOnnxChoicesHaveEncoderDecoderAndTokens() {
        listOf("whisper-base-onnx", "whisper-small-onnx", "whisper-medium-onnx", "whisper-large-v3-onnx").forEach { id ->
            val model = ModelCatalog.models.single { it.id == id }
            assertEquals("sherpa-whisper", model.engine)
            assertEquals(3, model.files.size)
            assertEquals(1, model.files.count { it.name.endsWith("-encoder.int8.onnx") })
            assertEquals(1, model.files.count { it.name.endsWith("-decoder.int8.onnx") })
            assertEquals(1, model.files.count { it.name.endsWith("-tokens.txt") })
            assertFalse(model.files.any { it.name.contains(".en-") })
        }
    }

    @Test fun requestedLargerExportsHavePinnedCompleteArtifactSetsAndLegacyModelsRemain() {
        val medium = ModelCatalog.models.single { it.id == "whisper-medium-onnx" }
        val large = ModelCatalog.models.single { it.id == "whisper-large-v3-onnx" }
        val parakeet = ModelCatalog.models.single { it.id == "parakeet-tdt-0-6b-v3" }
        assertEquals(946072270L, medium.totalBytes)
        assertEquals(1775753918L, large.totalBytes)
        assertFalse(large.files.any { it.url.contains("turbo") || it.url.contains("distil") })
        assertEquals(670478772L, parakeet.totalBytes)
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), parakeet.files.map { it.name }.toSet())
        ModelCatalog.models.filter { it.engine == "whisper" }.forEach {
            assertTrue(it.title.contains("legacy"))
            assertEquals(1, it.files.size)
            assertTrue(it.files.single().name.endsWith(".bin"))
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
            assertEquals(64, model.featureDim)
        }
        assertEquals("gigaam_v3_e2e_ctc.onnx", ModelCatalog.models.single { it.id == "gigaam-v3-fp32" }.files.first().name)
    }
}
