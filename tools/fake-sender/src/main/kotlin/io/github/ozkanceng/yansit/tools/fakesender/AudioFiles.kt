package io.github.ozkanceng.yansit.tools.fakesender

import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.sender.AudioPacket
import io.github.ozkanceng.yansit.sender.AudioSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Dosyadan okunmuş ses akışı: CONFIG yükü ve paketler (süreleriyle). */
class AudioStream(
    val codec: Int,
    val sampleRate: Int,
    val channels: Int,
    val config: ByteArray,
    val packets: List<Pair<ByteArray, Long>>,
)

/** Ogg/Opus (RFC 7845) ve ADTS/AAC ayrıştırıcıları — CI'da Android kodlayıcısının yerine geçer. */
object AudioFiles {
    fun parse(data: ByteArray): AudioStream = when {
        data.size >= 4 && data[0] == 'O'.code.toByte() && data[1] == 'g'.code.toByte() -> parseOggOpus(data)
        data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xF0) == 0xF0 -> parseAdts(data)
        else -> throw IllegalArgumentException("desteklenmeyen ses dosyası (Ogg/Opus veya ADTS/AAC bekleniyor)")
    }

    /** Ogg sayfalarından paketleri birleştirir; ilk paket OpusHead, ikincisi OpusTags. */
    fun parseOggOpus(data: ByteArray): AudioStream {
        val packets = ArrayList<ByteArray>()
        var pos = 0
        var current = ByteArrayOutputStream()
        while (pos + 27 <= data.size) {
            require(String(data, pos, 4, Charsets.US_ASCII) == "OggS") { "Ogg sayfası bozuk: $pos" }
            val segments = data[pos + 26].toInt() and 0xFF
            val table = pos + 27
            var body = table + segments
            for (i in 0 until segments) {
                val len = data[table + i].toInt() and 0xFF
                current.write(data, body, len)
                body += len
                if (len < 255) {
                    packets.add(current.toByteArray())
                    current = ByteArrayOutputStream()
                }
            }
            pos = body
        }
        require(packets.size >= 3) { "Opus paketi yok" }
        val head = packets[0]
        require(String(head, 0, 8, Charsets.US_ASCII) == "OpusHead") { "OpusHead yok" }
        val channels = head[9].toInt() and 0xFF
        val audio = packets.drop(2).filter { it.isNotEmpty() }.map { it to opusPacketDurationUs(it) }
        return AudioStream(Codec.OPUS, 48000, channels, head, audio)
    }

    /** RFC 6716 §3.1: TOC baytından paket süresi. */
    fun opusPacketDurationUs(p: ByteArray): Long {
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        val frameUs = when {
            config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
            config < 16 -> longArrayOf(10_000, 20_000)[config and 1]
            else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) p[1].toInt() and 0x3F else 1
        }
        return frameUs * frames
    }

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** ADTS çerçevelerinden ham AAC erişim birimleri ve AudioSpecificConfig (ISO 14496-3 §1.6.2.1). */
    fun parseAdts(data: ByteArray): AudioStream {
        val packets = ArrayList<Pair<ByteArray, Long>>()
        var pos = 0
        var asc: ByteArray? = null
        var rate = 0
        var channels = 0
        while (pos + 7 <= data.size) {
            val b = { i: Int -> data[pos + i].toInt() and 0xFF }
            require(b(0) == 0xFF && (b(1) and 0xF0) == 0xF0) { "ADTS eşitleme kaybı: $pos" }
            val protectionAbsent = b(1) and 1
            val objectType = ((b(2) shr 6) and 3) + 1
            val sfIndex = (b(2) shr 2) and 0xF
            val chanCfg = ((b(2) and 1) shl 2) or ((b(3) shr 6) and 3)
            val frameLen = ((b(3) and 3) shl 11) or (b(4) shl 3) or ((b(5) shr 5) and 7)
            val headerLen = if (protectionAbsent == 1) 7 else 9
            require(frameLen > headerLen && pos + frameLen <= data.size) { "ADTS çerçeve uzunluğu bozuk" }
            if (asc == null) {
                rate = AAC_RATES[sfIndex]
                channels = chanCfg
                val v = (objectType shl 11) or (sfIndex shl 7) or (chanCfg shl 3)
                asc = byteArrayOf((v shr 8).toByte(), v.toByte())
            }
            packets.add(data.copyOfRange(pos + headerLen, pos + frameLen) to 1024L * 1_000_000 / rate)
            pos += frameLen
        }
        return AudioStream(Codec.AAC_LC, rate, channels, requireNotNull(asc) { "AAC çerçevesi yok" }, packets)
    }
}

/** Ses paketlerini gerçek zaman hızında, CLOCK_MONOTONIC zaman damgalarıyla veren kaynak; dosya biterse başa sarar. */
class FileAudioSource(private val stream: AudioStream) : AudioSource {
    override val codec: Int get() = stream.codec
    override val sampleRate: Int get() = stream.sampleRate
    override val channels: Int get() = stream.channels
    override val config: ByteArray get() = stream.config

    val packetsProduced = AtomicInteger()
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(sink: (AudioPacket) -> Unit, onError: (Throwable) -> Unit) {
        running = true
        thread = Thread({
            var ptsUs = MonotonicClock.nowUs()
            var i = 0
            while (running) {
                val (data, durUs) = stream.packets[i % stream.packets.size]
                while (true) {
                    val waitUs = ptsUs + durUs - MonotonicClock.nowUs()
                    if (waitUs <= 0 || !running) break
                    LockSupport.parkNanos(waitUs * 1000)
                }
                // Zaman damgası paketin ilk örneğinin yakalama anı; paket süresi dolunca verilir (kodlayıcı gibi).
                sink(AudioPacket(data, ptsUs))
                packetsProduced.incrementAndGet()
                ptsUs += durUs
                i++
            }
        }, "file-audio").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        if (Thread.currentThread() != thread) thread?.join(1000)
    }
}
