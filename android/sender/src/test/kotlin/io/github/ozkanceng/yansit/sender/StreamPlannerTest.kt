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
        // 1280x800@30 is 31 % of BEST's pixel rate: the start bit rate follows, the teacher's maximum stays.
        assertEquals(4_938_271, plan.startBps)
    }

    @Test
    fun neverGoesAbove1920EvenForA4kBoardOrAnOverride() {
        val big = FakeEncoder(maxW = 4096, maxH = 2304, maxPixelsPerSec = 4096L * 2304 * 30)
        val board4k = ReceiverDisplay(3840, 2160, 60)
        val plan = StreamPlanner.plan(2560, 1600, Preset.BEST, board4k, listOf(big))!!
        assertEquals(1920, plan.width)
        val forced = StreamPlanner.plan(2560, 1600, Preset.BEST, board4k, listOf(big), StreamOverrides(longSide = 2560))!!
        assertEquals(1920, forced.width)
        assertEquals(listOf(1280, 1600, 1920), StreamPlanner.supportedLongSides(listOf(big), 2560, 1600))
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

    @Test
    fun aQualityStepKeepsTheFrameSize() {
        val enc = FakeEncoder()
        val base = StreamPlanner.plan(2560, 1600, Preset.BALANCED, board1080, listOf(enc))!!
        val weak = StreamPlanner.atFixedSize(base, Preset.WEAK_NETWORK, board1080, enc)!!
        assertEquals(base.width, weak.width)
        assertEquals(base.height, weak.height)
        assertEquals(base.encoderName, weak.encoderName)
        assertEquals(30, weak.fps)
        assertEquals(800_000, weak.minBps)
        assertEquals(5_000_000, weak.maxBps)

        val back = StreamPlanner.atFixedSize(weak, Preset.BALANCED, board1080, enc)!!
        assertEquals(base, back)
    }

    @Test
    fun aFixedSizeTheEncoderCannotRunFastFallsBackTo30() {
        val slow = FakeEncoder(maxPixelsPerSec = 1280L * 800 * 30)
        val base = StreamPlanner.plan(2560, 1600, Preset.WEAK_NETWORK, board1080, listOf(slow))!!
        val best = StreamPlanner.atFixedSize(base, Preset.BEST, board1080, slow)!!
        assertEquals(base.width, best.width)
        assertEquals(30, best.fps)
        assertEquals(7_407_407, best.maxBps) // BEST's numbers for 31 % of its pixel rate
    }

    @Test
    fun aFixedSizeKeepsTheEncodersBitrateLimits() {
        val narrow = FakeEncoder(bitrateRange = 1_000_000L..10_000_000L)
        val base = StreamPlanner.plan(2560, 1600, Preset.BEST, board1080, listOf(narrow))!!
        val weak = StreamPlanner.atFixedSize(base, Preset.WEAK_NETWORK, board1080, narrow)!!
        assertEquals(1_000_000, weak.minBps)
        assertEquals(5_000_000, weak.maxBps)
        assertEquals(10_000_000, StreamPlanner.atFixedSize(weak, Preset.BEST, board1080, narrow)!!.maxBps)
    }

    @Test
    fun aSmallBoardGetsASmallerBitrate() {
        val plan = StreamPlanner.plan(2560, 1600, Preset.BALANCED, ReceiverDisplay(1280, 720, 60), listOf(FakeEncoder()))!!
        assertEquals(1152, plan.width)
        assertEquals(720, plan.height)
        // 1152x720 is 72 % of BALANCED's full pixel rate (80 % of 1600x900).
        assertEquals(5_760_000, plan.startBps)
        assertEquals(8_640_000, plan.maxBps)
        assertEquals(1_440_000, plan.minBps)
    }

    @Test
    fun aFullHdBoardKeepsThePresetsBitrates() {
        for (preset in Preset.entries) {
            val plan = StreamPlanner.plan(2560, 1600, preset, board1080, listOf(FakeEncoder()))!!
            assertEquals(preset.startBps, plan.startBps, preset.name)
            assertEquals(preset.maxBps, plan.maxBps, preset.name)
            assertEquals(preset.minBps, plan.minBps, preset.name)
        }
    }

    @Test
    fun theFloorNeverGoesBelowHalfAMegabit() {
        val tiny = StreamPlanner.plan(2560, 1600, Preset.WEAK_NETWORK, ReceiverDisplay(640, 400, 30), listOf(FakeEncoder()))!!
        assertEquals(500_000, tiny.minBps)
        assertTrue(tiny.startBps >= tiny.minBps)
        assertTrue(tiny.maxBps >= tiny.startBps)
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
