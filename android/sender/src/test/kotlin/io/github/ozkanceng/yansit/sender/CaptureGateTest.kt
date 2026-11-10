package io.github.ozkanceng.yansit.sender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureGateTest {
    private var congestion = 0
    private val gate = CaptureGate(onCongestion = { congestion++ })

    @Test
    fun anEmptyQueueOrOneFreshFrameLetsAFrameThrough() {
        assertTrue(gate.ready(0, depth = 0, oldestAgeUs = 0, sending = true))
        assertTrue(gate.ready(0, depth = 1, oldestAgeUs = 10_000, sending = true))
    }

    @Test
    fun aWaitingFrameOrTwoHoldTheNextOne() {
        assertFalse(gate.ready(0, depth = 1, oldestAgeUs = 16_000, sending = true))
        assertFalse(gate.ready(0, depth = 2, oldestAgeUs = 1_000, sending = true))
    }

    @Test
    fun aLongHoldIsReportedOncePerStretch() {
        assertFalse(gate.ready(0, 2, 20_000, true))
        assertFalse(gate.ready(40_000, 2, 60_000, true))
        assertEquals(0, congestion)
        assertFalse(gate.ready(51_000, 2, 71_000, true))
        assertFalse(gate.ready(200_000, 2, 220_000, true))
        assertEquals(1, congestion)

        assertTrue(gate.ready(210_000, 0, 0, true))
        assertFalse(gate.ready(220_000, 2, 20_000, true))
        assertFalse(gate.ready(280_000, 2, 80_000, true))
        assertEquals(2, congestion)
    }

    @Test
    fun shortHoldsAreNotCongestion() {
        for (i in 0 until 20) {
            val t = i * 40_000L
            assertFalse(gate.ready(t, 2, 20_000, true))
            assertTrue(gate.ready(t + 30_000, 0, 0, true))
        }
        assertEquals(0, congestion)
    }

    @Test
    fun withNothingGoingOutTheQueueDecidesAsBefore() {
        // Reconnecting, draining or paused: the gate stays open and does not count.
        assertTrue(gate.ready(0, 2, 500_000, sending = false))
        assertTrue(gate.ready(100_000, 2, 600_000, sending = false))
        assertEquals(0, congestion)
    }
}
