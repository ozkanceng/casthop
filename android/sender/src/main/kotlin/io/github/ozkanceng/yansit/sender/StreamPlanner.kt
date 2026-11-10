package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.Codec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video kodlayıcının platformdan bağımsız yetenek görünümü. Android'de `MediaCodecInfo` ile,
 * birim testlerinde sahte olarak uygulanır.
 */
interface VideoEncoderCaps {
    val name: String
    /** Protokol codec kimliği (`Codec.H264`, `Codec.H265`). */
    val codec: Int
    val hardware: Boolean
    val widthAlignment: Int
    val heightAlignment: Int
    val bitrateRange: LongRange
    fun isSupported(width: Int, height: Int, fps: Int): Boolean
}

/** Ön ayarlar (ARCHITECTURE.md §2.1). Cihaz ve alıcı yeteneğiyle kırpılır. */
enum class Preset(
    val longSideCap: Int,
    val fps: Int,
    val startBps: Long,
    val maxBps: Long,
    val minBps: Long,
) {
    BEST(1920, 60, 16_000_000, 24_000_000, 4_000_000),
    BALANCED(1600, 60, 8_000_000, 12_000_000, 2_000_000),
    WEAK_NETWORK(1280, 30, 3_000_000, 5_000_000, 800_000),
    ;

    /** The next preset towards a weaker network; `null` at the end. */
    val lower: Preset? get() = entries.getOrNull(ordinal + 1)

    /** The next preset towards better quality; `null` at the end. */
    val higher: Preset? get() = entries.getOrNull(ordinal - 1)

    companion object {
        /**
         * Hard limit for the encoder's long side, advanced settings included. Samsung's Exynos encoder driver
         * (`exynos_mfc`) crashed the whole tablet (kernel panic) while setting up a 2560x1600 H.264 encoder; from a
         * board 2-5 m away 1920 looks the same and needs less of the network.
         */
        const val MAX_LONG_SIDE: Int = 1920

        /**
         * The preset a session starts at: the one the network last settled on for this receiver, so a slow link does
         * not freeze again while the governor steps down. Never better than the one the teacher chose.
         */
        fun start(chosen: Preset, remembered: Preset?): Preset =
            if (remembered != null && remembered > chosen) remembered else chosen
    }
}

/** Gelişmiş ayarlar: `null` = ön ayarın değeri. */
data class StreamOverrides(
    val longSide: Int? = null,
    val fps: Int? = null,
    val maxBitrateBps: Long? = null,
)

/** Alıcının WELCOME'da bildirdiği ekran. */
data class ReceiverDisplay(val width: Int, val height: Int, val refreshHz: Int)

data class StreamPlan(
    val encoderName: String,
    val codec: Int,
    val hardware: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val startBps: Long,
    val minBps: Long,
    val maxBps: Long,
)

/** Kaynak ekran, ön ayar, alıcı ve kodlayıcı yeteneklerinden yayın biçimini seçer. */
object StreamPlanner {
    private const val MIN_SHORT_SIDE = 360
    private const val SCALE_STEP = 0.9
    private const val HIGH_FPS_MIN_RATIO = 0.75
    private const val USEFUL_LONG_SIDE = 1024
    /** Below this even a still slide turns to mush; the governor's floor never goes lower on a small frame. */
    private const val MIN_BITRATE_FLOOR = 500_000L
    private const val FULL_RATE_SHARE = 0.8
    /** H.265 needs about a third less data than H.264 for the same picture; the presets are H.264 numbers. */
    internal const val HEVC_RATE_SHARE = 0.65

    /**
     * @param sourceWidth kaynak (yansıtılan ekran) genişliği, piksel — yönelime göre
     * @param encoders desteklenen codec'lere göre süzülmüş, tercih sırasındaki kodlayıcılar
     * @return uygun bir biçim yoksa `null`
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        preset: Preset,
        receiver: ReceiverDisplay?,
        encoders: List<VideoEncoderCaps>,
        overrides: StreamOverrides = StreamOverrides(),
    ): StreamPlan? {
        require(sourceWidth > 0 && sourceHeight > 0)
        val ordered = encoders.sortedByDescending { it.hardware } // donanım önce, aynı türde sıra korunur
        val fpsWanted = min(overrides.fps ?: preset.fps, receiver?.refreshHz?.coerceAtLeast(30) ?: 60)
        for (enc in ordered) {
            val longCap = longSideCap(preset, overrides)
            val high = largestSupportedSize(sourceWidth, sourceHeight, longCap, receiver, enc, fpsWanted)
            val low = if (fpsWanted > 30) largestSupportedSize(sourceWidth, sourceHeight, longCap, receiver, enc, 30) else null
            // Yüksek kare hızı ancak çözünürlükten fazla ödün vermiyorsa seçilir (metin okunurluğu önce gelir).
            val (size, fps) = when {
                high != null && (low == null || max(high.first, high.second) >= HIGH_FPS_MIN_RATIO * max(low.first, low.second)) ->
                    high to fpsWanted
                low != null -> low to 30
                else -> continue
            }
            val (startBps, minBps, maxBps) = bitrates(preset, enc, overrides, size.first, size.second, fps)
            return StreamPlan(enc.name, enc.codec, enc.hardware, size.first, size.second, fps, startBps, minBps, maxBps)
        }
        return null
    }

    /**
     * [preset]'s bit rates and frame rate at the session's fixed frame size: a quality step never changes the size
     * (the encoder keeps running). 30 fps if [enc] cannot do the preset's rate at that size.
     *
     * @param base the session's plan; its size and encoder are kept
     * @return `null` if [enc] cannot encode [base]'s size at all
     */
    fun atFixedSize(
        base: StreamPlan,
        preset: Preset,
        receiver: ReceiverDisplay?,
        enc: VideoEncoderCaps,
        overrides: StreamOverrides = StreamOverrides(),
    ): StreamPlan? {
        val fpsWanted = min(overrides.fps ?: preset.fps, receiver?.refreshHz?.coerceAtLeast(30) ?: 60)
        val fps = listOf(fpsWanted, 30).firstOrNull { enc.isSupported(base.width, base.height, it) } ?: return null
        val (startBps, minBps, maxBps) = bitrates(preset, enc, overrides, base.width, base.height, fps)
        return base.copy(fps = fps, startBps = startBps, minBps = minBps, maxBps = maxBps)
    }

    /**
     * (start, min, max) for [preset] at [width]×[height]@[fps], within what [enc] accepts.
     *
     * The preset's numbers are for a board-sized frame at its long side (16:9) and frame rate; anything from
     * [FULL_RATE_SHARE] of that pixel rate up counts as full (a 16:10 tablet on a 1080p board). A smaller pixel rate
     * (a 1280×720 board, a 30 fps step) gets proportionally less, never more: the same picture quality without
     * filling the network. H.265 gets [HEVC_RATE_SHARE] of the H.264 numbers: the same picture with less of a
     * crowded classroom network. The teacher's own maximum ([StreamOverrides.maxBitrateBps]) is kept as given.
     */
    internal fun bitrates(
        preset: Preset,
        enc: VideoEncoderCaps,
        overrides: StreamOverrides,
        width: Int,
        height: Int,
        fps: Int,
    ): Triple<Long, Long, Long> {
        val referenceRate = preset.longSideCap.toDouble() * (preset.longSideCap * 9 / 16) * preset.fps * FULL_RATE_SHARE
        val codecShare = if (enc.codec == Codec.H265) HEVC_RATE_SHARE else 1.0
        val scale = (width.toDouble() * height * fps / referenceRate).coerceIn(0.0, 1.0) * codecShare
        fun scaled(bps: Long) = (bps * scale).toLong()
        val maxBps = min(overrides.maxBitrateBps ?: max(scaled(preset.maxBps), MIN_BITRATE_FLOOR), enc.bitrateRange.last)
            .coerceAtLeast(enc.bitrateRange.first)
        val minBps = max(scaled(preset.minBps), min(MIN_BITRATE_FLOOR, preset.minBps)).coerceIn(enc.bitrateRange.first, maxBps)
        val startBps = scaled(preset.startBps).coerceIn(minBps, maxBps)
        return Triple(startBps, minBps, maxBps)
    }

    /** Ayarlar ekranında gösterilecek kare hızları: kullanışlı bir çözünürlükte (uzun kenar ≥ 1024) desteklenenler. */
    fun supportedFps(encoders: List<VideoEncoderCaps>, sourceWidth: Int, sourceHeight: Int): List<Int> =
        listOf(30, 60).filter { fps ->
            encoders.any { enc ->
                largestSupportedSize(sourceWidth, sourceHeight, 1280, null, enc, fps)
                    ?.let { max(it.first, it.second) >= USEFUL_LONG_SIDE } == true
            }
        }

    /** Ayarlar ekranında gösterilecek uzun kenar seçenekleri: kodlayıcının 30 fps'te desteklediği. */
    fun supportedLongSides(encoders: List<VideoEncoderCaps>, sourceWidth: Int, sourceHeight: Int): List<Int> =
        listOf(1280, 1600, 1920).filter { side ->
            val (w, h) = fit(sourceWidth, sourceHeight, side, null)
            encoders.any { enc ->
                val aw = alignDown(w, enc.widthAlignment)
                val ah = alignDown(h, enc.heightAlignment)
                max(aw, ah) >= side * 0.95 && enc.isSupported(aw, ah, 30)
            }
        }

    private fun longSideCap(preset: Preset, o: StreamOverrides): Int =
        min(o.longSide ?: preset.longSideCap, Preset.MAX_LONG_SIDE)

    private fun largestSupportedSize(
        sw: Int,
        sh: Int,
        longCap: Int,
        receiver: ReceiverDisplay?,
        enc: VideoEncoderCaps,
        fps: Int,
    ): Pair<Int, Int>? {
        var (w, h) = fit(sw, sh, longCap, receiver)
        while (min(w, h) >= MIN_SHORT_SIDE) {
            val aw = alignDown(w, enc.widthAlignment)
            val ah = alignDown(h, enc.heightAlignment)
            if (enc.isSupported(aw, ah, fps)) return aw to ah
            w = (w * SCALE_STEP).roundToInt()
            h = (h * SCALE_STEP).roundToInt()
        }
        return null
    }

    /**
     * En-boy oranını koruyarak tavana ve alıcı ekranına sığdırır; büyütmez. Tahta dönmez: dikey bir kaynak
     * yatay tahtada kenar boşluklu gösterilir, bu yüzden alıcının genişlik/yüksekliği olduğu gibi kullanılır.
     */
    internal fun fit(sw: Int, sh: Int, longCap: Int, receiver: ReceiverDisplay?): Pair<Int, Int> {
        var scale = min(1.0, longCap.toDouble() / max(sw, sh))
        if (receiver != null) {
            scale = min(scale, min(receiver.width.toDouble() / sw, receiver.height.toDouble() / sh))
        }
        return (sw * scale).roundToInt() to (sh * scale).roundToInt()
    }

    internal fun alignDown(v: Int, alignment: Int): Int {
        val a = max(2, alignment)
        return v / a * a
    }
}
