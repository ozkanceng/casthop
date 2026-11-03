package io.github.ozkanceng.yansit.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClockSyncTest {
    /** Yanıtlayan saat = başlatan + [offset]; gidiş/dönüş gecikmeleri ayrı. */
    private fun exchange(cs: ClockSync, t0: Long, offset: Long, up: Long, down: Long, proc: Long = 100) =
        cs.onPong(t0, t0 + up + offset, t0 + up + proc + offset, t0 + up + proc + down)

    @Test
    fun `symmetric path gives exact offset and rtt`() {
        val cs = ClockSync()
        val s = exchange(cs, t0 = 1_000_000, offset = 5_000_000, up = 2_000, down = 2_000)!!
        assertEquals(5_000_000, s.offsetUs)
        assertEquals(4_000, s.rttUs)
    }

    @Test
    fun `minimum rtt sample wins over queued samples`() {
        val cs = ClockSync()
        exchange(cs, 0, offset = -300_000, up = 40_000, down = 2_000)   // asimetrik: hatalı ofset
        exchange(cs, 1_000_000, offset = -300_000, up = 1_000, down = 1_000)
        exchange(cs, 2_000_000, offset = -300_000, up = 2_000, down = 30_000)
        assertEquals(-300_000, cs.offsetUs)
        assertEquals(2_000, cs.rttUs)
    }

    @Test
    fun `window evicts old samples`() {
        val cs = ClockSync(window = 2)
        exchange(cs, 0, offset = 10, up = 100, down = 100)          // en iyi, ama atılacak
        exchange(cs, 1_000, offset = 10, up = 5_000, down = 5_000)
        exchange(cs, 2_000, offset = 10, up = 3_000, down = 3_000)
        assertEquals(6_000, cs.rttUs)
    }

    @Test
    fun `negative rtt is ignored`() {
        val cs = ClockSync()
        assertNull(cs.onPong(t0Us = 100, t1Us = 50, t2Us = 1_000, t3Us = 200))
        assertNull(cs.offsetUs)
    }
}
