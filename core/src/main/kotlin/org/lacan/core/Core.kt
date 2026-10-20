package org.lacan.core

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

private const val SAMPLE_RATE = 16000
private const val MAX_WINDOW = 20 * SAMPLE_RATE
private const val MAX_SESSION = 600L * SAMPLE_RATE

/** Sample offsets are half-open and refer to the session PCM spool. */
data class AudioSegment(val start: Long, val end: Long, val overlapSamples: Int = 0)

/** Private, little-endian PCM16 storage. Memory use is bounded by a single ASR window. */
class AudioSpool(private val file: File) : AutoCloseable {
    private val storage = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var closed = false
    private var size = 0L
    val sampleCount: Long @Synchronized get() = size

    @Synchronized
    fun append(samples: ShortArray, count: Int = samples.size) {
        check(!closed) { "Audio spool is closed" }
        require(count in 0..samples.size)
        require(size + count <= MAX_SESSION) { "Recording exceeds ten minutes" }
        storage.seek(size * 2)
        val bytes = ByteArray(minOf(count * 2L, 8192L).toInt())
        var offset = 0
        while (offset < count) {
            val length = minOf(count - offset, bytes.size / 2)
            repeat(length) { index ->
                val value = samples[offset + index].toInt()
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value shr 8).toByte()
            }
            storage.write(bytes, 0, length * 2)
            // Publish only complete writes. Any I/O failure must stop capture at the caller.
            size += length
            offset += length
        }
    }

    @Synchronized
    fun read(start: Long, end: Long): FloatArray {
        check(!closed) { "Audio spool is closed" }
        require(start >= 0 && end >= start && end <= size)
        require(end - start <= MAX_WINDOW) { "ASR windows cannot exceed twenty seconds" }
        val output = FloatArray((end - start).toInt())
        val bytes = ByteArray(minOf(output.size * 2, 8192))
        storage.seek(start * 2)
        var offset = 0
        while (offset < output.size) {
            val length = minOf(output.size - offset, bytes.size / 2)
            storage.readFully(bytes, 0, length * 2)
            repeat(length) { index ->
                val pcm = ((bytes[index * 2].toInt() and 255) or
                    (bytes[index * 2 + 1].toInt() shl 8)).toShort()
                output[offset + index] = pcm / 32768f
            }
            offset += length
        }
        return output
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            storage.close()
        }
    }

    @Synchronized
    fun delete() {
        try { close() } finally {
            check(!file.exists() || file.delete()) { "Cannot delete temporary audio" }
        }
    }
}

/**
 * Local-alpha energy fallback, NOT Silero VAD. 10 ms RMS frames use a fixed PCM
 * threshold of 500, retain 200 ms pre-roll and close after 600 ms quiet.
 * No PCM is retained here. Quiet/noisy microphones can need threshold tuning.
 * The caller should use the same ten-minute limit when writing to AudioSpool.
 */
class Segmenter {
    private var position = 0L
    private var frameSamples = 0
    private var frameEnergy = 0L
    private var start: Long? = null
    private var lastSpeechEnd = 0L
    private var overlap = 0
    private var novelSpeech = false
    private var finished = false

    @Synchronized
    fun accept(samples: ShortArray, count: Int = samples.size): List<AudioSegment> {
        require(count in 0..samples.size)
        if (finished) return emptyList()
        val result = mutableListOf<AudioSegment>()
        val available = minOf(count.toLong(), MAX_SESSION - position).toInt()
        repeat(available) { index ->
            val value = samples[index].toLong()
            frameEnergy += value * value
            frameSamples++
            position++
            if (frameSamples == 160) processFrame(result)
        }
        if (position == MAX_SESSION) {
            flush(result)
            finished = true
        }
        return result
    }

    @Synchronized
    fun finish(): List<AudioSegment> {
        if (finished) return emptyList()
        val result = mutableListOf<AudioSegment>()
        flush(result)
        finished = true
        return result
    }

    private fun processFrame(result: MutableList<AudioSegment>) {
        if (frameSamples == 0) return
        val speech = frameEnergy >= 500L * 500 * frameSamples
        if (speech) {
            if (start == null) start = maxOf(0, position - frameSamples - 3200)
            novelSpeech = true
            lastSpeechEnd = position
        }
        frameSamples = 0
        frameEnergy = 0
        val from = start ?: return
        if (position - lastSpeechEnd >= 9600) {
            if (novelSpeech) result += AudioSegment(from, position, overlap)
            start = null
            novelSpeech = false
            overlap = 0
        } else if (position - from >= MAX_WINDOW) {
            if (novelSpeech) result += AudioSegment(from, position, overlap)
            start = position - 12000
            overlap = 12000
            novelSpeech = false
        }
    }

    private fun flush(result: MutableList<AudioSegment>) {
        processFrame(result)
        val from = start
        if (from != null && novelSpeech && position > from) {
            result += AudioSegment(from, position, overlap)
        }
        start = null
        novelSpeech = false
    }
}

/** Only compare the small known temporal overlap; never globally remove repeats. */
class TranscriptAssembler {
    var text: String = ""
        private set

    @Synchronized
    fun append(text: String, overlap: Boolean): String {
        val incoming = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (incoming.isEmpty()) return this.text
        val previous = this.text.split(' ').filter { it.isNotEmpty() }
        var skip = 0
        if (overlap) {
            val limit = minOf(8, previous.size, incoming.size)
            for (length in limit downTo 1) {
                if ((0 until length).all {
                    val a = word(previous[previous.size - length + it])
                    a.isNotEmpty() && a == word(incoming[it])
                }) {
                    skip = length
                    break
                }
            }
        }
        val added = incoming.drop(skip).joinToString(" ")
        if (added.isNotEmpty()) this.text = listOf(this.text, added).filter { it.isNotEmpty() }.joinToString(" ")
        return this.text
    }

    private fun word(value: String) = value.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
}

/** Cancel rejects callbacks immediately but remains busy until native work returns and finish runs. */
class SessionGate {
    private var nextId = 0L
    private var activeId: Long? = null
    private var cancelled = false

    @Synchronized
    fun start(): Long? {
        if (activeId != null) return null
        check(nextId < Long.MAX_VALUE) { "Session identifiers exhausted" }
        val id = ++nextId
        activeId = id
        cancelled = false
        return id
    }

    @Synchronized
    fun stop(id: Long) {
        // Capture stops at the caller. Final results still belong to this session.
        if (activeId != id) return
    }

    @Synchronized
    fun cancel(id: Long) { if (activeId == id) cancelled = true }

    @Synchronized
    fun accepts(id: Long): Boolean = activeId == id && !cancelled

    @Synchronized
    fun finish(id: Long) { if (activeId == id) activeId = null }
}
