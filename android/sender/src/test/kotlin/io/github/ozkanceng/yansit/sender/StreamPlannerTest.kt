package io.github.ozkanceng.yansit.sender

import io.github.ozkanceng.yansit.protocol.Codec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamPlannerTest {
    /** Tipik donanım kodlayıcı: 16 hizalama, piksel hızı sınırı ile kare hızı. */
    private class FakeEncoder(
        override val name: String = "c2.fake.avc.encoder",
        override val hardware: Boolean = true,
        val maxW: Int = 1920,
        val maxH: Int = 1088,
        val maxPixelsPerSec: Long = 1920L * 1088 * 60,
        override val widthAlignment: Int = 16,
        override val heightAlignment: Int = 16,
        override val bitrateRange: LongRange = 64_000L..40_000_000L,
    ) : VideoEncoderCaps {
        override val codec: Int = Codec.H264
        override fun isSupported(width: Int, height: Int, fps: Int): Boolean {
            val fitsLandscape = width <= maxW && height <= maxH
            val fitsPortrait = width <= maxH && height <= maxW
            return (fitsLandscape || fitsPortrait) && width % widthAlignment == 0 && height % heightAlignment == 0 &&
                width.toLong() * height * fps <= maxPixelsPerSec
        }
    }

    private val board1080 = ReceiverDisplay(1920, 1080, 60)

    @Test
    fun bestPresetScales16by10TabletToFit1080pBoard() {
        val plan = StreamPlanner.plan(2560, 1600, Preset.BEST, board1080, listOf(FakeEncoder()))
        assertNotNull(plan)
        plan!!
        // 2560x1600 → 1728x1080 (alıcının kısa kenarı sınırlar), 16'ya hizalı.
        assertEquals(1728, plan.width)
        assertEquals(1072, plan.height) // 1080 → 16'nın katı
        assertEquals(60, plan.fps)
        assertEquals(16_000_000, plan.startBps)
        assertEquals(24_000_000, plan.maxBps)
        assertTrue(plan.hardware)
    }

    @Test
    fun portraitSourceFitsBoardHeight() {
        // Dikey tablet, yatay tahtada kenar boşluklu gösterilir: yükseklik tahtanın 1080 pikseliyle sınırlı.
        val plan = StreamPlanner.plan(1200, 1920, Preset.BALANCED, board1080, listOf(FakeEncoder()))!!
        assertTrue(plan.height > plan.width)
        assertEquals(1072, plan.height) // 1080 → 16'nın katı
        assertEquals(0.625, plan.width.toDouble() / plan.height, 0.02)
    }

    @Test
    fun neverUpscalesSmallSource() {
        val plan = StreamPlanner.plan(1280, 800, Preset.BEST, board1080, listOf(FakeEncoder()))!!
        assertEquals(1280, plan.width)
        assertEquals(800, plan.height)
    }

    @Test
    fun weakNetworkPresetUses30FpsAndLowBitrate() {
        val plan = StreamPlanner.plan(2560, 1600, Preset.WEAK_NETWORK, board1080, listOf(FakeEncoder()))!!
        assertEquals(30, plan.fps)
        assertEquals(1280, plan.width)
        assertEquals(800, plan.height)
        assertEquals(3_000_000, plan.startBps)
        assertEquals(800_000, plan.minBps)
    }

    @Test
    fun receiverRefreshLimitsFps() {
        val plan = StreamPlanner.plan(2560, 1600, Preset.BEST, ReceiverDisplay(1920, 1080, 30), listOf(FakeEncoder()))!!
        assertEquals(30, plan.fps)
    }

    @Test
    fun highFpsOnlyWhenResolutionStaysCloseToFull() {
        // 60 fps'te 1552x960'a inebilen kodlayıcı: 30 fps boyutunun (1728) %75'inden büyük → 60 fps.
        val enc45 = FakeEncoder(maxPixelsPerSec = 1920L * 1088 * 45)
        val plan = StreamPlanner.plan(2560, 1600, Preset.BEST, board1080, listOf(enc45))!!
        assertEquals(60, plan.fps)
        assertEquals(1552, plan.width)
        assertTrue(plan.width.toLong() * plan.height * 60 <= enc45.maxPixelsPerSec)

        // 1080p30 kodlayıcı 60 fps'te ancak 1248'e iner (%72) → metin okunurluğu için 30 fps.
        val enc30 = FakeEncoder(maxPixelsPerSec = 1920L * 1088 * 30)
        val plan2 = StreamPlanner.plan(2560, 1600, Preset.BEST, board1080, listOf(enc30))!!
        assertEquals(30, plan2.fps)
        assertEquals(1728, plan2.width)
    }

    @Test
    fun prefersHardwareEncoder() {
        val sw = FakeEncoder(name = "c2.android.avc.encoder", hardware = false)
        val hw = FakeEncoder(name = "c2.qti.avc.encoder", hardware = true)
        assertEquals("c2.qti.avc.encoder", StreamPlanner.plan(1920, 1200, Preset.BEST, board1080, listOf(sw, hw))!!.encoderName)
    }

    @Test
    fun bitrateIsClampedToEncoderRange() {
        val enc = FakeEncoder(bitrateRange = 100_000L..10_000_000L)
        val plan = StreamPlanner.plan(1920, 1200, Preset.BEST, board1080, listOf(enc))!!
        assertEquals(10_000_000, plan.maxBps)
        assertEquals(10_000_000, plan.startBps)
        assertEquals(4_000_000, plan.minBps)
    }

    @Test
    fun overridesApply() {
        val plan = StreamPlanner.plan(
            2560, 1600, Preset.BEST, board1080, listOf(FakeEncoder()),
            StreamOverrides(longSide = 1280, fps = 30, maxBitrateBps = 6_000_000),
        )!!
        assertEquals(1280, plan.width)
        assertEquals(30, plan.fps)
        assertEquals(6_000_000, plan.maxBps)
        assertEquals(6_000_000, plan.startBps)
    }

    @Test
    fun uses2560OnlyFor4kBoardWithCapableEncoder() {
        val big = FakeEncoder(maxW = 4096, maxH = 2304, maxPixelsPerSec = 4096L * 2304 * 30)
        val board4k = ReceiverDisplay(3840, 2160, 60)
        val plan = StreamPlanner.plan(2560, 1600, Preset.BEST, board4k, listOf(big))!!
        assertEquals(2560, plan.width)
        val planHd = StreamPlanner.plan(2560, 1600, Preset.BEST, board4k, listOf(FakeEncoder()))!!
        assertEquals(1728, planHd.width)
    }

    @Test
    fun noEncoderMeansNoPlan() {
        assertNull(StreamPlanner.plan(1920, 1200, Preset.BEST, board1080, emptyList()))
        val useless = FakeEncoder(maxW = 176, maxH = 144)
        assertNull(StreamPlanner.plan(1920, 1200, Preset.BEST, board1080, listOf(useless)))
    }

    @Test
    fun settingsOnlyOfferSupportedOptions() {
        val enc720 = FakeEncoder(maxW = 1280, maxH = 720, maxPixelsPerSec = 1280L * 720 * 30)
        assertEquals(listOf(30), StreamPlanner.supportedFps(listOf(enc720), 2560, 1600))
        assertEquals(listOf(30, 60), StreamPlanner.supportedFps(listOf(FakeEncoder()), 2560, 1600))
        // 16:10 kaynakta 1920 uzun kenar = 1920x1200; 1088 yükseklik sınırlı kodlayıcı bunu yapamaz.
        assertEquals(listOf(1280, 1600), StreamPlanner.supportedLongSides(listOf(FakeEncoder()), 2560, 1600))
        assertEquals(listOf(1280, 1600, 1920), StreamPlanner.supportedLongSides(listOf(FakeEncoder()), 1920, 1080))
        assertTrue(StreamPlanner.supportedLongSides(listOf(enc720), 2560, 1600).isEmpty() ||
            StreamPlanner.supportedLongSides(listOf(enc720), 2560, 1600).all { it <= 1280 })
    }
}

class PresetLadderTest {
    @org.junit.jupiter.api.Test
    fun `presets step one at a time and stop at the ends`() {
        org.junit.jupiter.api.Assertions.assertEquals(Preset.BALANCED, Preset.BEST.lower)
        org.junit.jupiter.api.Assertions.assertEquals(Preset.WEAK_NETWORK, Preset.BALANCED.lower)
        org.junit.jupiter.api.Assertions.assertNull(Preset.WEAK_NETWORK.lower)
        org.junit.jupiter.api.Assertions.assertEquals(Preset.BALANCED, Preset.WEAK_NETWORK.higher)
        org.junit.jupiter.api.Assertions.assertNull(Preset.BEST.higher)
    }

    @Test
    fun `a session starts where the network last settled but never above the chosen preset`() {
        assertEquals(Preset.BEST, Preset.start(Preset.BEST, null))
        assertEquals(Preset.WEAK_NETWORK, Preset.start(Preset.BEST, Preset.WEAK_NETWORK))
        assertEquals(Preset.BALANCED, Preset.start(Preset.BALANCED, Preset.BEST))
        assertEquals(Preset.WEAK_NETWORK, Preset.start(Preset.WEAK_NETWORK, Preset.BALANCED))
    }
}
