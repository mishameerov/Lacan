package org.lacan.asr

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lacan.models.ModelCatalog
import org.lacan.models.ModelRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/** Physical-device opt-in, public audio only; use IsolatedAsrRunner. */
class GigaGpuSmokeTest {
    @Test fun gpuFrontendDecodingAndReuse() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gigaGpuSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.applicationContext.javaClass == Application::class.java)
        val fixtures = File(context.filesDir, "test-fixtures")
        val spec = ModelCatalog.models.single { it.id == "gigaam-v3-fp32-gpu" }
        val repository = ModelRepository(context)
        if (!repository.verifyInstalled(spec)) {
            repository.installVerified(spec, File(fixtures, "test-models/${spec.id}"))
        }
        assertTrue(repository.verifyInstalled(spec))
        val audio = readSixSeconds(File(fixtures, "test-audio/gigaam-example.wav"))
        val report = File(fixtures, "gigaam-gpu-smoke-result.txt").apply { writeText("") }
        lateinit var loaded: MnnGigaAmEngine
        val initMs = measureTimeMillis {
            loaded = MnnGigaAmEngine(spec, repository.directory(spec)) { report.appendText("$it\n") }
        }
        report.appendText("initializationMillis=$initMs\n")
        loaded.use { engine ->
            engine.beginSession("ru")
            var text = ""
            val elapsed = measureTimeMillis { text = engine.transcribe(audio, "ru") }
            report.appendText("inferenceMillis=$elapsed\n$text\n")
            assertEquals("Ничьих, не требуя похвал, счастлив уж я надеждой сладкой, Что дева с трепетом любви посмотрит", text)
            engine.cancel()
            assertTrue(engine.transcribe(audio, "ru").isEmpty())
            engine.beginSession("ru")
            val silenceMs = measureTimeMillis {
                assertTrue("Silence after cancellation reset", engine.transcribe(FloatArray(32000), "ru").isBlank())
            }
            report.appendText("silenceMillis=$silenceMs\n")
            assertEquals("Warm reuse after input resize", text, engine.transcribe(audio, "ru"))
            assertTrue("All observed neural operations must use GPU", report.readText().contains("foreign_ops=0"))
            val result = java.util.concurrent.atomic.AtomicReference<String>()
            val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val started = java.util.concurrent.CountDownLatch(1)
            val pending = Thread {
                started.countDown()
                try { result.set(engine.transcribe(audio, "ru")) }
                catch (failure: Throwable) { error.set(failure) }
            }
            pending.start()
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(150) // Shorter than measured GPU execution; cancellation races with the native call.
            engine.cancel()
            pending.join(30_000)
            assertTrue("Cancelled worker must finish", !pending.isAlive)
            error.get()?.let { throw it }
            assertEquals("Cancelled result must be suppressed", "", result.get())
            engine.beginSession("ru")
            assertTrue(engine.transcribe(FloatArray(32000), "ru").isBlank())
            report.appendText("cancelRaceAndReuse=passed\n")
        }
    }

    private fun readSixSeconds(file: File): FloatArray {
        val bytes = file.readBytes()
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun uint(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        check(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE")
        var offset = 12
        var pcmOffset = -1
        var pcmLength = 0
        var formatChecked = false
        while (offset + 8 <= bytes.size) {
            val size = uint(offset + 4)
            check(size >= 0 && offset + 8L + size <= bytes.size)
            if (tag(offset) == "fmt ") {
                check(size >= 16)
                val format = ByteBuffer.wrap(bytes, offset + 8, size).order(ByteOrder.LITTLE_ENDIAN)
                check(format.short.toInt() == 1 && format.short.toInt() == 1 && format.int == 16000)
                format.int; format.short
                check(format.short.toInt() == 16)
                formatChecked = true
            }
            if (tag(offset) == "data") { pcmOffset = offset + 8; pcmLength = size }
            offset += 8 + size + (size and 1)
        }
        check(formatChecked && pcmOffset >= 0 && pcmLength >= 6 * 16000 * 2 && pcmLength % 2 == 0)
        val pcm = ByteBuffer.wrap(bytes, pcmOffset, 6 * 16000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(6 * 16000) { pcm.short / 32768f }
    }
}
