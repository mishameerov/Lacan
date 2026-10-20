package org.lacan.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoreTest {
    @Test fun spoolSupportsRandomReadsAndExactPcmNormalization() {
        val file = File.createTempFile("lacan", ".pcm")
        val spool = AudioSpool(file)
        try {
            val random = Random(42)
            val pcm = ShortArray(64000) { random.nextInt(65536).toShort() }
            spool.append(pcm, 32000)
            spool.append(pcm.copyOfRange(32000, pcm.size))
            assertEquals(64000L, spool.sampleCount)
            repeat(30) {
                val start = random.nextInt(63000)
                val actual = spool.read(start.toLong(), (start + 1000).toLong())
                repeat(1000) { assertEquals(pcm[start + it] / 32768f, actual[it], 0f) }
            }
            assertEquals(128000L, file.length())
            assertThrows(IllegalArgumentException::class.java) { spool.read(-1, 0) }
            assertThrows(IllegalArgumentException::class.java) { spool.read(0, 64001) }
        } finally { spool.delete() }
        assertFalse(file.exists())
        assertThrows(IllegalStateException::class.java) { spool.append(shortArrayOf(1)) }
    }

    @Test fun spoolSerializesConcurrentReadsAndAppendsAndBoundsWorkingWindows() {
        val spool = AudioSpool(File.createTempFile("lacan", ".pcm"))
        try {
            spool.append(ShortArray(320000) { 1234 })
            val pool = Executors.newFixedThreadPool(3)
            val jobs = (0..2).map { job -> pool.submit {
                repeat(30) {
                    if (job == 0) spool.append(ShortArray(160) { 1234 })
                    else assertEquals(1234 / 32768f, spool.read(0, 320000).last(), 0f)
                }
            } }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            pool.shutdownNow()
            assertEquals(324800L, spool.sampleCount)
            assertThrows(IllegalArgumentException::class.java) { spool.read(0, 320001) }
        } finally { spool.delete() }
    }

    @Test fun spoolRejectsAudioBeyondTenMinutes() {
        val spool = AudioSpool(File.createTempFile("lacan", ".pcm"))
        try {
            repeat(30) { spool.append(ShortArray(320000)) }
            assertThrows(IllegalArgumentException::class.java) { spool.append(shortArrayOf(1)) }
            assertEquals(9600000L, spool.sampleCount)
        } finally { spool.delete() }
    }

    @Test fun silenceNeverSchedulesRecognition() {
        val segmenter = Segmenter()
        repeat(35) { assertTrue(segmenter.accept(ShortArray(16000)).isEmpty()) }
        assertTrue(segmenter.finish().isEmpty())
        assertTrue(segmenter.finish().isEmpty())
    }

    @Test fun pauseEndsSpeechAndNextSpeechRetainsPreroll() {
        val segmenter = Segmenter()
        assertTrue(segmenter.accept(ShortArray(16000)).isEmpty())
        assertTrue(segmenter.accept(ShortArray(8000) { 2000 }).isEmpty())
        val first = segmenter.accept(ShortArray(9600)).single()
        assertEquals(AudioSegment(12800, 33600), first)
        segmenter.accept(ShortArray(1000))
        segmenter.accept(ShortArray(100) { 2000 })
        val tail = segmenter.finish().single()
        assertTrue(tail.start <= 34600L)
        assertEquals(34700L, tail.end)
        assertEquals(0, tail.overlapSamples)
    }

    @Test fun continuousSpeechCoversTenMinutesWithBoundedOverlapAndShortTail() {
        val segmenter = Segmenter()
        val segments = mutableListOf<AudioSegment>()
        repeat(600) { segments += segmenter.accept(ShortArray(16000) { 2000 }) }
        segments += segmenter.finish()
        assertEquals(0L, segments.first().start)
        assertEquals(9600000L, segments.last().end)
        segments.forEach { assertTrue(it.end - it.start <= 320000L) }
        segments.zipWithNext().forEach { (a, b) ->
            assertEquals(a.end - 12000, b.start)
            assertEquals(12000, b.overlapSamples)
            assertTrue(b.end > a.end)
        }
        assertTrue(segmenter.accept(shortArrayOf(2000)).isEmpty())
    }

    @Test fun forcedBoundaryDoesNotDropSubframeTailOrReplayOnlyOverlap() {
        val segmenter = Segmenter()
        val first = segmenter.accept(ShortArray(320000) { 2000 }).single()
        assertEquals(AudioSegment(0, 320000), first)
        segmenter.accept(ShortArray(17) { 2000 })
        assertEquals(AudioSegment(308000, 320017, 12000), segmenter.finish().single())
        val exact = Segmenter()
        assertEquals(1, exact.accept(ShortArray(320000) { 2000 }).size)
        assertTrue(exact.finish().isEmpty())
    }

    @Test fun transcriptPreservesRepeatsAndOnlyDeduplicatesKnownOverlap() {
        val a = TranscriptAssembler()
        assertEquals("да, да", a.append(" да, да ", false))
        assertEquals("да, да да", a.append("да", false))
        assertEquals("да, да да дальше", a.append("да дальше", true))
        assertEquals("да, да да дальше", a.append("  ", true))
        val b = TranscriptAssembler()
        b.append("один два три четыре пять шесть семь восемь девять", false)
        b.append("один два три четыре пять шесть семь восемь девять десять", true)
        assertTrue(b.text.contains("девять один"))
    }

    @Test fun stopAcceptsFinalsAndCancelRejectsLateCallbacksUntilWorkerFinishes() {
        val gate = SessionGate()
        val first = gate.start()!!
        assertNull(gate.start())
        gate.stop(first)
        assertTrue(gate.accepts(first))
        assertNull(gate.start())
        gate.cancel(first)
        assertFalse(gate.accepts(first))
        assertNull(gate.start())
        gate.finish(first)
        val second = gate.start()!!
        assertTrue(second > first)
        gate.finish(first)
        gate.cancel(first)
        assertTrue(gate.accepts(second))
        assertFalse(gate.accepts(first))
        gate.finish(second)
        assertNotNull(gate.start())
    }
}
