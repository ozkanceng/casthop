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
    /** Quality is below the chosen preset because the network cannot carry it. */
    val autoLowered: Boolean = false,
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
    private val pingId = AtomicLong()
    private val framesSent = AtomicLong()
    private val lastKeyframeRequestUs = AtomicLong()
    private val encoderRestarts = AtomicInteger()
    /** Bu ana kadar gönderim yapılmaz (yolu boşaltma); 0 = yok. */
    @Volatile private var drainUntilUs = 0L
    @Volatile private var drainCooldownUntilUs = 0L
    /** Yol boşaltma sayısı (test ve tanılama). */
    val drainCount: AtomicInteger = AtomicInteger()
    private var statsFramesAtLastTick = 0L
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
        video = SenderClient.attach(session, StreamKind.VIDEO).also { it.setSendBufferBytes(sendBufferFor(plan.startBps)) }
        sendStreamConfig(plan)
        sender = Thread(::sendLoop, "mirror-video").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        statsLastTickUs = MonotonicClock.nowUs()
        scheduler.scheduleWithFixedDelay(::bitrateTick, BITRATE_TICK_MS, BITRATE_TICK_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(::statsTick, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        startSource(plan)
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
        val channel = SenderClient.attach(s, StreamKind.AUDIO)
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
     * Kaynak boyutu değişti (döndürme, paylaşılan uygulama penceresi): kodlayıcı yeni ayarla yeniden başlar,
     * yeni CONFIG + IDR gönderilir. Çağıran VirtualDisplay'i önceden yeniden boyutlandırmalıdır.
     */
    fun reconfigure(newPlan: StreamPlan) {
        val src = source ?: return
        if (ended.get()) return
        src.stop()
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
                    is ControlMessage.Pong -> clock.onPong(msg.t0Us, msg.t1Us, msg.t2Us, MonotonicClock.nowUs())
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

    private fun checkLiveness() {
        if (linkState == LinkState.CONNECTED &&
            MonotonicClock.nowUs() - lastRxUs > config.livenessTimeoutMs * 1000
        ) {
            linkLost(epoch.get(), IOException("tahtadan ${config.livenessTimeoutMs} ms yanıt yok"))
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
                val channel = SenderClient.attach(newSession, StreamKind.VIDEO)
                channel.setSendBufferBytes(sendBufferFor(bitrate?.appliedBps ?: plan?.startBps ?: 0))
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

    private fun withCodec(p: StreamPlan, config: ByteArray) = byteArrayOf(p.codec.toByte()) + config

    private fun sendStreamConfig(p: StreamPlan) {
        session.control.send(
            ControlMessage.StreamConfig(
                streamKind = StreamKind.VIDEO,
                codec = p.codec,
                width = p.width,
                height = p.height,
                fps = p.fps,
                bitrateBps = bitrate?.appliedBps ?: p.startBps,
            ),
        )
    }

    private fun checkLatencyBudget(report: ControlMessage.ReceiverReport) {
        val now = MonotonicClock.nowUs()
        if (report.framesReceived == 0L || report.c2rAvgUs <= config.latencyBudgetUs || now < drainCooldownUntilUs) return
        // Yoldaki fazla veri (tampon) boşalana kadar bekle: bütçenin üstündeki kısım kadar, en fazla 1 sn.
        val drainUs = (report.c2rAvgUs - config.latencyBudgetUs / 3).coerceIn(50_000, 1_000_000)
        drainUntilUs = now + drainUs
        // Boşaltma sırasında gelen raporlar eski kareleri anlatır; bir rapor aralığı daha bekle.
        drainCooldownUntilUs = now + drainUs + 600_000
        drainCount.incrementAndGet()
        queue.dropUntilKeyframe()
        bitrate?.onSenderDrop()
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
        qualityTick(nowMs, b)
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
        val fps = (frames - statsFramesAtLastTick) * 1e6 / maxOf(1, now - statsLastTickUs)
        statsFramesAtLastTick = frames
        statsLastTickUs = now
        val applied = bitrate?.appliedBps ?: p.startBps
        if (linkState == LinkState.CONNECTED) {
            trySend {
                session.control.send(ControlMessage.SenderStats(applied, (fps * 100).toInt().coerceIn(0, 0xFFFF),
                    queue.droppedTotal, p.encoderName.take(64)))
            }
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
                autoLowered = qualityStepsDown.get() > 0,
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
        /** ≈ 100 ms veri, 32–512 KiB. */
        fun sendBufferFor(bps: Long): Int = (bps / 8 / 10).coerceIn(32L * 1024, 512L * 1024).toInt()

        const val BITRATE_TICK_MS = 250L
        const val CONTROL_WRITE_WAIT_MS = 300L
        const val FAST_PINGS = 4
        const val FAST_PING_INTERVAL_MS = 50L
        const val STATS_INTERVAL_MS = 1000L
        const val KEYFRAME_COALESCE_US = 200_000L
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
