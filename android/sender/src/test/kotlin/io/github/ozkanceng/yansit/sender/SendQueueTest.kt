package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.FrameFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

class SendQueueTest {
    private fun frame(pts: Long, flags: Int = 0, at: Long = pts) = EncodedFrame(ByteArray(10), pts, flags, at)
    private fun key(pts: Long, at: Long = pts) = frame(pts, FrameFlags.KEYFRAME, at)
    private fun config(at: Long) = frame(0, FrameFlags.CONFIG, at)

    @Test
    fun passesFramesInOrder() {
        val q = SendQueue(maxFrames = 3)
        assertTrue(q.offer(key(1), 1))
        assertTrue(q.offer(frame(2), 2))
        assertEquals(1L, q.take(0)!!.first.ptsUs)
        assertEquals(2L, q.take(0)!!.first.ptsUs)
        assertNull(q.take(0))
    }

    @Test
    fun overflowDropsPFramesUntilNextKeyframe() {
        var drops = 0
        val q = SendQueue(maxFrames = 2, onDrop = { drops += it })
        q.offer(key(1), 1)
        q.offer(frame(2), 2)
        // Kuyruk dolu: bekleyen P-kareler (anahtar kare dahil değil, o da P-kare değil ama atılır) atılır.
        assertFalse(q.offer(frame(3), 3))
        assertFalse(q.offer(frame(4), 4)) // anahtar kare bekleniyor
        assertTrue(drops >= 3)
        assertTrue(q.offer(key(5), 5))
        val (f, disc) = q.take(0)!!
        assertEquals(5L, f.ptsUs)
        assertTrue(disc, "atılmış karelerden sonraki ilk kare DISCONTINUITY taşımalı")
        assertTrue(q.offer(frame(6), 6))
        assertFalse(q.take(0)!!.second)
    }

    @Test
    fun staleFramesTriggerDropEvenIfCountIsLow() {
        val q = SendQueue(maxFrames = 10, maxAgeUs = 50_000)
        q.offer(key(1, at = 0), 0)
        assertFalse(q.offer(frame(2, at = 60_000), 60_000))
        assertTrue(q.droppedTotal >= 1)
    }

    @Test
    fun keyframeIsAcceptedOnOverflowAndConfigIsNeverDropped() {
        val q = SendQueue(maxFrames = 1)
        q.offer(config(0), 0)
        q.offer(key(1), 1) // kuyrukta yalnız CONFIG vardı (taşma sayılır ama CONFIG korunur)
        assertTrue(q.offer(key(2), 2))
        assertTrue(q.take(0)!!.first.isConfig)
        assertEquals(2L, q.take(0)!!.first.ptsUs)
    }

    @Test
    fun takeBlocksUntilFrameOrClose() {
        val q = SendQueue()
        val t = thread { Thread.sleep(50); q.offer(key(7), 7) }
        assertEquals(7L, q.take(2000)!!.first.ptsUs)
        t.join()
        val t2 = thread { Thread.sleep(50); q.close() }
        assertNull(q.take(2000))
        t2.join()
        assertFalse(q.offer(key(8), 8))
    }
}
