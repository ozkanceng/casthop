package io.github.ozkanceng.yansit.sender

/**
 * Gecikme test deseni (tools/latency/README.md): 64 hücre; `1010` | 48 bit zaman damgası (µs) | 8 bit XOR | `0101`.
 * Alıcıdaki C++ çözücüyle ortak test vektörü vardır.
 */
object LatencyPattern {
    const val CELLS: Int = 64

    /** Hücre i'nin değeri: bit (63 − i). */
    fun encode(timestampUs: Long): Long {
        val ts = timestampUs and ((1L shl 48) - 1)
        var chk = 0L
        for (i in 0 until 6) chk = chk xor ((ts ushr (8 * i)) and 0xFF)
        return (0b1010L shl 60) or (ts shl 12) or (chk shl 4) or 0b0101L
    }

    fun cell(bits: Long, index: Int): Boolean = (bits ushr (63 - index)) and 1L == 1L
}
