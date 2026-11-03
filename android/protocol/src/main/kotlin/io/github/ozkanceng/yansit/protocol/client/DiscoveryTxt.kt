package io.github.ozkanceng.yansit.protocol.client

import io.github.ozkanceng.yansit.protocol.Bytes
import java.util.Base64

/** `_yansit._tcp` mDNS TXT kaydı (PROTOCOL.md §8): `v=1`, `name=<ad>`, `fp=<parmak izinin ilk 16 baytı>`. */
public data class DiscoveryTxt(val name: String?, val fingerprintPrefix: Bytes) {
    public companion object {
        public const val SERVICE_TYPE: String = "_yansit._tcp"
        private const val PREFIX_SIZE = 16

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
            return DiscoveryTxt(attributes["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }, Bytes.of(prefix))
        }
    }
}
