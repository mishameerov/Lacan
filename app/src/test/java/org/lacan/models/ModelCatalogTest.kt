package org.lacan.models

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun dailyModelsAndPreviousSelectionsUseCpu() {
        assertEquals(listOf("gigaam-v3-fp32", "parakeet-tdt-0-6b-v3"), ModelCatalog.activeModels.map { it.id })
        assertEquals("gigaam-v3-fp32", ModelCatalog.activeSelection("gigaam-v3-fp32-gpu"))
        assertEquals("parakeet-tdt-0-6b-v3", ModelCatalog.activeSelection("parakeet-tdt-gpu"))
        assertEquals("gigaam-v3-fp32", ModelCatalog.activeSelection("whisper-medium-onnx"))
        ModelCatalog.activeModels.forEach { assertEquals(it.id, ModelCatalog.activeSelection(it.id)) }
    }

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
        assertFalse(large.files.any { it.url?.contains("turbo") == true || it.url?.contains("distil") == true })
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
        models.filter { it.downloadable }.flatMap { it.files }.forEach { file ->
            assertTrue("Unpinned artifact: ${file.url}", file.url!!.matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[a-f0-9]{40}/.+")))
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
    @Test fun localGpuModelHasPinnedArtifactsWithoutInventedDownloadUrl() {
        val model = ModelCatalog.models.single { it.id == "gigaam-v3-fp32-gpu" }
        assertEquals("mnn-gigaam-gpu", model.engine)
        assertEquals(64, model.featureDim)
        assertFalse(model.downloadable)
        assertEquals("gigaam_v3_e2e_ctc_fp32.mnn", model.localImportFile?.name)
        assertEquals(886031144L, model.localImportFile?.sizeBytes)
        assertEquals("bdebf9ad1f4e5221096c723fbafbe0c726a4903ea623dc33557d51bed5f5e76c", model.localImportFile?.sha256)
        assertTrue(model.files.all { it.url == null })
        assertThrows(IllegalArgumentException::class.java) { model.requireDownloadable() }
        assertTrue(ModelCatalog.models.filter { it.id != model.id }.all { it.downloadable })
    }

    @Test fun nullableLocalUrlStillRejectsInsecureRemoteUrl() {
        val hash = "a".repeat(64)
        assertNull(ModelFile("local.mnn", null, hash, 1).url)
        assertThrows(IllegalArgumentException::class.java) { ModelFile("remote.mnn", "http://example.org/model", hash, 1) }
    }
}
