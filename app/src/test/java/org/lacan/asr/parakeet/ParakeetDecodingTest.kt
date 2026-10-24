package org.lacan.asr.parakeet

import org.junit.Assert.*
import org.junit.Test

class ParakeetDecodingTest {
    @Test fun windowsCoverLongAudioIncludingTail() {
        val windows = parakeetWindows(176001)
        assertEquals(listOf(0 until 80000, 71680 until 151680, 143360 until 176001), windows)
        assertTrue(windows.last().last == 176000)
    }

    @Test fun durationZeroBlankStillAdvances() {
        assertEquals(TdtChoice(8192, 1), tdtChoice(FloatArray(8198).apply { this[8192] = 2f; this[8193] = 3f }, 0))
    }

    @Test fun nonblankMayStayAtSameTime() {
        assertEquals(TdtChoice(42, 0), tdtChoice(FloatArray(8198).apply { this[42] = 2f; this[8193] = 3f }, 0))
    }

    @Test fun decodingPreservesCyrillicPiecesAndSkipsOnlySpecialIds() {
        assertEquals("Привет мир!", decodeMetaspace(listOf("▁При", "вет", "▁мир", "!", "<pad>"), setOf(4), intArrayOf(0, 1, 4, 2, 3)))
    }
    @Test fun timestampOwnershipDropsMisrecognizedOverlapButKeepsTail() {
        val vocab = listOf("▁В", "▁этом", "▁любви", "▁посмотрит", "▁на")
        val tokens = listOf(TimedParakeetToken(0, 0), TimedParakeetToken(1, 1), TimedParakeetToken(2, 2), TimedParakeetToken(3, 8), TimedParakeetToken(4, 10))
        assertArrayEquals(intArrayOf(3, 4), ownedParakeetTokens(tokens, vocab, emptySet(), 4000))
    }

    @Test fun repeatedWordsOutsideOverlapArePreserved() {
        val vocab = listOf("▁да")
        val tokens = listOf(TimedParakeetToken(0, 0), TimedParakeetToken(0, 8), TimedParakeetToken(0, 10))
        assertArrayEquals(intArrayOf(0, 0), ownedParakeetTokens(tokens, vocab, emptySet(), 4000))
    }

    @Test fun ownershipNeverKeepsOnlySuffixOfAWord() {
        val vocab = listOf("▁лю", "бви", "▁снова")
        val tokens = listOf(TimedParakeetToken(0, 2), TimedParakeetToken(1, 8), TimedParakeetToken(2, 10))
        assertArrayEquals(intArrayOf(2), ownedParakeetTokens(tokens, vocab, emptySet(), 4000))
        assertArrayEquals(intArrayOf(0, 1, 2), ownedParakeetTokens(tokens, vocab, emptySet(), 0))
    }
    @Test fun midpointKeepsWholeWordWithLookaheadPastOwnershipEnd() {
        val vocab = listOf("▁пос", "мотрит", "▁на")
        val earlier = listOf(TimedParakeetToken(0, 59), TimedParakeetToken(1, 61), TimedParakeetToken(2, 62))
        assertArrayEquals(intArrayOf(0, 1), ownedParakeetTokens(earlier, vocab, emptySet(), 0, 76000))
        val later = listOf(TimedParakeetToken(0, 2), TimedParakeetToken(1, 5), TimedParakeetToken(2, 6))
        assertArrayEquals(intArrayOf(2), ownedParakeetTokens(later, vocab, emptySet(), 4000))
    }
    @Test fun neighboringWindowsOwnEverySharedGlobalTimeStepExactlyOnce() {
        val vocab = listOf("▁word")
        val cutoff = PARAKEET_WINDOW_SAMPLES - PARAKEET_HALF_OVERLAP
        for (absoluteStep in 56..62) {
            val earlier = ownedParakeetTokens(listOf(TimedParakeetToken(0, absoluteStep)), vocab, emptySet(), 0, cutoff)
            val later = ownedParakeetTokens(listOf(TimedParakeetToken(0, absoluteStep - 56)), vocab, emptySet(), PARAKEET_HALF_OVERLAP)
            assertEquals("Global step $absoluteStep must belong to exactly one window", 1, earlier.size + later.size)
        }
        assertEquals(0, PARAKEET_WINDOW_STRIDE % 1280)
        assertEquals(cutoff, PARAKEET_WINDOW_STRIDE + PARAKEET_HALF_OVERLAP)
    }
    @Test fun shiftedUniqueWordInSharedAudioIsReconciled() {
        val vocab = listOf("▁лю", "б", "ви", ".", "▁В", "▁этом", "▁посмотрит")
        val earlier = parakeetWords(listOf(TimedParakeetToken(0, 58), TimedParakeetToken(1, 59), TimedParakeetToken(2, 60), TimedParakeetToken(3, 61)), vocab, emptySet(), 0)
        val later = parakeetWords(listOf(TimedParakeetToken(4, 1), TimedParakeetToken(5, 2), TimedParakeetToken(0, 4), TimedParakeetToken(1, 5), TimedParakeetToken(2, 7), TimedParakeetToken(6, 11)), vocab, emptySet(), 72000)
        val owned = later.filter { it.startSample >= 76000 }
        val result = reconcileParakeetOverlap(earlier, earlier, later, owned, 72000, 80000)
        assertEquals(listOf("посмотрит"), result.map { it.text })
    }

    @Test fun ambiguousCloseRepeatedWordsArePreserved() {
        val a = ParakeetWord(listOf(0), 74240, "давай")
        val b = ParakeetWord(listOf(0), 76800, "давай")
        val laterA = a.copy(startSample = 75520)
        val laterB = b.copy(startSample = 78080)
        val result = reconcileParakeetOverlap(listOf(a, b), listOf(a), listOf(laterA, laterB), listOf(laterB), 72000, 80000)
        assertEquals(listOf(laterB), result)
    }

    @Test fun matchingWordsOutsideSharedAudioOrTooFarApartArePreserved() {
        val a = ParakeetWord(listOf(0), 74240, "любви")
        val outside = a.copy(startSample = 81000)
        val distant = a.copy(startSample = 79500)
        val result = reconcileParakeetOverlap(listOf(a), listOf(a), listOf(outside, distant), listOf(outside, distant), 72000, 80000)
        assertEquals(listOf(outside, distant), result)
    }

    @Test fun punctuationAloneCannotAnchorASeam() {
        val a = ParakeetWord(listOf(0), 74240, ".")
        val b = a.copy(startSample = 76800)
        assertEquals(listOf(b), reconcileParakeetOverlap(listOf(a), listOf(a), listOf(b), listOf(b), 72000, 80000))
    }
}
