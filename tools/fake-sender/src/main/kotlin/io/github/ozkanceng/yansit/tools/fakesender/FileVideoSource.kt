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
 * Yeniden başlatıldığında (döndürme benzetimi) [select] ile seçilen dosyanın başından, toplam kare sayısını
 * koruyarak devam eder.
 */
class FileVideoSource(
    private val select: (StreamPlan) -> H264Stream,
    private val fps: Int,
    private val frames: Int,
    /** Added to every picture frame (not CONFIG), e.g. FrameFlags.ROTATED. */
    private val extraFlags: Int = 0,
    private val onFrame: (index: Int) -> Unit = {},
    private val onFinished: () -> Unit,
) : VideoSource {
    val framesProduced = AtomicInteger()
    val starts = AtomicInteger()
    private val nextIndex = AtomicInteger()
    val keyframeRequests = AtomicInteger()
    val bitrateChanges = AtomicInteger()
    val lastBitrate = AtomicLong()
    @Volatile private var running = false
    @Volatile private var skipToKeyframe = false
    private var thread: Thread? = null

    override fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit) {
        running = true
        starts.incrementAndGet()
        if (lastBitrate.get() == 0L) lastBitrate.set(plan.startBps)
        val stream = select(plan)
        val first = nextIndex.get()
        thread = Thread({
            try {
                sink(EncodedFrame(stream.config, MonotonicClock.nowUs(), FrameFlags.CONFIG, MonotonicClock.nowUs()))
                val intervalNs = 1_000_000_000L / fps
                val start = System.nanoTime()
                for (i in first until frames) {
                    if (!running) return@Thread
                    nextIndex.set(i + 1)
                    val target = start + (i - first) * intervalNs
                    while (true) {
                        val wait = target - System.nanoTime()
                        if (wait <= 0) break
                        LockSupport.parkNanos(wait)
                    }
                    onFrame(i)
                    val au = stream.units[(i - first) % stream.units.size]
                    if (skipToKeyframe && !au.keyframe) continue
                    if (au.keyframe) skipToKeyframe = false
                    val now = MonotonicClock.nowUs()
                    val flags = (if (au.keyframe) FrameFlags.KEYFRAME else 0) or extraFlags
                    sink(EncodedFrame(au.data, now, flags, now))
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
