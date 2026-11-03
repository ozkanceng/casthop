package io.github.ozkanceng.yansit.tools.fakesender

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ClockSync
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.QrPayload
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.StreamKind
import io.github.ozkanceng.yansit.protocol.client.Credentials
import io.github.ozkanceng.yansit.protocol.client.DeviceInfo
import io.github.ozkanceng.yansit.protocol.client.Endpoint
import io.github.ozkanceng.yansit.protocol.client.HelloOutcome
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.protocol.client.SenderClient
import io.github.ozkanceng.yansit.protocol.client.Session
import io.github.ozkanceng.yansit.sender.LinkState
import io.github.ozkanceng.yansit.sender.MirrorSession
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.sender.SessionEnd
import io.github.ozkanceng.yansit.sender.StreamPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

private const val USAGE = """fake-sender — Yansıt test göndericisi

  --pairing-file F    alıcının --pairing-file çıktısı (uri + pin)
  --uri U             QR içeriği (yansit://...)
  --host IP           URI'deki adres yerine bu adres
  --pin NNNNNN        tahtadaki 6 haneli kod (--auth pin için; pairing dosyasındakinin yerine)
  --auth M            qr | pin | bad-token | bad-pin | paired   (varsayılan qr)
  --pairing-key F     paired: kayıtlı eşleştirme (JSON); --request-pairing ile WELCOME'daki anahtar buraya yazılır
  --input F           H.264/H.265 Annex-B dosyası (akış için)
  --codec C           h264 | h265  (varsayılan h264)
  --report-csv F      (mirror) saniyede bir: süre, gecikme (alıcı c2d), fps, bit hızı, atılan kare
  --pattern-base US   (raw) dosyadaki gecikme deseninin ilk damgası; saat ofseti buna göre kaydırılır
  --fps N             gönderim hızı (varsayılan 60)
  --frames N          gönderilecek kare (0 = dosyanın tamamı; dosya kısaysa başa sarar)
  --device-name S     HELLO cihaz adı
  --device-id HEX     16 bayt cihaz kimliği (varsayılan rastgele)
  --request-pairing   kalıcı eşleştirme iste
  --expect E          stream | reject:<NEDEN>   (varsayılan stream)
  --expect-pending    tahta onayı beklemesi (PENDING_APPROVAL) zorunlu
  --hold-ms N         son kareden sonra BYE'dan önce bekleme (varsayılan 1500)
  --result F          sonuç özeti (JSON)
  --audio-input F     (mirror) Ogg/Opus veya ADTS/AAC ses dosyası; tahta kabul ederse ses de gönderilir
  --port N            URI'deki port yerine bu port (ör. ağ benzetim vekili)
  --rotate-input F    (mirror) --rotate-after kareden sonra bu dosyaya geç ve kodlayıcıyı yeniden yapılandır
  --rotate-after N    (mirror) döndürme benzetiminin karesi
  --rotate-size WxH   (mirror) döndürülmüş akışın boyutu (varsayılan 720x1280)
  --engine E          raw | mirror  (varsayılan raw). mirror: Android uygulamasının gönderici çekirdeği
                      (MirrorSession: kuyruk, bit hızı uyarlaması, IDR istekleri) dosya kaynağıyla

Çıkış kodu: 0 beklenti tuttu, 1 tutmadı, 2 kullanım hatası, 3 bağlantı hatası."""

private data class Options(
    val uri: String,
    val pin: String?,
    val host: String?,
    val auth: String,
    val input: File?,
    val fps: Int,
    val frames: Int,
    val deviceName: String,
    val deviceId: Bytes,
    val requestPairing: Boolean,
    val expect: String,
    val expectPending: Boolean,
    val holdMs: Long,
    val result: File?,
    val engine: String,
    val pairingKey: File?,
    val audioInput: File?,
    val codec: Int,
    val patternBaseUs: Long?,
    val reportCsv: File?,
    val port: Int?,
    val rotateInput: File?,
    val rotateAfter: Int,
    val rotateSize: Pair<Int, Int>,
)

private fun parseVideo(o: Options, f: File): H264Stream =
    if (o.codec == Codec.H265) AnnexB.parseHevc(f.readBytes()) else AnnexB.parse(f.readBytes())

private fun usage(msg: String): Nothing {
    System.err.println("hata: $msg\n\n$USAGE")
    exitProcess(2)
}

private fun parseArgs(args: Array<String>): Options {
    val m = HashMap<String, String>()
    val flags = HashSet<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when (a) {
            "--request-pairing", "--expect-pending" -> flags.add(a)
            "-h", "--help" -> {
                println(USAGE); exitProcess(0)
            }
            else -> {
                if (!a.startsWith("--") || i + 1 >= args.size) usage("geçersiz argüman: $a")
                m[a] = args[++i]
            }
        }
        i++
    }
    var uri = m["--uri"]
    var pin: String? = null
    m["--pairing-file"]?.let { path ->
        val o = Json.parseToJsonElement(File(path).readText()).jsonObject
        uri = uri ?: o["uri"]?.jsonPrimitive?.content
        pin = o["pin"]?.jsonPrimitive?.content
    }
    m["--pin"]?.let { pin = it }
    if (uri.isNullOrEmpty()) usage("--uri veya --pairing-file gerekli")
    val expect = m["--expect"] ?: "stream"
    if (expect != "stream" && !expect.startsWith("reject:")) usage("--expect: stream | reject:<NEDEN>")
    val input = m["--input"]?.let(::File)
    if (expect == "stream" && (input == null || !input.isFile)) usage("akış için --input dosyası gerekli")
    return Options(
        uri = uri!!,
        pin = pin,
        host = m["--host"],
        auth = m["--auth"] ?: "qr",
        input = input,
        fps = m["--fps"]?.toIntOrNull()?.takeIf { it in 1..240 } ?: if ("--fps" in m) usage("--fps") else 60,
        frames = m["--frames"]?.toIntOrNull()?.takeIf { it >= 0 } ?: if ("--frames" in m) usage("--frames") else 0,
        deviceName = m["--device-name"] ?: "fake-sender",
        deviceId = m["--device-id"]?.let(Bytes::fromHex)?.takeIf { it.size == 16 } ?: Auth.randomBytes(16),
        requestPairing = "--request-pairing" in flags,
        expect = expect,
        expectPending = "--expect-pending" in flags,
        holdMs = m["--hold-ms"]?.toLongOrNull() ?: 1500,
        result = m["--result"]?.let(::File),
        engine = (m["--engine"] ?: "raw").also { if (it != "raw" && it != "mirror") usage("--engine: raw | mirror") },
        pairingKey = m["--pairing-key"]?.let(::File),
        audioInput = m["--audio-input"]?.let(::File),
        patternBaseUs = m["--pattern-base"]?.toLongOrNull(),
        reportCsv = m["--report-csv"]?.let(::File),
        codec = when (m["--codec"] ?: "h264") {
            "h264" -> Codec.H264
            "h265" -> Codec.H265
            else -> usage("--codec: h264 | h265")
        },
        port = m["--port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: if ("--port" in m) usage("--port") else null,
        rotateInput = m["--rotate-input"]?.let(::File),
        rotateAfter = m["--rotate-after"]?.toIntOrNull() ?: 0,
        rotateSize = (m["--rotate-size"] ?: "720x1280").split('x').let { parts ->
            val w = parts.getOrNull(0)?.toIntOrNull()
            val h = parts.getOrNull(1)?.toIntOrNull()
            if (w == null || h == null) usage("--rotate-size WxH") else w to h
        },
    )
}

private val rejectNames: Map<Int, String> = RejectReason::class.java.declaredFields
    .filter { it.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(it.modifiers) }
    .associate { it.getInt(null) to it.name }

private fun credentials(o: Options, qr: QrPayload): Credentials = when (o.auth) {
    "qr" -> Credentials.QrToken(qr.token)
    "bad-token" -> Credentials.QrToken(Auth.randomBytes(16))
    "pin" -> Credentials.Pin(o.pin?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: usage("--auth pin: --pin NNNNNN gerekli"))
    "bad-pin" -> Credentials.Pin(if (o.pin == "000000") "000001" else "000000")
    "paired" -> {
        val f = o.pairingKey ?: usage("--auth paired için --pairing-key gerekli")
        val k = Json.parseToJsonElement(f.readText()).jsonObject
        Credentials.Paired(Bytes.fromHex(k["key_id"]!!.jsonPrimitive.content), Bytes.fromHex(k["pair_key"]!!.jsonPrimitive.content))
    }
    else -> usage("--auth: qr | pin | bad-token | bad-pin")
}

/** Akış süresince toplanan sonuçlar. */
private class StreamResult {
    val framesSent = AtomicInteger()
    val keyframeRequests = AtomicInteger()
    val reports = AtomicInteger()
    val lastC2dUs = AtomicLong(-1)
    val maxC2dUs = AtomicLong(0)
    val maxBacklog = AtomicInteger()
    val receiverFramesRendered = AtomicLong()
    val receiverFramesDropped = AtomicLong()
    @Volatile var byeReason: Int? = null
    @Volatile var offsetUs: Long? = null
    @Volatile var rttUs: Long? = null
}

private fun stream(o: Options, session: Session, result: StreamResult) {
    val h264 = parseVideo(o, o.input!!)
    val total = if (o.frames == 0) h264.units.size else o.frames
    val control = session.control
    val stopped = AtomicBoolean(false)
    val skipToKeyframe = AtomicBoolean(false)
    val clock = ClockSync()
    // Desen damgaları (BASE + i·aralık) gönderim anlarına (başlangıç + i·aralık) hizalansın diye bildirilen ofset kaydırılır.
    val patternShift = AtomicLong(0)

    val reader = Thread({
        try {
            while (true) {
                when (val msg = control.receive() ?: break) {
                    is ControlMessage.Pong -> {
                        clock.onPong(msg.t0Us, msg.t1Us, msg.t2Us, MonotonicClock.nowUs())
                        result.offsetUs = clock.offsetUs
                        result.rttUs = clock.rttUs
                    }
                    is ControlMessage.KeyframeRequest -> {
                        result.keyframeRequests.incrementAndGet()
                        skipToKeyframe.set(true)
                    }
                    is ControlMessage.ReceiverReport -> {
                        result.reports.incrementAndGet()
                        result.receiverFramesRendered.addAndGet(msg.framesRendered)
                        result.receiverFramesDropped.addAndGet(msg.framesDropped)
                        if (msg.framesRendered > 0 && msg.c2dAvgUs > 0) {
                            result.lastC2dUs.set(msg.c2dAvgUs)
                            result.maxC2dUs.accumulateAndGet(msg.c2dAvgUs, ::maxOf)
                        }
                        result.maxBacklog.accumulateAndGet(msg.decodeBacklog, ::maxOf)
                    }
                    is ControlMessage.Bye -> {
                        result.byeReason = msg.reason
                        break
                    }
                    else -> {}
                }
            }
        } catch (_: IOException) {
        } finally {
            stopped.set(true)
        }
    }, "control-reader").apply { isDaemon = true; start() }

    val pinger = Thread({
        var id = 0L
        try {
            while (!stopped.get()) {
                control.send(ControlMessage.Ping(id++, MonotonicClock.nowUs(), clock.offsetUs?.let { it + patternShift.get() }))
                Thread.sleep(if (id < 4) 100 else 1000) // ilk ofset hızlı oturur
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }, "pinger").apply { isDaemon = true; start() }

    val video = SenderClient.attach(session, StreamKind.VIDEO)
    control.send(ControlMessage.StreamConfig(StreamKind.VIDEO, o.codec, fps = o.fps))
    video.send(FrameFlags.CONFIG, MonotonicClock.nowUs(), byteArrayOf(o.codec.toByte()) + h264.config)

    val intervalNs = 1_000_000_000L / o.fps
    val start = System.nanoTime()
    o.patternBaseUs?.let { patternShift.set(start / 1000 - it) }
    var discontinuity = false
    for (i in 0 until total) {
        if (stopped.get()) break
        val target = start + i * intervalNs
        while (true) {
            val wait = target - System.nanoTime()
            if (wait <= 0) break
            LockSupport.parkNanos(wait)
        }
        val au = h264.units[i % h264.units.size]
        if (skipToKeyframe.get()) {
            if (!au.keyframe) {
                discontinuity = true
                continue
            }
            skipToKeyframe.set(false)
        }
        var flags = if (au.keyframe) FrameFlags.KEYFRAME else 0
        if (discontinuity) flags = flags or FrameFlags.DISCONTINUITY
        discontinuity = false
        video.send(flags, MonotonicClock.nowUs(), au.data)
        result.framesSent.incrementAndGet()
    }

    Thread.sleep(o.holdMs)
    if (!stopped.get()) control.send(ControlMessage.Bye(ByeReason.USER_STOP))
    pinger.interrupt()
    video.close()
    session.close()
    reader.join(2000)
}

/** Uygulamanın gönderici çekirdeğiyle (MirrorSession) akış. Oturum düzgün biterse true. */
private fun streamWithMirrorSession(
    o: Options,
    session: Session,
    summary: MutableMap<String, kotlinx.serialization.json.JsonElement>,
): Boolean {
    val main = parseVideo(o, o.input!!)
    val rotated = o.rotateInput?.let { parseVideo(o, it) }
    val total = if (o.frames == 0) main.units.size else o.frames
    val ended = CountDownLatch(1)
    val endReason = AtomicReference<SessionEnd?>()
    val lastStats = AtomicReference<MirrorStats?>()
    val latencySamples = java.util.Collections.synchronizedList(ArrayList<Int>())
    val csv = o.reportCsv?.printWriter()?.also { it.println("t_s,latency_ms,fps,bitrate_bps,sender_dropped,resumes") }
    val csvStart = MonotonicClock.nowUs()
    val linkEvents = java.util.Collections.synchronizedList(ArrayList<Pair<LinkState, Long>>())
    val planA = StreamPlan("file", o.codec, false, 1280, 720, o.fps, 8_000_000, 2_000_000, 12_000_000)
    val planB = planA.copy(width = o.rotateSize.first, height = o.rotateSize.second)
    lateinit var mirror: MirrorSession
    val reconfigured = AtomicBoolean(false)
    val source = FileVideoSource(
        select = { plan -> if (plan == planB && rotated != null) rotated else main },
        fps = o.fps,
        frames = total,
        onFrame = { index ->
            if (rotated != null && index == o.rotateAfter && reconfigured.compareAndSet(false, true)) {
                // Döndürme benzetimi: kodlayıcı yeni boyutla yeniden başlar (Android'de VirtualDisplay.resize sonrası).
                Thread { mirror.reconfigure(planB) }.start()
            }
        },
        onFinished = {
            Thread.sleep(o.holdMs)
            mirror.stop(ByeReason.USER_STOP)
        },
    )
    mirror = MirrorSession(
        session,
        onStats = { st ->
            lastStats.set(st)
            st.latencyMs?.let(latencySamples::add)
            csv?.let { w ->
                synchronized(w) {
                    w.println("%.1f,%s,%.1f,%d,%d,%d".format(java.util.Locale.ROOT, (MonotonicClock.nowUs() - csvStart) / 1e6,
                        st.latencyMs?.toString() ?: "", st.fps, st.bitrateBps, st.droppedFrames, mirror.resumeCount.get()))
                    w.flush()
                }
            }
        },
        onEnd = { endReason.set(it); ended.countDown() },
        onLink = { state ->
            linkEvents.add(state to MonotonicClock.nowUs())
            println("bağlantı: $state")
        },
    )
    mirror.start()
    val audioSource = o.audioInput?.let { FileAudioSource(AudioFiles.parse(it.readBytes())) }
    try {
        mirror.startVideo(planA, source)
        audioSource?.let { mirror.startAudio(it) }
    } catch (e: IOException) {
        System.err.println("medya bağlantısı: $e")
        mirror.stop(ByeReason.ERROR)
        return false
    }
    val finished = ended.await(total * 1000L / o.fps + o.holdMs + 30_000, TimeUnit.MILLISECONDS)
    csv?.close()
    val end = endReason.get()
    summary["engine"] = JsonPrimitive("mirror")
    summary["frames_sent"] = JsonPrimitive(source.framesProduced.get())
    summary["keyframe_requests"] = JsonPrimitive(source.keyframeRequests.get())
    summary["bitrate_changes"] = JsonPrimitive(source.bitrateChanges.get())
    summary["final_bitrate_bps"] = JsonPrimitive(source.lastBitrate.get())
    summary["restarts"] = JsonPrimitive(source.starts.get() - 1)
    summary["resume_count"] = JsonPrimitive(mirror.resumeCount.get())
    summary["drain_count"] = JsonPrimitive(mirror.drainCount.get())
    audioSource?.let {
        summary["audio_packets_sent"] = JsonPrimitive(it.packetsProduced.get())
        summary["audio_dropped"] = JsonPrimitive(mirror.audioDropped)
    }
    // Kopma → yeniden bağlanma süreleri (ms).
    val outages = synchronized(linkEvents) {
        linkEvents.zipWithNext().filter { (a, b) -> a.first == LinkState.RECONNECTING && b.first == LinkState.CONNECTED }
            .map { (a, b) -> (b.second - a.second) / 1000 }
    }
    summary["reconnect_ms"] = kotlinx.serialization.json.JsonArray(outages.map { JsonPrimitive(it) })
    summary["end"] = JsonPrimitive(end?.toString() ?: "timeout")
    summary["c2d_samples_ms"] = kotlinx.serialization.json.JsonArray(synchronized(latencySamples) {
        latencySamples.map { JsonPrimitive(it) }
    })
    lastStats.get()?.let { st ->
        st.latencyMs?.let { summary["last_c2d_ms"] = JsonPrimitive(it) }
        summary["sender_dropped"] = JsonPrimitive(st.droppedFrames)
    }
    println("MirrorSession: ${source.framesProduced.get()} kare, RESUME ${mirror.resumeCount.get()}, son: $end")
    return finished && end == SessionEnd.Local(ByeReason.USER_STOP)
}

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    // Eşleştirme cihaza bağlıdır: kayıtlı anahtarla bağlanırken aynı device_id kullanılır.
    val o = if (parsed.auth == "paired" && parsed.pairingKey?.isFile == true) {
        val k = Json.parseToJsonElement(parsed.pairingKey.readText()).jsonObject
        parsed.copy(deviceId = Bytes.fromHex(k["device_id"]!!.jsonPrimitive.content))
    } else {
        parsed
    }
    val qr = QrPayload.parse(o.uri)
    val endpoint = Endpoint(o.host ?: qr.hosts.first(), o.port ?: qr.port, qr.certFingerprint)
    val device = DeviceInfo(
        name = o.deviceName,
        deviceId = o.deviceId,
        appVersion = "fake-sender",
        videoCodecs = listOf(o.codec),
        maxWidth = 1920,
        maxHeight = 1080,
        maxFps = 60,
    )

    var pendingSeen = false
    val outcome = try {
        SenderClient.hello(endpoint, credentials(o, qr), device, o.requestPairing) {
            pendingSeen = true
            println("tahtada onay bekleniyor (${it} ms)")
        }
    } catch (e: IOException) {
        System.err.println("bağlantı hatası: $e")
        exitProcess(3)
    }

    val streamResult = StreamResult()
    val summary = buildJsonObject {
        put("pending_seen", pendingSeen)
        when (outcome) {
            is HelloOutcome.Rejected -> {
                put("outcome", "rejected")
                put("reject_reason", rejectNames[outcome.reason] ?: outcome.reason.toString())
                outcome.retryAfterMs?.let { put("retry_after_ms", it) }
            }
            is HelloOutcome.Accepted -> {
                put("outcome", "welcome")
                val w = outcome.session.welcome
                put("paired", w.pairKey != null)
                val keyFile = o.pairingKey
                if (o.requestPairing && keyFile != null && w.pairKey != null && w.keyId != null) {
                    keyFile.writeText(
                        buildJsonObject {
                            put("key_id", w.keyId!!.toHex())
                            put("pair_key", w.pairKey!!.toHex())
                            put("device_id", o.deviceId.toHex())
                        }.toString(),
                    )
                }
            }
        }
    }.toMutableMap()

    var ok = true
    if (o.expectPending && !pendingSeen) ok = false
    when (outcome) {
        is HelloOutcome.Rejected -> {
            println("reddedildi: ${rejectNames[outcome.reason]}")
            ok = ok && o.expect == "reject:${rejectNames[outcome.reason]}"
        }
        is HelloOutcome.Accepted -> {
            println("WELCOME: ${outcome.session.welcome.receiverName}")
            if (o.expect != "stream") {
                ok = false
                outcome.session.control.send(ControlMessage.Bye(ByeReason.USER_STOP))
                outcome.session.close()
            } else {
                if (o.engine == "mirror") {
                    ok = streamWithMirrorSession(o, outcome.session, summary) && ok
                    summary["ok"] = JsonPrimitive(ok)
                    val text = kotlinx.serialization.json.JsonObject(summary).toString()
                    o.result?.writeText(text + "\n")
                    println(text)
                    exitProcess(if (ok) 0 else 1)
                }
                try {
                    stream(o, outcome.session, streamResult)
                } catch (e: IOException) {
                    System.err.println("akış hatası: $e")
                    ok = false
                }
                summary["frames_sent"] = JsonPrimitive(streamResult.framesSent.get())
                summary["keyframe_requests"] = JsonPrimitive(streamResult.keyframeRequests.get())
                summary["reports"] = JsonPrimitive(streamResult.reports.get())
                summary["receiver_frames_rendered"] = JsonPrimitive(streamResult.receiverFramesRendered.get())
                summary["receiver_frames_dropped"] = JsonPrimitive(streamResult.receiverFramesDropped.get())
                summary["last_c2d_ms"] = JsonPrimitive(streamResult.lastC2dUs.get() / 1000.0)
                summary["max_c2d_ms"] = JsonPrimitive(streamResult.maxC2dUs.get() / 1000.0)
                summary["max_backlog"] = JsonPrimitive(streamResult.maxBacklog.get())
                streamResult.offsetUs?.let { summary["clock_offset_us"] = JsonPrimitive(it) }
                streamResult.rttUs?.let { summary["rtt_us"] = JsonPrimitive(it) }
                streamResult.byeReason?.let { summary["bye_reason"] = JsonPrimitive(it) }
                println("gönderilen kare: ${streamResult.framesSent.get()}, anahtar kare isteği: " +
                    "${streamResult.keyframeRequests.get()}, rapor: ${streamResult.reports.get()}")
            }
        }
    }
    summary["ok"] = JsonPrimitive(ok)
    val text = kotlinx.serialization.json.JsonObject(summary).toString()
    o.result?.writeText(text + "\n")
    println(text)
    exitProcess(if (ok) 0 else 1)
}
