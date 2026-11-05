package io.github.ozkanceng.casthop.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.receiver.AudioAligner
import io.github.ozkanceng.yansit.receiver.AudioBackend
import io.github.ozkanceng.yansit.receiver.AudioConfig
import io.github.ozkanceng.yansit.receiver.AudioPipeline
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.ozkanceng.yansit.receiver.AudioFormat as StreamFormat

/**
 * Opus and AAC-LC through MediaCodec to AudioTrack. Each packet carries the moment it should be heard as its
 * presentation time, through the decoder and into the writer thread, where [AudioAligner] keeps AudioTrack on that
 * clock. Audio failing never touches the picture: the stream simply stays silent until the next CONFIG.
 */
internal class MediaCodecAudioBackend(
    private val catalog: DecoderCatalog,
    private val clockUs: () -> Long,
) : AudioBackend {
    private class Packet(val data: ByteArray, val playAtUs: Long)

    private class Block(val pcm: ByteArray, val playAtUs: Long, val shape: PcmShape)

    private data class PcmShape(val rate: Int, val channels: Int, val encoding: Int) {
        val frameBytes: Int get() = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) FLOAT_BYTES else PCM16_BYTES
    }

    @Volatile
    var pipeline: AudioPipeline? = null

    private val handlerThread = HandlerThread("casthop-audio").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var decoder: MediaCodec? = null
    private var streamCodec = 0
    private var streamConfig = ByteArray(0)
    private var shape = PcmShape(OPUS_RATE, 2, AudioFormat.ENCODING_PCM_16BIT)
    private val pending = ArrayDeque<Packet>()
    private val freeInputs = ArrayDeque<Int>()

    private val blocks = ArrayBlockingQueue<Block>(MAX_QUEUED_BLOCKS)
    private var writer: Thread? = null

    @Volatile
    private var writing = false

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== decoder) return
            freeInputs.addLast(index)
            drain()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== decoder) return
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && !isConfig) {
                val pcm = ByteArray(info.size)
                buffer.get(pcm)
                val block = Block(pcm, info.presentationTimeUs, shape)
                // A full queue means the output has stalled: the oldest audio is the one to lose.
                if (!blocks.offer(block)) {
                    blocks.poll()
                    blocks.offer(block)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec !== decoder) return
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            shape = PcmShape(
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                encoding,
            )
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== decoder) return
            Log.w(TAG, "audio decoder failed: ${e.diagnosticInfo}")
            release()
        }
    }

    override fun configure(codec: Int, config: ByteArray): Boolean = onHandler { applyConfig(codec, config) } ?: false

    private fun applyConfig(codec: Int, config: ByteArray): Boolean {
        if (codec == streamCodec && config.contentEquals(streamConfig) && decoder != null) return true
        release()
        val mime: String
        val stream: StreamFormat?
        when (codec) {
            Codec.OPUS -> {
                mime = MediaFormat.MIMETYPE_AUDIO_OPUS
                stream = AudioConfig.parseOpusHead(config)
            }
            Codec.AAC_LC -> {
                mime = MediaFormat.MIMETYPE_AUDIO_AAC
                stream = AudioConfig.parseAudioSpecificConfig(config)
            }
            else -> return false
        }
        if (stream == null) {
            Log.w(TAG, "unreadable audio CONFIG")
            return false
        }
        val format = MediaFormat.createAudioFormat(mime, stream.sampleRate, stream.channels)
        format.setByteBuffer(KEY_CSD_0, ByteBuffer.wrap(config))
        if (codec == Codec.OPUS) {
            // Android's Opus decoders take the codec delay and the seek pre-roll in nanoseconds.
            format.setByteBuffer(KEY_CSD_1, nanosBuffer(stream.preSkipSamples * NANOS_PER_SECOND / OPUS_RATE))
            format.setByteBuffer(KEY_CSD_2, nanosBuffer(OPUS_SEEK_PREROLL_NS))
        }
        for (candidate in catalog.decoders(mime).filter { it.supports(stream) }) {
            if (open(candidate, format)) {
                streamCodec = codec
                streamConfig = config
                shape = PcmShape(stream.sampleRate, stream.channels, AudioFormat.ENCODING_PCM_16BIT)
                startWriter()
                return true
            }
        }
        Log.w(TAG, "no audio decoder accepted the stream")
        return false
    }

    private fun open(candidate: DecoderCatalog.Decoder, format: MediaFormat): Boolean {
        var codec: MediaCodec? = null
        val failure: Exception = try {
            codec = MediaCodec.createByCodecName(candidate.name)
            decoder = codec
            codec.setCallback(callback, handler)
            codec.configure(format, null, null, 0)
            codec.start()
            Log.i(TAG, "decoding audio with ${candidate.name}")
            return true
        } catch (e: IOException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        } catch (e: IllegalStateException) {
            e
        }
        Log.w(TAG, "${candidate.name} did not start", failure)
        codec?.release()
        decoder = null
        return false
    }

    override fun submit(packet: ByteArray, playAtUs: Long) {
        handler.post {
            if (decoder != null) {
                pending.addLast(Packet(packet, playAtUs))
                if (pending.size > MAX_QUEUED_PACKETS) pending.removeFirst()
                drain()
            }
        }
    }

    private fun drain() {
        val codec = decoder ?: return
        try {
            while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
                val index = freeInputs.removeFirst()
                val packet = pending.removeFirst()
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(packet.data)
                codec.queueInputBuffer(index, 0, packet.data.size, packet.playAtUs, 0)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio decoder refused input", e)
        }
    }

    private fun startWriter() {
        if (writer != null) return
        writing = true
        writer = Thread(::writeLoop, "casthop-audio-out").apply {
            isDaemon = true
            start()
        }
    }

    private fun writeLoop() {
        var output: Output? = null
        var refused: PcmShape? = null
        try {
            while (writing) {
                val block = blocks.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (block.shape == refused) continue
                if (output?.shape != block.shape) {
                    output?.close()
                    output = Output.open(block.shape)
                    if (output == null) refused = block.shape
                }
                output?.play(block, clockUs(), pipeline)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        output?.close()
    }

    private fun release() {
        val codec = decoder ?: return
        decoder = null
        freeInputs.clear()
        pending.clear()
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio decoder did not stop cleanly", e)
        }
        codec.release()
    }

    override fun reset() {
        onHandler {
            streamCodec = 0
            streamConfig = ByteArray(0)
            release()
            writing = false
            writer?.join(WRITER_JOIN_MS)
            writer = null
            blocks.clear()
        }
    }

    /** Releases everything and ends the thread. The backend cannot be used afterwards. */
    fun close() {
        reset()
        handlerThread.quitSafely()
    }

    private fun <T> onHandler(block: () -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        val posted = handler.post {
            result = block()
            done.countDown()
        }
        if (posted) done.await(HANDLER_WAIT_S, TimeUnit.SECONDS)
        return result
    }

    private fun nanosBuffer(value: Long): ByteBuffer =
        ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }

    /** One AudioTrack and how much has been written to it. */
    private class Output(private val track: AudioTrack, val shape: PcmShape) {
        private val timestamp = AudioTimestamp()
        private var framesWritten = 0L
        private var rolling = false

        // The queue swings by up to a buffer as the output drains it in bursts, and that is not drift. Measured on
        // the emulator: a fixed 15 ms tolerance threw away a quarter of the audio and starved the track.
        private val aligner = AudioAligner(
            maxOf(
                AudioAligner.DEFAULT_TOLERANCE_US,
                track.bufferSizeInFrames * MICROS_PER_SECOND / shape.rate / 2,
            ),
        )

        fun play(block: Block, nowUs: Long, pipeline: AudioPipeline?) {
            val frameBytes = shape.frameBytes
            val frames = block.pcm.size / frameBytes
            val queuedUs = queuedUs()
            // A track starts only once its buffer holds enough. Trimming "late" audio before that would keep it from
            // ever filling, and the stream would stay silent.
            val plan = if (rolling) {
                aligner.plan(block.playAtUs, nowUs, queuedUs, frames * MICROS_PER_SECOND / shape.rate)
            } else {
                AudioAligner.Plan(silenceUs = 0, skipUs = 0)
            }
            if (plan.silenceUs > 0) {
                val silence = (plan.silenceUs * shape.rate / MICROS_PER_SECOND).toInt() * frameBytes
                write(ByteBuffer.allocate(silence))
            }
            val skipped = (plan.skipUs * shape.rate / MICROS_PER_SECOND).toInt().coerceAtMost(frames)
            if (skipped == frames) return
            write(ByteBuffer.wrap(block.pcm, skipped * frameBytes, (frames - skipped) * frameBytes))
            // Captured at playAt - target; heard once everything queued ahead of it has played.
            pipeline?.let { it.onPlayed(nowUs + queuedUs + plan.silenceUs - block.playAtUs + it.targetLatencyUs) }
        }

        private fun write(data: ByteBuffer) {
            val written = track.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING)
            if (written > 0) framesWritten += written / shape.frameBytes
        }

        /** Audio handed to the track but not heard yet. */
        private fun queuedUs(): Long {
            val heard = if (track.getTimestamp(timestamp) && timestamp.framePosition > 0) {
                val sinceUs = (System.nanoTime() - timestamp.nanoTime) / NANOS_PER_MICRO
                timestamp.framePosition + sinceUs * shape.rate / MICROS_PER_SECOND
            } else {
                track.playbackHeadPosition.toLong() and U32_MASK
            }
            if (heard > 0) rolling = true
            return ((framesWritten - heard) * MICROS_PER_SECOND / shape.rate).coerceAtLeast(0)
        }

        fun close() {
            track.pause()
            track.flush()
            track.release()
        }

        companion object {
            fun open(shape: PcmShape): Output? {
                val mask = when (shape.channels) {
                    1 -> AudioFormat.CHANNEL_OUT_MONO
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    else -> return null
                }
                val format = AudioFormat.Builder()
                    .setSampleRate(shape.rate)
                    .setChannelMask(mask)
                    .setEncoding(shape.encoding)
                    .build()
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val minimum = AudioTrack.getMinBufferSize(shape.rate, mask, shape.encoding)
                return try {
                    if (minimum <= 0) throw UnsupportedOperationException("no buffer size for this format")
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(minimum)
                        .build()
                    track.play()
                    Output(track, shape)
                } catch (e: UnsupportedOperationException) {
                    Log.w(TAG, "no audio output for $shape", e)
                    null
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "audio output did not start", e)
                    null
                }
            }
        }
    }

    private companion object {
        const val TAG = "CastHopAudio"
        const val KEY_CSD_0 = "csd-0"
        const val KEY_CSD_1 = "csd-1"
        const val KEY_CSD_2 = "csd-2"
        const val HANDLER_WAIT_S = 5L
        const val MAX_QUEUED_BLOCKS = 64
        const val MAX_QUEUED_PACKETS = 64
        const val WRITER_POLL_MS = 200L
        const val WRITER_JOIN_MS = 1000L
        const val OPUS_RATE = 48_000
        const val OPUS_SEEK_PREROLL_NS = 80_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_MICRO = 1000L
        const val FLOAT_BYTES = 4
        const val PCM16_BYTES = 2
        const val U32_MASK = 0xFFFF_FFFFL
    }
}
