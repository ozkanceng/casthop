package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Bytes
import java.util.Base64

/** mDNS advertisement values (PROTOCOL.md §8, §11). */
public object Discovery {
    public const val DEFAULT_NAME: String = "CastHop"
    private const val MAX_INSTANCE_BYTES = 63
    private const val FINGERPRINT_PREFIX_SIZE = 16

    /** An mDNS key and value together fit in 255 bytes; `name=` leaves room for this many. */
    private const val MAX_TXT_NAME_BYTES = 250

    /** Cuts [name] to [maxBytes] of UTF-8 without splitting a character. */
    public fun truncateUtf8(name: String, maxBytes: Int): String {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        if (utf8.size <= maxBytes) return name
        var cut = maxBytes
        while (cut > 0 && (utf8[cut].toInt() and 0xC0) == 0x80) cut--
        return String(utf8, 0, cut, Charsets.UTF_8)
    }

    /** The service instance name: at most 63 bytes, [DEFAULT_NAME] when empty. */
    public fun instanceName(name: String): String =
        truncateUtf8(name, MAX_INSTANCE_BYTES).ifEmpty { DEFAULT_NAME }

    /** The TXT record: `v=1`, `name=<name>`, `fp=<first 16 fingerprint bytes, base64url>`. */
    public fun txt(name: String, fingerprint: Bytes): Map<String, String> {
        val prefix = fingerprint.toByteArray().copyOf(FINGERPRINT_PREFIX_SIZE)
        return mapOf(
            "v" to "1",
            "name" to truncateUtf8(name, MAX_TXT_NAME_BYTES),
            "fp" to Base64.getUrlEncoder().withoutPadding().encodeToString(prefix),
        )
    }
}
