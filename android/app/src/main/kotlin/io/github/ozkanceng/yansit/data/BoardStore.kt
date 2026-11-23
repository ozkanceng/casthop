package io.github.ozkanceng.yansit.data

import android.content.Context
import androidx.core.content.edit
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.Preset
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Daha önce QR veya PIN ile bağlanılmış ve kalıcı eşleştirme anahtarı alınmış tahta (PROTOCOL.md §7.1, auth_method=3).
 * Sonraki bağlantılar QR/kod olmadan, tek dokunuşla yapılır.
 */
data class RememberedBoard(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    /** TLS sertifika parmak izi: bağlantıda sabitlenir (araya girme koruması). */
    val fingerprint: Bytes,
    val keyId: Bytes,
    val pairKey: Bytes,
    val lastUsedMs: Long,
    /** Kullanıcının tahtaya verdiği özel takma ad (ör. "9-B Matematik"). */
    val nickname: String? = null,
    /** Favori olarak sabitlenen tahtalar listede en üstte çıkar. */
    val favorite: Boolean = false,
    /** Alıcı türü ("board", "tv", "pc", "tablet"). */
    val kind: String? = null,
) {
    /** Arayüzde gösterilen birincil ad: varsa takma ad, yoksa tahtanın bildirdiği ad. */
    val displayName: String get() = nickname?.trim()?.takeIf { it.isNotEmpty() } ?: name

    /** Kalıcı 4 haneli kısa kimlik (ör. "A4F2"). */
    val shortId: String get() = BoardIdentity.shortId(fingerprint)
}

/** Hatırlanan tahtalar; uygulamaya özel depoda, `pairKey` Android Keystore AES-GCM ile şifrelenir. */
class BoardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("boards", Context.MODE_PRIVATE)

    /** Favoriler üstte, ardından en son kullanılanlar. */
    fun list(): List<RememberedBoard> = BoardIdentity.sortRemembered(load())

    fun find(fingerprint: Bytes): RememberedBoard? = load().firstOrNull { it.fingerprint == fingerprint }

    /** mDNS parmak izi önekiyle eşleşen hatırlanan tahta. */
    fun findByPrefix(prefix: Bytes): RememberedBoard? =
        load().firstOrNull { BoardIdentity.matchesPrefix(it.fingerprint, prefix) }

    /** Manuel IP ekranında hızlı seçim için son kullanılan benzersiz IPv4 adresleri. */
    fun recentIps(limit: Int = 4): List<String> =
        list().flatMap { it.hosts }.mapNotNull { h -> BoardIdentity.primaryIpv4(listOf(h)) }.distinct().take(limit)

    /**
     * Aynı tahtanın (parmak izi) eski kaydının yerini alır; mevcut takma ad, favori ve tür bilgisi korunur.
     * En fazla [MAX_BOARDS] tahta tutulur (favoriler öncelikli korunur).
     */
    fun save(board: RememberedBoard) {
        val current = load()
        val existing = current.firstOrNull { it.fingerprint == board.fingerprint }
        val merged = board.copy(
            nickname = board.nickname ?: existing?.nickname,
            favorite = board.favorite || (existing?.favorite == true),
            kind = board.kind ?: existing?.kind,
        )
        val all = current.filterNot { it.fingerprint == board.fingerprint } + merged
        store(BoardIdentity.sortRemembered(all).take(MAX_BOARDS))
    }

    /** Tahtanın özel takma adını günceller (boş/null → takma adı temizle). */
    fun setNickname(fingerprint: Bytes, nickname: String?) {
        val clean = nickname?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
        val all = load().map { if (it.fingerprint == fingerprint) it.copy(nickname = clean) else it }
        store(BoardIdentity.sortRemembered(all))
    }

    /** Tahtayı favorilere ekler veya çıkarır. */
    fun setFavorite(fingerprint: Bytes, favorite: Boolean) {
        val all = load().map { if (it.fingerprint == fingerprint) it.copy(favorite = favorite) else it }
        store(BoardIdentity.sortRemembered(all))
    }

    /** Tahtanın güncel IP listesini günceller (ör. ağ/hotspot değişiminde mDNS ile bulunduğunda). */
    fun updateHosts(fingerprint: Bytes, hosts: List<String>, port: Int) {
        if (hosts.isEmpty()) return
        val all = load().map { if (it.fingerprint == fingerprint) it.copy(hosts = hosts, port = port) else it }
        store(BoardIdentity.sortRemembered(all))
    }

    fun touch(fingerprint: Bytes, nowMs: Long) {
        val all = load()
        store(all.map { if (it.fingerprint == fingerprint) it.copy(lastUsedMs = nowMs) else it })
    }

    fun remove(fingerprint: Bytes) {
        store(load().filterNot { it.fingerprint == fingerprint })
        savePreset(fingerprint, null)
    }

    /** The preset the network last settled on with this board; `null` when it kept the chosen one. */
    fun preset(fingerprint: Bytes): Preset? = loadPresets().optString(fingerprint.toHex()).let { name ->
        Preset.entries.firstOrNull { it.name == name }
    }

    /** `null` forgets it. Only boards still remembered keep an entry. */
    fun savePreset(fingerprint: Bytes, preset: Preset?) {
        val known = load().map { it.fingerprint.toHex() }.toSet()
        val old = loadPresets()
        val presets = JSONObject()
        for (key in old.keys()) if (key in known) presets.put(key, old.getString(key))
        val key = fingerprint.toHex()
        if (preset != null && key in known) presets.put(key, preset.name) else presets.remove(key)
        prefs.edit { putString(KEY_PRESETS, presets.toString()) }
    }

    private fun loadPresets(): JSONObject = try {
        JSONObject(prefs.getString(KEY_PRESETS, null) ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    private fun load(): List<RememberedBoard> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        var needsMigration = false
        val parsed = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (obj.has("pair_key") && !obj.has("pair_key_enc")) needsMigration = true
                parse(obj)
            }
        } catch (_: JSONException) {
            emptyList()
        }
        if (needsMigration && parsed.isNotEmpty()) {
            store(parsed)
        }
        return parsed
    }

    private fun parse(o: JSONObject): RememberedBoard? = try {
        val hosts = o.getJSONArray("hosts")
        val pairKey = o.optString("pair_key_enc", "").takeIf { it.isNotEmpty() }?.let(BoardKeyVault::decrypt)
            ?: o.optString("pair_key", "").takeIf { it.isNotEmpty() }?.let(Bytes::fromHex)
            ?: return null
        RememberedBoard(
            name = o.getString("name"),
            hosts = (0 until hosts.length()).map { hosts.getString(it) },
            port = o.getInt("port"),
            fingerprint = Bytes.fromHex(o.getString("fp")),
            keyId = Bytes.fromHex(o.getString("key_id")),
            pairKey = pairKey,
            lastUsedMs = o.getLong("last_used"),
            nickname = o.optString("nickname", "").takeIf { it.isNotBlank() },
            favorite = o.optBoolean("favorite", false),
            kind = o.optString("kind", "").takeIf { it.isNotBlank() },
        ).takeIf { it.fingerprint.size == 32 && it.keyId.size == 16 && it.pairKey.size == 32 && it.hosts.isNotEmpty() }
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun store(boards: List<RememberedBoard>) {
        val arr = JSONArray()
        for (b in boards) {
            val encryptedKey = BoardKeyVault.encrypt(b.pairKey)
            val obj = JSONObject()
                .put("name", b.name)
                .put("hosts", JSONArray(b.hosts))
                .put("port", b.port)
                .put("fp", b.fingerprint.toHex())
                .put("key_id", b.keyId.toHex())
                .put("last_used", b.lastUsedMs)
                .put("favorite", b.favorite)
            if (encryptedKey != null) {
                obj.put("pair_key_enc", encryptedKey)
            } else {
                obj.put("pair_key", b.pairKey.toHex())
            }
            b.nickname?.let { obj.put("nickname", it) }
            b.kind?.let { obj.put("kind", it) }
            arr.put(obj)
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private companion object {
        const val KEY = "boards"
        const val KEY_PRESETS = "presets"
        const val MAX_BOARDS = 20
    }
}
