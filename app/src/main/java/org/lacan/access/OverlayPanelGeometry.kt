package org.lacan.access

/** The same timeline runs forward to open and backward to close. */
internal data class OverlayPanelGeometry(
    val mainX: Int,
    val cancelX: Int,
    val bubbleX: Int,
    val bubbleWidth: Int,
    val size: Int,
    val gap: Int
) {
    companion object {
        const val DURATION_MS = 200
        const val SEPARATION_MS = 80
    }

    data class Frame(val cancelX: Float, val bubbleX: Float, val bubbleWidth: Float)

    // A very narrow display may have room for a pill smaller than a control. Never
    // create a staging circle wider than its available, settled space.
    private val stageWidth = minOf(size, bubbleWidth).coerceAtLeast(0)
    private val stageBubbleX = if (mainX > cancelX) mainX - gap - stageWidth else mainX + size + gap
    private val stageCancelX = if (mainX > cancelX) stageBubbleX - gap - size else stageBubbleX + stageWidth + gap

    fun frame(milliseconds: Float): Frame {
        val phase = milliseconds.coerceIn(0f, DURATION_MS.toFloat())
        if (bubbleWidth == 0) {
            return Frame(lerp(mainX, cancelX, (phase / SEPARATION_MS).coerceAtMost(1f)), mainX.toFloat(), 0f)
        }
        if (phase <= SEPARATION_MS) {
            val p = phase / SEPARATION_MS
            return Frame(lerp(mainX, stageCancelX, p), lerp(mainX, stageBubbleX, p), stageWidth.toFloat())
        }
        val p = (phase - SEPARATION_MS) / (DURATION_MS - SEPARATION_MS)
        return Frame(lerp(stageCancelX, cancelX, p), lerp(stageBubbleX, bubbleX, p), lerp(stageWidth, bubbleWidth, p))
    }

    private fun lerp(start: Int, end: Int, progress: Float) = start + (end - start) * progress
}
