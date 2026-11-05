package io.github.ozkanceng.casthop.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.receiver.VideoBackend
import io.github.ozkanceng.yansit.receiver.VideoConfig
import io.github.ozkanceng.yansit.receiver.VideoPipeline
import io.github.ozkanceng.yansit.receiver.VideoSize
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MediaCodec in asynchronous mode, straight to a Surface. Frames are shown as soon as they are decoded: nothing here
 * paces them, because the receiver favours liveness over smoothness.
 *
 * Parameter sets travel inside the first keyframe, so a decoder needs no csd buffers. A new CONFIG with different
 * bytes (rotation, resolution or codec change) rebuilds the decoder. All decoder state lives on one handler thread.
 */
internal class MediaCodecVideoBackend(
    private val catalog: DecoderCatalog,
    private val clockUs: () -> Long,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val onFailure: (failed: Boolean) -> Unit,
) : VideoBackend {
    private class Item(val data: ByteArray, val timestampUs: Long)

    @Volatile
    var pipeline: VideoPipeline? = null

    @Volatile
    override var decoderName: String = ""
        private set

    @Volatile
    override var hardware: Boolean = false
        private set

    private val handlerThread = HandlerThread("casthop-video").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var streamCodec = 0
    private var streamConfig = ByteArray(0)
    private var streamSize = VideoSize(0, 0)
    private var candidates = emptyList<DecoderCatalog.Decoder>()
    private var nextCandidate = 0
    private val pending = ArrayDeque<Item>()
    private val freeInputs = ArrayDeque<Int>()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== decoder) return
            freeInputs.addLast(index)
            drain()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== decoder) return
            val show = info.size > 0
            codec.releaseOutputBuffer(index, show)
            if (show) {
                pipeline?.onRendered(info.presentationTimeUs, clockUs())
            } else {
                pipeline?.onDiscarded(info.presentationTimeUs)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec !== decoder) return
            val left = format.intOr(KEY_CROP_LEFT, 0)
            val top = format.intOr(KEY_CROP_TOP, 0)
            val width = format.intOr(KEY_CROP_RIGHT, format.intOr(MediaFormat.KEY_WIDTH, 1) - 1) - left + 1
            val height = format.intOr(KEY_CROP_BOTTOM, format.intOr(MediaFormat.KEY_HEIGHT, 1) - 1) - top + 1
            onVideoSize(width, height)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== decoder) return
            Log.w(TAG, "decoder $decoderName failed: ${e.diagnosticInfo}")
            pending.clear()
            // A decoder that fails mid-stream is not worth a retry: the next candidate takes over.
            openNext()
            pipeline?.onDecoderError()
        }
    }

    /**
     * The surface to draw on, or `null` once it is going away. Waits for the decoder to let go, because the surface
     * must not be used after SurfaceHolder.Callback.surfaceDestroyed returns.
     */
    fun setSurface(value: Surface?) {
        onHandler {
            surface = value
            pending.clear()
            if (value == null) {
                release()
            } else if (streamCodec != 0) {
                nextCandidate = 0
                openNext()
            }
            if (streamCodec != 0) pipeline?.onDecoderError()
        }
    }

    override fun configure(codec: Int, config: ByteArray): Boolean = onHandler { applyConfig(codec, config) } ?: false

    private fun applyConfig(codec: Int, config: ByteArray): Boolean {
        if (codec == streamCodec && config.contentEquals(streamConfig) && decoder != null) return true
        val size = VideoConfig.parseSize(codec, config)
        if (size == null) {
            Log.w(TAG, "no readable SPS in CONFIG")
            onFailure(true)
            return false
        }
        while (pending.isNotEmpty()) pipeline?.onDiscarded(pending.removeFirst().timestampUs)
        streamCodec = codec
        streamConfig = config
        streamSize = size
        candidates = catalog.decoders(mimeOf(codec)).filter { it.supports(size) }
        nextCandidate = 0
        onVideoSize(size.width, size.height)
        return openNext()
    }

    /** Opens the next decoder that accepts the stream. Without a surface yet, frames wait for one. */
    private fun openNext(): Boolean {
        release()
        val target = surface ?: return true
        while (nextCandidate < candidates.size) {
            val candidate = candidates[nextCandidate++]
            if (open(candidate, target)) {
                onFailure(false)
                return true
            }
        }
        Log.w(TAG, "no decoder can take ${streamSize.width}x${streamSize.height}")
        onFailure(true)
        return false
    }

    private fun open(candidate: DecoderCatalog.Decoder, target: Surface): Boolean {
        var codec: MediaCodec? = null
        val failure: Exception = try {
            codec = MediaCodec.createByCodecName(candidate.name)
            val format = MediaFormat.createVideoFormat(mimeOf(streamCodec), streamSize.width, streamSize.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && candidate.lowLatency) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder = codec
            codec.setCallback(callback, handler)
            codec.configure(format, target, null, 0)
            codec.start()
            decoderName = candidate.name
            hardware = candidate.hardware
            Log.i(TAG, "decoding ${streamSize.width}x${streamSize.height} with ${candidate.name}")
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

    override fun submit(accessUnit: ByteArray, timestampUs: Long, keyframe: Boolean) {
        handler.post {
            if (streamCodec == 0 || (decoder == null && surface != null)) {
                pipeline?.onDiscarded(timestampUs)
            } else {
                pending.addLast(Item(accessUnit, timestampUs))
                drain()
            }
        }
    }

    private fun drain() {
        val codec = decoder ?: return
        try {
            while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
                val item = pending.removeFirst()
                val index = freeInputs.first()
                val buffer = codec.getInputBuffer(index)
                if (buffer == null || item.data.size > buffer.capacity()) {
                    // Losing a frame breaks the references after it, so restart from a keyframe.
                    pipeline?.onDiscarded(item.timestampUs)
                    pipeline?.onDecoderError()
                    continue
                }
                freeInputs.removeFirst()
                buffer.clear()
                buffer.put(item.data)
                codec.queueInputBuffer(index, 0, item.data.size, item.timestampUs, 0)
            }
        } catch (e: IllegalStateException) {
            // onError follows and rebuilds the decoder.
            Log.w(TAG, "decoder refused input", e)
        }
    }

    private fun release() {
        val codec = decoder ?: return
        decoder = null
        freeInputs.clear()
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decoder did not stop cleanly", e)
        }
        codec.release()
    }

    override fun reset() {
        onHandler {
            streamCodec = 0
            streamConfig = ByteArray(0)
            candidates = emptyList()
            pending.clear()
            release()
            decoderName = ""
            hardware = false
            onFailure(false)
        }
    }

    /** Releases the decoder and ends the thread. The backend cannot be used afterwards. */
    fun close() {
        reset()
        handlerThread.quitSafely()
    }

    /** Runs [block] on the decoder thread and waits, so configuring keeps its place among the frames. */
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

    private fun MediaFormat.intOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback

    private fun mimeOf(codec: Int): String =
        if (codec == Codec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    private companion object {
        const val TAG = "CastHopVideo"
        const val HANDLER_WAIT_S = 5L
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"
    }
}
