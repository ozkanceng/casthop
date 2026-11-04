package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.FrameHeader

/** The audio decoder and output behind [AudioPipeline]. Reports back through [AudioPipeline.onPlayed]. */
public interface AudioBackend {
    /** A CONFIG frame arrived. False when this codec cannot be played. */
    public fun configure(codec: Int, config: ByteArray): Boolean

    /** One codec packet, to be heard at [playAtUs] on the receiver's monotonic clock. Must not block. */
    public fun submit(packet: ByteArray, playAtUs: Long)

    /** The session ended: drop everything. */
    public fun reset()
}

/** Counters for one report interval. */
public data class AudioStats(
    val received: Long = 0,
    val played: Long = 0,
    val late: Long = 0,
    val latencySumUs: Long = 0,
    val latencyCount: Long = 0,
)

/**
 * The receiver's lip sync rule (PROTOCOL.md §11). Port of the timing in `receiver/src/media/AudioPipeline`:
 * each packet plays at capture time + clock offset + target; a packet that cannot make it is dropped and counted.
 * Until PING has given the clock offset nothing can be scheduled, so packets are dropped (§7.4).
 */
public class AudioPipeline(private val backend: AudioBackend) {
    private val lock = Any()
    private var ready = false
    private var offsetUs: Long? = null
    private var targetUs = MIN_TARGET_LATENCY_US
    private val arrivalUs = ArrayDeque<Long>()
    private var stats = AudioStats()

    public var totalReceived: Long = 0
        private set
    public var totalPlayed: Long = 0
        private set
    public var totalLate: Long = 0
        private set

    public val targetLatencyUs: Long get() = synchronized(lock) { targetUs }

    public fun reset() {
        backend.reset()
        synchronized(lock) {
            ready = false
            offsetUs = null
            targetUs = MIN_TARGET_LATENCY_US
            arrivalUs.clear()
            stats = AudioStats()
            totalReceived = 0
            totalPlayed = 0
            totalLate = 0
        }
    }

    public fun setClockOffset(offsetUs: Long) {
        synchronized(lock) { this.offsetUs = offsetUs }
    }

    /**
     * Called with the video capture-to-display delay plus one screen frame. The target follows the slower of the
     * picture and the audio's own arrival delay (p95 plus a margin), kept inside the §11 limits.
     */
    public fun setTargetLatencyUs(videoUs: Long) {
        synchronized(lock) {
            var audioNeed = 0L
            if (arrivalUs.size >= MIN_ARRIVAL_SAMPLES) {
                val sorted = arrivalUs.sorted()
                audioNeed = sorted[sorted.size * 95 / 100] + ARRIVAL_MARGIN_US
            }
            targetUs = maxOf(videoUs, audioNeed).coerceIn(MIN_TARGET_LATENCY_US, MAX_TARGET_LATENCY_US)
        }
    }

    public fun pushFrame(header: FrameHeader, payload: ByteArray, recvUs: Long) {
        if (header.isConfig) {
            if (payload.isEmpty()) return
            val ok = backend.configure(payload[0].toInt() and 0xFF, payload.copyOfRange(1, payload.size))
            synchronized(lock) { ready = ok }
            return
        }
        val playAtUs: Long
        synchronized(lock) {
            totalReceived++
            stats = stats.copy(received = stats.received + 1)
            val offset = offsetUs
            if (!ready || offset == null) return
            val captureLocalUs = header.ptsUs + offset
            arrivalUs.addLast((recvUs - captureLocalUs).coerceIn(0, MAX_ARRIVAL_US))
            if (arrivalUs.size > ARRIVAL_WINDOW) arrivalUs.removeFirst()
            if (captureLocalUs + targetUs < recvUs + LATE_MARGIN_US) {
                totalLate++
                stats = stats.copy(late = stats.late + 1)
                return
            }
            playAtUs = captureLocalUs + targetUs
        }
        backend.submit(payload, playAtUs)
    }

    /** A packet reached the output, [latencyUs] after it was captured. */
    public fun onPlayed(latencyUs: Long) {
        synchronized(lock) {
            totalPlayed++
            stats = stats.copy(
                played = stats.played + 1,
                latencySumUs = stats.latencySumUs + latencyUs,
                latencyCount = stats.latencyCount + 1,
            )
        }
    }

    /** Returns the interval's counters and starts a new interval. */
    public fun takeStats(): AudioStats = synchronized(lock) {
        val s = stats
        stats = AudioStats()
        s
    }

    public companion object {
        public const val MIN_TARGET_LATENCY_US: Long = 40_000L
        public const val MAX_TARGET_LATENCY_US: Long = 250_000L

        /** §11: a packet due less than this far ahead is already late. */
        public const val LATE_MARGIN_US: Long = 5_000L
        private const val ARRIVAL_MARGIN_US = 10_000L
        private const val ARRIVAL_WINDOW = 250 // about 5 s of Opus
        private const val MIN_ARRIVAL_SAMPLES = 10
        private const val MAX_ARRIVAL_US = 1_000_000L
    }
}

/**
 * Keeps an output that cannot schedule playback (AudioTrack) on time: pads silence when a block would come too early
 * and trims it when it would come too late. Corrections smaller than [toleranceUs] are ignored, so steady playback
 * has no clicks.
 */
public class AudioAligner(private val toleranceUs: Long = DEFAULT_TOLERANCE_US) {
    /** @property silenceUs silence to write first. @property skipUs start of the block to leave out. */
    public data class Plan(val silenceUs: Long, val skipUs: Long)

    /**
     * @param playAtUs when the block should be heard.
     * @param nowUs the same clock, now.
     * @param queuedUs audio already handed to the output but not heard yet.
     * @param blockUs length of the block.
     */
    public fun plan(playAtUs: Long, nowUs: Long, queuedUs: Long, blockUs: Long): Plan {
        val drift = nowUs + queuedUs - playAtUs
        return when {
            drift < -toleranceUs -> Plan(silenceUs = minOf(-drift, MAX_SILENCE_US), skipUs = 0)
            drift > toleranceUs -> Plan(silenceUs = 0, skipUs = minOf(drift, blockUs))
            else -> Plan(silenceUs = 0, skipUs = 0)
        }
    }

    public companion object {
        public const val DEFAULT_TOLERANCE_US: Long = 15_000L
        private const val MAX_SILENCE_US = AudioPipeline.MAX_TARGET_LATENCY_US
    }
}
