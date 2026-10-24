package org.lacan.asr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lacan.models.ModelCatalog
import org.lacan.models.ModelRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/** Opt in on a physical device with -e parakeetGpuSmoke true after importing public fixtures. */
class ParakeetGpuSmokeTest {
    @Test fun gpuDecodesShortRussianFixture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("parakeetGpuSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = File(context.filesDir, "test-fixtures")
        val spec = ModelCatalog.models.single { it.id == "parakeet-tdt-gpu" }
        val repository = ModelRepository(context)
        repository.installVerified(spec, File(fixtures, "test-models/${spec.id}"))
        assertTrue(repository.verifyInstalled(spec))
        val audio = readAudio(File(fixtures, "test-audio/gigaam-example.wav"), 6)
        val trace = File(fixtures, "parakeet-gpu-trace.txt").apply { writeText("") }
        LiteRtParakeetEngine(spec, repository.directory(spec)) { trace.appendText("$it\n") }.use { engine ->
            var text = ""
            engine.beginSession("ru")
            val elapsed = measureTimeMillis { text = engine.transcribe(audio, "ru") }
            File(fixtures, "parakeet-tdt-gpu-smoke-result.txt").writeText("inferenceMillis=$elapsed\n$text\n")
            assertTrue("GPU Parakeet should decode Cyrillic from the public Russian fixture", text.count { it in 'А'..'я' || it == 'ё' } >= 8)
            assertTrue("Reference words must survive the window boundary", text.contains("похвал", true) && text.contains("любви", true))
            assertTrue("Overlap must not duplicate the fixture's single любви", Regex("любви", RegexOption.IGNORE_CASE).findAll(text).count() == 1)
            engine.cancel()
            assertTrue("Cancel must suppress pending decoding", engine.transcribe(audio, "ru").isBlank())
            engine.beginSession("en")
            var english = ""
            val enMs = measureTimeMillis { english = engine.transcribe(readAudio(File(fixtures, "test-audio/jfk.wav"), 4), "en") }
            File(fixtures, "parakeet-tdt-gpu-smoke-result.txt").appendText("englishMillis=$enMs\n$english\n")
            assertTrue("English reference word after reuse", english.contains("fellow", true) || english.contains("Americans", true))
            assertTrue("Silence should not produce text", engine.transcribe(FloatArray(32000), "en").isBlank())

        }
    }
    private fun readAudio(file: File, seconds: Int): FloatArray {
        val bytes = file.readBytes()
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun uint(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        check(tag(0) == "RIFF" && tag(8) == "WAVE")
        var offset = 12
        var pcmOffset = -1
        var pcmLength = 0
        while (offset + 8 <= bytes.size) {
            val size = uint(offset + 4)
            check(size >= 0 && offset + 8L + size <= bytes.size)
            if (tag(offset) == "fmt ") {
                val format = ByteBuffer.wrap(bytes, offset + 8, size).order(ByteOrder.LITTLE_ENDIAN)
                check(format.short.toInt() == 1 && format.short.toInt() == 1 && format.int == 16000)
                format.int; format.short
                check(format.short.toInt() == 16)
            }
            if (tag(offset) == "data") { pcmOffset = offset + 8; pcmLength = size; break }
            offset += 8 + size + (size and 1)
        }
        check(pcmOffset >= 0)
        // Public mono PCM16 fixture only.
        return FloatArray(minOf(pcmLength / 2, seconds * 16000)) { i ->
            ByteBuffer.wrap(bytes, pcmOffset + i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short / 32768f
        }
    }

}
