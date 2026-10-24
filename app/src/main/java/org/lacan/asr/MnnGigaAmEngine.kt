package org.lacan.asr

import org.lacan.models.ModelSpec
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** FP32 OpenCL model and every GPU operation share one worker/context thread. */
internal class MnnGigaAmEngine(
    spec: ModelSpec,
    directory: File,
    private val diagnostic: ((String) -> Unit)? = null,
) : AsrEngine {
    override val allowsSpeculativePreviews: Boolean get() = false
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "lacan-giga-gpu") }
    private val lifecycle = Any()
    private val nativeLifetime = Any()
    private val cancelled = AtomicBoolean(false)
    private var closed = false
    private var pointer = 0L
    private val tokens = arrayOfNulls<String>(257)

    init {
        try {
            val model = File(directory, spec.files.single { it.name.endsWith(".mnn") }.name)
            val tokenFile = File(directory, spec.files.single { it.name.endsWith("_tokens.txt") || it.name == "tokens.txt" }.name)
            require(model.isFile && tokenFile.isFile) { "GigaAM GPU model files are missing" }
            tokenFile.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.forEach { line ->
                val split = line.lastIndexOf(' ')
                require(split > 0) { "Malformed GigaAM token" }
                val id = line.substring(split + 1).toInt()
                require(id in tokens.indices && tokens[id] == null) { "Invalid or duplicate GigaAM token ID" }
                tokens[id] = line.substring(0, split)
            }
            require(tokens.all { it != null }) { "GigaAM requires 257 tokens" }
            await(executor.submit(Callable {
                synchronized(nativeLifetime) {
                    pointer = nativeLoad(model.absolutePath)
                    check(pointer != 0L) { "Cannot load GigaAM on GPU" }
                }
            }))
        } catch (error: Throwable) {
            executor.shutdown()
            throw error
        }
    }

    override fun beginSession(language: String) {
        val future = synchronized(lifecycle) {
            check(!closed) { "GigaAM GPU engine is closed" }
            cancelled.set(false)
            executor.submit(Callable { synchronized(nativeLifetime) { nativeBegin(pointer) } })
        }
        await(future)
    }

    override fun transcribe(samples: FloatArray, language: String): String {
        val future = synchronized(lifecycle) {
            check(!closed) { "GigaAM GPU engine is closed" }
            executor.submit(Callable {
                try {
                    checkActive()
                    require(samples.size <= MAX_SEGMENT_SAMPLES) { "GigaAM GPU accepts at most 20 seconds per segment" }
                    require(samples.all { it.isFinite() }) { "Audio contains non-finite samples" }
                    if (samples.isEmpty()) return@Callable ""
                    val handle = synchronized(nativeLifetime) { pointer.also { check(it != 0L) } }
                    val ids = nativeTranscribe(handle, samples)
                    checkActive()
                    diagnostic?.invoke(nativeStats(handle))
                    buildString {
                        ids.forEach { id ->
                            require(id in 0..255) { "GigaAM returned an invalid CTC token" }
                            append(tokens[id])
                        }
                    }.replace('▁', ' ').trim()
                } catch (_: CancellationException) { "" }
            })
        }
        return await(future)
    }

    private fun checkActive() {
        if (cancelled.get()) throw CancellationException("GigaAM GPU transcription cancelled")
    }

    override fun cancel() {
        cancelled.set(true)
        synchronized(nativeLifetime) { if (pointer != 0L) nativeCancel(pointer) }
    }

    override fun close() {
        val future = synchronized(lifecycle) {
            if (closed) return
            closed = true
            cancel()
            executor.submit(Callable {
                synchronized(nativeLifetime) {
                    if (pointer != 0L) {
                        nativeFree(pointer)
                        pointer = 0L
                    }
                }
            })
        }
        // Native GPU calls cannot be aborted safely. Cleanup queues behind them.
        try { await(future) } finally { executor.shutdown() }
    }

    private fun <T> await(future: Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try { return future.get() }
                catch (_: InterruptedException) { interrupted = true; cancel() }
                catch (error: ExecutionException) { throw error.cause ?: error }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private external fun nativeLoad(path: String): Long
    private external fun nativeBegin(handle: Long)
    private external fun nativeTranscribe(handle: Long, samples: FloatArray): IntArray
    private external fun nativeStats(handle: Long): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    companion object {
        const val MAX_SEGMENT_SAMPLES = 20 * 16000
        init { System.loadLibrary("lacan-giga-mnn") }
    }
}
