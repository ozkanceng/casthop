package io.github.ozkanceng.yansit.protocol.client

import io.github.ozkanceng.yansit.protocol.Bytes
import java.util.Base64

/** `_yansit._tcp` mDNS TXT kaydı (PROTOCOL.md §8): `v=1`, `name=<ad>`, `fp=<parmak izinin ilk 16 baytı>`, isteğe bağlı `kind` ve `ip`. */
public data class DiscoveryTxt(
    val name: String?,
    val fingerprintPrefix: Bytes,
    val kind: String? = null,
    /** İsteğe bağlı IPv4 ipucu listesi (`ip=10.0.0.1,192.168.1.2`); IPv6-only NSD ve `.local` çakışmalarını aşar. */
    val hosts: List<String> = emptyList(),
) {
    public companion object {
        public const val SERVICE_TYPE: String = "_yansit._tcp"
        private const val PREFIX_SIZE = 16
        private const val MAX_TXT_HOSTS = 4
        private val KINDS = setOf("board", "tv", "pc", "tablet")

        /** Sürümü desteklenmeyen veya parmak izi öneki geçersiz kayıtta `null`. */
        public fun parse(attributes: Map<String, ByteArray?>): DiscoveryTxt? {
            if (attributes["v"]?.toString(Charsets.UTF_8) != "1") return null
            val fp = attributes["fp"]?.toString(Charsets.US_ASCII) ?: return null
            val prefix = try {
                Base64.getUrlDecoder().decode(fp)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (prefix.size != PREFIX_SIZE) return null
            val kind = attributes["kind"]?.toString(Charsets.US_ASCII)?.takeIf { it in KINDS }
            val hosts = attributes["ip"]?.toString(Charsets.US_ASCII)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter(::isIpv4)
                ?.distinct()
                ?.take(MAX_TXT_HOSTS)
                .orEmpty()
            return DiscoveryTxt(
                attributes["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() },
                Bytes.of(prefix),
                kind,
                hosts,
            )
        }

        private fun isIpv4(text: String): Boolean {
            val parts = text.split('.')
            if (parts.size != 4) return false
            return parts.all { p ->
                p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && (p.length == 1 || p[0] != '0') &&
                    (p.toIntOrNull() ?: 256) <= 255
            }
        }
    }
}
