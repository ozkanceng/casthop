package io.github.ozkanceng.yansit.protocol

/**
 * SemVer (major.minor.patch + isteğe bağlı ön-sürüm) ayrıştırıcısı ve sürüm karşılaştırması.
 * Hem Android gönderici (:app) hem Android TV alıcı (:tv) tarafından ortak kullanılır.
 */
public data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: Boolean = false,
) {
    public fun isNewerThan(current: SemVer): Boolean {
        if (prerelease && !current.prerelease) return false
        if (major != current.major) return major > current.major
        if (minor != current.minor) return minor > current.minor
        if (patch != current.patch) return patch > current.patch
        return current.prerelease && !prerelease
    }

    public companion object {
        private val PATTERN = Regex("""^\s*v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\s*$""")

        public fun parse(raw: String): SemVer? {
            val m = PATTERN.matchEntire(raw) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: return null
            return SemVer(major, minor, patch, prerelease = m.groupValues[4].isNotEmpty())
        }
    }
}

/** `version.json` manifestosundan tek bir dağıtım kanalı için çözümlenmiş güncelleme bilgisi. */
public data class UpdateRelease(
    val version: String,
    val url: String,
    val notes: String,
    val publishedAt: String = "",
)

/**
 * Harici JSON bağımlılığı olmadan `site/version.json` dosyasını güvenli biçimde ayrıştırır.
 * Yalnız `https://` bağlantıları kabul eder; geçersiz/güvensiz URL gelirse GitHub Releases'a düşer.
 */
public object UpdateManifestParser {
    public const val DEFAULT_MANIFEST_URL: String = "https://ozkanceng.github.io/casthop/version.json"
    public const val FALLBACK_RELEASE_URL: String = "https://github.com/ozkanceng/casthop/releases/latest"
    public const val MAX_BYTES: Int = 64 * 1024

    public fun isSafeHttpsUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        val afterScheme = trimmed.substring(8)
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && !host.any { it.isWhitespace() }
    }

    public fun parse(json: String, channel: String, preferTurkish: Boolean): UpdateRelease? {
        if (json.isEmpty() || json.length > MAX_BYTES) return null
        val root = JsonMini.parseObject(json) ?: return null
        val channels = root["channels"] as? Map<*, *>
        val ch = channels?.get(channel) as? Map<*, *>

        val version = ((ch?.get("version") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (root["latest"] as? String)?.trim())
            ?.takeIf { SemVer.parse(it) != null }
            ?: return null

        val rawUrl = (ch?.get("url") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (root["releaseUrl"] as? String)?.trim().orEmpty()
        val url = if (isSafeHttpsUrl(rawUrl)) rawUrl else FALLBACK_RELEASE_URL

        val notesObj = root["notes"] as? Map<*, *>
        val tr = (notesObj?.get("tr") as? String)?.trim().orEmpty()
        val en = (notesObj?.get("en") as? String)?.trim().orEmpty()
        val notes = if (preferTurkish) tr.ifEmpty { en } else en.ifEmpty { tr }
        val publishedAt = (root["publishedAt"] as? String)?.trim().orEmpty()
        return UpdateRelease(version = version, url = url, notes = notes, publishedAt = publishedAt)
    }
}

/** Yalnız düz nesne/dize/sayı/mantıksal içeren küçük JSON okuyucu (JVM + Android ortak). */
private class JsonMini(private val s: String) {
    private var i = 0

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun parseValue(): Any? {
        skipWs()
        if (i >= s.length) return null
        return when (s[i]) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> parseStr()
            't' -> if (s.startsWith("true", i)) { i += 4; true } else null
            'f' -> if (s.startsWith("false", i)) { i += 5; false } else null
            'n' -> if (s.startsWith("null", i)) { i += 4; null } else null
            '-', in '0'..'9' -> parseNum()
            else -> null
        }
    }

    private fun parseObj(): Map<String, Any?>? {
        if (i >= s.length || s[i] != '{') return null
        i++
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return map }
        while (i < s.length) {
            skipWs()
            val key = parseStr() ?: return null
            skipWs()
            if (i >= s.length || s[i] != ':') return null
            i++
            val value = parseValue()
            map[key] = value
            skipWs()
            if (i >= s.length) return null
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return map }
                else -> return null
            }
        }
        return null
    }

    private fun parseArr(): List<Any?>? {
        if (i >= s.length || s[i] != '[') return null
        i++
        val list = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return list }
        while (i < s.length) {
            list.add(parseValue())
            skipWs()
            if (i >= s.length) return null
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return list }
                else -> return null
            }
        }
        return null
    }

    private fun parseStr(): String? {
        if (i >= s.length || s[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) return null
                    when (val esc = s[i++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
        }
        return null
    }

    private fun parseNum(): String? {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) {
            i++
        }
        return if (i > start) s.substring(start, i) else null
    }

    companion object {
        fun parseObject(raw: String): Map<String, Any?>? {
            val parser = JsonMini(raw)
            parser.skipWs()
            return parser.parseObj()
        }
    }
}
