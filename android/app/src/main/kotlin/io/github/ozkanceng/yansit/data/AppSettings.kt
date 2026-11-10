package io.github.ozkanceng.yansit.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.Preset
import io.github.ozkanceng.yansit.sender.StreamOverrides

/** Uygulama ayarları ve kalıcı cihaz kimliği (yedeklenmez: data_extraction_rules). */
class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("yansit", Context.MODE_PRIVATE)

    /** Kurulumda bir kez üretilen 16 baytlık rastgele kimlik (PROTOCOL.md §7.1 device_id). */
    val deviceId: Bytes
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { hex ->
                runCatching { Bytes.fromHex(hex) }.getOrNull()?.takeIf { it.size == 16 }?.let { return it }
            }
            val id = Auth.randomBytes(16)
            prefs.edit { putString(KEY_DEVICE_ID, id.toHex()) }
            return id
        }

    /** Tahtada görünen ad: cihaz adı (Ayarlar → Telefon hakkında) yoksa model. */
    val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    var preset: Preset
        get() = prefs.getString(KEY_PRESET, null)?.let { runCatching { Preset.valueOf(it) }.getOrNull() } ?: DEFAULT_PRESET
        set(value) = prefs.edit { putString(KEY_PRESET, value.name) }

    /** Send the tablet's sound to the board (AudioPlaybackCapture). Off by default: classrooms mostly share the picture. */
    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, false)
        set(value) = prefs.edit { putBoolean(KEY_AUDIO, value) }

    /** Send the camera instead of the screen, for showing books and papers. */
    var cameraMode: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_CAMERA_MODE, value) }

    /** Tahta donanımla çözebiliyorsa H.265 kullan (deneysel; aynı kalitede ≈ %30–40 daha az bit hızı). */
    var hevcEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEVC, false)
        set(value) = prefs.edit { putBoolean(KEY_HEVC, value) }

    var overrides: StreamOverrides
        get() = StreamOverrides(
            longSide = prefs.getInt(KEY_LONG_SIDE, 0).takeIf { it > 0 },
            fps = prefs.getInt(KEY_FPS, 0).takeIf { it > 0 },
            maxBitrateBps = prefs.getLong(KEY_MAX_BITRATE, 0).takeIf { it > 0 },
        )
        set(value) = prefs.edit {
            putInt(KEY_LONG_SIDE, value.longSide ?: 0)
            putInt(KEY_FPS, value.fps ?: 0)
            putLong(KEY_MAX_BITRATE, value.maxBitrateBps ?: 0)
        }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PRESET = "preset"
        /** Balanced: school Wi-Fi rarely carries the best preset without freezes. A choice the teacher saved stays. */
        val DEFAULT_PRESET = Preset.BALANCED
        const val KEY_AUDIO = "audio"
        const val KEY_CAMERA_MODE = "camera_mode"
        const val KEY_HEVC = "hevc"
        const val KEY_LONG_SIDE = "override_long_side"
        const val KEY_FPS = "override_fps"
        const val KEY_MAX_BITRATE = "override_max_bitrate"
    }
}
