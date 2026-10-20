/*
 * Adapted from Google AI Edge LiteRT speech sample TdtDecoder.kt.
 * Copyright 2026 Google LLC. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package org.lacan.asr.parakeet

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer

internal class ParakeetTdtDecoder(
    private val model: CompiledModel,
    private val own: (List<TensorBuffer>) -> List<TensorBuffer>,
) {
    private val inputs = own(model.createInputBuffers("decode"))
    private val outputs = own(model.createOutputBuffers("decode"))
    // Required: stateless-only exports silently stop after their fixed token capacity.
    private val statefulInputs = own(model.createInputBuffers("decode_1"))
    private val statefulOutputs = own(model.createOutputBuffers("decode_1"))
    private fun inputCount(index: Int, signature: String) =
        model.getInputTensorType("args_$index", signature).layout!!.dimensions.fold(1, Math::multiplyExact)
    private fun outputCount(signature: String) =
        model.getOutputTensorType("output_0", signature).layout!!.dimensions.fold(1, Math::multiplyExact)
    private val times = inputCount(0, "decode") / 1024
    private val capacity = inputCount(1, "decode")
    private val stateSize = inputCount(2, "decode")

    init {
        require(inputs.size == 4 && outputs.size == 3 && statefulInputs.size == 4 && statefulOutputs.size == 3)
        require(times == 63 && capacity > 1 && stateSize == 1280)
        require(inputCount(1, "decode_1") == 1 && inputCount(2, "decode_1") == stateSize)
        require(inputCount(0, "decode_1") == times * 1024)
        require(outputCount("decode") == times * capacity * 8198 && outputCount("decode_1") == times * 8198)
    }

    fun decode(encoded: List<TensorBuffer>, validSamples: Int, checkActive: () -> Unit): List<TimedParakeetToken> {
        require(encoded.size == 1)
        val states = listOf(listOf(inputs[2], inputs[3]), listOf(outputs[1], outputs[2]))
        var stateIndex = 0
        states[0].forEach { it.writeFloat(FloatArray(stateSize)) }
        var signature = "decode"
        var tokenBuffer = inputs[1]
        var logitsBuffer = outputs[0]
        var ids = IntArray(capacity).also { it[0] = 8192 }
        var tokenIndex = 0
        var time = 0
        var calls = 0
        val result = ArrayList<TimedParakeetToken>()
        // 160 samples per frontend frame, eight frontend frames per encoder step.
        val validTimes = minOf(times, (validSamples + 1279) / 1280)
        while (time < validTimes) {
            checkActive()
            // A corrupt model must not hold the dictation worker forever on zero-duration emissions.
            check(++calls <= 2048) { "Parakeet decoder exceeded its emission limit" }
            tokenBuffer.writeInt(ids)
            model.run(encoded + tokenBuffer + states[stateIndex], listOf(logitsBuffer) + states[1 - stateIndex], signature)
            checkActive()
            val choice = tdtChoice(logitsBuffer.readFloat(), (time * ids.size + tokenIndex) * 8198)
            if (choice.token != 8192) {
                result += TimedParakeetToken(choice.token, time)
                if (ids.size > 1) {
                    tokenIndex++
                    if (tokenIndex == capacity) {
                        signature = "decode_1"
                        tokenBuffer = statefulInputs[1]
                        logitsBuffer = statefulOutputs[0]
                        ids = IntArray(1)
                        tokenIndex = 0
                    }
                }
                ids[tokenIndex] = choice.token
            }
            time += choice.advance
            // Prediction state advances on emissions only, including the transition to decode_1.
            if (ids.size == 1 && choice.token != 8192) stateIndex = 1 - stateIndex
        }
        return result
    }
}
