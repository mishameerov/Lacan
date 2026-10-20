package org.lacan.parakeetprobe

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Isolated encoder dispatch probe. No microphone, network, tokenizer or host TDT decoder. */
class ProbeActivity : Activity() {
    private lateinit var output: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        output = TextView(this).apply { text = "Preparing bounded encoder probe…"; setPadding(24,24,24,24) }
        setContentView(output)
        Thread({
            try { runProbe() } catch (error: Throwable) {
                Log.e(TAG, "probe failed", error)
                show("FAILED ${error.javaClass.simpleName}: ${error.message}")
            }
        }, "parakeet-probe").start()
    }
    private fun show(message: String) { Log.i(TAG, message); runOnUiThread { output.text = message } }
    private fun runProbe() {
        val backend = intent.getStringExtra("backend") ?: "gpu"
        require(backend == "gpu" || backend == "cpu") { "backend must be gpu or cpu" }
        val root = getExternalFilesDir(null) ?: error("external files directory unavailable")
        val model = File(root, "parakeet.tflite")
        require(model.isFile) { "Place verified model at ${model.absolutePath}" }
        val options = CompiledModel.Options(setOf(if (backend == "gpu") Accelerator.GPU else Accelerator.CPU))
        if (backend == "gpu") options.gpuOptions = CompiledModel.GpuOptions(
            precision = CompiledModel.GpuOptions.Precision.FP32,
            constantTensorSharing = true,
        )
        val start = SystemClock.elapsedRealtimeNanos()
        CompiledModel.create(model.absolutePath, options).use { compiled ->
            val compileMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val inputs = compiled.createInputBuffers("encode")
            val outputs = compiled.createOutputBuffers("encode")
            try {
                require(inputs.size == 1) { "Expected one encode input, found ${inputs.size}" }
                val shape = compiled.getInputTensorType("args_0", "encode").layout!!.dimensions
                val count = shape.fold(1) { a, b -> Math.multiplyExact(a, b) }
                val featureFile = File(root, "features.f32le")
                val features = if (featureFile.isFile) {
                    require(featureFile.length() == count.toLong() * 4) { "Feature byte count must be ${count.toLong() * 4}" }
                    val floats = ByteBuffer.wrap(featureFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    FloatArray(count).also { floats.get(it) }
                } else FloatArray(count)
                require(features.all { it.isFinite() }) { "Features contain non-finite values" }
                inputs[0].writeFloat(features)
                // Exactly one invocation. Zeros test dispatch, not frontend correctness or speech accuracy.
                val runStart = SystemClock.elapsedRealtimeNanos()
                compiled.run(inputs, outputs, "encode")
                val values = outputs[0].readFloat()
                val runMs = (SystemClock.elapsedRealtimeNanos() - runStart) / 1e6
                require(values.isNotEmpty() && values.all { it.isFinite() }) { "Non-finite/empty encoder output" }
                val encoded = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { encoded.putFloat(it) }
                File(root, "encoder-$backend.f32le").writeBytes(encoded.array())
                val report = "requested=$backend only; partition placement UNVERIFIED\ninput=${shape} floats=$count\nfeatures=${if (featureFile.isFile) "supplied" else "ZERO SMOKE ONLY"}\ncompile_ms=$compileMs run_and_read_ms=$runMs\noutput_floats=${values.size} min=${values.minOrNull()} max=${values.maxOrNull()}\nSaved encoder-$backend.f32le\nThis is not a transcription or speed qualification."
                File(root, "report-$backend.txt").writeText(report)
                show(report)
            } finally { inputs.forEach { it.close() }; outputs.forEach { it.close() } }
        }
    }
    companion object { private const val TAG = "ParakeetGpuProbe" }
}
