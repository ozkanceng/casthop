package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.FrameFlags
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Kodlanmış bir video karesi. [flags] protokol çerçeve bayrakları (CONFIG/KEYFRAME). */
class EncodedFrame(val data: ByteArray, val ptsUs: Long, val flags: Int, val enqueuedUs: Long) {
    val isKeyframe: Boolean get() = flags and FrameFlags.KEYFRAME != 0
    val isConfig: Boolean get() = flags and FrameFlags.CONFIG != 0
}

/**
 * Kodlayıcı ile ağ yazıcısı arasındaki sınırlı kuyruk (ARCHITECTURE.md §2.1 "Gönderim kuyruğu").
 *
 * Ağ yavaşladığında gecikme biriktirmek yerine kare atar: kuyruk [maxFrames] kareyi veya en eski kare
 * [maxAgeUs] yaşını aşarsa bekleyen P-kareler atılır ve sonraki anahtar kareye kadar P-kare kabul edilmez
 * (H.264'te P-kareler önceki karelere dayandığından tek tek atılamaz). CONFIG çerçeveleri asla atılmaz; yeni bir
 * CONFIG (çözünürlük değişimi) kuyruktaki eski biçimli kareleri siler.
 * Atma olayı [onDrop] ile bildirilir: çağıran kodlayıcıdan IDR ister ve bit hızını düşürür.
 */
class SendQueue(
    private val maxFrames: Int = 2,
    private val maxAgeUs: Long = 50_000,
    private val onDrop: (droppedFrames: Int) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<EncodedFrame>()
    private var waitingForKeyframe = false
    private var discontinuity = false
    private var closed = false

    /** Toplam atılan kare (istatistik). */
    @Volatile var droppedTotal: Long = 0
        private set

    /** Kuyruktaki en eski karenin yaşı (µs); boşsa 0. */
    fun oldestAgeUs(nowUs: Long): Long = lock.withLock { queue.peekFirst()?.let { nowUs - it.enqueuedUs } ?: 0 }

    /** Kuyrukta bekleyen kare sayısı. */
    fun depth(): Int = lock.withLock { queue.size }

    /** @return kare kabul edildiyse true. */
    fun offer(frame: EncodedFrame, nowUs: Long): Boolean {
        var dropped = 0
        val accepted = lock.withLock {
            if (closed) return false
            if (frame.isConfig) {
                dropped = queue.count { !it.isConfig }
                queue.clear()
                queue.addLast(frame)
                notEmpty.signal()
                return@withLock true
            }
            if (waitingForKeyframe && !frame.isKeyframe) {
                dropped = 1
                discontinuity = true
                return@withLock false
            }
            if (frame.isKeyframe) waitingForKeyframe = false

            val oldest = queue.peekFirst()
            val overflow = queue.size >= maxFrames || (oldest != null && nowUs - oldest.enqueuedUs > maxAgeUs)
            if (overflow) {
                val before = queue.size
                queue.removeAll { !it.isConfig }
                dropped = before - queue.size
                discontinuity = discontinuity || dropped > 0
                if (!frame.isKeyframe) {
                    // Bu P-kare de atılan karelere dayanır.
                    dropped += 1
                    waitingForKeyframe = true
                    return@withLock false
                }
            }
            queue.addLast(frame)
            notEmpty.signal()
            true
        }
        if (dropped > 0) {
            droppedTotal += dropped
            onDrop(dropped)
        }
        return accepted
    }

    /**
     * Sıradaki kareyi bekler. Dönen çiftteki boolean, bu kareden önce kare atıldıysa true
     * (DISCONTINUITY bayrağı). Kuyruk kapatıldıysa `null`.
     */
    fun take(timeoutMs: Long = Long.MAX_VALUE): Pair<EncodedFrame, Boolean>? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000))
        while (queue.isEmpty() && !closed) {
            if (remaining <= 0) return null
            remaining = notEmpty.awaitNanos(remaining)
        }
        val frame = queue.pollFirst() ?: return null
        val disc = discontinuity && !frame.isConfig
        if (disc) discontinuity = false
        frame to disc
    }

    /** Bekleyen tüm kareleri atar ve sonraki anahtar kareyi bekler (bağlantı yeniden kurulunca). */
    fun dropUntilKeyframe() {
        val dropped = lock.withLock {
            val n = queue.count { !it.isConfig }
            queue.removeAll { !it.isConfig }
            waitingForKeyframe = true
            discontinuity = true
            n
        }
        if (dropped > 0) droppedTotal += dropped
    }

    fun close() = lock.withLock {
        closed = true
        queue.clear()
        notEmpty.signalAll()
    }
}
