package io.github.ozkanceng.yansit.tools.fakesender

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnnexBTest {
    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)

    // NAL başlığı: forbidden=0, nal_ref_idc=3, tür. Dilim yükünün ilk biti first_mb_in_slice (1 → 0 değeri).
    private val aud = byteArrayOf(0x09, 0xF0.toByte())
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val sei = byteArrayOf(0x06, 0x05, 0x01)
    private fun idr(firstMb: Boolean) = byteArrayOf(0x65, if (firstMb) 0x88.toByte() else 0x41, 0x22)
    private fun slice(firstMb: Boolean) = byteArrayOf(0x41, if (firstMb) 0x9A.toByte() else 0x42, 0x11)

    private fun join(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { acc, b -> acc + b }

    @Test
    fun splitsNalsWithThreeAndFourByteStartCodes() {
        val data = join(sc4, sps, sc3, pps, byteArrayOf(0), sc3, idr(true))
        val nals = AnnexB.splitNals(data)
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1]) // sonraki başlangıç kodunun önündeki sıfır atılır
        assertArrayEquals(idr(true), nals[2])
    }

    @Test
    fun groupsNalsIntoAccessUnits() {
        val data = join(
            sc4, aud, sc4, sps, sc4, pps, sc4, sei, sc4, idr(true), sc4, idr(false), // AU 0: iki dilimli IDR
            sc4, aud, sc4, slice(true), // AU 1
            sc4, slice(true), sc4, slice(false), // AU 2 (AUD'siz, iki dilim)
            sc4, sps, sc4, pps, sc4, idr(true), // AU 3: yeni IDR
        )
        val s = AnnexB.parse(data)
        assertArrayEquals(join(sc4, sps, sc4, pps), s.config)
        assertEquals(4, s.units.size)
        assertTrue(s.units[0].keyframe)
        assertFalse(s.units[1].keyframe)
        assertFalse(s.units[2].keyframe)
        assertTrue(s.units[3].keyframe)
        assertArrayEquals(join(sc4, sps, sc4, pps, sc4, sei, sc4, idr(true), sc4, idr(false)), s.units[0].data)
        assertArrayEquals(join(sc4, slice(true)), s.units[1].data)
        assertArrayEquals(join(sc4, slice(true), sc4, slice(false)), s.units[2].data)
    }

    @Test
    fun groupsHevcAccessUnits() {
        fun nal(type: Int, vararg rest: Int) = byteArrayOf((type shl 1).toByte(), 1) + rest.map { it.toByte() }.toByteArray()
        val vps = nal(32, 0x0C); val sps = nal(33, 0x01); val pps = nal(34, 0xC1)
        val idrFirst = nal(19, 0x80, 0x11) // IDR_W_RADL, first_slice_segment_in_pic_flag = 1
        val idrNext = nal(19, 0x40, 0x22)  // aynı resmin ikinci dilimi
        val trailFirst = nal(1, 0x80, 0x33)
        val data = join(sc4, nal(35, 0x50), sc4, vps, sc4, sps, sc4, pps, sc4, idrFirst, sc4, idrNext, sc4, trailFirst)
        val s = AnnexB.parseHevc(data)
        assertArrayEquals(join(sc4, vps, sc4, sps, sc4, pps), s.config)
        assertEquals(2, s.units.size)
        assertTrue(s.units[0].keyframe)
        assertFalse(s.units[1].keyframe)
        assertArrayEquals(join(sc4, vps, sc4, sps, sc4, pps, sc4, idrFirst, sc4, idrNext), s.units[0].data)
    }

    @Test
    fun rejectsStreamWithoutParameterSets() {
        assertThrows<IllegalArgumentException> { AnnexB.parse(join(sc4, idr(true))) }
    }
}
