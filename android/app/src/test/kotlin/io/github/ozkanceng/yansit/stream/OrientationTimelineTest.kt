package io.github.ozkanceng.yansit.stream

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrientationTimelineTest {
    @Test
    fun eachFrameGetsTheOrientationItWasDrawnWith() {
        val t = OrientationTimeline()
        t.record(1_000, false)
        t.record(2_000, false)
        t.record(3_000, true) // the tablet turned
        t.record(4_000, true)
        assertFalse(t.rotatedAt(1_000))
        assertFalse(t.rotatedAt(2_000))
        assertTrue(t.rotatedAt(3_000))
        assertTrue(t.rotatedAt(4_000))
    }

    @Test
    fun theEncoderLaggingBehindStillMatches() {
        val t = OrientationTimeline()
        t.record(1_000, true)
        t.record(2_000, true)
        t.record(3_000, false)
        t.record(4_000, true)
        // Output comes after all four were drawn.
        assertTrue(t.rotatedAt(1_000))
        assertTrue(t.rotatedAt(2_000))
        assertFalse(t.rotatedAt(3_000))
        assertTrue(t.rotatedAt(4_000))
        assertTrue(t.rotatedAt(5_000)) // a frame without its own entry: the last one before it
    }

    @Test
    fun nothingRecordedMeansNotRotated() {
        assertFalse(OrientationTimeline().rotatedAt(123))
    }
}
