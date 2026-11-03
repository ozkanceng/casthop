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
        /** Her [tickMs]'de çarpan; 250 ms'de 1.02 ≈ saniyede %8. */
        val increaseFactor: Double = 1.02,
        val tickMs: Long = 250,
        val decreaseHoldMs: Long = 500,
        val stableMsBeforeIncrease: Long = 2000,
        val queueDelayThresholdUs: Long = 50_000,
        val delaySlopeThresholdUsPerSec: Long = 15_000,
        val slopeWindowMs: Long = 1000,
        val dropRatioThreshold: Double = 0.05,
        /** Uygulanan değerden en az bu oranda farklıysa kodlayıcıya yeni değer verilir. */
        val applyThresholdRatio: Double = 0.05,
    ) {
        init {
            require(minBps in 1..maxBps && initialBps in minBps..maxBps)
            require(decreaseFactor in 0.1..0.99 && increaseFactor in 1.0..1.5)
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
    private var pendingCongestion = false
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
        pendingCongestion = true
    }

    public fun onReceiverReport(nowMs: Long, report: ControlMessage.ReceiverReport) {
        delayHistory.addLast(DelayPoint(nowMs, report.c2rAvgUs))
        while (delayHistory.size > 2 && nowMs - delayHistory[1].atMs >= config.slopeWindowMs) delayHistory.removeFirst()

        val total = report.framesReceived
        if (total > 0 && report.framesDropped.toDouble() / total > config.dropRatioThreshold) pendingCongestion = true

        val oldest = delayHistory.first()
        val dt = nowMs - oldest.atMs
        if (dt >= config.slopeWindowMs / 2) {
            val slopePerSec = (report.c2rAvgUs - oldest.c2rAvgUs) * 1000.0 / dt
            if (slopePerSec > config.delaySlopeThresholdUsPerSec) pendingCongestion = true
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

        val congested = pendingCongestion || queueDelayUs > config.queueDelayThresholdUs
        pendingCongestion = false

        if (congested) {
            lastCongestionMs = nowMs
            if (nowMs - lastDecreaseMs >= config.decreaseHoldMs) {
                target = max(config.minBps.toDouble(), target * config.decreaseFactor)
                lastDecreaseMs = nowMs
            }
        } else if (nowMs - max(lastCongestionMs, start) >= config.stableMsBeforeIncrease) {
            target = min(config.maxBps.toDouble(), target * config.increaseFactor.pow(elapsedTicks))
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
        pendingCongestion = false
        delayHistory.clear()
    }
}
