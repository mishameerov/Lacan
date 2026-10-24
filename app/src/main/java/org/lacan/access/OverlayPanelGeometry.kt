package org.lacan.access

/** The same, internally eased timeline runs forward to open and backward to close. */
internal data class OverlayPanelGeometry(
    val mainX: Int,
    val cancelX: Int,
    val bubbleX: Int,
    val bubbleWidth: Int,
    val size: Int,
    val gap: Int
) {
    companion object {
        const val DURATION_MS = 230
        const val EDGE_PAUSE_MS = 35
        const val RETRACTION_MS = 46
        const val EXTENSION_MS = DURATION_MS - EDGE_PAUSE_MS - RETRACTION_MS
        // Map an interrupted opening to the equal spatial point on the closing curve.
        fun closingTime(openingTime: Float): Float {
            if (openingTime >= EXTENSION_MS) return openingTime
            val p = (openingTime / EXTENSION_MS).coerceIn(0f, 1f)
            return (1f - kotlin.math.sqrt(1f - p * p)) * EXTENSION_MS
        }
        fun openingTime(closingTime: Float): Float {
            if (closingTime >= EXTENSION_MS) return closingTime
            val p = (closingTime / EXTENSION_MS).coerceIn(0f, 1f)
            return kotlin.math.sqrt(1f - (1f - p) * (1f - p)) * EXTENSION_MS
        }
        // Panels shown while preparing have only the cancel control.
        const val SEPARATION_MS = 92
    }

    data class Frame(
        val cancelX: Float,
        val bubbleX: Float,
        val bubbleWidth: Float,
        val cancelVisible: Boolean = true
    )

    fun frame(milliseconds: Float, closing: Boolean = false): Frame {
        val phase = milliseconds.coerceIn(0f, DURATION_MS.toFloat())
        if (bubbleWidth <= 0) {
            return Frame(lerp(mainX.toFloat(), cancelX.toFloat(), smooth((phase / SEPARATION_MS).coerceAtMost(1f))),
                mainX.toFloat(), 0f)
        }
        // At full extension the pill covers the future cancel circle and its gap.
        // Its edge nearest the main control then stays fixed throughout retraction.
        val extendedLeft = minOf(bubbleX, cancelX).toFloat()
        val extendedRight = maxOf(bubbleX + bubbleWidth, cancelX + size).toFloat()
        if (phase < EXTENSION_MS) {
            val elapsed = phase / EXTENSION_MS
            val progress = if (closing) 1f - (1f - elapsed) * (1f - elapsed) else elapsed * elapsed
            val left = lerp(mainX.toFloat(), extendedLeft, progress)
            val right = lerp((mainX + size).toFloat(), extendedRight, progress)
            return Frame(cancelX.toFloat(), left, right - left, cancelVisible = false)
        }
        val progress = smooth(((phase - EXTENSION_MS - EDGE_PAUSE_MS) / RETRACTION_MS).coerceIn(0f, 1f))
        val left = lerp(extendedLeft, bubbleX.toFloat(), progress)
        val right = lerp(extendedRight, (bubbleX + bubbleWidth).toFloat(), progress)
        return Frame(cancelX.toFloat(), left, right - left)
    }

    private fun smooth(progress: Float) = progress * progress * (3f - 2f * progress)
    private fun lerp(start: Float, end: Float, progress: Float) = start + (end - start) * progress
}
