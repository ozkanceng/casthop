package io.github.ozkanceng.casthop.tv.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.receiver.AudioFormat
import io.github.ozkanceng.yansit.receiver.VideoSize

/** What this device's decoders can do. Every codec setting in the app comes from here, never from a device model. */
internal class DecoderCatalog {
    class Decoder(val info: MediaCodecInfo, private val mime: String) {
        val name: String get() = info.name
        val hardware: Boolean get() = info.isHardwareAccelerated && !info.isSoftwareOnly
        private val capabilities: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)

        /** FEATURE_LowLatency: the decoder can hand a frame out without waiting for the next one. */
        val lowLatency: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)

        fun supports(size: VideoSize): Boolean =
            capabilities.videoCapabilities?.isSizeSupported(size.width, size.height) == true

        fun supports(format: AudioFormat): Boolean {
            val audio = capabilities.audioCapabilities ?: return false
            return audio.isSampleRateSupported(format.sampleRate) && format.channels <= audio.maxInputChannelCount
        }
    }

    private val infos: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }

    /** Decoders for [mime], hardware first. */
    fun decoders(mime: String): List<Decoder> = infos
        .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        .map { Decoder(it, mime) }
        .sortedBy { !it.hardware }

    /** For WELCOME. §11: H.265 is only offered with a hardware decoder, H.264 is the preferred codec. */
    val videoSupport: List<CodecSupport> by lazy {
        buildList {
            decoders(MediaFormat.MIMETYPE_VIDEO_AVC).firstOrNull()?.let {
                add(CodecSupport(Codec.H264, CodecSupport.FLAG_PREFERRED or hardwareFlag(it)))
            }
            decoders(MediaFormat.MIMETYPE_VIDEO_HEVC).firstOrNull { it.hardware }?.let {
                add(CodecSupport(Codec.H265, hardwareFlag(it)))
            }
        }
    }

    val audioSupport: List<CodecSupport> by lazy {
        buildList {
            if (decoders(MediaFormat.MIMETYPE_AUDIO_OPUS).isNotEmpty()) {
                add(CodecSupport(Codec.OPUS, CodecSupport.FLAG_PREFERRED))
            }
            if (decoders(MediaFormat.MIMETYPE_AUDIO_AAC).isNotEmpty()) add(CodecSupport(Codec.AAC_LC, 0))
        }
    }

    private fun hardwareFlag(decoder: Decoder): Int = if (decoder.hardware) CodecSupport.FLAG_HARDWARE else 0
}
