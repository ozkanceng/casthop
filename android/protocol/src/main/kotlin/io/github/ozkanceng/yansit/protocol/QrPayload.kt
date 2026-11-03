package io.github.ozkanceng.yansit.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64

public enum class QrError { BAD_SCHEME, UNSUPPORTED_VERSION, MISSING_FIELD, BAD_FIELD }

public class QrException(public val error: QrError, detail: String) : Exception("${error.name}: $detail")

/**
 * Tahtanın bekleme ekranındaki QR içeriği (PROTOCOL.md §8):
 * `yansit://c?v=1&h=<ip,ip>&p=<port>&t=<token>&f=<parmak izi>&n=<ad>`
 */
public data class QrPayload(
    val hosts: List<String>,
    val port: Int,
    val token: Bytes,
    val certFingerprint: Bytes,
    val name: String,
) {
    init {
        require(hosts.size in 1..MAX_HOSTS && hosts.all(::isIPv4)) { "1–$MAX_HOSTS geçerli IPv4 adresi gerekli" }
        require(port in 1..65535)
        require(token.size == TOKEN_SIZE && certFingerprint.size == Auth.FINGERPRINT_SIZE)
    }

    public fun toUri(): String = buildString {
        append(PREFIX).append("v=").append(VERSION)
        append("&h=").append(hosts.joinToString(","))
        append("&p=").append(port)
        append("&t=").append(b64url(token)).append("&f=").append(b64url(certFingerprint))
        append("&n=").append(percentEncode(name))
    }

    public companion object {
        public const val VERSION: Int = 1
        public const val MAX_HOSTS: Int = 4
        public const val TOKEN_SIZE: Int = 16
        private const val PREFIX = "yansit://c?"

        /** @throws QrException geçersiz içerikte. */
        public fun parse(uri: String): QrPayload {
            if (!uri.startsWith(PREFIX)) throw QrException(QrError.BAD_SCHEME, uri.substringBefore('?'))
            val params = HashMap<String, String>()
            for (part in uri.substring(PREFIX.length).split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) throw QrException(QrError.BAD_FIELD, "parametre: $part")
                params.putIfAbsent(part.substring(0, eq), part.substring(eq + 1))
            }
            fun need(key: String) = params[key] ?: throw QrException(QrError.MISSING_FIELD, key)

            if (need("v") != VERSION.toString()) throw QrException(QrError.UNSUPPORTED_VERSION, params["v"]!!)
            val hosts = need("h").split(',')
            if (hosts.isEmpty() || hosts.size > MAX_HOSTS || !hosts.all(::isIPv4)) {
                throw QrException(QrError.BAD_FIELD, "h")
            }
            val port = need("p").toIntOrNull()?.takeIf { it in 1..65535 } ?: throw QrException(QrError.BAD_FIELD, "p")
            val token = b64urlDecode(need("t"), TOKEN_SIZE, "t")
            val fp = b64urlDecode(need("f"), Auth.FINGERPRINT_SIZE, "f")
            val name = params["n"]?.let(::percentDecode) ?: ""
            return QrPayload(hosts, port, token, fp, name)
        }

        internal fun isIPv4(s: String): Boolean {
            val parts = s.split('.')
            if (parts.size != 4) return false
            return parts.all { p ->
                p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                    (p.length == 1 || p[0] != '0') && p.toInt() <= 255
            }
        }

        private fun b64url(b: Bytes): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b.unsafeArray())

        private fun b64urlDecode(s: String, size: Int, key: String): Bytes {
            if (s.contains('=')) throw QrException(QrError.BAD_FIELD, key)
            val decoded = try {
                Base64.getUrlDecoder().decode(s)
            } catch (e: IllegalArgumentException) {
                throw QrException(QrError.BAD_FIELD, key)
            }
            if (decoded.size != size) throw QrException(QrError.BAD_FIELD, "$key: ${decoded.size} bayt")
            return Bytes.wrap(decoded)
        }

        private fun isUnreserved(c: Int): Boolean =
            c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code

        internal fun percentEncode(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (isUnreserved(c)) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
            }
            return sb.toString()
        }

        internal fun percentDecode(s: String): String {
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) throw QrException(QrError.BAD_FIELD, "n")
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) throw QrException(QrError.BAD_FIELD, "n")
                    out.write((hi shl 4) or lo)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            val bytes = out.toByteArray()
            val decoder = Charsets.UTF_8.newDecoder()
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw QrException(QrError.BAD_FIELD, "n: UTF-8")
            }
        }
    }
}
