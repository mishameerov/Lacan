package org.lacan.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPanelGeometryTest {
    @Test fun rightAnchoredPanelSeparatesFor80ThenGrowsFor120() {
        val geometry = OverlayPanelGeometry(350, 10, 60, 280, 40, 10)
        assertEquals(OverlayPanelGeometry.Frame(350f, 350f, 40f), geometry.frame(0f))
        assertEquals(OverlayPanelGeometry.Frame(300f, 325f, 40f), geometry.frame(40f))
        assertEquals(OverlayPanelGeometry.Frame(250f, 300f, 40f), geometry.frame(80f))
        assertEquals(OverlayPanelGeometry.Frame(130f, 180f, 160f), geometry.frame(140f))
        assertEquals(OverlayPanelGeometry.Frame(10f, 60f, 280f), geometry.frame(200f))
        for (time in 0..OverlayPanelGeometry.SEPARATION_MS) assertEquals(40f, geometry.frame(time.toFloat()).bubbleWidth, 0f)
        for (time in OverlayPanelGeometry.SEPARATION_MS..OverlayPanelGeometry.DURATION_MS) {
            val frame = geometry.frame(time.toFloat())
            assertEquals(340f, frame.bubbleX + frame.bubbleWidth, .001f)
            assertEquals(10f, frame.bubbleX - frame.cancelX - 40f, .001f)
        }
    }

    @Test fun leftAnchoredPanelKeepsItsLeadingBubbleEdgeDuringGrowth() {
        val geometry = OverlayPanelGeometry(10, 350, 60, 280, 40, 10)
        assertEquals(OverlayPanelGeometry.Frame(110f, 60f, 40f), geometry.frame(OverlayPanelGeometry.SEPARATION_MS.toFloat()))
        assertEquals(OverlayPanelGeometry.Frame(230f, 60f, 160f), geometry.frame(140f))
        for (time in OverlayPanelGeometry.SEPARATION_MS..OverlayPanelGeometry.DURATION_MS) {
            val frame = geometry.frame(time.toFloat())
            assertEquals(60f, frame.bubbleX, 0f)
            assertEquals(10f, frame.cancelX - frame.bubbleX - frame.bubbleWidth, .001f)
        }
    }

    @Test fun reverseTimelineShrinksFor120BeforeButtonsConvergeFor80() {
        val geometry = OverlayPanelGeometry(350, 10, 60, 280, 40, 10)
        for (elapsed in 0..(OverlayPanelGeometry.DURATION_MS - OverlayPanelGeometry.SEPARATION_MS)) {
            val frame = geometry.frame((OverlayPanelGeometry.DURATION_MS - elapsed).toFloat())
            assertEquals(340f, frame.bubbleX + frame.bubbleWidth, .001f)
            assertTrue(frame.bubbleWidth >= 40f)
        }
        for (elapsed in (OverlayPanelGeometry.DURATION_MS - OverlayPanelGeometry.SEPARATION_MS)..OverlayPanelGeometry.DURATION_MS) {
            assertEquals(40f, geometry.frame((OverlayPanelGeometry.DURATION_MS - elapsed).toFloat()).bubbleWidth, 0f)
        }
        assertEquals(OverlayPanelGeometry.Frame(350f, 350f, 40f), geometry.frame(0f))
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
                assertTrue("bubble right at $width/$time", frame.bubbleX + frame.bubbleWidth <= width)
                assertTrue(frame.bubbleWidth >= 0f)
            }
        }
    }

    @Test fun preparingPanelHasNoBubbleAndOnlyMovesItsCancelControl() {
        val geometry = OverlayPanelGeometry(350, 300, 0, 0, 40, 10)
        assertEquals(OverlayPanelGeometry.Frame(325f, 350f, 0f), geometry.frame(40f))
        assertEquals(OverlayPanelGeometry.Frame(300f, 350f, 0f), geometry.frame(OverlayPanelGeometry.SEPARATION_MS.toFloat()))
        assertEquals(geometry.frame(OverlayPanelGeometry.SEPARATION_MS.toFloat()), geometry.frame(OverlayPanelGeometry.DURATION_MS.toFloat()))
    }
}
