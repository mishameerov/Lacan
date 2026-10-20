package org.lacan.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class VoskPcmTest {
    @Test fun normalizedPcmRoundTripsIncludingNegativeFullScale() {
        for (sample in listOf(-32768, -32767, -1, 0, 1, 16384, 32767)) {
            assertEquals(sample.toShort(), voskPcm16(sample / 32768f))
        }
    }

    @Test fun clipsBeforeNarrowingSoFullScaleDoesNotWrap() {
        assertEquals(Short.MAX_VALUE, voskPcm16(1f))
        assertEquals(Short.MAX_VALUE, voskPcm16(2f))
        assertEquals(Short.MIN_VALUE, voskPcm16(-2f))
    }
}
