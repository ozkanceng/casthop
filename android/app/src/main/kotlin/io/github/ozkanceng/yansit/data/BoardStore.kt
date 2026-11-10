package io.github.ozkanceng.yansit.data

import android.content.Context
import androidx.core.content.edit
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.Preset
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Daha önce QR ile bağlanılmış ve kalıcı eşleştirme anahtarı alınmış tahta (PROTOCOL.md §7.1, auth_method=3).
 * Sonraki bağlantılar QR'sız, tek dokunuşla yapılır.
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
)

/** Hatırlanan tahtalar; uygulamaya özel depoda, yedeklenmez (data_extraction_rules). */
class BoardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("boards", Context.MODE_PRIVATE)

    /** En son kullanılan önce. */
    fun list(): List<RememberedBoard> = load().sortedByDescending { it.lastUsedMs }

    fun find(fingerprint: Bytes): RememberedBoard? = load().firstOrNull { it.fingerprint == fingerprint }

    /** Aynı tahtanın (parmak izi) eski kaydının yerini alır. En fazla [MAX_BOARDS] tahta tutulur. */
    fun save(board: RememberedBoard) {
        val all = load().filterNot { it.fingerprint == board.fingerprint } + board
        store(all.sortedByDescending { it.lastUsedMs }.take(MAX_BOARDS))
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
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> parse(arr.getJSONObject(i)) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun parse(o: JSONObject): RememberedBoard? = try {
        val hosts = o.getJSONArray("hosts")
        RememberedBoard(
            name = o.getString("name"),
            hosts = (0 until hosts.length()).map { hosts.getString(it) },
            port = o.getInt("port"),
            fingerprint = Bytes.fromHex(o.getString("fp")),
            keyId = Bytes.fromHex(o.getString("key_id")),
            pairKey = Bytes.fromHex(o.getString("pair_key")),
            lastUsedMs = o.getLong("last_used"),
        ).takeIf { it.fingerprint.size == 32 && it.keyId.size == 16 && it.pairKey.size == 32 && it.hosts.isNotEmpty() }
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun store(boards: List<RememberedBoard>) {
        val arr = JSONArray()
        for (b in boards) {
            arr.put(
                JSONObject()
                    .put("name", b.name)
                    .put("hosts", JSONArray(b.hosts))
                    .put("port", b.port)
                    .put("fp", b.fingerprint.toHex())
                    .put("key_id", b.keyId.toHex())
                    .put("pair_key", b.pairKey.toHex())
                    .put("last_used", b.lastUsedMs),
            )
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private companion object {
        const val KEY = "boards"
        const val KEY_PRESETS = "presets"
        const val MAX_BOARDS = 8
    }
}
