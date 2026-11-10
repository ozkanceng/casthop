package io.github.ozkanceng.yansit.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrameFitTest {
    @Test
    fun aLandscapeScreenFillsALandscapeFrame() {
        assertEquals(FrameRect(0, 0, 1920, 1200), FrameFit.fit(1920, 1200, 2560, 1600))
    }

    @Test
    fun aPortraitScreenIsCentredWithBlackSides() {
        // SM-X610 turned upright, same frame: 750 px wide picture in the middle of 1920.
        assertEquals(FrameRect(585, 0, 750, 1200), FrameFit.fit(1920, 1200, 1600, 2560))
    }

    @Test
    fun aWiderScreenGetsBarsAboveAndBelow() {
        assertEquals(FrameRect(0, 60, 1920, 1080), FrameFit.fit(1920, 1200, 1920, 1080))
    }

    @Test
    fun sizesAreAlwaysEven() {
        val r = FrameFit.fit(1280, 800, 1080, 2340) // phone, 19.5:9
        assertEquals(0, r.width % 2)
        assertEquals(0, r.height % 2)
        assertEquals(FrameRect(456, 0, 368, 800), r)
    }

    @Test
    fun aPortraitScreenIsTurnedToFillALandscapeFrame() {
        val l = FrameFit.layout(1920, 1200, 1600, 2560, canRotate = true)
        assertEquals(true, l.rotated)
        assertEquals(FrameRect(0, 0, 1920, 1200), l.rect)
        assertEquals(1200, l.sourceWidth) // the VirtualDisplay stays portrait
        assertEquals(1920, l.sourceHeight)
    }

    @Test
    fun withoutReceiverSupportOrInTheSameOrientationNothingTurns() {
        assertEquals(FrameLayout(FrameRect(585, 0, 750, 1200), false), FrameFit.layout(1920, 1200, 1600, 2560, false))
        assertEquals(FrameLayout(FrameRect(0, 0, 1920, 1200), false), FrameFit.layout(1920, 1200, 2560, 1600, true))
        // A phone (19.5:9) turned into a 16:10 frame: bars at the ends, still better than a strip.
        val phone = FrameFit.layout(1280, 800, 1080, 2340, true)
        assertEquals(true, phone.rotated)
        assertEquals(FrameRect(0, 105, 1280, 590), phone.rect)
    }

    @Test
    fun theFrameIsPlannedForTheBoardsOrientation() {
        assertEquals(2560 to 1600, FrameFit.planningSource(1600, 2560, 1920, 1080))
        assertEquals(2560 to 1600, FrameFit.planningSource(2560, 1600, 1920, 1080))
        assertEquals(2560 to 1600, FrameFit.planningSource(1600, 2560, 0, 0))
        assertEquals(1600 to 2560, FrameFit.planningSource(2560, 1600, 1080, 1920))
    }
}
