/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lacan.asr.parakeet

import com.jlibrosa.audio.JLibrosa
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM to the pinned Parakeet LiteRT model's [1, 128, 500] input.
 *
 * Adapted from Google's MelSpectroProcessor. Keep its JLibrosa STFT/mel implementation:
 * FFT/window/padding and Slaney mel filters are part of the exported model's contract.
 * Input is the actual (un-padded) audio, at most five seconds. This class pads feature
 * frames, after normalization over floor(sampleCount / 160) valid frames.
 * Instances are owned by one inference worker; JLibrosa is not shared across engines.
 */
internal class ParakeetFrontend {
    private val jLibrosa = JLibrosa()

    fun process(samples: FloatArray): FloatArray {
        require(samples.size <= MAX_SAMPLES) { "Parakeet windows must not exceed $MAX_SAMPLES samples" }
        require(samples.all { it.isFinite() }) { "Audio samples must be finite" }
        val result = FloatArray(MEL_BINS * FRAMES)
        val validFrames = samples.size / HOP_LENGTH
        // No complete frame has no features; a single frame normalizes to exactly zero.
        // Avoid JLibrosa's reflect-padding out-of-bounds access for these short inputs.
        if (validFrames <= 1) return result

        val emphasized = FloatArray(samples.size)
        emphasized[0] = samples[0]
        for (i in 1 until samples.size) emphasized[i] = samples[i] - 0.97f * samples[i - 1]
        val mel = jLibrosa.generateMelSpectroGram(emphasized, SAMPLE_RATE, 512, MEL_BINS, HOP_LENGTH)
        for (bin in mel.indices) {
            for (frame in mel[bin].indices) mel[bin][frame] = ln(mel[bin][frame] + LOG_ZERO_GUARD)
            var sum = 0f
            for (frame in 0 until validFrames) sum += mel[bin][frame]
            val mean = sum / validFrames
            var sumSq = 0f
            for (frame in 0 until validFrames) {
                val diff = mel[bin][frame] - mean
                sumSq += diff * diff
            }
            val std = sqrt(sumSq / max(1, validFrames - 1)) + 1e-5f
            for (frame in 0 until validFrames) {
                result[bin * FRAMES + frame] = (mel[bin][frame] - mean) / std
            }
        }
        return result
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SAMPLES = 80_000
        const val HOP_LENGTH = 160
        const val MEL_BINS = 128
        const val FRAMES = 500
        private const val LOG_ZERO_GUARD = 5.9604645e-8f // 2**-24, as in the upstream frontend.
    }
}
