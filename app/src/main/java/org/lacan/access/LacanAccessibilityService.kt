package org.lacan.access

import android.accessibilityservice.AccessibilityService
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

open class LacanAccessibilityService : AccessibilityService() {
    companion object {
        var instance: LacanAccessibilityService? = null
            private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null
    private val runtime get() = LacanApp.runtime
    private var wasEligible = false

    protected open fun editorEligible(target: TargetFacts): Boolean = true

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

    protected fun locked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    protected fun facts(node: AccessibilityNodeInfo): TargetFacts {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return TargetFacts(node.packageName?.toString().orEmpty(), node.windowId,
            node.viewIdResourceName, bounds.left, bounds.top, bounds.right, bounds.bottom,
            node.isEditable, node.isEnabled, node.isVisibleToUser, node.isPassword, node.inputType)
    }

    protected fun focused(): AccessibilityNodeInfo? =
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
        val eligible = target != null && TargetPolicy.eligible(target, packageName, isLocked) &&
            editorEligible(target)
        val keyboard = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val visibleTarget = eligible && keyboard && !isLocked
        if (visibleTarget && !wasEligible) runtime.prepareModel()
        wasEligible = visibleTarget
        overlay?.update(runtime.state.value, eligible, keyboard, isLocked)
    }

    /** One paste attempt only. Never restore the clipboard or retry an uncertain result. */
    open suspend fun deliver(text: String): String = withContext(Dispatchers.Main.immediate) {
        if (text.isBlank() || locked() || instance !== this@LacanAccessibilityService) return@withContext "manual"
        var issued = false
        try {
            val firstNode = focused() ?: return@withContext "manual"
            val first = facts(firstNode)
            if (!TargetPolicy.eligible(first, packageName, locked())) return@withContext "manual"
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lacan", text))
            // Clipboard writes can cause UI work. Re-resolve focus and password status immediately
            // before pasting; never use ACTION_SET_TEXT, which replaces the entire field.
            val freshNode = focused() ?: return@withContext "manual"
            val fresh = facts(freshNode)
            if (firstNode != freshNode || !TargetPolicy.sameTarget(first, fresh) ||
                !TargetPolicy.eligible(fresh, packageName, locked())) return@withContext "manual"
            val beforeText = freshNode.text?.toString()
            val start = minOf(freshNode.textSelectionStart, freshNode.textSelectionEnd)
            val end = maxOf(freshNode.textSelectionStart, freshNode.textSelectionEnd)
            issued = true
            freshNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            // A false action result can still follow a remote edit. Do not retry.
            repeat(3) {
                delay(70)
                val currentNode = focused() ?: return@withContext "uncertain"
                val current = facts(currentNode)
                if (currentNode != freshNode || locked() ||
                    !TargetPolicy.sameEditorAfterCommit(first, current) ||
                    !TargetPolicy.eligible(current, packageName, false)) return@withContext "uncertain"
                if (TargetPolicy.verifyPaste(text, beforeText, start, end,
                        currentNode.text?.toString(), currentNode.textSelectionStart,
                        currentNode.textSelectionEnd)) return@withContext "inserted"
            }
            "uncertain"
        } catch (_: Exception) {
            if (issued) "uncertain" else "manual"
        }
    }
}
