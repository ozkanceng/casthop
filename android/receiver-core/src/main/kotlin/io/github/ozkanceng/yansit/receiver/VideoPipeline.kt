package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.FrameHeader
import io.github.ozkanceng.yansit.protocol.KeyframeReason

/**
 * The decoder behind [VideoPipeline]: MediaCodec in android/tv, a frame counter in tools/fake-receiver.
 * Reports back through [VideoPipeline.onRendered], [VideoPipeline.onDiscarded] and [VideoPipeline.onDecoderError].
 */
public interface VideoBackend {
    public val decoderName: String
    public val hardware: Boolean

    /** The screen can turn ROTATED pictures back (WELCOME `rotated_frames`, PROTOCOL.md §11). */
    public val supportsRotation: Boolean get() = false

    /** A CONFIG frame arrived (start of stream, rotation, codec change). False when no decoder can take it. */
    public fun configure(codec: Int, config: ByteArray): Boolean

    /**
     * Annex-B access unit in decode order. [timestampUs] rises strictly and is the key for the callbacks.
     * Must not block: the caller is the socket reader, and a stalled reader is delay piling up in TCP.
     * [rotated]: the picture is turned 90° clockwise and must be shown turned back, from the moment this frame is
     * shown (only when [supportsRotation]).
     */
    public fun submit(accessUnit: ByteArray, timestampUs: Long, keyframe: Boolean, rotated: Boolean)

    /** The session ended: drop everything. */
    public fun reset()
}

/** Counters for one report interval. */
public data class VideoStats(
    val framesReceived: Long = 0,
    val framesRendered: Long = 0,
    val framesDropped: Long = 0,
    val bytesReceived: Long = 0,
    val c2rSumUs: Long = 0,
    val c2rCount: Long = 0,
    val c2rMinUs: Long = 0,
    val c2rMaxUs: Long = 0,
    val c2dSumUs: Long = 0,
    val c2dCount: Long = 0,
    val backlog: Int = 0,
)

/**
 * The receiver's flow rules for video (PROTOCOL.md §11), apart from any decoder. Port of the gating in
 * `receiver/src/media/VideoPipeline`: liveness beats smoothness, so frames are dropped up to the next IDR instead
 * of letting delay build up. [pushFrame] runs on the VIDEO reader thread; the render callbacks on decoder threads.
 */
public class VideoPipeline(
    private val backend: VideoBackend,
    private val clockUs: () -> Long,
    private val onKeyframeNeeded: (reason: Int) -> Unit,
    private val onFirstFrame: () -> Unit,
) {
    private val lock = Any()
    private var codec = 0
    private var ready = false
    private var config = ByteArray(0)
    private var configPending = false
    private var waitKeyframe = true
    private var lastSeq: Long? = null
    private var lastTimestampUs = NO_TIMESTAMP
    private var lastKeyframeRequestUs = 0L
    private var backlogSinceUs = 0L
    private var firstRendered = false
    private var basePtsUs: Long? = null
    private var offsetUs: Long? = null
    private val inFlight = ArrayDeque<Long>()
    private var stats = VideoStats()

    public var totalReceived: Long = 0
        private set
    public var totalRendered: Long = 0
        private set
    public var totalDropped: Long = 0
        private set

    /** A turned picture reached the decoder in this session (stats file `video_rotated`). */
    public var rotatedSeen: Boolean = false
        private set

    public val decoderName: String get() = backend.decoderName
    public val hardware: Boolean get() = backend.hardware
    public val supportsRotation: Boolean get() = backend.supportsRotation

    public fun reset() {
        backend.reset()
        synchronized(lock) {
            codec = 0
            ready = false
            config = ByteArray(0)
            configPending = false
            waitKeyframe = true
            lastSeq = null
            lastTimestampUs = NO_TIMESTAMP
            lastKeyframeRequestUs = 0
            backlogSinceUs = 0
            firstRendered = false
            basePtsUs = null
            offsetUs = null
            inFlight.clear()
            stats = VideoStats()
            totalReceived = 0
            totalRendered = 0
            totalDropped = 0
            rotatedSeen = false
        }
    }

    public fun setClockOffset(offsetUs: Long) {
        synchronized(lock) { this.offsetUs = offsetUs }
    }

    public fun pushFrame(header: FrameHeader, payload: ByteArray, recvUs: Long) {
        if (header.isConfig) {
            pushConfig(payload)
            return
        }
        var keyframeRequest: Int? = null
        var accessUnit: ByteArray? = null
        var timestampUs = 0L
        val rotated = header.isRotated && backend.supportsRotation
        synchronized(lock) {
            countReceived(header, payload.size, recvUs)
            val gap = lastSeq.let { it != null && header.seq != (it + 1) and 0xFFFF_FFFFL }
            lastSeq = header.seq
            val key = header.isKeyframe
            if (!key && (gap || header.isDiscontinuity) && !waitKeyframe) {
                waitKeyframe = true
                keyframeRequest = throttledRequest(KeyframeReason.BACKLOG_DROPPED)
            }
            if (!ready) {
                drop()
            } else if (waitKeyframe && !key) {
                drop()
                keyframeRequest = keyframeRequest ?: throttledRequest(KeyframeReason.DECODER_RESET)
            } else if (!key && backlogExceeded(recvUs)) {
                // Skip to the next IDR instead of building up delay.
                backlogSinceUs = 0
                drop()
                waitKeyframe = true
                keyframeRequest = keyframeRequest ?: throttledRequest(KeyframeReason.BACKLOG_DROPPED)
            } else {
                waitKeyframe = false
                accessUnit = if (key && configPending) {
                    configPending = false
                    config + payload
                } else {
                    payload
                }
                timestampUs = nextTimestamp(header.ptsUs)
                inFlight.addLast(timestampUs)
                if (rotated) rotatedSeen = true
            }
        }
        keyframeRequest?.let(onKeyframeNeeded)
        accessUnit?.let { backend.submit(it, timestampUs, header.isKeyframe, rotated) }
    }

    private fun pushConfig(payload: ByteArray) {
        if (payload.isEmpty()) return
        val newCodec = payload[0].toInt() and 0xFF
        if (newCodec != Codec.H264 && newCodec != Codec.H265) return
        val newConfig = payload.copyOfRange(1, payload.size)
        synchronized(lock) {
            codec = newCodec
            config = newConfig
            configPending = true
            waitKeyframe = true
        }
        val ok = backend.configure(newCodec, newConfig)
        synchronized(lock) { ready = ok }
    }

    private fun countReceived(header: FrameHeader, size: Int, recvUs: Long) {
        totalReceived++
        var s = stats.copy(
            framesReceived = stats.framesReceived + 1,
            bytesReceived = stats.bytesReceived + size,
        )
        if (basePtsUs == null) basePtsUs = header.ptsUs
        offsetUs?.let { offset ->
            val c2r = (recvUs - (header.ptsUs + offset)).coerceIn(0, 0xFFFF_FFFFL)
            s = s.copy(
                c2rSumUs = s.c2rSumUs + c2r,
                c2rMinUs = if (s.c2rCount == 0L) c2r else minOf(s.c2rMinUs, c2r),
                c2rMaxUs = maxOf(s.c2rMaxUs, c2r),
                c2rCount = s.c2rCount + 1,
            )
        }
        stats = s
    }

    /** A short burst melts by itself; more than [MAX_IN_FLIGHT] for [BACKLOG_GRACE_US] means we cannot keep up. */
    private fun backlogExceeded(recvUs: Long): Boolean {
        val count = inFlight.size
        if (count < MAX_IN_FLIGHT) {
            backlogSinceUs = 0
        } else if (backlogSinceUs == 0L) {
            backlogSinceUs = recvUs
        }
        val sustained = backlogSinceUs != 0L && firstRendered && recvUs - backlogSinceUs >= BACKLOG_GRACE_US
        val hardLimit = if (firstRendered) HARD_MAX_IN_FLIGHT else STARTUP_MAX_IN_FLIGHT
        return sustained || count >= hardLimit
    }

    private fun nextTimestamp(ptsUs: Long): Long {
        val base = basePtsUs ?: ptsUs
        val t = maxOf(ptsUs - base, lastTimestampUs + 1)
        lastTimestampUs = t
        return t
    }

    private fun throttledRequest(reason: Int): Int? {
        val now = clockUs()
        if (lastKeyframeRequestUs != 0L && now - lastKeyframeRequestUs < KEYFRAME_REQUEST_INTERVAL_US) return null
        lastKeyframeRequestUs = now
        return reason
    }

    private fun drop() {
        totalDropped++
        stats = stats.copy(framesDropped = stats.framesDropped + 1)
    }

    /** The frame [timestampUs] reached the screen at [nowUs]. */
    public fun onRendered(timestampUs: Long, nowUs: Long) {
        val first: Boolean
        synchronized(lock) {
            var s = stats.copy(framesRendered = stats.framesRendered + 1)
            totalRendered++
            while (inFlight.isNotEmpty() && inFlight.first() <= timestampUs) inFlight.removeFirst()
            val base = basePtsUs
            val offset = offsetUs
            if (base != null && offset != null) {
                val c2d = nowUs - (base + timestampUs + offset)
                if (c2d >= 0) s = s.copy(c2dSumUs = s.c2dSumUs + c2d, c2dCount = s.c2dCount + 1)
            }
            stats = s
            first = !firstRendered
            firstRendered = true
        }
        if (first) onFirstFrame()
    }

    /** The decoder finished [timestampUs] without showing it. */
    public fun onDiscarded(timestampUs: Long) {
        synchronized(lock) {
            while (inFlight.isNotEmpty() && inFlight.first() <= timestampUs) inFlight.removeFirst()
            drop()
        }
    }

    /** The decoder failed and was rebuilt: everything in flight is gone, so restart from CONFIG and a new IDR. */
    public fun onDecoderError() {
        val request = synchronized(lock) {
            inFlight.clear()
            configPending = true
            waitKeyframe = true
            lastKeyframeRequestUs = 0
            throttledRequest(KeyframeReason.DECODE_ERROR)
        }
        request?.let(onKeyframeNeeded)
    }

    /** Returns the interval's counters and starts a new interval. */
    public fun takeStats(): VideoStats = synchronized(lock) {
        val s = stats.copy(backlog = inFlight.size.coerceAtMost(0xFFFF))
        stats = VideoStats()
        s
    }

    public companion object {
        /** §11: more than this many frames ahead of the decoder for [BACKLOG_GRACE_US] is a backlog. */
        public const val MAX_IN_FLIGHT: Int = 4
        public const val BACKLOG_GRACE_US: Long = 250_000L

        /** §11: the limit that always holds. */
        public const val HARD_MAX_IN_FLIGHT: Int = 30

        /** §11: the limit until the first frame is on screen, while the decoder warms up. */
        public const val STARTUP_MAX_IN_FLIGHT: Int = 120
        private const val KEYFRAME_REQUEST_INTERVAL_US = 200_000L
        private const val NO_TIMESTAMP = -1L
    }
}
