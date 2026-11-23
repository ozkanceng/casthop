package io.github.ozkanceng.yansit.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * AIMD bit hızı denetleyicisi (PROTOCOL.md §7.6). Saf mantık: zaman dışarıdan verilir, test edilebilir.
 *
 * Tıkanıklık işaretleri: gönderici kuyruk gecikmesi, alıcı raporlarındaki yakalama→alım gecikmesinin
 * artış eğimi, alıcıda atılan kare oranı ve göndericinin kendi kare atma olayları.
 */
public class BitrateController(private val config: Config) {
    public data class Config(
        val initialBps: Long,
        val minBps: Long,
        val maxBps: Long,
        val decreaseFactor: Double = 0.7,
        /** Gentler decrease for elevated absolute delay plateau or RTT spike before frames are dropped. */
        val mildDecreaseFactor: Double = 0.85,
        /** Her [tickMs]'de çarpan; 250 ms'de 1.02 ≈ saniyede %8. */
        val increaseFactor: Double = 1.02,
        /** Growth per tick while returning to the rate that worked before a congestion episode (≈ 31% per second). */
        val recoveryIncreaseFactor: Double = 1.07,
        /** Share of the pre-congestion rate that is regained quickly; the rest grows at [increaseFactor]. */
        val recoveryTargetRatio: Double = 0.85,
        val tickMs: Long = 250,
        val decreaseHoldMs: Long = 500,
        val stableMsBeforeIncrease: Long = 2000,
        val queueDelayThresholdUs: Long = 50_000,
        val delaySlopeThresholdUsPerSec: Long = 15_000,
        val slopeWindowMs: Long = 1000,
        val dropRatioThreshold: Double = 0.05,
        /** Absolute capture→receive delay above which bitrate stops growing (prevents bufferbloat plateau ramps). */
        val highDelayHoldUs: Long = 160_000,
        /** Absolute capture→receive delay above which bitrate gently backs off even if slope is flat. */
        val highDelayDecreaseUs: Long = 240_000,
        /** RTT above this AND [rttSpikeFactor]× baseline signals link congestion (e.g. mobile hotspot contention). */
        val highRttThresholdUs: Long = 100_000,
        val rttSpikeFactor: Double = 2.0,
        /**
         * A report with fewer frames says nothing about the link: a still screen is redrawn twice a second, and one
         * late frame would read as a rising delay or a 100% drop.
         */
        val minReportFrames: Long = IDLE_REPORT_FRAMES,
        /** Uygulanan değerden en az bu oranda farklıysa kodlayıcıya yeni değer verilir. */
        val applyThresholdRatio: Double = 0.05,
    ) {
        init {
            require(minBps in 1..maxBps && initialBps in minBps..maxBps)
            require(decreaseFactor in 0.1..0.99 && mildDecreaseFactor in decreaseFactor..0.99)
            require(increaseFactor in 1.0..1.5)
            require(recoveryIncreaseFactor in increaseFactor..1.5 && recoveryTargetRatio in 0.1..1.0)
        }
    }

    private data class DelayPoint(val atMs: Long, val c2rAvgUs: Long)

    private var target: Double = config.initialBps.toDouble()
    private var applied: Long = config.initialBps
    private var lastDecreaseMs: Long = Long.MIN_VALUE / 2
    private var lastCongestionMs: Long = Long.MIN_VALUE / 2
    private var lastTickMs: Long? = null
    private var startMs: Long? = null
    private var queueDelayUs: Long = 0
    private var pendingHardCongestion = false
    private var pendingMildCongestion = false
    private var lastReportC2rUs: Long = 0
    private var lastReportAtMs: Long = Long.MIN_VALUE / 2
    private var baselineRttUs: Long? = null
    private var recoveryTarget: Double = 0.0
    private var falling = false
    private val delayHistory = ArrayDeque<DelayPoint>()

    /** Kodlayıcıya son uygulanan (ya da uygulanacak) bit hızı. */
    public val appliedBps: Long get() = applied

    /** Denetleyicinin iç hedefi (uygulanan değerden küçük farklarla ayrışabilir). */
    public val targetBps: Long get() = kotlin.math.round(target).toLong()

    public fun onSenderQueueDelay(delayUs: Long) {
        queueDelayUs = delayUs
    }

    /** Gönderici birikme yüzünden kare attı. */
    public fun onSenderDrop() {
        pendingHardCongestion = true
    }

    /** PING/PONG RTT ölçümü: taban RTT'nin belirgin üstündeki sıçramalar yumuşak tıkanıklık sayılır. */
    public fun onRttSample(nowMs: Long, rttUs: Long) {
        if (rttUs <= 0) return
        val base = baselineRttUs
        if (base == null || rttUs < base) {
            baselineRttUs = rttUs
        } else {
            // Very slow upward drift so a permanent route change does not lock an obsolete baseline forever.
            baselineRttUs = (base * 0.99 + rttUs * 0.01).toLong()
        }
        val ref = baselineRttUs ?: rttUs
        if (rttUs >= config.highRttThresholdUs && (rttUs >= ref * config.rttSpikeFactor || rttUs >= config.highRttThresholdUs * 2.5)) {
            pendingMildCongestion = true
            lastCongestionMs = max(lastCongestionMs, nowMs - config.stableMsBeforeIncrease / 2)
        }
    }

    public fun onReceiverReport(nowMs: Long, report: ControlMessage.ReceiverReport) {
        if (report.framesReceived < config.minReportFrames) {
            // The delay trend starts again once the picture moves.
            delayHistory.clear()
            lastReportC2rUs = 0
            return
        }
        lastReportC2rUs = report.c2rAvgUs
        lastReportAtMs = nowMs
        delayHistory.addLast(DelayPoint(nowMs, report.c2rAvgUs))
        while (delayHistory.size > 2 && nowMs - delayHistory[1].atMs >= config.slopeWindowMs) delayHistory.removeFirst()

        val total = report.framesReceived
        if (total > 0 && report.framesDropped.toDouble() / total > config.dropRatioThreshold) {
            pendingHardCongestion = true
        }

        val oldest = delayHistory.first()
        val dt = nowMs - oldest.atMs
        if (dt >= config.slopeWindowMs / 2) {
            val slopePerSec = (report.c2rAvgUs - oldest.c2rAvgUs) * 1000.0 / dt
            if (slopePerSec > config.delaySlopeThresholdUsPerSec) pendingHardCongestion = true
        }

        if (report.c2rAvgUs >= config.highDelayDecreaseUs) {
            pendingMildCongestion = true
        }
    }

    /**
     * Periyodik çağrılır ([Config.tickMs] aralığında). Kodlayıcıya uygulanması gereken yeni bit hızını,
     * değişiklik yoksa `null` döndürür.
     */
    public fun tick(nowMs: Long): Long? {
        val start = startMs ?: nowMs.also { startMs = it }
        val elapsedTicks = lastTickMs?.let { ((nowMs - it).toDouble() / config.tickMs).coerceIn(0.0, 8.0) } ?: 1.0
        lastTickMs = nowMs

        val hardCongested = pendingHardCongestion || queueDelayUs > config.queueDelayThresholdUs
        val mildCongested = pendingMildCongestion
        val reportFresh = nowMs - lastReportAtMs <= 1_500
        val holdIncrease = reportFresh && lastReportC2rUs >= config.highDelayHoldUs
        pendingHardCongestion = false
        pendingMildCongestion = false

        if (hardCongested || mildCongested) {
            lastCongestionMs = nowMs
            if (nowMs - lastDecreaseMs >= config.decreaseHoldMs) {
                // A short Wi-Fi stall can take the rate to the floor. Remember what worked so the picture does not
                // stay soft for many seconds; congestion during recovery lowers this, so a slower link still settles.
                if (!falling) recoveryTarget = target * config.recoveryTargetRatio
                falling = true
                val factor = if (hardCongested) config.decreaseFactor else config.mildDecreaseFactor
                target = max(config.minBps.toDouble(), target * factor)
                lastDecreaseMs = nowMs
            }
        } else if (!holdIncrease && nowMs - max(lastCongestionMs, start) >= config.stableMsBeforeIncrease) {
            falling = false
            target = if (target < recoveryTarget) {
                min(recoveryTarget, target * config.recoveryIncreaseFactor.pow(elapsedTicks))
            } else {
                min(config.maxBps.toDouble(), target * config.increaseFactor.pow(elapsedTicks))
            }
        }

        val next = kotlin.math.round(target).toLong()
        val change = kotlin.math.abs(next - applied).toDouble() / applied
        val atBound = (next == config.minBps || next == config.maxBps) && next != applied
        return if (change >= config.applyThresholdRatio || atBound) {
            applied = next
            next
        } else {
            null
        }
    }

    /** Kullanıcı ön ayar değiştirdiğinde veya akış yeniden başladığında. */
    public fun reset(initialBps: Long = config.initialBps) {
        require(initialBps in config.minBps..config.maxBps)
        target = initialBps.toDouble()
        applied = initialBps
        lastDecreaseMs = Long.MIN_VALUE / 2
        lastCongestionMs = Long.MIN_VALUE / 2
        lastTickMs = null
        startMs = null
        queueDelayUs = 0
        pendingHardCongestion = false
        pendingMildCongestion = false
        lastReportC2rUs = 0
        lastReportAtMs = Long.MIN_VALUE / 2
        baselineRttUs = null
        recoveryTarget = 0.0
        falling = false
        delayHistory.clear()
    }

    public companion object {
        /** Frames per receiver report (500 ms) below which the stream counts as a still screen: under 10 fps. */
        public const val IDLE_REPORT_FRAMES: Long = 5
    }
}
