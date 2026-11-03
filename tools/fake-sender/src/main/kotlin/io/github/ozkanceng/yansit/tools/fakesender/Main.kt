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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

private const val USAGE = """fake-sender — Yansıt test göndericisi

  --pairing-file F    alıcının --pairing-file çıktısı (uri + pin)
  --uri U             QR içeriği (yansit://...)
  --host IP           URI'deki adres yerine bu adres
  --pin NNNNNN        tahtadaki 6 haneli kod (--auth pin için; pairing dosyasındakinin yerine)
  --auth M            qr | pin | bad-token | bad-pin   (varsayılan qr)
  --input F           H.264 Annex-B dosyası (akış için)
  --fps N             gönderim hızı (varsayılan 60)
  --frames N          gönderilecek kare (0 = dosyanın tamamı; dosya kısaysa başa sarar)
  --device-name S     HELLO cihaz adı
  --device-id HEX     16 bayt cihaz kimliği (varsayılan rastgele)
  --request-pairing   kalıcı eşleştirme iste
  --expect E          stream | reject:<NEDEN>   (varsayılan stream)
  --expect-pending    tahta onayı beklemesi (PENDING_APPROVAL) zorunlu
  --hold-ms N         son kareden sonra BYE'dan önce bekleme (varsayılan 1500)
  --result F          sonuç özeti (JSON)

Çıkış kodu: 0 beklenti tuttu, 1 tutmadı, 2 kullanım hatası, 3 bağlantı hatası."""

private class Options(
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
)

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
    val h264 = AnnexB.parse(o.input!!.readBytes())
    val total = if (o.frames == 0) h264.units.size else o.frames
    val control = session.control
    val stopped = AtomicBoolean(false)
    val skipToKeyframe = AtomicBoolean(false)
    val clock = ClockSync()

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
                control.send(ControlMessage.Ping(id++, MonotonicClock.nowUs(), clock.offsetUs))
                Thread.sleep(if (id < 4) 100 else 1000) // ilk ofset hızlı oturur
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }, "pinger").apply { isDaemon = true; start() }

    val video = SenderClient.attach(session, StreamKind.VIDEO)
    control.send(ControlMessage.StreamConfig(StreamKind.VIDEO, Codec.H264, fps = o.fps))
    video.send(FrameFlags.CONFIG, MonotonicClock.nowUs(), byteArrayOf(Codec.H264.toByte()) + h264.config)

    val intervalNs = 1_000_000_000L / o.fps
    val start = System.nanoTime()
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

fun main(args: Array<String>) {
    val o = parseArgs(args)
    val qr = QrPayload.parse(o.uri)
    val endpoint = Endpoint(o.host ?: qr.hosts.first(), qr.port, qr.certFingerprint)
    val device = DeviceInfo(
        name = o.deviceName,
        deviceId = o.deviceId,
        appVersion = "fake-sender",
        videoCodecs = listOf(Codec.H264),
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
                put("paired", outcome.session.welcome.pairKey != null)
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
