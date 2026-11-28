package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.BitrateController
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.ClockSync
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.ProtocolException
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.StreamKind
import io.github.ozkanceng.yansit.protocol.client.ControlChannel
import io.github.ozkanceng.yansit.protocol.client.HelloOutcome
import io.github.ozkanceng.yansit.protocol.client.MediaChannel
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.protocol.client.SenderClient
import io.github.ozkanceng.yansit.protocol.client.Session
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Kodlayıcı soyutlaması: [MirrorSession] Android'den bağımsız kalır (CI'da dosya kaynağıyla çalışır). */
interface VideoSource {
    /**
     * Kodlanmış çıktılar [sink]'e verilir; ilk çıktı CONFIG (SPS/PPS, Annex-B) olmalıdır. The size may differ from
     * the last start (quality step): every frame the new encoder gets must have the plan's size.
     */
    fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit)
    fun requestKeyframe()
    fun setBitrate(bps: Long)

    /** Fewer or more frames per second without restarting; sources that cannot do this keep their rate. */
    fun setFrameRate(fps: Int) = Unit

    /**
     * [ready] says whether the network can take a new frame now ([CaptureGate]). A source that can hold its
     * picture asks before encoding one and waits while it says no; others ignore it.
     */
    fun setFrameGate(ready: () -> Boolean) = Unit

    fun stop()
}

/** Canlı bağlantı kalitesi seviyesi. */
enum class ConnectionQuality { GOOD, FAIR, POOR }

/** Arayüzde gösterilen anlık durum. */
data class MirrorStats(
    val latencyMs: Int?,
    val fps: Double,
    val bitrateBps: Long,
    val droppedFrames: Long,
    val width: Int,
    val height: Int,
    /** Quality is below the chosen preset because the network cannot carry it. */
    val autoLowered: Boolean = false,
    /** PING/PONG gidiş-dönüş süresi (ms); henüz ölçülmediyse null. */
    val rttMs: Int? = null,
    /** Son penceredeki tahmini kare kayıp oranı (%0–100). */
    val lossPercent: Double = 0.0,
    /** Güncel hedef kare hızı. */
    val targetFps: Int = 0,
    /** Özet bağlantı kalitesi. */
    val quality: ConnectionQuality = ConnectionQuality.GOOD,
)

/** Bağlantının anlık durumu (arayüzde "yeniden bağlanılıyor" göstermek için). */
enum class LinkState { CONNECTED, RECONNECTING }

/** Oturumun neden bittiği. */
sealed interface SessionEnd {
    data class Local(val byeReason: Int) : SessionEnd
    data class Remote(val byeReason: Int) : SessionEnd
    /** Bağlantı koptu ve RESUME penceresinde geri gelmedi; [sessionExpired] ise tahta oturumu unuttu. */
    data class ConnectionLost(val cause: Throwable?, val sessionExpired: Boolean = false) : SessionEnd
    data class EncoderFailed(val cause: Throwable) : SessionEnd
}

/**
 * Kabul edilmiş bir oturumun gönderici tarafı (PROTOCOL.md §4–7):
 * - CONTROL okuyucu (PONG, KEYFRAME_REQUEST, RECEIVER_REPORT, BYE), saniyede bir PING + SENDER_STATS,
 *   250 ms'de bir bit hızı uyarlaması, kuyruklu ve kare atmalı VIDEO gönderimi.
 * - Canlılık: [Config.livenessTimeoutMs] boyunca tahtadan hiçbir şey gelmezse bağlantı kopmuş sayılır ve
 *   RESUME ile sürdürülür (Wi-Fi kopması); VIDEO yeniden bağlanır, CONFIG yeniden gönderilir ve hemen IDR istenir.
 * - Döndürme: [reconfigure]; duraklatma: [pause]/[unpause].
 * - Kodlayıcı hatası: aynı ayarla yeniden başlatma, tekrarlarsa [fallbackPlan] ile daha düşük ayar.
 * - Kalıcı zayıf ağ: [QualityGovernor] kararıyla [qualityStep] alt veya üst ön ayara geçer.
 *
 * [start] HELLO kabul edilir edilmez çağrılır (yansıtma onayı beklenirken oturum canlı kalsın diye);
 * [startVideo] ekran yakalama hazır olunca.
 */
class MirrorSession(
    initial: Session,
    private val onStats: (MirrorStats) -> Unit,
    private val onEnd: (SessionEnd) -> Unit,
    private val onLink: (LinkState) -> Unit = {},
    private val config: Config = Config(),
    private val fallbackPlan: (StreamPlan) -> StreamPlan? = { null },
    /** Ses durdu veya yeniden bağlanamadı (görüntü sürer). */
    private val onAudioIssue: (Throwable) -> Unit = {},
    /** Applies a [QualityStep] (resizes the source, calls [reconfigure]); `false` if there is nothing to move to. */
    private val qualityStep: (QualityStep) -> Boolean = { false },
) {
    data class Config(
        val pingIntervalMs: Long = 1000,
        val livenessTimeoutMs: Long = 2500,
        /**
         * Video başlamadan önce (Android ekran paylaşım onay penceresi açıkken) daha toleranslı canlılık eşiği:
         * OEM izin diyalogları arka plan zamanlayıcısını kısa süre bekletebilir.
         */
        val preVideoLivenessTimeoutMs: Long = 8000,
        /** Tahtanın RESUME penceresi 10 sn; biraz önce vazgeçilir. */
        val resumeWindowMs: Long = 9500,
        val maxEncoderRestarts: Int = 3,
        /**
         * Alıcının bildirdiği yakalama→alım gecikmesi bunu aşarsa (ağ, en düşük bit hızında bile yetmiyor)
         * yol boşaltılır: kareler atılır, ardından IDR ile devam edilir. Gecikme birikmez (RISKS R7).
         */
        val latencyBudgetUs: Long = 300_000,
        val quality: QualityGovernor.Config = QualityGovernor.Config(),
    )

    @Volatile private var session: Session = initial
    private val clock = ClockSync()
    private val ended = AtomicBoolean(false)
    /**
     * Work handed in after [end] shut it down is dropped, not thrown: a RESUME or an encoder callback can race the
     * end of the session, and the default policy crashed the app with RejectedExecutionException.
     */
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(
        2,
        { r -> Thread(r, "mirror-ticker").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )
    @Volatile private var video: MediaChannel? = null
    @Volatile private var source: VideoSource? = null
    @Volatile private var audio: MediaChannel? = null
    @Volatile private var audioSource: AudioSource? = null
    private val audioQueue = AudioQueue()
    private var audioSender: Thread? = null
    @Volatile private var plan: StreamPlan? = null
    @Volatile private var bitrate: BitrateController? = null
    private val quality = QualityGovernor(config.quality)
    private val qualityStepsDown = AtomicInteger()
    private val keyframeGate = KeyframeGate()
    @Volatile private var lastConfig: ByteArray? = null
    @Volatile private var lastReport: ControlMessage.ReceiverReport? = null
    @Volatile private var lastRxUs: Long = MonotonicClock.nowUs()
    @Volatile var linkState: LinkState = LinkState.CONNECTED
        private set
    @Volatile var paused: Boolean = false
        private set
    private val epoch = AtomicInteger()
    private val queue = SendQueue(onDrop = { onQueueDrop() })
    private val captureGate = CaptureGate(onCongestion = { bitrate?.onSenderDrop() })
    /** Warm-up at the start of the session; `null` once done or cancelled by a quality step. */
    @Volatile private var ramp: FrameRateRamp? = null
    private val pingId = AtomicLong()
    private val framesSent = AtomicLong()
    private val lastKeyframeRequestUs = AtomicLong()
    private val encoderRestarts = AtomicInteger()
    /** Bu ana kadar gönderim yapılmaz (yolu boşaltma); 0 = yok. */
    @Volatile private var drainUntilUs = 0L
    @Volatile private var drainCooldownUntilUs = 0L
    @Volatile private var consecutiveHighDelay = 0
    /** Yol boşaltma sayısı (test ve tanılama). */
    val drainCount: AtomicInteger = AtomicInteger()
    private var statsFramesAtLastTick = 0L
    private var statsDroppedAtLastTick = 0L
    private var statsLastTickUs = 0L
    private var sender: Thread? = null

    /** Güncel WELCOME (RESUME sonrası yenilenir). */
    val welcome: ControlMessage.Welcome get() = session.welcome

    /** Başarılı RESUME sayısı (test ve tanılama). */
    val resumeCount: AtomicInteger = AtomicInteger()

    fun start() {
        startReader(epoch.get(), session.control)
        // İlk PING'ler sık: saat ofseti (ses senkronu ve gecikme ölçümü için) ilk ≈ 150 ms'de oturur.
        for (i in 0 until FAST_PINGS) scheduler.schedule(::ping, i * FAST_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(::ping, config.pingIntervalMs, config.pingIntervalMs, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(::checkLiveness, config.livenessTimeoutMs / 5, config.livenessTimeoutMs / 5,
            TimeUnit.MILLISECONDS)
    }

    /** VIDEO bağlantısını açar ve kodlayıcıyı başlatır. Engelleyicidir (ağ). */
    @Throws(IOException::class)
    fun startVideo(plan: StreamPlan, source: VideoSource) {
        check(this.source == null) { "video zaten başladı" }
        this.plan = plan
        this.source = source
        bitrate = BitrateController(BitrateController.Config(plan.startBps, plan.minBps, plan.maxBps))
        ramp = FrameRateRamp(plan.fps).takeIf { it.active }
        val initBuf = sendBufferFor(plan.startBps)
        video = attachVideoWithRetry(initBuf).also { it.setSendBufferBytes(initBuf) }
        sendStreamConfig(plan)
        sender = Thread(::sendLoop, "mirror-video").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        statsLastTickUs = MonotonicClock.nowUs()
        source.setFrameGate(::captureReady)
        scheduler.scheduleWithFixedDelay(::bitrateTick, BITRATE_TICK_MS, BITRATE_TICK_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(::statsTick, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        startSource(plan)
    }

    /**
     * Ekran izni sırasında kısa bir kopma olduysa RESUME'un bitmesini bekler; ardından geçici ağ hatasında
     * VIDEO `ATTACH` bağlantısını en fazla 2 kez kısa beklemeyle yeniden dener.
     */
    @Throws(IOException::class)
    private fun attachVideoWithRetry(sendBufferBytes: Int): MediaChannel {
        val waitDeadlineUs = MonotonicClock.nowUs() + ATTACH_WAIT_RECONNECT_MS * 1000L
        while (!ended.get() && linkState == LinkState.RECONNECTING && MonotonicClock.nowUs() < waitDeadlineUs) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("video bağlantısı iptal edildi")
            }
        }
        if (ended.get()) throw IOException("oturum kapandı")
        var lastError: IOException? = null
        val delays = longArrayOf(0L, 250L, 500L)
        for (delayMs in delays) {
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw lastError ?: IOException("video bağlantısı iptal edildi")
                }
            }
            if (ended.get()) throw lastError ?: IOException("oturum kapandı")
            try {
                return SenderClient.attach(session, StreamKind.VIDEO, sendBufferBytes = sendBufferBytes)
            } catch (e: io.github.ozkanceng.yansit.protocol.client.AttachRejectedException) {
                // Oturum bilinmiyor veya kimlik hatası: tekrar deneme anlamsız.
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("video bağlantısı kurulamadı")
    }

    /**
     * Ses akışını başlatır (tahta WELCOME'da audio_enabled bildirdiyse). Engelleyicidir (ağ).
     * Ses başlatılamazsa video etkilenmez; çağıran hatayı kullanıcıya bildirebilir.
     */
    @Throws(IOException::class)
    fun startAudio(src: AudioSource) {
        check(audioSource == null) { "ses zaten başladı" }
        if (!session.welcome.audioEnabled) throw IOException("tahta sesi kabul etmiyor")
        if (session.welcome.audioDecoders.none { it.codec == src.codec }) throw IOException("tahta bu ses codec'ini çözemiyor")
        audioSource = src
        attachAudio(session, src)
        audioSender = Thread(::audioSendLoop, "mirror-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        src.start({ audioQueue.offer(it) }) { e ->
            // Ses hatası oturumu bitirmez: ses durur, görüntü sürer.
            audioQueue.close()
            audio?.close()
            audio = null
            onAudioIssue(e)
        }
    }

    /** Sesin toplam atılan paket sayısı (tanılama). */
    val audioDropped: Long get() = audioQueue.dropped

    private fun attachAudio(s: Session, src: AudioSource) {
        val channel = SenderClient.attach(s, StreamKind.AUDIO, sendBufferBytes = 32 * 1024)
        s.control.send(
            ControlMessage.StreamConfig(
                streamKind = StreamKind.AUDIO,
                codec = src.codec,
                sampleRate = src.sampleRate.toLong(),
                channels = src.channels,
            ),
        )
        channel.send(FrameFlags.CONFIG, MonotonicClock.nowUs(), byteArrayOf(src.codec.toByte()) + src.config)
        audio = channel
    }

    private fun audioSendLoop() {
        while (!ended.get()) {
            val channel = audio
            if (channel == null) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
                audioQueue.clear() // kopuklukta biriken ses bayattır
                continue
            }
            val p = audioQueue.take(100) ?: continue
            if (paused) continue // duraklatma gizlilik içindir: ses de paylaşılmaz
            try {
                channel.send(0, p.ptsUs, p.data)
            } catch (e: IOException) {
                linkLost(epoch.get(), e)
            }
        }
    }

    /**
     * New stream format (quality step): the encoder restarts with the new plan and sends a new CONFIG + IDR.
     * Turning the tablet does not come here; the source keeps the frame size ([VideoSource.start]).
     */
    fun reconfigure(newPlan: StreamPlan) {
        val src = source ?: return
        if (ended.get()) return
        src.stop()
        ramp = null
        plan = newPlan
        bitrate = BitrateController(
            BitrateController.Config(
                (bitrate?.appliedBps ?: newPlan.startBps).coerceIn(newPlan.minBps, newPlan.maxBps),
                newPlan.minBps,
                newPlan.maxBps,
            ),
        )
        sendOffThread { sendStreamConfig(newPlan) }
        startSource(newPlan)
    }

    /**
     * Quality step at the same frame size ([StreamPlanner.atFixedSize]): new bit rate range and frame rate, the
     * encoder keeps running (no CONFIG, no IDR). A plan of another size or encoder goes through [reconfigure].
     */
    fun retune(newPlan: StreamPlan) {
        val src = source ?: return
        val old = plan ?: return
        if (ended.get()) return
        if (newPlan.copy(fps = old.fps, startBps = old.startBps, minBps = old.minBps, maxBps = old.maxBps) != old) {
            reconfigure(newPlan)
            return
        }
        ramp = null
        plan = newPlan
        val b = BitrateController(
            BitrateController.Config(
                (bitrate?.appliedBps ?: newPlan.startBps).coerceIn(newPlan.minBps, newPlan.maxBps),
                newPlan.minBps,
                newPlan.maxBps,
            ),
        )
        bitrate = b
        src.setFrameRate(newPlan.fps)
        src.setBitrate(b.appliedBps)
        video?.setSendBufferBytes(sendBufferFor(b.appliedBps))
        sendOffThread { sendStreamConfig(newPlan) }
    }

    /** Tahtada "Yansıtma duraklatıldı" gösterilir; çağıran kareleri durdurmalıdır (VirtualDisplay.setSurface(null)). */
    fun pause() {
        if (paused || ended.get()) return
        paused = true
        sendOffThread { session.control.send(ControlMessage.Pause) }
    }

    /** Duraklatmayı bitirir; ilk kare IDR olur (PROTOCOL.md §7.2). */
    fun unpause() {
        if (!paused || ended.get()) return
        paused = false
        sendOffThread { session.control.send(ControlMessage.Unpause) }
        forceKeyframe()
    }

    /** Göndericinin isteğiyle bitirir (kullanıcı durdurdu, yansıtma izni bitti...). */
    fun stop(byeReason: Int) = end(SessionEnd.Local(byeReason))

    // --- Kaynak ve kodlayıcı hataları ---

    private fun startSource(p: StreamPlan) {
        val src = source ?: return
        try {
            src.start(p, { frame -> queue.offer(frame, MonotonicClock.nowUs()) }, ::onSourceError)
            ramp?.let { src.setFrameRate(it.fps) }
        } catch (e: Exception) {
            onSourceError(e)
        }
    }

    private fun onSourceError(e: Throwable) {
        if (ended.get()) return
        // Kodlayıcı geri çağrısının içinden durdurmamak için ayrı iş parçacığında ele alınır.
        scheduler.execute {
            val src = source ?: return@execute
            val current = plan ?: return@execute
            val attempt = encoderRestarts.incrementAndGet()
            try {
                src.stop()
            } catch (_: RuntimeException) {
            }
            val next = if (attempt <= config.maxEncoderRestarts) current else fallbackPlan(current)
            if (next == null || attempt > config.maxEncoderRestarts * 2) {
                end(SessionEnd.EncoderFailed(e))
                return@execute
            }
            if (next != current) {
                plan = next
                trySend { sendStreamConfig(next) }
            }
            startSource(next)
        }
    }

    // --- Bağlantı ve RESUME ---

    private fun startReader(myEpoch: Int, control: ControlChannel) {
        Thread({ readLoop(myEpoch, control) }, "mirror-control-$myEpoch").apply { isDaemon = true; start() }
    }

    private fun readLoop(myEpoch: Int, control: ControlChannel) {
        try {
            while (!ended.get() && epoch.get() == myEpoch) {
                val msg = control.receive() ?: break
                lastRxUs = MonotonicClock.nowUs()
                when (msg) {
                    is ControlMessage.Pong -> {
                        val now = MonotonicClock.nowUs()
                        val sample = clock.onPong(msg.t0Us, msg.t1Us, msg.t2Us, now)
                        if (sample != null) bitrate?.onRttSample(now / 1000, sample.rttUs)
                    }
                    is ControlMessage.KeyframeRequest -> requestKeyframe()
                    is ControlMessage.ReceiverReport -> {
                        lastReport = msg
                        bitrate?.onReceiverReport(MonotonicClock.nowUs() / 1000, msg)
                        checkLatencyBudget(msg)
                    }
                    is ControlMessage.Bye -> {
                        end(SessionEnd.Remote(msg.reason))
                        return
                    }
                    else -> {}
                }
            }
            linkLost(myEpoch, null)
        } catch (e: IOException) {
            linkLost(myEpoch, e)
        } catch (e: ProtocolException) {
            linkLost(myEpoch, e)
        }
    }

    /**
     * Ağ değişikliği (2,4↔5 GHz geçişi, Wi-Fi/hotspot dalgalanması): hemen bir PING gönderir ve kısa sürede
     * yanıt gelmezse 2,5 sn beklemeden kesintisiz RESUME başlatır.
     */
    fun onNetworkChanged() {
        if (ended.get() || linkState != LinkState.CONNECTED) return
        val probeAt = MonotonicClock.nowUs()
        val myEpoch = epoch.get()
        scheduler.execute { ping() }
        scheduler.schedule(
            {
                if (!ended.get() && linkState == LinkState.CONNECTED && epoch.get() == myEpoch && lastRxUs < probeAt) {
                    linkLost(myEpoch, IOException("ağ değişimi sonrası yanıt yok"))
                }
            },
            NETWORK_PROBE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun checkLiveness() {
        val timeoutMs = if (source == null) config.preVideoLivenessTimeoutMs else config.livenessTimeoutMs
        if (linkState == LinkState.CONNECTED &&
            MonotonicClock.nowUs() - lastRxUs > timeoutMs * 1000
        ) {
            linkLost(epoch.get(), IOException("tahtadan $timeoutMs ms yanıt yok"))
        }
    }

    /** Bağlantı koptu: eski kanalları kapat ve RESUME denemelerine başla (tek sefer, dönem başına). */
    private fun linkLost(lostEpoch: Int, cause: Throwable?) {
        if (ended.get()) return
        synchronized(this) {
            if (epoch.get() != lostEpoch || linkState != LinkState.CONNECTED) return
            epoch.incrementAndGet()
            linkState = LinkState.RECONNECTING
        }
        val old = session
        video?.close()
        video = null
        audio?.close()
        audio = null
        old.control.close()
        onLink(LinkState.RECONNECTING)
        Thread({ reconnectLoop(old, cause) }, "mirror-reconnect").apply { isDaemon = true; start() }
    }

    private fun reconnectLoop(old: Session, cause: Throwable?) {
        val deadline = MonotonicClock.nowUs() + config.resumeWindowMs * 1000
        var backoffMs = 250L
        while (!ended.get() && MonotonicClock.nowUs() < deadline) {
            try {
                when (val r = SenderClient.resume(old)) {
                    is HelloOutcome.Rejected -> {
                        end(SessionEnd.ConnectionLost(cause, sessionExpired = r.reason == RejectReason.SESSION_UNKNOWN))
                        return
                    }
                    is HelloOutcome.Accepted -> {
                        if (ended.get()) {
                            r.session.close()
                            return
                        }
                        onResumed(r.session)
                        return
                    }
                }
            } catch (e: IOException) {
                // Ağ henüz geri gelmedi.
            } catch (e: ProtocolException) {
                end(SessionEnd.ConnectionLost(e))
                return
            }
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(1000)
        }
        end(SessionEnd.ConnectionLost(cause))
    }

    private fun onResumed(newSession: Session) {
        session = newSession
        lastRxUs = MonotonicClock.nowUs()
        val myEpoch = epoch.get()
        startReader(myEpoch, newSession.control)
        if (source != null) {
            try {
                val buf = sendBufferFor(bitrate?.appliedBps ?: plan?.startBps ?: 0)
                val channel = SenderClient.attach(newSession, StreamKind.VIDEO, sendBufferBytes = buf)
                channel.setSendBufferBytes(buf)
                plan?.let { p -> lastConfig?.let { cfg -> channel.send(FrameFlags.CONFIG, MonotonicClock.nowUs(), withCodec(p, cfg)) } }
                plan?.let { sendStreamConfig(it) }
                video = channel
            } catch (e: IOException) {
                synchronized(this) { linkState = LinkState.CONNECTED }
                linkLost(myEpoch, e)
                return
            }
        }
        audioSource?.let { src ->
            try {
                attachAudio(newSession, src)
            } catch (e: IOException) {
                // Ses yeniden bağlanamazsa görüntü sürer.
                onAudioIssue(e)
            }
        }
        // Kopukluk sırasında biriken kareler bayattır: atılır, ilk kare yeni IDR olur.
        queue.dropUntilKeyframe()
        synchronized(this) { linkState = LinkState.CONNECTED }
        resumeCount.incrementAndGet()
        for (i in 1..2) scheduler.schedule(::ping, i * FAST_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        if (paused) trySend { newSession.control.send(ControlMessage.Pause) } else forceKeyframe()
        onLink(LinkState.CONNECTED)
    }

    // --- Gönderim ---

    private fun sendLoop() {
        while (!ended.get()) {
            val channel = video
            if (channel == null) {
                // Yeniden bağlanılıyor: kuyruk dolup P-kareleri atar, IDR bekler.
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val drainUntil = drainUntilUs
            if (drainUntil != 0L) {
                if (MonotonicClock.nowUs() < drainUntil) {
                    // Kuyruk dolup P-kareleri atar; boşaltma bitince yeni IDR ile devam.
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        return
                    }
                    continue
                }
                drainUntilUs = 0L
                queue.dropUntilKeyframe()
                forceKeyframe()
            }
            val (frame, discontinuity) = queue.take(100) ?: continue
            if (frame.isConfig) lastConfig = frame.data
            var flags = frame.flags
            if (discontinuity) flags = flags or FrameFlags.DISCONTINUITY
            val payload = if (frame.isConfig) withCodec(plan!!, frame.data) else frame.data
            if (frame.isKeyframe) keyframeGate.onWriteStarted(MonotonicClock.nowUs())
            try {
                channel.send(flags, frame.ptsUs, payload)
                if (!frame.isConfig) framesSent.incrementAndGet()
                bitrate?.onSenderQueueDelay(queue.oldestAgeUs(MonotonicClock.nowUs()))
            } catch (e: IOException) {
                linkLost(epoch.get(), e)
            }
            if (frame.isKeyframe && keyframeGate.onWriteEnded()) forceKeyframe()
        }
    }

    private fun captureReady(): Boolean {
        val now = MonotonicClock.nowUs()
        val sending = video != null && linkState == LinkState.CONNECTED && drainUntilUs == 0L && !paused
        return captureGate.ready(now, queue.depth(), queue.oldestAgeUs(now), sending)
    }

    private fun withCodec(p: StreamPlan, config: ByteArray) = byteArrayOf(p.codec.toByte()) + config

    private fun sendStreamConfig(p: StreamPlan) {
        session.control.send(
            ControlMessage.StreamConfig(
                streamKind = StreamKind.VIDEO,
                codec = p.codec,
                width = p.width,
                height = p.height,
                fps = ramp?.fps ?: p.fps,
                bitrateBps = bitrate?.appliedBps ?: p.startBps,
            ),
        )
    }

    private fun checkLatencyBudget(report: ControlMessage.ReceiverReport) {
        val now = MonotonicClock.nowUs()
        // A still screen (a few redraws a second) has nothing queued to drain; one late redraw is not a backlog.
        if (report.framesReceived < BitrateController.IDLE_REPORT_FRAMES) {
            consecutiveHighDelay = 0
            return
        }
        if (report.c2rAvgUs <= config.latencyBudgetUs) {
            consecutiveHighDelay = 0
            return
        }
        if (now < drainCooldownUntilUs) return
        consecutiveHighDelay += 1
        // Always step down bitrate immediately instead of waiting for a hard freeze.
        bitrate?.onSenderDrop()
        // Require either 2 consecutive over-budget reports or a severe spike (> 2× budget) before draining,
        // and cap the drain at 250 ms so hotspot hiccups lower quality smoothly instead of freezing for 1 second.
        if (consecutiveHighDelay < 2 && report.c2rAvgUs < config.latencyBudgetUs * 2) return
        consecutiveHighDelay = 0
        val drainUs = ((report.c2rAvgUs - config.latencyBudgetUs / 2) / 2).coerceIn(50_000, MAX_DRAIN_US)
        drainUntilUs = now + drainUs
        drainCooldownUntilUs = now + drainUs + 1_000_000
        drainCount.incrementAndGet()
        queue.dropUntilKeyframe()
    }

    private fun onQueueDrop() {
        bitrate?.onSenderDrop()
        requestKeyframe()
    }

    /** IDR isteklerini birleştirir (PROTOCOL.md §7.2: 200 ms'den sık istekler tek IDR). */
    private fun requestKeyframe() {
        val now = MonotonicClock.nowUs()
        if (!keyframeGate.onRequest(now)) return
        val last = lastKeyframeRequestUs.get()
        if (now - last < KEYFRAME_COALESCE_US || !lastKeyframeRequestUs.compareAndSet(last, now)) return
        source?.requestKeyframe()
    }

    private fun forceKeyframe() {
        lastKeyframeRequestUs.set(MonotonicClock.nowUs())
        source?.requestKeyframe()
    }

    private fun ping() {
        if (linkState != LinkState.CONNECTED) return
        trySend {
            session.control.send(ControlMessage.Ping(pingId.getAndIncrement() and 0xFFFFFFFFL, MonotonicClock.nowUs(), clock.offsetUs))
        }
    }

    /** CONTROL gönderimi; hata canlılık denetimine/okuyucuya bırakılır (RESUME onlar üzerinden başlar). */
    private inline fun trySend(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
        }
    }

    /** For calls that can come from the UI thread, where Android forbids socket writes. */
    private fun sendOffThread(block: () -> Unit) = runOffThread(CONTROL_WRITE_WAIT_MS) { trySend(block) }

    private fun bitrateTick() {
        val b = bitrate ?: return
        val nowMs = MonotonicClock.nowUs() / 1000
        b.tick(nowMs)?.let { bps ->
            source?.setBitrate(bps)
            video?.setSendBufferBytes(sendBufferFor(bps))
        }
        rampTick(nowMs, b)
        qualityTick(nowMs, b)
    }

    private fun rampTick(nowMs: Long, b: BitrateController) {
        val r = ramp ?: return
        val p = plan ?: return
        val fps = r.tick(nowMs, b.appliedBps, p.startBps) ?: return
        if (ramp !== r) return // a quality step came in between
        ramp = null
        source?.setFrameRate(fps)
        if (linkState == LinkState.CONNECTED) trySend { sendStreamConfig(p) }
    }

    private fun qualityTick(nowMs: Long, b: BitrateController) {
        val p = plan ?: return
        if (paused || linkState != LinkState.CONNECTED) {
            quality.clearTimers()
            return
        }
        val step = quality.tick(nowMs, b.appliedBps, p.minBps, p.maxBps) ?: return
        // Restarting the encoder takes a while; the bitrate tick must keep its pace.
        scheduler.execute { applyQualityStep(step) }
    }

    private fun applyQualityStep(step: QualityStep) {
        val changed = try {
            !ended.get() && qualityStep(step)
        } catch (_: RuntimeException) {
            false
        }
        if (!changed) {
            quality.onRejected()
            return
        }
        quality.onSwitched(MonotonicClock.nowUs() / 1000, step)
        if (step == QualityStep.DOWN) qualityStepsDown.incrementAndGet() else qualityStepsDown.decrementAndGet()
    }

    private fun statsTick() {
        val p = plan ?: return
        val now = MonotonicClock.nowUs()
        val frames = framesSent.get()
        val deltaFrames = (frames - statsFramesAtLastTick).coerceAtLeast(0)
        val fps = deltaFrames * 1e6 / maxOf(1, now - statsLastTickUs)
        statsFramesAtLastTick = frames
        val droppedTotal = queue.droppedTotal
        val deltaDropped = (droppedTotal - statsDroppedAtLastTick).coerceAtLeast(0)
        statsDroppedAtLastTick = droppedTotal
        statsLastTickUs = now
        val applied = bitrate?.appliedBps ?: p.startBps
        if (linkState == LinkState.CONNECTED) {
            trySend {
                session.control.send(ControlMessage.SenderStats(applied, (fps * 100).toInt().coerceIn(0, 0xFFFF),
                    droppedTotal, p.encoderName.take(64)))
            }
        }
        val report = lastReport
        val latencyMs = report?.takeIf { it.c2dAvgUs > 0 }?.let { (it.c2dAvgUs / 1000).toInt() }
        val rttMs = clock.rttUs?.let { (it / 1000).toInt() }
        val senderLossPct = if (deltaFrames + deltaDropped > 0) deltaDropped * 100.0 / (deltaFrames + deltaDropped) else 0.0
        val receiverLossPct = report?.takeIf { it.framesReceived >= BitrateController.IDLE_REPORT_FRAMES }
            ?.let { it.framesDropped * 100.0 / it.framesReceived } ?: 0.0
        val lossPct = maxOf(senderLossPct, receiverLossPct).coerceIn(0.0, 100.0)
        val lowered = qualityStepsDown.get() > 0
        val qualityLevel = classifyQuality(latencyMs, rttMs, lossPct, lowered)
        onStats(
            MirrorStats(
                latencyMs = latencyMs,
                fps = fps,
                bitrateBps = applied,
                droppedFrames = droppedTotal,
                width = p.width,
                height = p.height,
                autoLowered = lowered,
                rttMs = rttMs,
                lossPercent = lossPct,
                targetFps = ramp?.fps ?: p.fps,
                quality = qualityLevel,
            ),
        )
    }

    private fun end(reason: SessionEnd) {
        if (!ended.compareAndSet(false, true)) return
        val bye = when (reason) {
            is SessionEnd.Local -> reason.byeReason
            is SessionEnd.EncoderFailed -> ByeReason.ERROR
            else -> null
        }
        if (bye != null && linkState == LinkState.CONNECTED) sendOffThread { session.control.send(ControlMessage.Bye(bye)) }
        scheduler.shutdownNow()
        queue.close()
        audioQueue.close()
        try {
            source?.stop()
        } catch (_: RuntimeException) {
        }
        try {
            audioSource?.stop()
        } catch (_: RuntimeException) {
        }
        // Closing a TLS socket writes close_notify, and end() can run on the UI thread, where Android forbids that.
        val channels = listOfNotNull(video, audio, session)
        runOffThread(CONTROL_WRITE_WAIT_MS) { channels.forEach(::closeQuietly) }
        audioSender?.interrupt()
        sender?.interrupt()
        onEnd(reason)
    }

    private companion object {
        /** ≈ 80 ms veri, 32–256 KiB (küçük tampon = hotspot bufferbloat'ı yerine hızlı geri basınç). */
        fun sendBufferFor(bps: Long): Int = (bps / 100).coerceIn(32L * 1024, 256L * 1024).toInt()

        fun classifyQuality(latencyMs: Int?, rttMs: Int?, lossPercent: Double, autoLowered: Boolean): ConnectionQuality = when {
            (latencyMs != null && latencyMs > 250) || (rttMs != null && rttMs > 180) || lossPercent >= 8.0 -> ConnectionQuality.POOR
            autoLowered && ((latencyMs != null && latencyMs > 160) || (rttMs != null && rttMs > 100) || lossPercent >= 3.0) ->
                ConnectionQuality.POOR
            autoLowered || (latencyMs != null && latencyMs > 120) || (rttMs != null && rttMs > 70) || lossPercent >= 2.0 ->
                ConnectionQuality.FAIR
            else -> ConnectionQuality.GOOD
        }

        const val BITRATE_TICK_MS = 250L
        const val CONTROL_WRITE_WAIT_MS = 300L
        const val FAST_PINGS = 4
        const val FAST_PING_INTERVAL_MS = 50L
        const val STATS_INTERVAL_MS = 1000L
        const val KEYFRAME_COALESCE_US = 200_000L
        const val MAX_DRAIN_US = 250_000L
        const val ATTACH_WAIT_RECONNECT_MS = 3000L
        const val NETWORK_PROBE_TIMEOUT_MS = 750L
    }
}

/** Runs [block] on its own thread and waits at most [waitMs], so a stalled socket cannot freeze the caller. */
/** The link is going away anyway, so one failed close must not stop the rest. */
internal fun closeQuietly(channel: AutoCloseable) {
    try {
        channel.close()
    } catch (_: Exception) {
    }
}

internal fun runOffThread(waitMs: Long, block: () -> Unit) {
    val done = CountDownLatch(1)
    Thread({
        try {
            block()
        } finally {
            done.countDown()
        }
    }, "mirror-control").apply { isDaemon = true }.start()
    done.await(waitMs, TimeUnit.MILLISECONDS)
}
