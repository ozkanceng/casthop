package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.FrameHeader
import io.github.ozkanceng.yansit.protocol.FrameType
import io.github.ozkanceng.yansit.protocol.KeyframeReason
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The §11 rules, including the ones the Pardus receiver only keeps in `VideoPipeline.cpp`. */
class MediaPipelineTest {
    private var now = 1_000_000L
    private val backend = RecordingVideoBackend()
    private val requests = ArrayList<Int>()
    private var firstFrames = 0
    private val video = VideoPipeline(backend, { now }, { requests += it }, { firstFrames++ })
    private var seq = 0L

    private fun frame(flags: Int, size: Int = 3, pts: Long = now, gap: Long = 0): FrameHeader {
        seq += 1 + gap
        return FrameHeader(FrameType.VIDEO, flags, pts, seq, size)
    }

    private fun config(body: ByteArray = byteArrayOf(9, 9)) {
        video.pushFrame(
            FrameHeader(FrameType.VIDEO, FrameFlags.CONFIG, now, seq, body.size + 1),
            byteArrayOf(Codec.H264.toByte()) + body,
            now,
        )
    }

    private fun key() = video.pushFrame(frame(FrameFlags.KEYFRAME), byteArrayOf(1, 2, 3), now)

    private fun delta(gap: Long = 0) = video.pushFrame(frame(0, gap = gap), byteArrayOf(4, 5, 6), now)

    @Test
    fun configGoesInFrontOfTheFirstKeyframe() {
        config()
        key()
        delta()
        assertEquals(Codec.H264, backend.configs.single().first)
        assertArrayEquals(byteArrayOf(9, 9, 1, 2, 3), backend.submitted[0].first)
        assertArrayEquals(byteArrayOf(4, 5, 6), backend.submitted[1].first)
    }

    @Test
    fun theRotatedFlagReachesABackendThatCanTurn() {
        backend.rotation = true
        config()
        video.pushFrame(frame(FrameFlags.KEYFRAME or FrameFlags.ROTATED), byteArrayOf(1, 2, 3), now)
        delta()
        assertEquals(listOf(true, false), backend.rotated)
        assertTrue(video.rotatedSeen)
        video.reset()
        assertEquals(false, video.rotatedSeen)
    }

    @Test
    fun aBackendThatCannotTurnNeverGetsTheFlag() {
        config()
        video.pushFrame(frame(FrameFlags.KEYFRAME or FrameFlags.ROTATED), byteArrayOf(1, 2, 3), now)
        assertEquals(listOf(false), backend.rotated)
        assertEquals(false, video.rotatedSeen)
    }

    @Test
    fun deltaBeforeTheFirstKeyframeIsDroppedAndRequestsOne() {
        config()
        delta()
        assertTrue(backend.submitted.isEmpty())
        assertEquals(1, video.totalDropped)
        assertEquals(listOf(KeyframeReason.DECODER_RESET), requests)
    }

    @Test
    fun framesWithoutConfigAreDropped() {
        key()
        assertTrue(backend.submitted.isEmpty())
        assertEquals(1, video.totalDropped)
    }

    @Test
    fun aRefusedConfigDropsFrames() {
        backend.accept = false
        config()
        key()
        assertTrue(backend.submitted.isEmpty())
    }

    @Test
    fun aSeqGapWaitsForTheNextKeyframe() {
        config()
        key()
        backend.onSubmit = { video.onRendered(it, now) }
        delta(gap = 1)
        assertEquals(KeyframeReason.BACKLOG_DROPPED, requests.single())
        assertEquals(1, backend.submitted.size)
        now += 300_000
        key()
        assertEquals(2, backend.submitted.size)
    }

    @Test
    fun discontinuityWaitsForTheNextKeyframe() {
        config()
        key()
        video.pushFrame(frame(FrameFlags.DISCONTINUITY), byteArrayOf(1), now)
        assertEquals(KeyframeReason.BACKLOG_DROPPED, requests.single())
        assertEquals(1, backend.submitted.size)
    }

    @Test
    fun keyframeRequestsAreSpacedOut() {
        config()
        delta()
        delta()
        delta()
        assertEquals(1, requests.size)
        now += 200_000
        delta()
        assertEquals(2, requests.size)
    }

    @Test
    fun aShortBurstIsKept() {
        config()
        key()
        video.onRendered(backend.submitted[0].second, now)
        repeat(4) { delta() }
        now += VideoPipeline.BACKLOG_GRACE_US - 1
        delta()
        assertEquals(6, backend.submitted.size)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aSustainedBacklogDropsToTheNextIdr() {
        config()
        key()
        video.onRendered(backend.submitted[0].second, now)
        repeat(4) { delta() } // four in flight: the clock starts on the next frame
        delta()
        val before = backend.submitted.size
        now += VideoPipeline.BACKLOG_GRACE_US
        delta()
        assertEquals(before, backend.submitted.size)
        assertEquals(listOf(KeyframeReason.BACKLOG_DROPPED), requests)

        // Everything up to the next keyframe goes too, and the keyframe gets through despite the backlog.
        delta()
        assertEquals(before, backend.submitted.size)
        key()
        assertEquals(before + 1, backend.submitted.size)
    }

    @Test
    fun hardLimitHoldsAfterTheFirstFrame() {
        config()
        key()
        video.onRendered(backend.submitted[0].second, now)
        repeat(VideoPipeline.HARD_MAX_IN_FLIGHT) { delta() }
        assertEquals(1 + VideoPipeline.HARD_MAX_IN_FLIGHT, backend.submitted.size)
        delta()
        assertEquals(1 + VideoPipeline.HARD_MAX_IN_FLIGHT, backend.submitted.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun startupLimitIsLooser() {
        config()
        key()
        repeat(VideoPipeline.STARTUP_MAX_IN_FLIGHT - 1) { delta() }
        assertEquals(VideoPipeline.STARTUP_MAX_IN_FLIGHT, backend.submitted.size)
        assertTrue(requests.isEmpty())
        delta()
        assertEquals(VideoPipeline.STARTUP_MAX_IN_FLIGHT, backend.submitted.size)
    }

    @Test
    fun firstFrameIsReportedOnce() {
        config()
        key()
        delta()
        video.onRendered(backend.submitted[0].second, now)
        video.onRendered(backend.submitted[1].second, now)
        assertEquals(1, firstFrames)
        assertEquals(2, video.totalRendered)
    }

    @Test
    fun timestampsRiseEvenWhenPtsDoesNot() {
        config()
        key()
        video.pushFrame(frame(0, pts = now), byteArrayOf(1), now)
        video.pushFrame(frame(0, pts = now - 500), byteArrayOf(1), now)
        val t = backend.submitted.map { it.second }
        assertTrue(t[1] > t[0] && t[2] > t[1])
    }

    @Test
    fun statsCarryCaptureToReceiveAndDisplay() {
        video.setClockOffset(100)
        config()
        val pts = now - 20_100 // captured 20 ms ago once the offset is applied
        video.pushFrame(frame(FrameFlags.KEYFRAME, pts = pts), byteArrayOf(1, 2, 3), now)
        now += 10_000
        video.onRendered(backend.submitted[0].second, now)
        val s = video.takeStats()
        assertEquals(1, s.framesReceived)
        assertEquals(1, s.framesRendered)
        assertEquals(3, s.bytesReceived)
        assertEquals(20_000, s.c2rSumUs)
        assertEquals(20_000, s.c2rMinUs)
        assertEquals(20_000, s.c2rMaxUs)
        assertEquals(30_000, s.c2dSumUs)
        assertEquals(0, s.backlog)
        assertEquals(VideoStats(), video.takeStats())
    }

    @Test
    fun decoderErrorRestartsFromConfig() {
        config()
        key()
        video.onDecoderError()
        assertEquals(KeyframeReason.DECODE_ERROR, requests.single())
        delta()
        assertEquals(1, backend.submitted.size)
        key()
        assertArrayEquals(byteArrayOf(9, 9, 1, 2, 3), backend.submitted[1].first)
    }

    private val audioBackend = RecordingAudioBackend()
    private val audio = AudioPipeline(audioBackend)

    private fun packet(pts: Long, recv: Long) =
        audio.pushFrame(FrameHeader(FrameType.AUDIO, 0, pts, 0, 1), byteArrayOf(1), recv)

    private fun audioConfig() = audio.pushFrame(
        FrameHeader(FrameType.AUDIO, FrameFlags.CONFIG, 0, 0, 2),
        byteArrayOf(Codec.OPUS.toByte(), 7),
        0,
    )

    @Test
    fun audioWaitsForTheClockOffset() {
        audioConfig()
        packet(1_000_000, 1_000_000)
        assertEquals(1, audio.totalReceived)
        assertTrue(audioBackend.submitted.isEmpty())
        assertEquals(0, audio.totalLate)
    }

    @Test
    fun audioPlaysAtCapturePlusOffsetPlusTarget() {
        audioConfig()
        audio.setClockOffset(500)
        packet(1_000_000, 1_000_000)
        assertEquals(1_000_000 + 500 + AudioPipeline.MIN_TARGET_LATENCY_US, audioBackend.submitted.single().second)
    }

    @Test
    fun audioLateByTheMarginIsDropped() {
        audioConfig()
        audio.setClockOffset(0)
        val target = AudioPipeline.MIN_TARGET_LATENCY_US
        packet(1_000_000, 1_000_000 + target - AudioPipeline.LATE_MARGIN_US) // due exactly at the margin: still fine
        assertEquals(1, audioBackend.submitted.size)
        packet(1_000_000, 1_000_000 + target - AudioPipeline.LATE_MARGIN_US + 1)
        assertEquals(1, audioBackend.submitted.size)
        assertEquals(1, audio.totalLate)
        assertEquals(1, audio.takeStats().late)
    }

    @Test
    fun targetFollowsVideoWithinLimits() {
        audio.setTargetLatencyUs(100_000)
        assertEquals(100_000, audio.targetLatencyUs)
        audio.setTargetLatencyUs(1_000)
        assertEquals(AudioPipeline.MIN_TARGET_LATENCY_US, audio.targetLatencyUs)
        audio.setTargetLatencyUs(10_000_000)
        assertEquals(AudioPipeline.MAX_TARGET_LATENCY_US, audio.targetLatencyUs)
    }

    @Test
    fun targetFollowsSlowAudioArrival() {
        audioConfig()
        audio.setClockOffset(0)
        repeat(20) { packet(1_000_000, 1_000_000 + 90_000) }
        audio.setTargetLatencyUs(50_000)
        assertEquals(100_000, audio.targetLatencyUs) // 90 ms arrival + 10 ms margin
    }

    @Test
    fun alignerIgnoresSmallDrift() {
        val plan = AudioAligner().plan(playAtUs = 1_000_000, nowUs = 900_000, queuedUs = 110_000, blockUs = 20_000)
        assertEquals(AudioAligner.Plan(0, 0), plan)
    }

    @Test
    fun alignerPadsWhenEarly() {
        val plan = AudioAligner().plan(playAtUs = 1_000_000, nowUs = 900_000, queuedUs = 50_000, blockUs = 20_000)
        assertEquals(AudioAligner.Plan(silenceUs = 50_000, skipUs = 0), plan)
    }

    @Test
    fun alignerTrimsWhenLate() {
        val plan = AudioAligner().plan(playAtUs = 1_000_000, nowUs = 900_000, queuedUs = 150_000, blockUs = 20_000)
        assertEquals(AudioAligner.Plan(silenceUs = 0, skipUs = 20_000), plan)
    }
}
