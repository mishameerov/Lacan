package org.lacan.asr

import android.app.Application
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lacan.models.ModelCatalog
import org.lacan.models.ModelRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Physical-device opt-in only. Uses the installed, verified Medium ONNX model; downloads nothing.
 * Build the test APK with -PlacanTestRunner=org.lacan.asr.IsolatedAsrRunner, then run this class alone
 * with -e whisperPaddingProbe true and component org.lacan.test/org.lacan.asr.IsolatedAsrRunner.
 * Builds without that Gradle property retain the normal runner for other tests.
 * The isolated runner skips LacanApp, preventing its automatic model warmup without changing prefs.
 * Two cold recognizers decode the same first six seconds of test-audio/gigaam-example.wav.
 * This small sequential probe is not a general benchmark or an accuracy evaluation.
 */
class WhisperPaddingProbeTest {
    @Test fun compareInstalledMediumPaddingOnShortRussianFixture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whisperPaddingProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.applicationContext.javaClass == Application::class.java) {
            "Run with IsolatedAsrRunner so LacanApp cannot load another model"
        }
        val spec = ModelCatalog.models.single { it.id == "whisper-medium-onnx" }
        val repository = ModelRepository(context)
        check(repository.verifyInstalled(spec)) { "Install and verify Whisper Medium ONNX before this probe" }
        val fixtures = File(context.filesDir, "test-fixtures")
        val samples = readSixSeconds(File(fixtures, "test-audio/gigaam-example.wav"))
        val runs = JSONArray()
        val report = JSONObject()
            .put("model", spec.id)
            .put("language", "ru")
            .put("sampleRate", 16000)
            .put("sampleCount", samples.size)
            .put("audioMillis", samples.size * 1000L / 16000)
            .put("order", "1000 then 200; separate recognizers; no warmup")
            .put("runs", runs)
        val output = File(fixtures, "whisper-medium-padding-probe-result.json")
        for (tailPaddings in listOf(1000, 200)) {
            val result = JSONObject().put("tailPaddings", tailPaddings)
            runs.put(result)
            var recognizer: OfflineRecognizer? = null
            try {
                val config = sherpaWhisperConfig(spec, repository.directory(spec), "ru")
                config.modelConfig.whisper.tailPaddings = tailPaddings
                val initStart = SystemClock.elapsedRealtimeNanos()
                val loaded = OfflineRecognizer(assetManager = null, config = config)
                recognizer = loaded
                result.put("initializationMillis", elapsedMillis(initStart))
                val stream = loaded.createStream()
                try {
                    val acceptStart = SystemClock.elapsedRealtimeNanos()
                    stream.acceptWaveform(samples, 16000)
                    result.put("acceptWaveformMillis", elapsedMillis(acceptStart))
                    val decodeStart = SystemClock.elapsedRealtimeNanos()
                    loaded.decode(stream)
                    result.put("decodeMillis", elapsedMillis(decodeStart))
                    result.put("text", loaded.getResult(stream).text)
                } finally {
                    stream.release()
                }
            } catch (failure: Exception) {
                result.put("error", "${failure.javaClass.simpleName}: ${failure.message}")
                throw failure
            } finally {
                try { recognizer?.release() }
                finally { output.writeText(report.toString(2) + "\n") }
            }
        }
        // Preserve both timings and outputs before checking basic decoding compatibility.
        for (index in 0 until runs.length()) {
            val result = runs.getJSONObject(index)
            assertTrue("Padding ${result.getInt("tailPaddings")} should decode Cyrillic; inspect $output",
                result.getString("text").count { it in 'А'..'я' || it == 'ё' } >= 8)
        }
    }

    private fun elapsedMillis(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

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
