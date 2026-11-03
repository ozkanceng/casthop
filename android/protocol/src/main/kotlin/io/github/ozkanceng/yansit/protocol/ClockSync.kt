package io.github.ozkanceng.yansit.protocol

/**
 * PING/PONG ile saat ofseti tahmini (PROTOCOL.md §7.4). Son [window] örnekten en düşük RTT'li
 * örneğin ofseti kullanılır: asimetrik kuyruklanmadan en az etkilenen ölçüm odur.
 *
 * `offset = yanıtlayan_saat − başlatan_saat` (µs).
 */
public class ClockSync(private val window: Int = 8) {
    public data class Sample(val offsetUs: Long, val rttUs: Long)

    private val samples = ArrayDeque<Sample>(window)

    /** Kabul edilen örneği döndürür; RTT negatifse (bozuk zaman damgası) `null`. */
    public fun onPong(t0Us: Long, t1Us: Long, t2Us: Long, t3Us: Long): Sample? {
        val rtt = (t3Us - t0Us) - (t2Us - t1Us)
        if (rtt < 0) return null
        // Taşmayı önlemek için ikiye bölmeden önce ayrı ayrı topla.
        val offset = (t1Us - t0Us) / 2 + (t2Us - t3Us) / 2
        val s = Sample(offset, rtt)
        if (samples.size == window) samples.removeFirst()
        samples.addLast(s)
        return s
    }

    public val best: Sample? get() = samples.minByOrNull { it.rttUs }

    public val offsetUs: Long? get() = best?.offsetUs

    public val rttUs: Long? get() = best?.rttUs

    public fun reset() {
        samples.clear()
    }
}
