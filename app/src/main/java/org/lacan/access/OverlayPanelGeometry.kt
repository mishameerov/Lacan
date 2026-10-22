package org.lacan.access

/** The same 300 ms timeline runs forward to open and backward to close. */
internal data class OverlayPanelGeometry(
    val mainX: Int,
    val cancelX: Int,
    val bubbleX: Int,
    val bubbleWidth: Int,
    val size: Int,
    val gap: Int
) {
    data class Frame(val cancelX: Float, val bubbleX: Float, val bubbleWidth: Float)

    // A very narrow display may have room for a pill smaller than a control. Never
    // create a staging circle wider than its available, settled space.
    private val stageWidth = minOf(size, bubbleWidth).coerceAtLeast(0)
    private val stageBubbleX = if (mainX > cancelX) mainX - gap - stageWidth else mainX + size + gap
    private val stageCancelX = if (mainX > cancelX) stageBubbleX - gap - size else stageBubbleX + stageWidth + gap

    fun frame(milliseconds: Float): Frame {
        val phase = milliseconds.coerceIn(0f, 300f)
        if (bubbleWidth == 0) {
            return Frame(lerp(mainX, cancelX, (phase / 120f).coerceAtMost(1f)), mainX.toFloat(), 0f)
        }
        if (phase <= 120f) {
            val p = phase / 120f
            return Frame(lerp(mainX, stageCancelX, p), lerp(mainX, stageBubbleX, p), stageWidth.toFloat())
        }
        val p = (phase - 120f) / 180f
        return Frame(lerp(stageCancelX, cancelX, p), lerp(stageBubbleX, bubbleX, p), lerp(stageWidth, bubbleWidth, p))
    }

    private fun lerp(start: Int, end: Int, progress: Float) = start + (end - start) * progress
}
