package io.github.ozkanceng.yansit.stream

import io.github.ozkanceng.yansit.sender.VideoEncoderCaps
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import io.github.ozkanceng.yansit.protocol.Codec

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

    val mime: String = mime

    /**
     * H.264: High → Main → Baseline (B-kare kapalıyken High aynı bit hızında daha iyi kalite verir).
     * H.265: Main (8 bit; alıcıdaki VA çözücülerin hepsi destekler).
     */
    val bestProfile: Int?
        get() {
            val profiles = caps.profileLevels.map { it.profile }.toSet()
            val order = if (codec == Codec.H265) {
                listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            } else {
                listOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                )
            }
            return order.firstOrNull { it in profiles }
        }

    companion object {
        /** Cihazdaki H.264 kodlayıcıları (takma adlar hariç), donanım önce. */
        fun h264Encoders(): List<AndroidEncoderCaps> = encodersFor(MediaFormat.MIMETYPE_VIDEO_AVC)

        /** Cihazdaki donanım H.265 kodlayıcıları (yazılım HEVC kodlama gerçek zamanlı yetişmez). */
        fun hevcHardwareEncoders(): List<AndroidEncoderCaps> =
            encodersFor(MediaFormat.MIMETYPE_VIDEO_HEVC).filter { it.hardware }

        private fun encodersFor(mime: String): List<AndroidEncoderCaps> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && !it.isAlias && mime in it.supportedTypes }
                .mapNotNull { info ->
                    val caps = info.getCapabilitiesForType(mime)
                    val video = caps.videoCapabilities ?: return@mapNotNull null
                    val encoder = caps.encoderCapabilities ?: return@mapNotNull null
                    AndroidEncoderCaps(info, mime, caps, video, encoder)
                }
                .sortedByDescending { it.hardware }
    }
}
