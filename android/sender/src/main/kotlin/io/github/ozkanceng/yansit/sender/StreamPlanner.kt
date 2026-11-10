package io.github.ozkanceng.yansit.sender

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
        /** En iyi kalitede alıcı 4K ise ve kodlayıcı izin verirse uzun kenar tavanı. */
        const val BEST_4K_LONG_SIDE: Int = 2560

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
            val longCap = longSideCap(preset, receiver, enc, overrides)
            val high = largestSupportedSize(sourceWidth, sourceHeight, longCap, receiver, enc, fpsWanted)
            val low = if (fpsWanted > 30) largestSupportedSize(sourceWidth, sourceHeight, longCap, receiver, enc, 30) else null
            // Yüksek kare hızı ancak çözünürlükten fazla ödün vermiyorsa seçilir (metin okunurluğu önce gelir).
            val (size, fps) = when {
                high != null && (low == null || max(high.first, high.second) >= HIGH_FPS_MIN_RATIO * max(low.first, low.second)) ->
                    high to fpsWanted
                low != null -> low to 30
                else -> continue
            }
            val maxBps = min(overrides.maxBitrateBps ?: preset.maxBps, enc.bitrateRange.last)
                .coerceAtLeast(enc.bitrateRange.first)
            val minBps = preset.minBps.coerceIn(enc.bitrateRange.first, maxBps)
            val startBps = preset.startBps.coerceIn(minBps, maxBps)
            return StreamPlan(enc.name, enc.codec, enc.hardware, size.first, size.second, fps, startBps, minBps, maxBps)
        }
        return null
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
        listOf(1280, 1600, 1920, 2560).filter { side ->
            val (w, h) = fit(sourceWidth, sourceHeight, side, null)
            encoders.any { enc ->
                val aw = alignDown(w, enc.widthAlignment)
                val ah = alignDown(h, enc.heightAlignment)
                max(aw, ah) >= side * 0.95 && enc.isSupported(aw, ah, 30)
            }
        }

    private fun longSideCap(preset: Preset, receiver: ReceiverDisplay?, enc: VideoEncoderCaps, o: StreamOverrides): Int {
        o.longSide?.let { return it }
        if (preset == Preset.BEST && receiver != null && max(receiver.width, receiver.height) >= 3840 &&
            enc.isSupported(Preset.BEST_4K_LONG_SIDE, Preset.BEST_4K_LONG_SIDE * 9 / 16, 30)
        ) {
            return Preset.BEST_4K_LONG_SIDE
        }
        return preset.longSideCap
    }

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
