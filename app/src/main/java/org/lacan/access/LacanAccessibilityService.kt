package org.lacan.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lacan.LacanApp

class LacanAccessibilityService : AccessibilityService() {
    companion object {
        var instance: LacanAccessibilityService? = null
            private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null
    private val runtime get() = LacanApp.runtime
    private var editorGeneration = 0L
    private var composing = false
    private var wasEligible = false

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) { editorGeneration++; composing = false }
        override fun onFinishInput() { editorGeneration++; composing = false }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
                                       newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            composing = candidatesStart >= 0 && candidatesEnd > candidatesStart
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        runtime.refreshPermissions()
        scope.launch { runtime.state.collect { refreshOverlay() } }
        scope.launch {
            while (true) {
                refreshOverlay()
                runtime.refreshPermissions()
                delay(1000)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { refreshOverlay() }
    override fun onInterrupt() { overlay?.hide() }
    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        overlay?.close()
        overlay = null
        runtime.refreshPermissions()
        if (runtime.state.value.recording) runtime.stop("control_lost")
        super.onDestroy()
    }

    private fun locked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    private fun facts(node: AccessibilityNodeInfo): TargetFacts {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return TargetFacts(node.packageName?.toString().orEmpty(), node.windowId,
            node.viewIdResourceName, bounds.left, bounds.top, bounds.right, bounds.bottom,
            node.isEditable, node.isEnabled, node.isVisibleToUser, node.isPassword, node.inputType)
    }

    private fun focused(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.refresh() && it.isFocused }

    private fun refreshOverlay() {
        if (instance !== this) return
        val state = runtime.state.value
        val isLocked = locked()
        if (isLocked || (!state.enabled && !state.busy && state.result.isBlank())) {
            overlay?.update(state, false, false, isLocked)
            return
        }
        val target = runCatching { focused()?.let(::facts) }.getOrNull()
        val editor = inputMethod?.currentInputEditorInfo
        val eligible = target != null && TargetPolicy.eligible(target, packageName, isLocked) &&
            editor?.packageName == target.packageName && !TargetPolicy.passwordInputType(editor.inputType)
        val keyboard = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val visibleTarget = eligible && keyboard && !isLocked
        if (visibleTarget && !wasEligible) runtime.prepareModel()
        wasEligible = visibleTarget
        overlay?.update(runtime.state.value, eligible, keyboard, isLocked)
    }

    /** One attempt only. An uncertain return must never be retried automatically. */
    suspend fun deliver(text: String): String = withContext(Dispatchers.Main.immediate) {
        if (text.isBlank() || locked() || instance !== this@LacanAccessibilityService) return@withContext "manual"
        var issued = false
        try {
            val generation = editorGeneration
            val firstNode = focused() ?: return@withContext "manual"
            val first = facts(firstNode)
            if (!TargetPolicy.eligible(first, packageName, locked())) return@withContext "manual"
            val method = inputMethod ?: return@withContext "manual"
            val editor = method.currentInputEditorInfo ?: return@withContext "manual"
            if (editor.packageName != first.packageName || TargetPolicy.passwordInputType(editor.inputType)) return@withContext "manual"
            val connection = method.currentInputConnection ?: return@withContext "manual"
            // Selection offsets need no read of pre-existing field text.
            val selection = connection.getSurroundingText(0, 0, 0)
            val cursor = if (selection != null && selection.offset >= 0 &&
                selection.selectionStart >= 0 && selection.selectionEnd >= 0) {
                selection.offset + minOf(selection.selectionStart, selection.selectionEnd)
            } else minOf(firstNode.textSelectionStart, firstNode.textSelectionEnd)
            val freshNode = focused() ?: return@withContext "manual"
            val fresh = facts(freshNode)
            val freshEditor = method.currentInputEditorInfo ?: return@withContext "manual"
            if (firstNode != freshNode || !TargetPolicy.sameTarget(first, fresh) ||
                !TargetPolicy.eligible(fresh, packageName, locked()) ||
                freshEditor.packageName != fresh.packageName ||
                TargetPolicy.passwordInputType(freshEditor.inputType) ||
                freshEditor.fieldId != editor.fieldId || freshEditor.inputType != editor.inputType ||
                generation != editorGeneration || composing || !method.currentInputStarted) return@withContext "manual"
            // Mark before issuing: an exception can occur after the remote editor consumed text.
            issued = true
            connection.commitText(text, 1, null)
            if (cursor < 0) return@withContext "uncertain"
            repeat(3) {
                delay(70)
                val currentNode = focused() ?: return@withContext "uncertain"
                val current = facts(currentNode)
                if (currentNode != freshNode || generation != editorGeneration || locked() ||
                    !TargetPolicy.sameEditorAfterCommit(first, current) ||
                    !TargetPolicy.eligible(current, packageName, false)) return@withContext "uncertain"
                val after = connection.getSurroundingText(minOf(text.length, TargetPolicy.VERIFICATION_TAIL) + 128, 0, 0)
                if (after != null && after.selectionStart == after.selectionEnd &&
                    TargetPolicy.verifyInsertion(text, cursor, after.text.toString(), after.selectionStart, after.offset)) {
                    return@withContext "inserted"
                }
            }
            "uncertain"
        } catch (_: Exception) {
            if (issued) "uncertain" else "manual"
        }
    }
}
