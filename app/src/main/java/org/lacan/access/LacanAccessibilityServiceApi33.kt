package org.lacan.access

import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Loaded only on Android 13+, where accessibility InputMethod exists. */
@RequiresApi(33)
class LacanAccessibilityServiceApi33 : LacanAccessibilityService() {
    private var editorGeneration = 0L
    private var composing = false

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) { editorGeneration++; composing = false }
        override fun onFinishInput() { editorGeneration++; composing = false }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
                                       newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            composing = candidatesStart >= 0 && candidatesEnd > candidatesStart
        }
    }

    override fun editorEligible(target: TargetFacts): Boolean {
        val editor = inputMethod?.currentInputEditorInfo
        return editor?.packageName == target.packageName && !TargetPolicy.passwordInputType(editor.inputType)
    }

    /** One attempt only. An uncertain return must never be retried automatically. */
    override suspend fun deliver(text: String): String = withContext(Dispatchers.Main.immediate) {
        if (text.isBlank() || locked() || instance !== this@LacanAccessibilityServiceApi33) return@withContext "manual"
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
