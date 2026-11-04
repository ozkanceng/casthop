package io.github.ozkanceng.yansit.sender

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyframeGateTest {
    @Test
    fun `requests pass while nothing is being written`() {
        val g = KeyframeGate()
        assertTrue(g.onRequest(0))
        assertTrue(g.onRequest(1))
    }

    @Test
    fun `requests during a write leave as one when it ends`() {
        val g = KeyframeGate()
        g.onWriteStarted(0)
        assertFalse(g.onRequest(10))
        assertFalse(g.onRequest(20))
        assertTrue(g.onWriteEnded())
        assertFalse(g.onWriteEnded())
    }

    @Test
    fun `a write without requests asks for nothing`() {
        val g = KeyframeGate()
        g.onWriteStarted(0)
        assertFalse(g.onWriteEnded())
    }

    @Test
    fun `a stalled write stops holding requests`() {
        val g = KeyframeGate(timeoutUs = 1_000)
        g.onWriteStarted(0)
        assertFalse(g.onRequest(999))
        assertTrue(g.onRequest(1_000))
    }
}
