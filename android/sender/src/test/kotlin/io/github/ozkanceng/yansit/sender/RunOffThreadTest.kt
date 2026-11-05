package io.github.ozkanceng.yansit.sender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class RunOffThreadTest {
    @Test
    fun `runs on another thread and waits for the result`() {
        var ranOn: Thread? = null
        var value = 0
        runOffThread(5_000) {
            ranOn = Thread.currentThread()
            value = 1
        }
        assertNotEquals(Thread.currentThread(), ranOn)
        assertEquals(1, value)
    }

    @Test
    fun `a stalled write does not hold the caller past the limit`() {
        val release = CountDownLatch(1)
        val start = System.nanoTime()
        runOffThread(50) { release.await() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        release.countDown()
        assertTrue(elapsedMs < 2_000, "waited $elapsedMs ms")
    }

    @Test
    fun `a failed close does not stop the next one`() {
        var closed = false
        listOf(AutoCloseable { throw java.io.IOException("reset") }, AutoCloseable { closed = true })
            .forEach(::closeQuietly)
        assertTrue(closed)
    }
}
