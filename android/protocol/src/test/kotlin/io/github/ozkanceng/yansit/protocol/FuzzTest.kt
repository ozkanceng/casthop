package io.github.ozkanceng.yansit.protocol

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Bozuk girdide yalnız ProtocolException fırlatılmalı; başka hiçbir istisna (çökme) olmamalı. */
class FuzzTest {
    private val seeds: List<ByteArray> = Vectors.section("control").map { Vectors.hex(it["hex"]!!.jsonPrimitive.content) }

    private fun mutate(r: Random, src: ByteArray): ByteArray {
        var b = src.copyOf()
        repeat(1 + r.nextInt(4)) {
            when (r.nextInt(4)) {
                0 -> if (b.isNotEmpty()) b[r.nextInt(b.size)] = r.nextInt(256).toByte()
                1 -> if (b.isNotEmpty()) b = b.copyOf(r.nextInt(b.size))
                2 -> b += ByteArray(r.nextInt(8)) { r.nextInt(256).toByte() }
                else -> if (b.size > 2) { val i = r.nextInt(b.size - 1); val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t }
            }
        }
        return b
    }

    @Test
    fun `control decoder only throws ProtocolException`() {
        val r = Random(20261103)
        repeat(50_000) {
            val input = mutate(r, seeds[r.nextInt(seeds.size)])
            try {
                ControlCodec.decode(input)
            } catch (e: ProtocolException) {
                // beklenen
            }
        }
    }

    @Test
    fun `frame header decoder only throws ProtocolException`() {
        val r = Random(7)
        val valid = Frames.encode(FrameType.VIDEO, FrameFlags.KEYFRAME, 1, 1, ByteArray(4))
        repeat(50_000) {
            val input = mutate(r, valid)
            try {
                Frames.decodeHeader(input)
            } catch (e: ProtocolException) {
                // beklenen
            }
        }
    }
}
