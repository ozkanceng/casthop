package io.github.ozkanceng.yansit.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BitrateControllerTest {
    private val config = BitrateController.Config(initialBps = 16_000_000, minBps = 4_000_000, maxBps = 24_000_000)

    private fun report(c2rAvgUs: Long, received: Long = 30, dropped: Long = 0) = ControlMessage.ReceiverReport(
        intervalMs = 500, framesReceived = received, framesRendered = received - dropped, framesDropped = dropped,
        bytesReceived = 1_000_000, c2rAvgUs = c2rAvgUs, c2rMinUs = c2rAvgUs, c2rMaxUs = c2rAvgUs,
        c2dAvgUs = c2rAvgUs, decodeBacklog = 0, audioLate = 0,
    )

    @Test
    fun `stable network increases slowly after two seconds up to the ceiling`() {
        val c = BitrateController(config)
        var t = 0L
        repeat(7) { assertNull(c.tick(t)); t += 250 }   // ilk 2 sn artış yok
        var applied = 0
        while (t <= 60_000) {
            c.onReceiverReport(t, report(20_000))
            if (c.tick(t) != null) applied++
            t += 250
        }
        assertEquals(24_000_000, c.appliedBps)
        assertTrue(applied in 3..20, "küçük artışlar birleştirilmeli: $applied")
    }

    @Test
    fun `queue delay triggers multiplicative decrease with hold time`() {
        val c = BitrateController(config)
        c.onSenderQueueDelay(80_000)
        assertEquals(11_200_000, c.tick(0))
        assertNull(c.tick(250))                         // 500 ms bekleme
        assertEquals(7_840_000, c.tick(500))
        assertEquals(5_488_000, c.tick(1_000))
        assertEquals(4_000_000, c.tick(1_500))          // taban
        assertNull(c.tick(2_000))
        assertEquals(4_000_000, c.appliedBps)
    }

    @Test
    fun `rising receiver delay is congestion even without queue delay`() {
        val c = BitrateController(config)
        c.tick(0)
        c.onReceiverReport(0, report(20_000))
        c.tick(250)
        c.onReceiverReport(500, report(24_000))         // +8 ms/s: eşik altı
        assertNull(c.tick(500))
        c.onReceiverReport(1_000, report(45_000))       // 1 sn'de +25 ms
        assertEquals(11_200_000, c.tick(1_000))
    }

    @Test
    fun `steady high but flat delay is not congestion`() {
        val c = BitrateController(config)
        var t = 0L
        while (t <= 10_000) {
            c.onReceiverReport(t, report(70_000))
            c.tick(t)
            t += 250
        }
        assertTrue(c.appliedBps > 16_000_000)
    }

    @Test
    fun `receiver drops and sender drops are congestion`() {
        val c = BitrateController(config)
        c.onReceiverReport(0, report(20_000, received = 30, dropped = 3))
        assertEquals(11_200_000, c.tick(0))
        c.onSenderDrop()
        assertEquals(7_840_000, c.tick(600))
    }

    @Test
    fun `no increase within two seconds after congestion`() {
        val c = BitrateController(config)
        c.onSenderDrop()
        c.tick(0)
        val after = c.appliedBps
        var t = 250L
        while (t < 2_000) { assertNull(c.tick(t)); t += 250 }
        assertEquals(after, c.appliedBps)
    }

    /** Drives congestion until the floor, then returns the time of the last tick. */
    private fun fallToFloor(c: BitrateController): Long {
        var t = 0L
        while (c.appliedBps > config.minBps) {
            c.onSenderDrop()
            c.tick(t)
            t += 500
        }
        return t
    }

    @Test
    fun `short congestion regains most of the earlier rate within seconds`() {
        val c = BitrateController(config)
        var t = fallToFloor(c)
        val end = t + 7_000 // 2 s of stability, then 5 s of recovery
        while (t <= end) { c.tick(t); t += 250 }
        assertTrue(c.appliedBps >= 13_000_000, "recovered only to ${c.appliedBps}")
    }

    @Test
    fun `growth is slow again above the recovery target`() {
        val c = BitrateController(config)
        var t = fallToFloor(c)
        val recovered = t + 7_000
        while (t <= recovered) { c.tick(t); t += 250 }
        val before = c.targetBps
        assertTrue(before >= 13_600_000, "target $before")
        val end = t + 4_000
        while (t < end) { c.tick(t); t += 250 }
        assertTrue(c.targetBps <= before * 1.4, "${c.targetBps} grew too fast from $before")
    }

    @Test
    fun `congestion during recovery lowers the recovery target`() {
        val c = BitrateController(config)
        var t = fallToFloor(c)
        val end = t + 3_500
        while (t <= end) { c.tick(t); t += 250 }
        val atSecondCongestion = c.targetBps
        assertTrue(atSecondCongestion < 13_600_000)
        c.onSenderDrop()
        c.tick(t)
        val stop = t + 3_500 // would pass the earlier rate if the old recovery target still applied
        while (t <= stop) { t += 250; c.tick(t) }
        assertTrue(c.targetBps <= atSecondCongestion, "target ${c.targetBps} above $atSecondCongestion")
    }

    @Test
    fun `reset forgets the recovery target`() {
        val c = BitrateController(config)
        var t = fallToFloor(c)
        c.reset(4_000_000)
        val end = t + 7_000
        while (t <= end) { c.tick(t); t += 250 }
        assertTrue(c.appliedBps < 8_000_000, "applied ${c.appliedBps}")
    }

    @Test
    fun `reset restores the initial state`() {
        val c = BitrateController(config)
        c.onSenderDrop(); c.tick(0)
        c.reset(8_000_000)
        assertEquals(8_000_000, c.appliedBps)
        assertNull(c.tick(10_000))
    }
}
