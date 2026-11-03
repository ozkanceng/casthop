package io.github.ozkanceng.yansit.stream

import io.github.ozkanceng.yansit.sender.StreamPlan
import io.github.ozkanceng.yansit.sender.VideoEncoderCaps
import io.github.ozkanceng.yansit.sender.EncodedFrame
import io.github.ozkanceng.yansit.sender.VideoSource
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock

private const val TAG = "YansitEncoder"

/** `MediaCodecInfo` üzerinden [VideoEncoderCaps]. */
class AndroidEncoderCaps private constructor(
    private val info: MediaCodecInfo,
    mime: String,
    private val caps: MediaCodecInfo.CodecCapabilities,
    private val video: MediaCodecInfo.VideoCapabilities,
    private val encoder: MediaCodecInfo.EncoderCapabilities,
) : VideoEncoderCaps {

    override val name: String get() = info.name
    override val codec: Int = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) Codec.H265 else Codec.H264
    override val hardware: Boolean get() = info.isHardwareAccelerated
    override val widthAlignment: Int get() = video.widthAlignment
    override val heightAlignment: Int get() = video.heightAlignment
    override val bitrateRange: LongRange get() = video.bitrateRange.lower.toLong()..video.bitrateRange.upper.toLong()

    override fun isSupported(width: Int, height: Int, fps: Int): Boolean =
        video.isSizeSupported(width, height) && video.areSizeAndRateSupported(width, height, fps.toDouble())

    val supportsCbr: Boolean
        get() = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

    /** High → Main → Baseline: B-kare kapalıyken High profil aynı bit hızında daha iyi kalite verir. */
    val bestProfile: Int?
        get() {
            val profiles = caps.profileLevels.map { it.profile }.toSet()
            return listOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            ).firstOrNull { it in profiles }
        }

    companion object {
        /** Cihazdaki H.264 kodlayıcıları (takma adlar hariç), donanım önce. */
        fun h264Encoders(): List<AndroidEncoderCaps> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && !it.isAlias && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
                .mapNotNull { info ->
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val video = caps.videoCapabilities ?: return@mapNotNull null
                    val encoder = caps.encoderCapabilities ?: return@mapNotNull null
                    AndroidEncoderCaps(info, MediaFormat.MIMETYPE_VIDEO_AVC, caps, video, encoder)
                }
                .sortedByDescending { it.hardware }
    }
}

/**
 * Ekran kodlayıcı: VirtualDisplay'in çizdiği kalıcı giriş Surface'ından H.264 üretir (ARCHITECTURE.md §2.1).
 *
 * Kalıcı Surface sayesinde kodlayıcı yeniden yapılandırılırken (döndürme, hata) VirtualDisplay korunur.
 * Çıktı zaman damgaları VirtualDisplay karelerinin CLOCK_MONOTONIC yakalama anıdır.
 */
class ScreenEncoder(private val caps: AndroidEncoderCaps) : VideoSource {
    val inputSurface: Surface = MediaCodec.createPersistentInputSurface()

    private val thread = HandlerThread("encoder-video", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var codec: MediaCodec? = null

    override fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height).apply {
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
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // gerçek zamanlı
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, plan.fps.toFloat())
            setInteger(MediaFormat.KEY_OPERATING_RATE, plan.fps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            caps.bestProfile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        }

        val c = MediaCodec.createByCodecName(caps.name)
        c.setCallback(
            object : MediaCodec.Callback() {
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
            },
            handler,
        )
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.setInputSurface(inputSurface)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        codec = c
        Log.i(TAG, "kodlayıcı: ${caps.name} ${plan.width}x${plan.height}@${plan.fps} ${plan.startBps} bps")
    }

    override fun requestKeyframe() {
        setParams(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    override fun setBitrate(bps: Long) {
        setParams(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps.toInt()) })
    }

    private fun setParams(params: Bundle) {
        try {
            codec?.setParameters(params)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setParameters", e)
        }
    }

    override fun stop() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop", e)
        }
        c.release()
    }

    /** Kodlayıcıyı ve kalıcı Surface'ı bırakır; nesne bir daha kullanılamaz. */
    fun release() {
        stop()
        inputSurface.release()
        thread.quitSafely()
    }

    private companion object {
        const val I_FRAME_INTERVAL_S = 2
        const val REPEAT_AFTER_US = 100_000L
    }
}
