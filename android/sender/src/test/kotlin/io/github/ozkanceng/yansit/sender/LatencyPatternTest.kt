package io.github.ozkanceng.yansit.sender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LatencyPatternTest {
    @Test
    fun sharedVector() {
        // tools/latency/README.md ve receiver/tests/test_latency_pattern.cpp ile aynı.
        val bits = LatencyPattern.encode(0x0123456789ABL)
        val s = (0 until 64).joinToString("") { if (LatencyPattern.cell(bits, it)) "1" else "0" }
        assertEquals("1010000000010010001101000101011001111000100110101011001000100101", s)
    }
}
