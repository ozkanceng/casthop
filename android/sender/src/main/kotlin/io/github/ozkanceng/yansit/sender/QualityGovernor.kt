package io.github.ozkanceng.yansit.sender

import kotlin.math.min

/** Direction of an automatic quality change. */
enum class QualityStep { DOWN, UP }

/**
 * Decides when a session should move to another preset (ARCHITECTURE.md §7). Pure logic: time comes from the caller.
 *
 * Bitrate adaptation alone cannot save a link that stays below the preset's floor. The bitrate controller rises again
 * after two stable seconds, so a rate that stays at the floor means congestion has not ended, and a rate that stays
 * near the ceiling means the link has room for more.
 */
class QualityGovernor(private val config: Config = Config()) {
    data class Config(
        val downAfterMs: Long = 3_000,
        val upAfterMs: Long = 60_000,
        val maxUpAfterMs: Long = 480_000,
        /** A fall this soon after a rise means the rise failed, so the next one waits twice as long. */
        val relapseWindowMs: Long = 30_000,
        /** Least time after any switch before a rise. */
        val minGapMs: Long = 15_000,
        /** Least time after any switch before a fall: a slow link must reach a working preset within seconds. */
        val minGapDownMs: Long = 5_000,
        val floorMargin: Double = 1.05,
        val ceilingRatio: Double = 0.9,
    ) {
        init {
            require(downAfterMs > 0 && upAfterMs in 1..maxUpAfterMs && minGapMs >= 0 && minGapDownMs >= 0)
        }
    }

    private var atFloorSinceMs: Long? = null
    private var nearCeilingSinceMs: Long? = null
    private var lastSwitchMs: Long? = null
    private var lastStep: QualityStep? = null
    private var upAfterMs = config.upAfterMs
    private var busy = false

    /** @return a step to try; the caller must answer with [onSwitched] or [onRejected]. */
    @Synchronized
    fun tick(nowMs: Long, bitrateBps: Long, minBps: Long, maxBps: Long): QualityStep? {
        if (busy) return null
        atFloorSinceMs = if (bitrateBps <= minBps * config.floorMargin) atFloorSinceMs ?: nowMs else null
        nearCeilingSinceMs = if (bitrateBps >= maxBps * config.ceilingRatio) nearCeilingSinceMs ?: nowMs else null
        val sinceSwitchMs = lastSwitchMs?.let { nowMs - it } ?: Long.MAX_VALUE
        val step = when {
            sinceSwitchMs >= config.minGapDownMs &&
                atFloorSinceMs?.let { nowMs - it >= config.downAfterMs } == true -> QualityStep.DOWN
            sinceSwitchMs >= config.minGapMs &&
                nearCeilingSinceMs?.let { nowMs - it >= upAfterMs } == true -> QualityStep.UP
            else -> return null
        }
        busy = true
        return step
    }

    @Synchronized
    fun onSwitched(nowMs: Long, step: QualityStep) {
        val previous = lastSwitchMs
        if (step == QualityStep.DOWN && lastStep == QualityStep.UP && previous != null &&
            nowMs - previous <= config.relapseWindowMs
        ) {
            upAfterMs = min(upAfterMs * 2, config.maxUpAfterMs)
        }
        lastSwitchMs = nowMs
        lastStep = step
        clearTimers()
        busy = false
    }

    /** No lower or higher preset was available; waits a full period before trying again. */
    @Synchronized
    fun onRejected() {
        clearTimers()
        busy = false
    }

    /** Paused or reconnecting: time spent like this says nothing about the link. */
    @Synchronized
    fun clearTimers() {
        atFloorSinceMs = null
        nearCeilingSinceMs = null
    }
}
