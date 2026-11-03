package io.github.ozkanceng.yansit.stream

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.sender.AudioPacket
import io.github.ozkanceng.yansit.sender.AudioSource

private const val TAG = "YansitAudio"

/**
 * Tabletin çaldığı sesi yakalar (AudioPlaybackCapture: medya, oyun, bilinmeyen kullanım) ve Opus'a (yoksa
 * AAC-LC'ye) kodlar. Mikrofon kullanılmaz; yine de Android bu API için RECORD_AUDIO izni ister.
 *
 * Zaman damgaları `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` ile CLOCK_MONOTONIC tabanındadır (videoyla aynı).
 * Uygulamalar `allowAudioPlaybackCapture=false` ile sesini paylaşmayı reddedebilir (RISKS R10): o zaman sessizlik gelir.
 */
class AudioCapture private constructor(
    private val projection: MediaProjection,
    override val codec: Int,
    private val encoderName: String,
) : AudioSource {
    override val sampleRate: Int = SAMPLE_RATE
    override val channels: Int = CHANNELS

    /** Parametrelerden belirlenimci olarak üretilir: kodlayıcının özel CSD biçimine bağlı kalınmaz. */
    override val config: ByteArray = if (codec == Codec.OPUS) opusHead(CHANNELS, SAMPLE_RATE) else aacAsc(SAMPLE_RATE, CHANNELS)

    @Volatile private var running = false
    private var thread: Thread? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(sink: (AudioPacket) -> Unit, onError: (Throwable) -> Unit) {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
            .setAudioPlaybackCaptureConfig(capture)
            .build()
        val encoder = MediaCodec.createByCodecName(encoderName)
        try {
            encoder.configure(encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            encoder.release()
            throw e
        }
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                loop(record, encoder, sink)
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "ses yakalama hatası", e)
                    onError(e)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { encoder.stop() }
                encoder.release()
            }
        }, "audio-capture").apply { start() }
        Log.i(TAG, "ses: ${if (codec == Codec.OPUS) "Opus" else "AAC-LC"} ($encoderName)")
    }

    private fun loop(record: AudioRecord, encoder: MediaCodec, sink: (AudioPacket) -> Unit) {
        val pcm = ByteArray(CHUNK_BYTES)
        val ts = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var framesRead = 0L
        while (running) {
            var off = 0
            while (off < CHUNK_BYTES && running) {
                val n = record.read(pcm, off, CHUNK_BYTES - off, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IllegalStateException("AudioRecord.read: $n")
                off += n
            }
            if (!running) break
            // Bu parçanın ilk örneğinin yakalama anı: zaman damgası noktasından örnek sayısıyla öteleme.
            val chunkStart = framesRead
            framesRead += CHUNK_FRAMES
            val ptsUs = if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                ts.nanoTime / 1000 + (chunkStart - ts.framePosition) * 1_000_000 / SAMPLE_RATE
            } else {
                MonotonicClock.nowUs() - CHUNK_US
            }
            val index = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buf = encoder.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(pcm, 0, CHUNK_BYTES)
                encoder.queueInputBuffer(index, 0, CHUNK_BYTES, ptsUs, 0)
            }
            while (true) {
                val out = encoder.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val ob = encoder.getOutputBuffer(out)
                    if (ob != null) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val data = ByteArray(info.size).also { ob.get(it) }
                        sink(AudioPacket(data, info.presentationTimeUs))
                    }
                }
                encoder.releaseOutputBuffer(out, false)
            }
        }
    }

    private fun encoderFormat(): MediaFormat = if (codec == Codec.OPUS) {
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
    } else {
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
    }

    override fun stop() {
        running = false
        thread?.let { if (it != Thread.currentThread()) it.join(1000) }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        private const val BITRATE = 128_000
        private const val CHUNK_FRAMES = 960 // 20 ms
        private const val CHUNK_BYTES = CHUNK_FRAMES * CHANNELS * 2
        private const val CHUNK_US = CHUNK_FRAMES * 1_000_000L / SAMPLE_RATE
        private const val INPUT_TIMEOUT_US = 10_000L

        /**
         * Tahtanın çözebildiği ve tablette kodlayıcısı olan codec: Opus önce, sonra AAC-LC.
         * Uygun yoksa `null` (görüntü sessiz sürer).
         */
        fun create(projection: MediaProjection, boardDecoders: List<CodecSupport>): AudioCapture? {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && !it.isAlias }
            fun encoderFor(mime: String) = list.firstOrNull { mime in it.supportedTypes }?.name
            for ((codec, mime) in listOf(Codec.OPUS to MediaFormat.MIMETYPE_AUDIO_OPUS, Codec.AAC_LC to MediaFormat.MIMETYPE_AUDIO_AAC)) {
                if (boardDecoders.none { it.codec == codec }) continue
                val name = encoderFor(mime) ?: continue
                return AudioCapture(projection, codec, name)
            }
            return null
        }

        /** RFC 7845 §5.1 OpusHead (eşleme ailesi 0). Ön atlama: libopus'un 48 kHz'deki 312 örneği. */
        fun opusHead(channels: Int, inputRate: Int): ByteArray {
            val h = ByteArray(19)
            "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h)
            h[8] = 1
            h[9] = channels.toByte()
            h[10] = (312 and 0xFF).toByte()
            h[11] = (312 shr 8).toByte()
            for (i in 0 until 4) h[12 + i] = (inputRate shr (8 * i)).toByte()
            return h // kazanç 0, eşleme ailesi 0
        }

        /** ISO 14496-3 AudioSpecificConfig: AAC-LC (nesne türü 2), örnek hızı indeksi, kanal yapılandırması. */
        fun aacAsc(sampleRate: Int, channels: Int): ByteArray {
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val index = rates.indexOf(sampleRate).also { require(it >= 0) }
            val v = (2 shl 11) or (index shl 7) or (channels shl 3)
            return byteArrayOf((v shr 8).toByte(), v.toByte())
        }
    }
}
