package io.github.ozkanceng.yansit.tools.fakesender

import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.sender.EncodedFrame
import io.github.ozkanceng.yansit.sender.StreamPlan
import io.github.ozkanceng.yansit.sender.VideoSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Android'deki ScreenEncoder yerine geçen kaynak: Annex-B dosyasındaki kareleri gerçek zaman hızında verir.
 * Anahtar kare isteği gelince sonraki IDR'ye atlar (dosyadan yeni IDR üretilemez); bit hızı değişikliklerini sayar.
 */
class FileVideoSource(
    private val stream: H264Stream,
    private val fps: Int,
    private val frames: Int,
    private val onFinished: () -> Unit,
) : VideoSource {
    val framesProduced = AtomicInteger()
    val keyframeRequests = AtomicInteger()
    val bitrateChanges = AtomicInteger()
    val lastBitrate = AtomicLong()
    @Volatile private var running = false
    @Volatile private var skipToKeyframe = false
    private var thread: Thread? = null

    override fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit) {
        running = true
        lastBitrate.set(plan.startBps)
        thread = Thread({
            try {
                sink(EncodedFrame(stream.config, MonotonicClock.nowUs(), FrameFlags.CONFIG, MonotonicClock.nowUs()))
                val intervalNs = 1_000_000_000L / fps
                val start = System.nanoTime()
                for (i in 0 until frames) {
                    if (!running) return@Thread
                    val target = start + i * intervalNs
                    while (true) {
                        val wait = target - System.nanoTime()
                        if (wait <= 0) break
                        LockSupport.parkNanos(wait)
                    }
                    val au = stream.units[i % stream.units.size]
                    if (skipToKeyframe && !au.keyframe) continue
                    if (au.keyframe) skipToKeyframe = false
                    val now = MonotonicClock.nowUs()
                    sink(EncodedFrame(au.data, now, if (au.keyframe) FrameFlags.KEYFRAME else 0, now))
                    framesProduced.incrementAndGet()
                }
                if (running) onFinished()
            } catch (e: RuntimeException) {
                onError(e)
            }
        }, "file-source").apply { isDaemon = true; start() }
    }

    override fun requestKeyframe() {
        keyframeRequests.incrementAndGet()
        skipToKeyframe = true
    }

    override fun setBitrate(bps: Long) {
        bitrateChanges.incrementAndGet()
        lastBitrate.set(bps)
    }

    override fun stop() {
        running = false
        if (Thread.currentThread() != thread) thread?.join(1000)
    }
}
