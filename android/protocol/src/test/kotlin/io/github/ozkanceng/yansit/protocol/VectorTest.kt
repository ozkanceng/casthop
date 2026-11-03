package io.github.ozkanceng.yansit.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class VectorTest {
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `error codes match vectors`() {
        val expected = Vectors.root["error_codes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, ProtocolError.entries.map { it.name })
    }

    @TestFactory
    fun frames() = Vectors.section("frames").map { v ->
        DynamicTest.dynamicTest(v.str("name")) {
            val bytes = Vectors.hex(v.str("hex"))
            val headerOnly = v["header_only"]?.jsonPrimitive?.boolean ?: false
            val decode = {
                val h = Frames.decodeHeader(bytes)
                if (!headerOnly && bytes.size - Frames.HEADER_SIZE < h.length) {
                    throw ProtocolException(ProtocolError.TRUNCATED, "yük")
                }
                h
            }
            val err = v["expect_error"]?.jsonPrimitive?.content
            if (err != null) {
                val e = assertThrows<ProtocolException> { decode() }
                assertEquals(err, e.error.name)
                return@dynamicTest
            }
            val h = decode()
            val ex = v["expect"]!!.jsonObject
            assertEquals(ex["type"]!!.jsonPrimitive.int, h.type.id)
            assertEquals(ex["flags"]!!.jsonPrimitive.int, h.flags)
            assertEquals(ex["pts_us"]!!.jsonPrimitive.long, h.ptsUs)
            assertEquals(ex["seq"]!!.jsonPrimitive.long, h.seq)
            assertEquals(ex["length"]!!.jsonPrimitive.int, h.length)
            if (!headerOnly) {
                val payload = bytes.copyOfRange(Frames.HEADER_SIZE, bytes.size)
                assertEquals(ex.str("payload"), Bytes.of(payload).toHex())
                // Kodlayıcı ayrılmış alanı sıfır yazar; ayrılmış alanı sıfır olan vektörlerde bayt bayt aynı olmalı.
                if (bytes[6].toInt() == 0 && bytes[7].toInt() == 0) {
                    assertArrayEquals(bytes, Frames.encode(h.type, h.flags, h.ptsUs, h.seq, payload))
                }
            }
        }
    }

    @TestFactory
    fun control() = Vectors.section("control").map { v ->
        DynamicTest.dynamicTest(v.str("name")) {
            val bytes = Vectors.hex(v.str("hex"))
            val err = v["expect_error"]?.jsonPrimitive?.content
            if (err != null) {
                val e = assertThrows<ProtocolException> { ControlCodec.decode(bytes) }
                assertEquals(err, e.error.name)
                return@dynamicTest
            }
            val msg = ControlCodec.decode(bytes)
            val ex = v["expect"]!!.jsonObject
            assertEquals(ex.str("message"), msg.vectorName())
            assertEquals(Vectors.normalize(ex["fields"]!!), msg.toFields())
            if (v["roundtrip"]!!.jsonPrimitive.boolean) {
                assertEquals(v.str("hex"), Bytes.of(ControlCodec.encode(msg)).toHex())
            }
        }
    }

    @TestFactory
    fun crypto() = Vectors.section("crypto").map { v ->
        DynamicTest.dynamicTest(v.str("name")) {
            val i = v["inputs"]!!.jsonObject
            fun b(k: String) = Bytes.fromHex(i.str(k))
            val actual = when (v.str("kind")) {
                "auth" -> Auth.authProof(Vectors.hex(i.str("secret")), i["auth_method"]!!.jsonPrimitive.int,
                    b("server_nonce"), b("client_nonce"), b("receiver_cert_fp"), b("device_id"))
                "attach" -> Auth.attachProof(b("attach_key"), b("session_id"), i["stream_kind"]!!.jsonPrimitive.int, b("server_nonce"))
                "resume" -> Auth.resumeProof(b("resume_token"), b("session_id"), b("server_nonce"))
                "sha256" -> Auth.sha256(Vectors.hex(i.str("data")))
                else -> error("bilinmeyen kind")
            }
            assertEquals(v.str("expect"), actual.toHex())
        }
    }

    @TestFactory
    fun qr() = Vectors.section("qr").map { v ->
        DynamicTest.dynamicTest(v.str("name")) {
            val uri = v.str("uri")
            val err = v["expect_error"]?.jsonPrimitive?.content
            if (err != null) {
                val e = assertThrows<QrException> { QrPayload.parse(uri) }
                assertEquals(err, e.error.name)
                return@dynamicTest
            }
            val f = v["fields"]!!.jsonObject
            val expected = QrPayload(
                hosts = f["hosts"]!!.jsonArray.map { (it as JsonPrimitive).content },
                port = f["port"]!!.jsonPrimitive.int,
                token = Bytes.fromHex(f.str("token")),
                certFingerprint = Bytes.fromHex(f.str("cert_fp")),
                name = f.str("name"),
            )
            assertEquals(expected, QrPayload.parse(uri))
            assertEquals(uri, expected.toUri())
        }
    }
}
