package org.lacan.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationButtonColorTest {
    @Test fun missingOrObsoletePreferenceUsesOrange() {
        assertEquals(DictationButtonColor.Orange, DictationButtonColor.fromPreference(null))
        assertEquals(DictationButtonColor.Orange, DictationButtonColor.fromPreference("Red"))
        DictationButtonColor.entries.forEach {
            assertEquals(it, DictationButtonColor.fromPreference(it.name))
        }
    }
}
