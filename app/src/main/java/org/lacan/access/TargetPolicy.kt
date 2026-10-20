package org.lacan.access

/** Geometry and identity only: never retains a node or field text. */
internal data class TargetFacts(
    val packageName: String, val windowId: Int, val viewId: String?,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val editable: Boolean, val enabled: Boolean, val visible: Boolean,
    val password: Boolean, val inputType: Int
)

internal object TargetPolicy {
    fun eligible(target: TargetFacts, ownPackage: String, locked: Boolean): Boolean =
        !locked && target.packageName.isNotEmpty() && target.packageName != ownPackage &&
            target.editable && target.enabled && target.visible && !target.password &&
            !passwordInputType(target.inputType)

    // Android InputType class/variation masks, independent of framework stubs for JVM tests.
    fun passwordInputType(type: Int): Boolean {
        val inputClass = type and 0x0f
        val variation = type and 0xff0
        return (inputClass == 1 && variation in setOf(0x80, 0x90, 0xe0)) ||
            (inputClass == 2 && variation == 0x10)
    }

    fun sameTarget(a: TargetFacts, b: TargetFacts): Boolean =
        a.packageName == b.packageName && a.windowId == b.windowId && a.viewId == b.viewId &&
            a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

    fun verifyInsertion(inserted: String, beforeCursor: Int, afterText: String,
                        afterSelection: Int, afterOffset: Int): Boolean =
        inserted.isNotEmpty() && beforeCursor >= 0 && afterOffset >= 0 &&
            afterSelection in 0..afterText.length &&
            afterOffset.toLong() + afterSelection == beforeCursor.toLong() + inserted.length &&
            afterText.substring(0, afterSelection).endsWith(inserted)
}
