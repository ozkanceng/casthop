package io.github.ozkanceng.yansit.sender

/**
 * Warm-up (ARCHITECTURE.md §2.1): a 60 fps session starts at 30 and goes up only once the network has carried the
 * start bit rate for [holdMs] without congestion. The first seconds then never freeze on a weak link while the bit
 * rate controller is still finding its level; on a good one the jump comes after about three seconds.
 *
 * Not thread-safe; [MirrorSession] calls it from its bit rate tick.
 */
class FrameRateRamp(
    private val targetFps: Int,
    private val startFps: Int = 30,
    private val holdMs: Long = 3_000,
    private val minRatio: Double = 0.9,
) {
    private var goodSinceMs: Long? = null

    /** Still below the target: the source must run at [fps]. */
    var active: Boolean = targetFps > startFps
        private set

    /** The rate the source should run at now. */
    val fps: Int get() = if (active) startFps else targetFps

    /**
     * @param appliedBps the bit rate the encoder has now
     * @param startBps the plan's start bit rate
     * @return the target rate once, when it is time to go up; otherwise `null`
     */
    fun tick(nowMs: Long, appliedBps: Long, startBps: Long): Int? {
        if (!active) return null
        if (appliedBps < startBps * minRatio) {
            goodSinceMs = null
            return null
        }
        val since = goodSinceMs ?: nowMs.also { goodSinceMs = it }
        if (nowMs - since < holdMs) return null
        active = false
        return targetFps
    }
}
