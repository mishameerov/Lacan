package org.lacan.ui

import org.junit.Assert.*
import org.junit.Test

class SetupPolicyTest {
    private val ready = ScreenState(microphoneGranted = true, notificationsGranted = true,
        overlayGranted = true, accessibilityEnabled = true,
        models = listOf(ModelCard("giga", "GigaAM", "", 886, true, true)))
    @Test fun modelMustBeInstalledAndSelectedBeforePermissions() {
        assertEquals(SetupStep.Model, ScreenState().nextSetupStep())
        assertEquals(SetupStep.Model, ready.copy(models = ready.models.map { it.copy(installed = false) }).nextSetupStep())
        assertEquals(SetupStep.Model, ready.copy(models = ready.models.map { it.copy(selected = false) }).nextSetupStep())
    }
    @Test fun completedStepsAreSkippedAndRevocationIsDetected() {
        assertNull(ready.nextSetupStep())
        assertNull(ready.setupBlockReason())
        assertEquals(SetupStep.Microphone, ready.copy(microphoneGranted = false, overlayGranted = false).nextSetupStep())
        assertEquals(SetupStep.Notifications, ready.copy(notificationsGranted = false).nextSetupStep())
        assertEquals(SetupStep.Overlay, ready.copy(overlayGranted = false).nextSetupStep())
        assertEquals(SetupStep.Accessibility, ready.copy(accessibilityEnabled = false).nextSetupStep())
        assertEquals(SetupStep.Enable, ready.copy(enabled = false).nextSetupStep())
    }
    @Test fun warmingModelDoesNotSendConfiguredUserBackToSetup() {
        assertNull(ready.copy(readyToRecord = false).setupBlockReason())
    }
}
