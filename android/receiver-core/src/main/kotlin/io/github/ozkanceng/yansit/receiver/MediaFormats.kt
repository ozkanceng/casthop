package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Codec

/** Coded picture size after cropping. */
public data class VideoSize(val width: Int, val height: Int)

/**
 * Reads the picture size from a CONFIG frame's SPS. MediaCodec needs a size before the first frame, and the decoder
 * capability checks need the real one.
 */
public object VideoConfig {
    private const val H264_SPS = 7
    private const val H265_SPS = 33

    /** `null` when [annexB] holds no SPS this parser can read. */
    public fun parseSize(codec: Int, annexB: ByteArray): VideoSize? = try {
        when (codec) {
            Codec.H264 -> findNal(annexB, 1) { (it.toInt() and 0x1F) == H264_SPS }?.let(::parseH264)
            Codec.H265 -> findNal(annexB, 2) { ((it.toInt() ushr 1) and 0x3F) == H265_SPS }?.let(::parseH265)
            else -> null
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** The RBSP after the NAL header of the first unit [matches] accepts. */
    private fun findNal(data: ByteArray, headerSize: Int, matches: (Byte) -> Boolean): ByteArray? {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() != 0 || data[i + 1].toInt() != 0 || data[i + 2].toInt() != 1) {
                i++
                continue
            }
            val start = i + 3
            var end = start
            while (end < data.size &&
                !(end + 2 < data.size && data[end].toInt() == 0 && data[end + 1].toInt() == 0 && data[end + 2].toInt() in 0..1)
            ) {
                end++
            }
            if (start < data.size && matches(data[start])) return unescape(data, start + headerSize, end)
            i = end
        }
        return null
    }

    /** Removes emulation prevention bytes. */
    private fun unescape(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var n = 0
        var zeros = 0
        for (i in from until to) {
            val b = data[i]
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            zeros = if (b.toInt() == 0) zeros + 1 else 0
            out[n++] = b
        }
        return out.copyOf(n)
    }

    private fun parseH264(rbsp: ByteArray): VideoSize {
        val r = BitReader(rbsp)
        val profile = r.bits(8)
        r.skip(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        var separatePlanes = false
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) separatePlanes = r.bit()
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (r.bit()) skipScalingLists(r, if (chromaFormat == 3) 12 else 8)
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (!frameMbsOnly) r.skip(1) // mb_adaptive_frame_field_flag
        r.skip(1) // direct_8x8_inference_flag
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (r.bit()) {
            cropLeft = r.ue()
            cropRight = r.ue()
            cropTop = r.ue()
            cropBottom = r.ue()
        }
        val chroma = if (separatePlanes) 0 else chromaFormat
        val cropUnitX = if (chroma == 1 || chroma == 2) 2 else 1
        val cropUnitY = (if (chroma == 1) 2 else 1) * (if (frameMbsOnly) 1 else 2)
        return VideoSize(
            widthMbs * 16 - (cropLeft + cropRight) * cropUnitX,
            heightMapUnits * 16 * (if (frameMbsOnly) 1 else 2) - (cropTop + cropBottom) * cropUnitY,
        )
    }

    private fun skipScalingLists(r: BitReader, count: Int) {
        for (i in 0 until count) {
            if (!r.bit()) continue
            val size = if (i < 6) 16 else 64
            var last = 8
            var next = 8
            for (j in 0 until size) {
                if (next != 0) next = (last + r.se() + 256) % 256
                last = if (next == 0) last else next
            }
        }
    }

    private fun parseH265(rbsp: ByteArray): VideoSize {
        val r = BitReader(rbsp)
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayers = r.bits(3)
        r.skip(1) // sps_temporal_id_nesting_flag
        r.skip(GENERAL_PROFILE_BITS + LEVEL_BITS)
        val profilePresent = BooleanArray(maxSubLayers)
        val levelPresent = BooleanArray(maxSubLayers)
        for (i in 0 until maxSubLayers) {
            profilePresent[i] = r.bit()
            levelPresent[i] = r.bit()
        }
        if (maxSubLayers > 0) r.skip(2 * (8 - maxSubLayers))
        for (i in 0 until maxSubLayers) {
            if (profilePresent[i]) r.skip(GENERAL_PROFILE_BITS)
            if (levelPresent[i]) r.skip(LEVEL_BITS)
        }
        r.ue() // sps_seq_parameter_set_id
        val chromaFormat = r.ue()
        val separatePlanes = chromaFormat == 3 && r.bit()
        val width = r.ue()
        val height = r.ue()
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (r.bit()) {
            cropLeft = r.ue()
            cropRight = r.ue()
            cropTop = r.ue()
            cropBottom = r.ue()
        }
        val chroma = if (separatePlanes) 0 else chromaFormat
        val subWidth = if (chroma == 1 || chroma == 2) 2 else 1
        val subHeight = if (chroma == 1) 2 else 1
        return VideoSize(width - (cropLeft + cropRight) * subWidth, height - (cropTop + cropBottom) * subHeight)
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    private const val GENERAL_PROFILE_BITS = 88
    private const val LEVEL_BITS = 8

    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bit(): Boolean {
            val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b == 1
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or (if (bit()) 1 else 0) }
            return v
        }

        fun skip(n: Int) {
            pos += n
        }

        fun ue(): Int {
            var zeros = 0
            while (!bit()) {
                if (++zeros > 31) throw IndexOutOfBoundsException("exp-Golomb code too long")
            }
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}

/** What a decoder needs from an audio CONFIG payload (PROTOCOL.md §6). */
public data class AudioFormat(val sampleRate: Int, val channels: Int, val preSkipSamples: Int = 0)

public object AudioConfig {
    private const val OPUS_HEAD_SIZE = 19
    private const val OPUS_OUTPUT_RATE = 48_000
    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** RFC 7845 `OpusHead`. Opus always decodes at 48 kHz; the header's rate is only the original input rate. */
    public fun parseOpusHead(head: ByteArray): AudioFormat? {
        if (head.size < OPUS_HEAD_SIZE || String(head, 0, 8, Charsets.US_ASCII) != "OpusHead") return null
        val channels = head[9].toInt() and 0xFF
        if (channels == 0) return null
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        return AudioFormat(OPUS_OUTPUT_RATE, channels, preSkip)
    }

    /** ISO 14496-3 `AudioSpecificConfig`, AAC-LC with a table sampling rate. */
    public fun parseAudioSpecificConfig(config: ByteArray): AudioFormat? {
        if (config.size < 2) return null
        val b0 = config[0].toInt() and 0xFF
        val b1 = config[1].toInt() and 0xFF
        val rateIndex = ((b0 and 0x07) shl 1) or (b1 ushr 7)
        val channels = (b1 ushr 3) and 0x0F
        if (rateIndex >= AAC_RATES.size || channels == 0) return null
        return AudioFormat(AAC_RATES[rateIndex], channels)
    }
}
