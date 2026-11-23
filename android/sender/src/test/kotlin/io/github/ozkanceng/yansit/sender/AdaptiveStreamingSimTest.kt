package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.BitrateController
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Simulates poor hotspot / high-latency / packet-loss conditions across the full sender control loop:
 * [BitrateController] + [QualityGovernor] + [StreamPlanner] + [SendQueue] + [CaptureGate].
 */
class AdaptiveStreamingSimTest {
    private val encoder = object : VideoEncoderCaps {
        override val name = "c2.sim.avc.encoder"
        override val codec = Codec.H264
        override val hardware = true
        override val widthAlignment = 16
        override val heightAlignment = 16
        override val bitrateRange = 200_000L..30_000_000L
        override fun isSupported(width: Int, height: Int, fps: Int): Boolean =
            width <= 1920 && height <= 1088 && width % 16 == 0 && height % 16 == 0 && fps in 15..60
    }
    private val board = ReceiverDisplay(1920, 1080, 60)

    private fun report(c2rUs: Long, received: Long = 30, dropped: Long = 0) = ControlMessage.ReceiverReport(
        intervalMs = 500,
        framesReceived = received,
        framesRendered = (received - dropped).coerceAtLeast(0),
        framesDropped = dropped,
        bytesReceived = 400_000,
        c2rAvgUs = c2rUs,
        c2rMinUs = c2rUs - 10_000,
        c2rMaxUs = c2rUs + 20_000,
        c2dAvgUs = c2rUs + 12_000,
        decodeBacklog = 0,
        audioLate = 0,
    )

    @Test
    fun `hotspot congestion with 280ms latency and 10 percent loss steps quality down to 540p24 then recovers`() {
        var preset = Preset.BEST
        var plan = StreamPlanner.plan(1920, 1080, preset, board, listOf(encoder))!!
        var bitrate = BitrateController(BitrateController.Config(plan.startBps, plan.minBps, plan.maxBps))
        val governor = QualityGovernor(QualityGovernor.Config(downAfterMs = 2_000, upAfterMs = 8_000, minGapDownMs = 2_500, minGapMs = 5_000))
        val visited = mutableListOf(preset)

        fun switchTo(next: Preset, nowMs: Long, dir: QualityStep) {
            preset = next
            plan = StreamPlanner.plan(1920, 1080, preset, board, listOf(encoder))!!
            bitrate = BitrateController(BitrateController.Config(plan.startBps, plan.minBps, plan.maxBps))
            governor.onSwitched(nowMs, dir)
            visited += preset
        }

        // Phase 1 (0..30 s): Severe mobile hotspot congestion — 280 ms c2r delay, 10% drops, 180 ms RTT spikes.
        var t = 0L
        while (t <= 30_000L) {
            if (t % 500L == 0L) bitrate.onReceiverReport(t, report(c2rUs = 280_000, received = 30, dropped = 3))
            if (t % 1_000L == 0L) bitrate.onRttSample(t, 180_000)
            bitrate.tick(t)
            when (governor.tick(t, bitrate.appliedBps, plan.minBps, plan.maxBps)) {
                QualityStep.DOWN -> preset.lower?.let { switchTo(it, t, QualityStep.DOWN) } ?: governor.onRejected()
                QualityStep.UP -> preset.higher?.let { switchTo(it, t, QualityStep.UP) } ?: governor.onRejected()
                null -> Unit
            }
            t += 250
        }

        assertEquals(Preset.ULTRA_LOW, preset, "should step all the way down to 540p/24 under sustained congestion")
        assertEquals(24, plan.fps)
        assertEquals(960, plan.width)
        assertTrue(visited.containsAll(listOf(Preset.BEST, Preset.BALANCED, Preset.WEAK_NETWORK, Preset.ULTRA_LOW)))

        // Phase 2 (30..80 s): Link clears up — 25 ms delay, 0% drops, 12 ms RTT; quality climbs smoothly.
        val endRecovery = t + 50_000L
        while (t <= endRecovery) {
            if (t % 500L == 0L) bitrate.onReceiverReport(t, report(c2rUs = 25_000, received = 30, dropped = 0))
            if (t % 1_000L == 0L) bitrate.onRttSample(t, 12_000)
            bitrate.tick(t)
            when (governor.tick(t, bitrate.appliedBps, plan.minBps, plan.maxBps)) {
                QualityStep.UP -> preset.higher?.let { switchTo(it, t, QualityStep.UP) } ?: governor.onRejected()
                QualityStep.DOWN -> preset.lower?.let { switchTo(it, t, QualityStep.DOWN) } ?: governor.onRejected()
                null -> Unit
            }
            t += 250
        }

        assertTrue(preset.ordinal < Preset.ULTRA_LOW.ordinal, "quality should step back up once hotspot stabilizes, stayed at $preset")
    }

    @Test
    fun `latency first queue drops stale P frames and resumes cleanly at next IDR while capture gate suppresses drain storm`() {
        var congestionEvents = 0
        var dropped = 0
        val queue = SendQueue(maxFrames = 3, maxAgeUs = 80_000, onDrop = { dropped += it })
        val gate = CaptureGate(onCongestion = { congestionEvents++ })

        // Simulate a brief drain/reconnect pause: capture gate must refuse frames without raising congestion.
        assertFalse(gate.ready(0, queue.depth(), queue.oldestAgeUs(0), sending = false))
        assertFalse(gate.ready(200_000, queue.depth(), queue.oldestAgeUs(200_000), sending = false))
        assertEquals(0, congestionEvents)

        // Normal sending resumes, then TCP socket stalls for 120 ms.
        assertTrue(queue.offer(EncodedFrame(ByteArray(128), 0, FrameFlags.CONFIG, 0), 0))
        assertTrue(queue.offer(EncodedFrame(ByteArray(1024), 10_000, FrameFlags.KEYFRAME, 10_000), 10_000))
        assertTrue(queue.offer(EncodedFrame(ByteArray(512), 26_667, 0, 26_667), 26_667))

        // At 120 ms, oldest queued frame is > 80 ms old → P-frame dropped and waiting for IDR.
        assertFalse(queue.offer(EncodedFrame(ByteArray(512), 120_000, 0, 120_000), 120_000))
        assertFalse(queue.offer(EncodedFrame(ByteArray(512), 136_667, 0, 136_667), 136_667))
        assertTrue(dropped >= 2)

        // Next keyframe is accepted with DISCONTINUITY, while CONFIG stays intact ahead of it.
        assertTrue(queue.offer(EncodedFrame(ByteArray(1024), 153_333, FrameFlags.KEYFRAME, 153_333), 153_333))
        val first = queue.take(0)!!
        assertTrue(first.first.isConfig)
        val second = queue.take(0)!!
        assertEquals(153_333L, second.first.ptsUs)
        assertTrue(second.second, "first IDR after drops must carry DISCONTINUITY")
    }
}
