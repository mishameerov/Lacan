package org.lacan.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** One producer, fixed 100 ms buffer. The consumer must persist each block before returning. */
class AudioCapture(private val onSamples: (ShortArray, Int) -> Unit, private val onEnd: (Throwable?) -> Unit) {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @SuppressLint("MissingPermission")
    fun start() {
        check(!running.get())
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(size > 0) { "Устройство не поддерживает запись 16 кГц" }
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size * 2, 6400))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Не удалось открыть микрофон") }
        recorder = audio
        try { audio.startRecording() } catch (e: Exception) { recorder = null; audio.release(); throw e }
        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) { audio.release(); recorder = null; error("Микрофон недоступен") }
        running.set(true)
        Thread({
            var failure: Throwable? = null
            try {
                val buffer = ShortArray(1600)
                while (running.get()) {
                    val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        // A stop can race a successful partial read. Preserve its final phoneme.
                        if (running.get()) check(audio.activeRecordingConfiguration?.isClientSilenced != true) { "Микрофон занят другим приложением" }
                        onSamples(buffer, count)
                    } else {
                        if (!running.get()) break
                        error("Запись прервана: код $count")
                    }
                }
            } catch (e: Exception) { if (running.get()) failure = e }
            finally {
                running.set(false)
                synchronized(this) {
                    runCatching { audio.stop() }
                    audio.release()
                    if (recorder === audio) recorder = null
                }
                onEnd(failure)
            }
        }, "lacan-capture").start()
    }
    @Synchronized fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
    }
}
