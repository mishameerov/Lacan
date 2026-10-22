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
    @Test fun growingEditorCanConfirmButChangedIdentityCannot() {
        assertTrue(TargetPolicy.sameEditorAfterCommit(target, target.copy(bottom = 500)))
        assertFalse(TargetPolicy.sameEditorAfterCommit(target, target.copy(viewId = "other")))
        assertFalse(TargetPolicy.sameEditorAfterCommit(target, target.copy(windowId = 8)))
        assertTrue(TargetPolicy.sameEditorAfterCommit(target.copy(viewId = null), target.copy(viewId = null, bottom = 500)))
    }
    @Test fun longInsertionCanBeConfirmedFromBoundedTailAndCursorAdvance() {
        val inserted = "начало " + "а".repeat(5000) + " конец"
        val tail = inserted.takeLast(4096)
        assertTrue(TargetPolicy.verifyInsertion(inserted, 50, tail, tail.length, 50 + inserted.length - tail.length))
        assertFalse(TargetPolicy.verifyInsertion(inserted, 50, tail, tail.length, 49 + inserted.length - tail.length))
        assertFalse(TargetPolicy.verifyInsertion(inserted, 50, "б".repeat(4096), 4096, 50 + inserted.length - 4096))
    }
    @Test fun verificationRequiresExpectedCursorAndActualInsertedText() {
        assertTrue(TargetPolicy.verifyInsertion("мир", 7, "привет мир", 10, 0))
        assertFalse(TargetPolicy.verifyInsertion("мир", 7, "привет мир", 9, 0))
        assertFalse(TargetPolicy.verifyInsertion("мир", 7, "привет кот", 10, 0))
        assertTrue(TargetPolicy.verifyInsertion("мир", 107, "вет мир", 7, 103))
    }
}
