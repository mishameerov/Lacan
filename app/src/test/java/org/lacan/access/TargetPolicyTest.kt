package org.lacan.access

import org.junit.Assert.*
import org.junit.Test

class TargetPolicyTest {
    private val target = TargetFacts("org.editor", 4, "field", 0, 0, 100, 50, true, true, true, false, 1)
    @Test fun onlyExternalVisibleEnabledEditableUnlockedTargetsAreEligible() {
        assertTrue(TargetPolicy.eligible(target, "org.lacan", false))
        assertFalse(TargetPolicy.eligible(target, "org.lacan", true))
        listOf(target.copy(packageName = "org.lacan"), target.copy(packageName = ""),
            target.copy(editable = false), target.copy(enabled = false), target.copy(visible = false),
            target.copy(password = true)).forEach { assertFalse(TargetPolicy.eligible(it, "org.lacan", false)) }
    }
    @Test fun everyPasswordInputVariationIsRejected() {
        listOf(0x81, 0x91, 0xe1, 0x12, 0x80081).forEach {
            assertFalse(TargetPolicy.eligible(target.copy(inputType = it), "org.lacan", false))
        }
        assertTrue(TargetPolicy.eligible(target.copy(inputType = 0x21), "org.lacan", false))
    }
    @Test fun changedWindowFieldOrPackageCannotReceiveResult() {
        assertTrue(TargetPolicy.sameTarget(target, target.copy()))
        listOf(target.copy(windowId = 5), target.copy(viewId = "other"),
            target.copy(packageName = "other"), target.copy(left = 1)).forEach {
            assertFalse(TargetPolicy.sameTarget(target, it))
        }
    }
    @Test fun verificationRequiresExpectedCursorAndActualInsertedText() {
        assertTrue(TargetPolicy.verifyInsertion("мир", 7, "привет мир", 10, 0))
        assertFalse(TargetPolicy.verifyInsertion("мир", 7, "привет мир", 9, 0))
        assertFalse(TargetPolicy.verifyInsertion("мир", 7, "привет кот", 10, 0))
        assertTrue(TargetPolicy.verifyInsertion("мир", 107, "вет мир", 7, 103))
    }
}
