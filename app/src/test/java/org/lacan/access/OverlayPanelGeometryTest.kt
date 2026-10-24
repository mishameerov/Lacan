package org.lacan.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPanelGeometryTest {
    private val right = OverlayPanelGeometry(350, 10, 60, 280, 40, 10)
    private val left = OverlayPanelGeometry(10, 350, 60, 280, 40, 10)

    @Test fun reopeningMidCollapsePreservesBothEdges() {
        val g = OverlayPanelGeometry(350, 10, 60, 280, 40, 10)
        for (time in 0..OverlayPanelGeometry.DURATION_MS step 11) {
            val closing = g.frame(time.toFloat(), closing = true)
            val opening = g.frame(OverlayPanelGeometry.openingTime(time.toFloat()))
            assertEquals(closing.bubbleX, opening.bubbleX, .001f)
            assertEquals(closing.bubbleX + closing.bubbleWidth, opening.bubbleX + opening.bubbleWidth, .001f)
        }
    }

    @Test fun reversingMidExtensionPreservesTheVisibleBounds() {
        val g = OverlayPanelGeometry(350, 10, 60, 280, 40, 10)
        for (time in 0 until OverlayPanelGeometry.EXTENSION_MS step 17) {
            val opening = g.frame(time.toFloat())
            val closing = g.frame(OverlayPanelGeometry.closingTime(time.toFloat()), closing = true)
            assertEquals(opening.bubbleX, closing.bubbleX, .001f)
            assertEquals(opening.bubbleWidth, closing.bubbleWidth, .001f)
        }
    }

    @Test fun closingExtensionAcceleratesTowardsMainControl() {
        val g = OverlayPanelGeometry(350, 10, 60, 280, 40, 10)
        val end = OverlayPanelGeometry.EXTENSION_MS.toFloat()
        val a = g.frame(end, closing = true).bubbleX
        val b = g.frame(end * .75f, closing = true).bubbleX
        val c = g.frame(end * .5f, closing = true).bubbleX
        assertTrue(c - b > b - a)
        assertEquals(g.frame(0f), g.frame(0f, closing = true))
    }

    @Test fun bubbleAloneExtendsFromMainToCoverFutureCancel() {
        assertEquals(OverlayPanelGeometry.Frame(10f, 350f, 40f, false), right.frame(0f))
        assertEquals(OverlayPanelGeometry.Frame(350f, 10f, 40f, false), left.frame(0f))
        for (geometry in listOf(left, right)) {
            var previousWidth = 0f
            for (time in 0 until OverlayPanelGeometry.EXTENSION_MS) {
                val frame = geometry.frame(time.toFloat())
                assertFalse(frame.cancelVisible)
                assertEquals(geometry.cancelX.toFloat(), frame.cancelX, 0f)
                assertTrue(frame.bubbleWidth >= previousWidth)
                previousWidth = frame.bubbleWidth
            }
            val extended = geometry.frame(OverlayPanelGeometry.EXTENSION_MS.toFloat())
            assertEquals(minOf(geometry.bubbleX, geometry.cancelX).toFloat(), extended.bubbleX, 0f)
            assertEquals(330f, extended.bubbleWidth, 0f)
            assertTrue(extended.cancelVisible)
        }
    }

    @Test fun extensionAcceleratesTowardTheOppositeEdge() {
        val duration = OverlayPanelGeometry.EXTENSION_MS.toFloat()
        val earlyGrowth = right.frame(duration / 4f).bubbleWidth - right.frame(0f).bubbleWidth
        val lateGrowth = right.frame(duration).bubbleWidth - right.frame(duration * 3f / 4f).bubbleWidth
        assertTrue(lateGrowth > earlyGrowth * 6f)
    }

    @Test fun edgePauseLastsExactly35MillisecondsBeforeGentleRetraction() {
        assertEquals(230, OverlayPanelGeometry.DURATION_MS)
        assertEquals(35, OverlayPanelGeometry.EDGE_PAUSE_MS)
        val pauseStart = OverlayPanelGeometry.EXTENSION_MS
        val pauseEnd = pauseStart + OverlayPanelGeometry.EDGE_PAUSE_MS
        for (geometry in listOf(left, right)) {
            val extended = geometry.frame(pauseStart.toFloat())
            for (time in pauseStart..pauseEnd) assertEquals(extended, geometry.frame(time.toFloat()))
            assertTrue(geometry.frame((pauseEnd + 1).toFloat()).bubbleWidth < extended.bubbleWidth)
            val middle = geometry.frame((pauseEnd + OverlayPanelGeometry.RETRACTION_MS / 2).toFloat())
            assertEquals((extended.bubbleWidth + geometry.bubbleWidth) / 2f, middle.bubbleWidth, .001f)
            assertEquals(OverlayPanelGeometry.Frame(geometry.cancelX.toFloat(), geometry.bubbleX.toFloat(),
                geometry.bubbleWidth.toFloat()), geometry.frame(230f))
        }
    }

    @Test fun retractionKeepsNearEdgeFixedAndExposesCancelAndGap() {
        val start = OverlayPanelGeometry.EXTENSION_MS + OverlayPanelGeometry.EDGE_PAUSE_MS
        for (time in start..OverlayPanelGeometry.DURATION_MS) {
            val rightFrame = right.frame(time.toFloat())
            val leftFrame = left.frame(time.toFloat())
            assertTrue(rightFrame.cancelVisible)
            assertTrue(leftFrame.cancelVisible)
            assertEquals(340f, rightFrame.bubbleX + rightFrame.bubbleWidth, .001f)
            assertEquals(60f, leftFrame.bubbleX, .001f)
        }
        assertEquals(10f, right.frame(230f).bubbleX - right.cancelX - right.size, .001f)
        assertEquals(10f, left.cancelX - left.frame(230f).bubbleX - left.frame(230f).bubbleWidth, .001f)
    }

    @Test fun reverseTimelineCoversCancelBeforeCollapsingIntoMain() {
        var previousWidth = right.bubbleWidth.toFloat()
        for (elapsed in 0..OverlayPanelGeometry.RETRACTION_MS) {
            val frame = right.frame((OverlayPanelGeometry.DURATION_MS - elapsed).toFloat())
            assertTrue(frame.cancelVisible)
            assertTrue(frame.bubbleWidth >= previousWidth)
            previousWidth = frame.bubbleWidth
        }
        val extensionEnd = OverlayPanelGeometry.EXTENSION_MS
        assertTrue(right.frame(extensionEnd.toFloat()).cancelVisible)
        assertFalse(right.frame((extensionEnd - 1).toFloat()).cancelVisible)
        assertEquals(right.frame(0f), right.frame(-10f))
        assertEquals(right.frame(230f), right.frame(240f))
    }

    @Test fun everyFrameStaysInsideBothEdgesIncludingNarrowPanels() {
        for (width in listOf(140, 160, 200, 400, 1080)) {
            val right = OverlayPanelGeometry(width - 50, 10, 60, width - 120, 40, 10)
            val left = OverlayPanelGeometry(10, width - 50, 60, width - 120, 40, 10)
            for (geometry in listOf(left, right)) for (time in 0..OverlayPanelGeometry.DURATION_MS) {
                val frame = geometry.frame(time.toFloat())
                assertTrue("cancel left at $width/$time", frame.cancelX >= 0f)
                assertTrue("cancel right at $width/$time", frame.cancelX + 40f <= width)
                assertTrue("bubble left at $width/$time", frame.bubbleX >= 0f)
                assertTrue("bubble right at $width/$time", frame.bubbleX + frame.bubbleWidth <= width + .001f)
                assertTrue(frame.bubbleWidth >= 0f)
            }
        }
    }

    @Test fun preparingPanelHasNoBubbleAndOnlyMovesItsCancelControl() {
        val geometry = OverlayPanelGeometry(350, 300, 0, 0, 40, 10)
        assertEquals(OverlayPanelGeometry.Frame(325f, 350f, 0f), geometry.frame(46f))
        assertEquals(OverlayPanelGeometry.Frame(300f, 350f, 0f), geometry.frame(92f))
        assertEquals(geometry.frame(92f), geometry.frame(230f))
    }
}
