package io.github.ozkanceng.yansit.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class FrameStreamTest {
    /** Her read çağrısında en fazla 1 bayt veren akış: kısmi okumaları sınar. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (len == 0) return 0
            b[off] = data[pos++]
            return 1
        }
    }

    @Test
    fun `writer and reader round trip several frames over a trickling stream`() {
        val out = ByteArrayOutputStream()
        val writer = FrameWriter(out)
        val big = ByteArray(200_000) { (it * 31).toByte() }
        writer.write(FrameType.VIDEO, FrameFlags.CONFIG, 10, 0, byteArrayOf(1, 2, 3))
        writer.write(FrameType.VIDEO, FrameFlags.KEYFRAME, 20, 1, big)
        writer.write(FrameType.CONTROL, 0, 30, 0, ControlCodec.encode(ControlMessage.Pause))

        val reader = FrameReader(Trickle(out.toByteArray()))
        val f1 = reader.read()!!
        assertEquals(FrameType.VIDEO, f1.header.type)
        assertEquals(true, f1.header.isConfig)
        assertArrayEquals(byteArrayOf(1, 2, 3), f1.payload)
        val f2 = reader.read()!!
        assertEquals(true, f2.header.isKeyframe)
        assertEquals(1L, f2.header.seq)
        assertArrayEquals(big, f2.payload)
        val f3 = reader.read()!!
        assertEquals(ControlMessage.Pause, ControlCodec.decode(f3.payload))
        assertNull(reader.read())
    }

    @Test
    fun `stream ending inside a frame is truncated`() {
        val frame = Frames.encode(FrameType.AUDIO, 0, 1, 2, ByteArray(100))
        val e = assertThrows<ProtocolException> {
            FrameReader(ByteArrayInputStream(frame.copyOf(frame.size - 1))).read()
        }
        assertEquals(ProtocolError.TRUNCATED, e.error)
        val e2 = assertThrows<ProtocolException> { FrameReader(ByteArrayInputStream(frame.copyOf(10))).read() }
        assertEquals(ProtocolError.TRUNCATED, e2.error)
    }

    @Test
    fun `oversized length is rejected before allocating the payload`() {
        val header = Frames.encode(FrameType.CONTROL, 0, 0, 0, ByteArray(0))
        header[20] = 0x7F // length = 0x7F000000
        val e = assertThrows<ProtocolException> { FrameReader(ByteArrayInputStream(header)).read() }
        assertEquals(ProtocolError.FRAME_TOO_LARGE, e.error)
    }
}
