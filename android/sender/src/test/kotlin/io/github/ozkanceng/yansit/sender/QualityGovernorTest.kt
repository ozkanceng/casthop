package io.github.ozkanceng.yansit.sender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QualityGovernorTest {
    private val min = 4_000_000L
    private val max = 24_000_000L
    private val middle = 12_000_000L

    /** Ticks every 250 ms at [bps] from [from] for [forMs]; returns the first step and when it came. */
    private fun QualityGovernor.run(bps: Long, from: Long, forMs: Long): Pair<QualityStep, Long>? {
        var t = from
        while (t <= from + forMs) {
            tick(t, bps, min, max)?.let { return it to t }
            t += 250
        }
        return null
    }

    @Test
    fun `eight seconds at the floor steps down`() {
        val g = QualityGovernor()
        assertNull(g.run(min, 0, 7_750))
        assertEquals(QualityStep.DOWN, g.tick(8_000, min, min, max))
    }

    @Test
    fun `leaving the floor restarts the wait`() {
        val g = QualityGovernor()
        assertNull(g.run(min, 0, 7_000))
        assertNull(g.tick(7_250, middle, min, max))
        assertNull(g.run(min, 7_500, 7_750))
    }

    @Test
    fun `a minute near the ceiling steps up`() {
        val g = QualityGovernor()
        assertEquals(QualityStep.UP to 60_000L, g.run(max, 0, 61_000))
    }

    @Test
    fun `a middle rate never steps`() {
        assertNull(QualityGovernor().run(middle, 0, 600_000))
    }

    @Test
    fun `no second step while the first is pending`() {
        val g = QualityGovernor()
        g.run(min, 0, 8_000)
        assertNull(g.run(min, 8_250, 60_000))
    }

    @Test
    fun `switches keep a minimum gap`() {
        val g = QualityGovernor()
        val (_, at) = g.run(min, 0, 8_000)!!
        g.onSwitched(at, QualityStep.DOWN)
        val next = g.run(min, at + 250, 20_000)!!
        assertEquals(QualityStep.DOWN, next.first)
        assertEquals(at + 15_000, next.second)
    }

    @Test
    fun `a failed rise doubles the next wait up to the limit`() {
        val g = QualityGovernor()
        var t = 0L
        for (wait in listOf(60_000L, 120_000L, 240_000L, 480_000L, 480_000L)) {
            t += 20_000 // past the minimum gap
            val (step, at) = g.run(max, t, wait)!!
            assertEquals(QualityStep.UP, step)
            assertEquals(t + wait, at)
            g.onSwitched(at, QualityStep.UP)
            t = at + 20_000 // within the relapse window, past the gap
            g.onSwitched(t, QualityStep.DOWN)
        }
    }

    @Test
    fun `a late fall does not lengthen the wait`() {
        val g = QualityGovernor()
        val (_, at) = g.run(max, 0, 60_000)!!
        g.onSwitched(at, QualityStep.UP)
        val later = at + 40_000
        g.onSwitched(later, QualityStep.DOWN)
        val start = later + 20_000
        assertEquals(QualityStep.UP to start + 60_000, g.run(max, start, 61_000))
    }

    @Test
    fun `a rejected step waits a full period`() {
        val g = QualityGovernor()
        g.run(min, 0, 8_000)
        g.onRejected()
        assertNull(g.run(min, 8_250, 7_750))
        assertEquals(QualityStep.DOWN, g.tick(16_250, min, min, max))
    }

    @Test
    fun `cleared timers forget time spent paused`() {
        val g = QualityGovernor()
        assertNull(g.run(min, 0, 7_000))
        g.clearTimers()
        assertNull(g.run(min, 7_250, 7_750))
    }
}
