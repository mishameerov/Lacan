package org.lacan.asr

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Convert the shared normalized 16 kHz samples back to Vosk's signed PCM16 input. */
internal fun voskPcm16(sample: Float): Short =
    (sample * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()

/** [directory] is an extracted model root containing am/, conf/ and graph/. */
internal class VoskEngine(directory: File) : AsrEngine {
    private val model = Model(directory.absolutePath)
    private val cancelled = AtomicBoolean(false)
    private var closed = false

    // The downloaded model fixes the language; Vosk has no forced-language argument.
    @Synchronized override fun transcribe(samples: FloatArray, language: String): String {
        check(!closed)
        cancelled.set(false)
        if (samples.isEmpty()) return ""
        return Recognizer(model, 16000f).use { recognizer ->
            val text = StringBuilder()
            fun appendResult(json: String) {
                val part = JSONObject(json).optString("text", "").trim()
                if (part.isNotEmpty()) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(part)
                }
            }

            // Bound native calls to 100 ms of audio so cancellation can stop a long chunk.
            val pcm = ShortArray(minOf(1600, samples.size))
            var offset = 0
            while (offset < samples.size) {
                if (cancelled.get()) return@use ""
                val count = minOf(pcm.size, samples.size - offset)
                for (i in 0 until count) pcm[i] = voskPcm16(samples[offset + i])
                if (recognizer.acceptWaveForm(pcm, count)) appendResult(recognizer.result)
                offset += count
            }
            if (cancelled.get()) return@use ""
            // Endpoint results contain completed utterances; finalResult flushes the tail.
            appendResult(recognizer.finalResult)
            if (cancelled.get()) "" else text.toString()
        }
    }

    override fun cancel() { cancelled.set(true) }

    override fun close() {
        cancel()
        // Never release model data while a recognizer is inside a native call.
        synchronized(this) {
            if (!closed) {
                closed = true
                model.close()
            }
        }
    }
}
