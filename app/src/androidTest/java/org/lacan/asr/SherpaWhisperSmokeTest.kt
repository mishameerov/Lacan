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

/** Opt in on a physical device with -e sherpaWhisperSmoke true after importing public fixtures. */
class SherpaWhisperSmokeTest {
    @Test fun baseOnnxDecodesShortRussianFixture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sherpaWhisperSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = File(context.filesDir, "test-fixtures")
        val spec = ModelCatalog.models.single { it.id == "whisper-base-onnx" }
        val repository = ModelRepository(context)
        repository.installVerified(spec, File(fixtures, "test-models/${spec.id}"))
        assertTrue(repository.verifyInstalled(spec))
        val bytes = File(fixtures, "test-audio/gigaam-example.wav").readBytes()
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
        // One six-second public Russian clip only: native compatibility, not a benchmark.
        val audio = FloatArray(minOf(pcmLength / 2, 6 * 16000)) { i ->
            ByteBuffer.wrap(bytes, pcmOffset + i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short / 32768f
        }
        EngineFactory.create(spec, repository.directory(spec), "ru").use { engine ->
            var text = ""
            val elapsed = measureTimeMillis { text = engine.transcribe(audio, "ru") }
            File(fixtures, "whisper-base-onnx-smoke-result.txt").writeText("inferenceMillis=$elapsed\n$text\n")
            assertTrue("ONNX Whisper should decode Cyrillic from the public Russian fixture", text.count { it in 'А'..'я' || it == 'ё' } >= 8)
        }
    }
}
