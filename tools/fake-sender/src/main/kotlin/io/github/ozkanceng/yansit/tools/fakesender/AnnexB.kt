package io.github.ozkanceng.yansit.tools.fakesender

import java.io.ByteArrayOutputStream

/** Bir H.264 erişim birimi (tek kare), Annex-B (4 baytlık başlangıç kodlarıyla). */
class AccessUnit(val data: ByteArray, val keyframe: Boolean)

/** H.264 Annex-B akışı: parametre kümeleri (SPS+PPS) ve erişim birimleri. */
class H264Stream(val config: ByteArray, val units: List<AccessUnit>)

/**
 * Annex-B H.264 ayrıştırıcı. Erişim birimi sınırları ITU-T H.264 §7.4.1.2.3'e göre bulunur:
 * bir VCL NAL'ından sonra gelen AUD/SPS/PPS/SEI/14–18 türü NAL veya first_mb_in_slice = 0 olan dilim yeni bir
 * birim başlatır.
 */
object AnnexB {
    private const val NAL_SLICE = 1
    private const val NAL_IDR = 5
    private const val NAL_SEI = 6
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_AUD = 9
    private val START = byteArrayOf(0, 0, 0, 1)

    /** Başlangıç kodları çıkarılmış NAL birimleri. */
    fun splitNals(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) nals.add(trimTrailingZeros(data, start, i))
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0 && start < data.size) nals.add(trimTrailingZeros(data, start, data.size))
        return nals.filter { it.isNotEmpty() }
    }

    fun parse(data: ByteArray): H264Stream {
        val config = ByteArrayOutputStream()
        var sawSps = false
        var sawPps = false
        val units = ArrayList<AccessUnit>()
        var current = ByteArrayOutputStream()
        var hasVcl = false
        var hasIdr = false

        fun flush() {
            if (hasVcl) units.add(AccessUnit(current.toByteArray(), hasIdr))
            current = ByteArrayOutputStream()
            hasVcl = false
            hasIdr = false
        }

        for (nal in splitNals(data)) {
            val type = nal[0].toInt() and 0x1F
            val isVcl = type in NAL_SLICE..NAL_IDR
            val firstMbZero = isVcl && nal.size > 1 && (nal[1].toInt() and 0x80) != 0
            val startsNew = hasVcl && (type == NAL_AUD || type == NAL_SEI || type == NAL_SPS || type == NAL_PPS ||
                type in 14..18 || firstMbZero)
            if (startsNew) flush()
            if (type == NAL_SPS && !sawSps) {
                config.write(START); config.write(nal); sawSps = true
            } else if (type == NAL_PPS && !sawPps) {
                config.write(START); config.write(nal); sawPps = true
            }
            if (type == NAL_AUD) continue // erişim birimi ayırıcısı ağda gereksiz
            current.write(START)
            current.write(nal)
            if (isVcl) hasVcl = true
            if (type == NAL_IDR) hasIdr = true
        }
        flush()
        require(sawSps && sawPps) { "akışta SPS/PPS yok" }
        require(units.isNotEmpty()) { "akışta kare yok" }
        return H264Stream(config.toByteArray(), units)
    }

    /**
     * H.265 Annex-B (ITU-T H.265 §7.4.2.4.4): NAL türü = (bayt0 >> 1) & 0x3F, VCL 0–31, IRAP 16–23 (anahtar kare),
     * VPS/SPS/PPS 32/33/34, AUD 35, önek SEI 39. first_slice_segment_in_pic_flag = NAL başlığından sonraki ilk bit.
     */
    fun parseHevc(data: ByteArray): H264Stream {
        val config = ByteArrayOutputStream()
        val seen = HashSet<Int>()
        val units = ArrayList<AccessUnit>()
        var current = ByteArrayOutputStream()
        var hasVcl = false
        var hasIrap = false

        fun flush() {
            if (hasVcl) units.add(AccessUnit(current.toByteArray(), hasIrap))
            current = ByteArrayOutputStream()
            hasVcl = false
            hasIrap = false
        }

        for (nal in splitNals(data)) {
            if (nal.size < 2) continue
            val type = (nal[0].toInt() shr 1) and 0x3F
            val isVcl = type < 32
            val firstSlice = isVcl && nal.size > 2 && (nal[2].toInt() and 0x80) != 0
            val startsNew = hasVcl && (type in 32..35 || type == 39 || type in 41..44 || type in 48..55 || firstSlice)
            if (startsNew) flush()
            if (type in 32..34 && seen.add(type)) {
                config.write(START); config.write(nal)
            }
            if (type == 35) continue
            current.write(START)
            current.write(nal)
            if (isVcl) hasVcl = true
            if (type in 16..23) hasIrap = true
        }
        flush()
        require(seen.containsAll(listOf(32, 33, 34))) { "akışta VPS/SPS/PPS yok" }
        require(units.isNotEmpty()) { "akışta kare yok" }
        return H264Stream(config.toByteArray(), units)
    }

    private fun trimTrailingZeros(data: ByteArray, from: Int, to: Int): ByteArray {
        var end = to
        while (end > from && data[end - 1].toInt() == 0) end--
        return data.copyOfRange(from, end)
    }
}
