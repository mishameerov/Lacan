package org.lacan.asr.parakeet

internal const val PARAKEET_WINDOW_SAMPLES = 80000
// 56 encoder steps, also aligned to the frontend hop. Fractional-step strides produce
// incompatible absolute timestamp grids and can leave holes at an ownership boundary.
internal const val PARAKEET_WINDOW_STRIDE = 56 * 1280
internal const val PARAKEET_HALF_OVERLAP = (PARAKEET_WINDOW_SAMPLES - PARAKEET_WINDOW_STRIDE) / 2

/** Five-second windows with shared audio; the final partial window is retained. */
internal fun parakeetWindows(sampleCount: Int): List<IntRange> {
    require(sampleCount >= 0)
    val windows = mutableListOf<IntRange>()
    var start = 0
    while (start < sampleCount) {
        val end = minOf(start.toLong() + PARAKEET_WINDOW_SAMPLES, sampleCount.toLong()).toInt()
        windows += start until end
        if (end == sampleCount) break
        start += PARAKEET_WINDOW_STRIDE
    }
    return windows
}

internal data class TdtChoice(val token: Int, val advance: Int)

/** Vocabulary (8192 pieces + blank) followed by five duration logits. */
internal fun tdtChoice(logits: FloatArray, offset: Int): TdtChoice {
    require(offset >= 0 && offset.toLong() + 8198 <= logits.size)
    fun argmax(start: Int, end: Int): Int {
        var best = start
        for (i in start until end) {
            check(logits[i].isFinite()) { "Parakeet decoder returned a non-finite logit" }
            if (logits[i] > logits[best]) best = i
        }
        return best - start
    }
    val token = argmax(offset, offset + 8193)
    val duration = argmax(offset + 8193, offset + 8198)
    return TdtChoice(token, if (token == 8192 && duration == 0) 1 else duration)
}

/** Hugging Face Metaspace decoder, prepend_scheme=always, followed by upstream trim(). */
internal fun decodeMetaspace(vocabulary: List<String>, specialIds: Set<Int>, ids: IntArray): String =
    buildString {
        for (id in ids) {
            require(id in vocabulary.indices) { "Unknown Parakeet token $id" }
            if (id !in specialIds) append(vocabulary[id].replace('▁', ' '))
        }
    }.trim()

/** Encoder time steps are 80 ms (1280 input samples). */
internal data class TimedParakeetToken(val id: Int, val time: Int)

/**
 * Select whole words by their start time within a window's owned interval. Shared audio
 * is divided at its midpoint, leaving lookahead for the earlier window. No text matching
 * is involved: genuine repetitions outside shared audio always survive.
 */
internal fun ownedParakeetTokens(
    tokens: List<TimedParakeetToken>,
    vocabulary: List<String>,
    specialIds: Set<Int>,
    startSample: Int,
    endSample: Int = Int.MAX_VALUE,
): IntArray {
    val result = ArrayList<Int>()
    var keepWord = false
    var hasWord = false
    for (token in tokens) {
        require(token.id in vocabulary.indices)
        if (token.id in specialIds) continue
        val piece = vocabulary[token.id]
        if (!hasWord || piece.startsWith('▁')) {
            val wordStart = token.time.toLong() * 1280
            keepWord = wordStart >= startSample && wordStart < endSample
            hasWord = true
        }
        if (keepWord) result += token.id
    }
    return result.toIntArray()
}

/** Opt-in diagnostics for public-fixture tests; never emitted or logged during ordinary dictation. */
internal data class ParakeetTokenTrace(val id: Int, val timeStep: Int, val piece: String)

internal data class ParakeetWindowTrace(
    val windowIndex: Int,
    val windowStartSample: Int,
    val windowEndSample: Int,
    val ownedStartSample: Int,
    val ownedEndSample: Int,
    val tokens: List<ParakeetTokenTrace>,
)

internal data class ParakeetWord(val ids: List<Int>, val startSample: Long, val text: String) {
    val normalized: String get() = text.trim { !it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
}

internal fun parakeetWords(
    tokens: List<TimedParakeetToken>, vocabulary: List<String>, specialIds: Set<Int>, windowStart: Int,
): List<ParakeetWord> {
    val words = ArrayList<ParakeetWord>()
    val ids = ArrayList<Int>()
    var start = 0L
    fun flush() {
        if (ids.isNotEmpty()) words += ParakeetWord(ids.toList(), start, decodeMetaspace(vocabulary, specialIds, ids.toIntArray()))
        ids.clear()
    }
    for (token in tokens) {
        require(token.id in vocabulary.indices)
        if (token.id in specialIds) continue
        if (ids.isEmpty() || vocabulary[token.id].startsWith('▁')) {
            flush()
            start = windowStart.toLong() + token.time.toLong() * 1280
        }
        ids += token.id
    }
    flush()
    return words
}

/**
 * TDT word times can shift between overlapping inputs. Reconcile only the actual shared
 * audio and only an unambiguous word occurrence in each raw decode. Repeated words in
 * shared audio are deliberately left alone rather than guessing which occurrence matches.
 */
internal fun reconcileParakeetOverlap(
    previousRaw: List<ParakeetWord>, previousOwned: List<ParakeetWord>,
    currentRaw: List<ParakeetWord>, currentOwned: List<ParakeetWord>,
    sharedStart: Int, sharedEnd: Int,
): List<ParakeetWord> {
    fun shared(word: ParakeetWord) = word.startSample >= sharedStart && word.startSample < sharedEnd
    val earlier = previousRaw.filter(::shared)
    val later = currentRaw.filter(::shared)
    val ownedSuffix = previousOwned.takeLastWhile(::shared)
    return currentOwned.filterNot { incoming ->
        val key = incoming.normalized
        shared(incoming) && key.isNotEmpty() &&
            earlier.count { it.normalized == key } == 1 && later.count { it.normalized == key } == 1 &&
            ownedSuffix.any { existing ->
                existing.normalized == key && kotlin.math.abs(existing.startSample - incoming.startSample) <= 3 * 1280
            }
    }
}
