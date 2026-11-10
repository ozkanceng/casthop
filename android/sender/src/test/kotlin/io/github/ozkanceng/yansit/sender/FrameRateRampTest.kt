package io.github.ozkanceng.yansit.sender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameRateRampTest {
    @Test
    fun startsAt30AndGoesUpAfterThreeGoodSeconds() {
        val r = FrameRateRamp(60)
        assertTrue(r.active)
        assertEquals(30, r.fps)
        var up: Int? = null
        var t = 0L
        while (up == null && t < 10_000) {
            up = r.tick(t, 8_000_000, 8_000_000)
            t += 250
        }
        assertEquals(60, up)
        assertEquals(3_250, t)
        assertFalse(r.active)
        assertEquals(60, r.fps)
        assertNull(r.tick(t, 8_000_000, 8_000_000))
    }

    @Test
    fun congestionRestartsTheWait() {
        val r = FrameRateRamp(60)
        assertNull(r.tick(0, 8_000_000, 8_000_000))
        assertNull(r.tick(2_500, 8_000_000, 8_000_000))
        assertNull(r.tick(2_750, 5_600_000, 8_000_000)) // bit rate fell: the network is not there yet
        assertNull(r.tick(3_000, 7_300_000, 8_000_000))
        assertNull(r.tick(5_900, 7_300_000, 8_000_000))
        assertEquals(60, r.tick(6_000, 7_300_000, 8_000_000))
    }

    @Test
    fun a30FpsPlanHasNoWarmUp() {
        val r = FrameRateRamp(30)
        assertFalse(r.active)
        assertEquals(30, r.fps)
        assertNull(r.tick(10_000, 1, 3_000_000))
    }
}
