package org.lacan.asr

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.lacan.models.ModelCatalog
import org.lacan.models.ModelRepository
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/** Explicit fixture test: parent imports catalog weights and public audio into private test-fixtures first. */
class AsrSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures get() = File(context.filesDir, "test-fixtures")

    @Test fun whisperEnglishAndCancellationReuse() = runBlocking {
        val spec = ModelCatalog.models.single { it.id == "whisper-tiny" }
        val repository = ModelRepository(context)
        repository.installVerified(spec, File(fixtures, "test-models/${spec.id}"))
        assertTrue(repository.verifyInstalled(spec))
        val audio = readWave(File(fixtures, "test-audio/jfk.wav"))
        EngineFactory.create(spec, repository.directory(spec), "en").use { engine ->
            engine.beginSession("en")
            var text = ""
            val elapsed = measureTimeMillis { text = engine.transcribe(audio, "en") }
            record("whisper", text, elapsed)
            assertTrue("Whisper fixture output should contain country", text.contains("country", ignoreCase = true))
            // cancel is cooperative; it must leave the warm engine safe for the next session.
            val started = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val worker = Thread {
                try { started.countDown(); engine.transcribe(audio, "en") }
                catch (error: Throwable) { failure.set(error) }
            }
            worker.start()
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(75)
            engine.cancel()
            worker.join(60_000)
            assertFalse("Cancelled Whisper call did not return", worker.isAlive)
            failure.get()?.let { throw AssertionError("Cancellation crashed inference", it) }
            engine.beginSession("en")
            assertTrue(engine.transcribe(audio, "en").contains("country", ignoreCase = true))
        }
    }

    @Test fun gigaAmRussianWithModelPunctuation() = runBlocking {
        val spec = ModelCatalog.models.single { it.id == "gigaam-v3" }
        val repository = ModelRepository(context)
        repository.installVerified(spec, File(fixtures, "test-models/${spec.id}"))
        assertTrue(repository.verifyInstalled(spec))
        val audio = readWave(File(fixtures, "test-audio/gigaam-example.wav"))
        EngineFactory.create(spec, repository.directory(spec), "ru").use { engine ->
            engine.beginSession("ru")
            var text = ""
            val elapsed = measureTimeMillis { text = engine.transcribe(audio, "ru") }
            record("gigaam", text, elapsed)
            assertTrue("GigaAM fixture output should contain Cyrillic", text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' })
            assertTrue("e2e GigaAM should emit punctuation on official example", text.any { it in ".,!?;:" })
        }
    }

    private fun record(engine: String, text: String, millis: Long) {
        // This test handles only checked-in/public speech; production never logs recognized text.
        File(fixtures, "$engine-smoke-result.txt").writeText("inferenceMillis=$millis\n$text\n")
    }

    private fun readWave(file: File): FloatArray {
        check(file.isFile) { "Missing fixture: ${file.absolutePath}" }
        val b = file.readBytes()
        fun tag(offset: Int) = String(b, offset, 4, Charsets.US_ASCII)
        fun u16(offset: Int) = ByteBuffer.wrap(b, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
        fun u32(offset: Int) = ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        check(b.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "Invalid WAV header" }
        var offset = 12
        var validFormat = false
        var pcm: Pair<Int, Int>? = null
        while (offset + 8 <= b.size) {
            val length = u32(offset + 4)
            check(length <= Int.MAX_VALUE && offset + 8L + length <= b.size) { "Truncated WAV chunk" }
            val start = offset + 8
            when (tag(offset)) {
                "fmt " -> {
                    check(length >= 16 && u16(start) == 1 && u16(start + 2) == 1 && u32(start + 4) == 16000L && u16(start + 14) == 16) { "Fixture must be mono PCM16 at 16kHz" }
                    validFormat = true
                }
                "data" -> pcm = start to length.toInt()
            }
            offset = (start + length + (length and 1)).toInt()
        }
        check(validFormat && pcm != null) { "WAV missing fmt/data" }
        val (start, length) = checkNotNull(pcm)
        check(length % 2 == 0)
        return FloatArray(length / 2) { index ->
            ByteBuffer.wrap(b, start + index * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short / 32768f
        }
    }
}
