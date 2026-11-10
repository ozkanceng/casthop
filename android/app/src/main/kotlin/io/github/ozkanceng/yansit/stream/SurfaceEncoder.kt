package io.github.ozkanceng.yansit.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.sender.EncodedFrame
import io.github.ozkanceng.yansit.sender.StreamPlan
import io.github.ozkanceng.yansit.sender.VideoSource

private const val TAG = "YansitEncoder"

/** A [VideoSource] that owns capture resources beyond the encoder (ARCHITECTURE.md §2.1). */
internal interface CaptureSource : VideoSource {
    /** Pause: no new frames, the board keeps the last one. */
    fun setFramesEnabled(on: Boolean)

    /** Lets go of everything; the object cannot be used again. */
    fun release()
}

/**
 * The MediaCodec part shared by screen and camera capture: H.264/H.265 from an input Surface.
 * Output timestamps are the capture time of each frame (System.nanoTime clock).
 */
internal class SurfaceEncoder(private val caps: AndroidEncoderCaps) {
    private val thread = HandlerThread("encoder-video", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var codec: MediaCodec? = null
    private var ownInput: Surface? = null

    /**
     * Starts a new encoder for [plan]. With [persistentInput] the encoder reads from it; otherwise it makes its own
     * input, valid until [stop].
     *
     * @param pacedBySource the producer already limits the frame rate (OpenGL in screen mode); the encoder must not
     *   drop frames on its own then, or it would drop the evenly-late ones the producer let through. The producer
     *   also repeats the picture on a still screen itself.
     * @param extraFlags frame flags to add to each picture, by its presentation time (ROTATED)
     */
    @Synchronized
    fun start(
        plan: StreamPlan,
        sink: (EncodedFrame) -> Unit,
        onError: (Throwable) -> Unit,
        persistentInput: Surface?,
        pacedBySource: Boolean,
        extraFlags: ((ptsUs: Long) -> Int)? = null,
    ): Surface {
        stop()
        val format = MediaFormat.createVideoFormat(caps.mime, plan.width, plan.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.startBps.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (caps.supportsCbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            // Durağan ekranda (ders sunusu) kare akışı kesilmesin: alıcı canlı kalır, gecikme ölçülür.
            // Ekran yolunda bunu GL yapar, seyrek (ScreenSource.IDLE_REDRAW_MS): her 100 ms'de tam bir kare
            // kalabalık ağı boşuna doldurur.
            if (!pacedBySource) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // gerçek zamanlı
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (!pacedBySource) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, plan.fps.toFloat())
            setInteger(MediaFormat.KEY_OPERATING_RATE, plan.fps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            caps.bestProfile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        }

        val c = MediaCodec.createByCodecName(caps.name)
        c.setCallback(Callback(sink, onError, extraFlags), handler)
        val input: Surface
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = if (persistentInput != null) {
                c.setInputSurface(persistentInput)
                persistentInput
            } else {
                c.createInputSurface().also { ownInput = it }
            }
            c.start()
        } catch (e: Exception) {
            c.release()
            ownInput?.release()
            ownInput = null
            throw e
        }
        codec = c
        Log.i(TAG, "kodlayıcı: ${caps.name} ${plan.width}x${plan.height}@${plan.fps} ${plan.startBps} bps")
        return input
    }

    fun requestKeyframe() {
        setParams(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    fun setBitrate(bps: Long) {
        setParams(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps.toInt()) })
    }

    private fun setParams(params: Bundle) {
        try {
            codec?.setParameters(params)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setParameters", e)
        }
    }

    /** Stops and releases the encoder and the input it made. Whoever draws into that input must stop first. */
    @Synchronized
    fun stop() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop", e)
        }
        c.release()
        ownInput?.release()
        ownInput = null
    }

    @Synchronized
    fun release() {
        stop()
        thread.quitSafely()
    }

    private class Callback(
        private val sink: (EncodedFrame) -> Unit,
        private val onError: (Throwable) -> Unit,
        private val extraFlags: ((ptsUs: Long) -> Int)?,
    ) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(index) ?: return
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size).also { buf.get(it) }
                    var flags = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or FrameFlags.CONFIG
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FrameFlags.KEYFRAME
                    if (flags and FrameFlags.CONFIG == 0) extraFlags?.let { flags = flags or it(info.presentationTimeUs) }
                    val pts = if (flags and FrameFlags.CONFIG != 0) MonotonicClock.nowUs() else info.presentationTimeUs
                    sink(EncodedFrame(data, pts, flags, MonotonicClock.nowUs()))
                }
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "releaseOutputBuffer", e)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "kodlayıcı hatası (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "çıktı biçimi: $format")
        }
    }

    private companion object {
        // Each periodic IDR is a burst on the air. TCP loses nothing, and every real need for one (start,
        // reconnect, dropped frames, receiver request) already asks for it.
        const val I_FRAME_INTERVAL_S = 10
        const val REPEAT_AFTER_US = 100_000L
    }
}

/** Camera mode: the camera draws straight into one persistent encoder input; its size is fixed for the session. */
internal class CameraEncoder(caps: AndroidEncoderCaps) : CaptureSource {
    private val encoder = SurfaceEncoder(caps)

    /** The camera's output; valid from construction until [release]. */
    val inputSurface: Surface = MediaCodec.createPersistentInputSurface()

    override fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit) {
        encoder.start(plan, sink, onError, inputSurface, pacedBySource = false)
    }

    override fun requestKeyframe() = encoder.requestKeyframe()

    override fun setBitrate(bps: Long) = encoder.setBitrate(bps)

    override fun stop() = encoder.stop()

    /** The camera itself pauses ([CameraCapture.pause]). */
    override fun setFramesEnabled(on: Boolean) = Unit

    override fun release() {
        encoder.release()
        inputSurface.release()
    }
}
