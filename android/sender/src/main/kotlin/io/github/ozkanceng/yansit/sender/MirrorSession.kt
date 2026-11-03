package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.BitrateController
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.ClockSync
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.StreamKind
import io.github.ozkanceng.yansit.protocol.client.MediaChannel
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.protocol.client.SenderClient
import io.github.ozkanceng.yansit.protocol.client.Session
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Kodlayıcı soyutlaması: [MirrorSession] Android'den bağımsız kalır (birim testinde sahte uygulanır). */
interface VideoSource {
    /** Kodlanmış çıktılar [sink]'e verilir; ilk çıktı CONFIG (SPS/PPS, Annex-B) olmalıdır. */
    fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit)
    fun requestKeyframe()
    fun setBitrate(bps: Long)
    fun stop()
}

/** Arayüzde gösterilen anlık durum. */
data class MirrorStats(
    val latencyMs: Int?,
    val fps: Double,
    val bitrateBps: Long,
    val droppedFrames: Long,
    val width: Int,
    val height: Int,
)

/** Oturumun neden bittiği. */
sealed interface SessionEnd {
    data class Local(val byeReason: Int) : SessionEnd
    data class Remote(val byeReason: Int) : SessionEnd
    data class ConnectionLost(val cause: Throwable?) : SessionEnd
    data class EncoderFailed(val cause: Throwable) : SessionEnd
}

/**
 * Kabul edilmiş bir oturumun gönderici tarafı (PROTOCOL.md §4–7):
 * CONTROL okuyucu (PONG, KEYFRAME_REQUEST, RECEIVER_REPORT, BYE), saniyede bir PING + SENDER_STATS,
 * 250 ms'de bir bit hızı uyarlaması ve VIDEO bağlantısına kuyruklu, kare atmalı gönderim.
 *
 * [start] hemen çağrılır (oturum canlı kalsın diye; yansıtma onayı beklenirken de); [startVideo] ekran
 * yakalama hazır olunca.
 */
class MirrorSession(
    private val session: Session,
    private val onStats: (MirrorStats) -> Unit,
    private val onEnd: (SessionEnd) -> Unit,
) {
    private val control = session.control
    private val clock = ClockSync()
    private val ended = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mirror-ticker").apply { isDaemon = true }
    }
    private var reader: Thread? = null
    private var sender: Thread? = null
    @Volatile private var video: MediaChannel? = null
    @Volatile private var source: VideoSource? = null
    @Volatile private var plan: StreamPlan? = null
    @Volatile private var bitrate: BitrateController? = null
    private val queue = SendQueue(onDrop = { onQueueDrop() })
    private val pingId = AtomicLong()
    private val framesSent = AtomicLong()
    private val lastKeyframeRequestUs = AtomicLong()
    @Volatile private var lastReport: ControlMessage.ReceiverReport? = null
    private var statsFramesAtLastTick = 0L
    private var statsLastTickUs = 0L

    val welcome: ControlMessage.Welcome get() = session.welcome

    fun start() {
        reader = Thread(::readLoop, "mirror-control").apply { isDaemon = true; start() }
        scheduler.scheduleWithFixedDelay(::ping, 0, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /** VIDEO bağlantısını açar ve kodlayıcıyı başlatır. Engelleyicidir (ağ). */
    @Throws(IOException::class)
    fun startVideo(plan: StreamPlan, source: VideoSource) {
        check(this.source == null) { "video zaten başladı" }
        this.plan = plan
        this.source = source
        bitrate = BitrateController(BitrateController.Config(plan.startBps, plan.minBps, plan.maxBps))
        val channel = SenderClient.attach(session, StreamKind.VIDEO)
        video = channel
        control.send(
            ControlMessage.StreamConfig(
                streamKind = StreamKind.VIDEO,
                codec = plan.codec,
                width = plan.width,
                height = plan.height,
                fps = plan.fps,
                bitrateBps = plan.startBps,
            ),
        )
        sender = Thread({ sendLoop(channel) }, "mirror-video").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        statsLastTickUs = MonotonicClock.nowUs()
        scheduler.scheduleWithFixedDelay(::bitrateTick, BITRATE_TICK_MS, BITRATE_TICK_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(::statsTick, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        source.start(plan, { frame -> queue.offer(frame, MonotonicClock.nowUs()) }) { e -> end(SessionEnd.EncoderFailed(e)) }
    }

    /** Göndericinin isteğiyle bitirir (kullanıcı durdurdu, yansıtma izni bitti...). */
    fun stop(byeReason: Int) = end(SessionEnd.Local(byeReason))

    private fun end(reason: SessionEnd) {
        if (!ended.compareAndSet(false, true)) return
        val bye = when (reason) {
            is SessionEnd.Local -> reason.byeReason
            is SessionEnd.EncoderFailed -> ByeReason.ERROR
            else -> null
        }
        if (bye != null) {
            try {
                control.send(ControlMessage.Bye(bye))
            } catch (_: IOException) {
            }
        }
        scheduler.shutdownNow()
        queue.close()
        try {
            source?.stop()
        } catch (_: RuntimeException) {
        }
        video?.close()
        session.close()
        reader?.interrupt()
        sender?.interrupt()
        onEnd(reason)
    }

    private fun readLoop() {
        try {
            while (!ended.get()) {
                when (val msg = control.receive() ?: break) {
                    is ControlMessage.Pong -> clock.onPong(msg.t0Us, msg.t1Us, msg.t2Us, MonotonicClock.nowUs())
                    is ControlMessage.KeyframeRequest -> requestKeyframe()
                    is ControlMessage.ReceiverReport -> {
                        lastReport = msg
                        bitrate?.onReceiverReport(MonotonicClock.nowUs() / 1000, msg)
                    }
                    is ControlMessage.Bye -> {
                        end(SessionEnd.Remote(msg.reason))
                        return
                    }
                    else -> {}
                }
            }
            end(SessionEnd.ConnectionLost(null))
        } catch (e: IOException) {
            end(SessionEnd.ConnectionLost(e))
        } catch (e: io.github.ozkanceng.yansit.protocol.ProtocolException) {
            end(SessionEnd.ConnectionLost(e))
        }
    }

    private fun sendLoop(channel: MediaChannel) {
        try {
            while (!ended.get()) {
                val (frame, discontinuity) = queue.take() ?: break
                var flags = frame.flags
                if (discontinuity) flags = flags or FrameFlags.DISCONTINUITY
                val payload = if (frame.isConfig) byteArrayOf(plan!!.codec.toByte()) + frame.data else frame.data
                channel.send(flags, frame.ptsUs, payload)
                if (!frame.isConfig) framesSent.incrementAndGet()
                bitrate?.onSenderQueueDelay(queue.oldestAgeUs(MonotonicClock.nowUs()))
            }
        } catch (e: IOException) {
            end(SessionEnd.ConnectionLost(e))
        }
    }

    private fun onQueueDrop() {
        bitrate?.onSenderDrop()
        requestKeyframe()
    }

    /** IDR isteklerini birleştirir (PROTOCOL.md §7.2: 200 ms'den sık istekler tek IDR). */
    private fun requestKeyframe() {
        val now = MonotonicClock.nowUs()
        val last = lastKeyframeRequestUs.get()
        if (now - last < KEYFRAME_COALESCE_US || !lastKeyframeRequestUs.compareAndSet(last, now)) return
        source?.requestKeyframe()
    }

    private fun ping() {
        try {
            control.send(ControlMessage.Ping(pingId.getAndIncrement() and 0xFFFFFFFFL, MonotonicClock.nowUs(), clock.offsetUs))
        } catch (e: IOException) {
            end(SessionEnd.ConnectionLost(e))
        }
    }

    private fun bitrateTick() {
        val b = bitrate ?: return
        b.tick(MonotonicClock.nowUs() / 1000)?.let { source?.setBitrate(it) }
    }

    private fun statsTick() {
        val p = plan ?: return
        val now = MonotonicClock.nowUs()
        val frames = framesSent.get()
        val fps = (frames - statsFramesAtLastTick) * 1e6 / maxOf(1, now - statsLastTickUs)
        statsFramesAtLastTick = frames
        statsLastTickUs = now
        val applied = bitrate?.appliedBps ?: p.startBps
        try {
            control.send(ControlMessage.SenderStats(applied, (fps * 100).toInt().coerceIn(0, 0xFFFF), queue.droppedTotal,
                p.encoderName.take(64)))
        } catch (e: IOException) {
            end(SessionEnd.ConnectionLost(e))
            return
        }
        val report = lastReport
        onStats(
            MirrorStats(
                latencyMs = report?.takeIf { it.c2dAvgUs > 0 }?.let { (it.c2dAvgUs / 1000).toInt() },
                fps = fps,
                bitrateBps = applied,
                droppedFrames = queue.droppedTotal,
                width = p.width,
                height = p.height,
            ),
        )
    }

    private companion object {
        const val PING_INTERVAL_MS = 1000L
        const val BITRATE_TICK_MS = 250L
        const val STATS_INTERVAL_MS = 1000L
        const val KEYFRAME_COALESCE_US = 200_000L
    }
}
