package org.lacan.asr

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import org.lacan.asr.parakeet.PARAKEET_HALF_OVERLAP
import org.lacan.asr.parakeet.ParakeetWord
import org.lacan.asr.parakeet.reconcileParakeetOverlap
import org.lacan.asr.parakeet.ParakeetWindowTrace
import org.lacan.asr.parakeet.ParakeetFrontend
import org.lacan.asr.parakeet.ParakeetTdtDecoder
import org.lacan.asr.parakeet.ParakeetTokenizer
import org.lacan.asr.parakeet.parakeetWindows
import org.lacan.models.ModelSpec
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** GPU-only neural inference. Compilation, buffer access and disposal share one GPU context thread. */
internal class LiteRtParakeetEngine(
    spec: ModelSpec,
    directory: File,
    private val diagnostic: ((ParakeetWindowTrace) -> Unit)? = null,
) : AsrEngine {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "lacan-parakeet-gpu") }
    private val cancelled = AtomicBoolean(false)
    private val lifecycle = Any()
    private var closed = false
    private val resources: Resources

    init {
        try {
            resources = onWorker {
                Resources(
                    File(directory, spec.files.single { it.name.endsWith(".tflite") }.name),
                    File(directory, spec.files.single { it.name.endsWith(".json") }.name),
                )
            }
        } catch (error: Throwable) {
            executor.shutdown()
            throw error
        }
    }

    override fun beginSession(language: String) = synchronized(lifecycle) {
        check(!closed) { "Parakeet engine is closed" }
        cancelled.set(false)
    }

    // Language is detected from speech by Parakeet v3; there is no forced-language decoder input.
    override fun transcribe(samples: FloatArray, language: String): String {
        val future = synchronized(lifecycle) {
            check(!closed) { "Parakeet engine is closed" }
            executor.submit(Callable {
                try {
                    checkActive()
                    require(samples.all { it.isFinite() }) { "Audio contains non-finite samples" }
                    val transcript = ArrayList<String>()
                    val windows = parakeetWindows(samples.size)
                    var previousRaw = emptyList<ParakeetWord>()
                    var previousOwned = emptyList<ParakeetWord>()
                    for ((index, window) in windows.withIndex()) {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                        val active = {
                            checkActive()
                            if (System.nanoTime() > deadline) throw TimeoutException("Parakeet GPU window exceeded 120 seconds")
                        }
                        active()
                        val audio = samples.copyOfRange(window.first, window.last + 1)
                        val features = resources.frontend.process(audio)
                        active()
                        resources.encoderInputs[0].writeFloat(features)
                        resources.model.run(resources.encoderInputs, resources.encoderOutputs, "encode")
                        active()
                        val ids = resources.decoder.decode(resources.encoderOutputs, audio.size, active)
                        val ownedStart = if (index == 0) 0 else PARAKEET_HALF_OVERLAP
                        val ownedEnd = if (index == windows.lastIndex) audio.size else audio.size - PARAKEET_HALF_OVERLAP
                        diagnostic?.invoke(ParakeetWindowTrace(
                            index, window.first, window.last + 1, ownedStart, ownedEnd, resources.tokenizer.trace(ids),
                        ))
                        val words = resources.tokenizer.words(ids, window.first)
                        val owned = words.filter { it.startSample >= window.first.toLong() + ownedStart && it.startSample < window.first.toLong() + ownedEnd }
                        val reconciled = if (index == 0) owned else reconcileParakeetOverlap(
                            previousRaw, previousOwned, words, owned, window.first, windows[index - 1].last + 1,
                        )
                        resources.tokenizer.decodeWords(reconciled).takeIf { it.isNotBlank() }?.let(transcript::add)
                        previousRaw = words
                        previousOwned = reconciled
                    }
                    checkActive()
                    transcript.joinToString(" ")
                } catch (_: CancellationException) { "" }
            })
        }
        return await(future)
    }

    private fun checkActive() {
        if (cancelled.get()) throw CancellationException("Parakeet transcription cancelled")
    }

    override fun cancel() { cancelled.set(true) }

    override fun close() {
        val future = synchronized(lifecycle) {
            if (closed) return
            closed = true
            cancelled.set(true)
            executor.submit(Callable { resources.close() })
        }
        // Native dispatch cannot be interrupted safely. Disposal queues behind any in-flight call.
        try { await(future) } finally { executor.shutdown() }
    }

    private fun <T> onWorker(block: () -> T): T = await(executor.submit(Callable(block)))

    private fun <T> await(future: java.util.concurrent.Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try { return future.get() }
                catch (_: InterruptedException) { interrupted = true; cancelled.set(true) }
                catch (error: ExecutionException) { throw error.cause ?: error }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private class Resources(modelFile: File, tokenizerFile: File) : AutoCloseable {
        val frontend = ParakeetFrontend()
        val tokenizer = ParakeetTokenizer(tokenizerFile)
        val model: CompiledModel
        private val buffers = mutableListOf<TensorBuffer>()
        val encoderInputs: List<TensorBuffer>
        val encoderOutputs: List<TensorBuffer>
        val decoder: ParakeetTdtDecoder

        init {
            require(modelFile.isFile) { "Parakeet GPU model is missing" }
            val options = CompiledModel.Options(setOf(Accelerator.GPU)).apply {
                gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                    constantTensorSharing = true,
                )
            }
            // Deliberately no CPU accelerator or fallback retry. Device placement still needs native-log evidence.
            model = CompiledModel.create(modelFile.absolutePath, options)
            try {
                encoderInputs = own(model.createInputBuffers("encode"))
                encoderOutputs = own(model.createOutputBuffers("encode"))
                val dimensions = model.getInputTensorType("args_0", "encode").layout!!.dimensions
                require(dimensions.toList() == listOf(1, 128, 500) && encoderInputs.size == 1)
                decoder = ParakeetTdtDecoder(model, ::own)
            } catch (error: Throwable) {
                try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }

        private fun own(items: List<TensorBuffer>): List<TensorBuffer> = items.also { buffers.addAll(it) }

        override fun close() {
            var failure: Throwable? = null
            for (resource in buffers.asReversed() + model) {
                try { resource.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            buffers.clear()
            failure?.let { throw it }
        }
    }
}
