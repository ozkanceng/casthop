package io.github.ozkanceng.casthop.tv

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.ozkanceng.yansit.protocol.SemVer
import io.github.ozkanceng.yansit.protocol.UpdateManifestParser
import io.github.ozkanceng.yansit.protocol.UpdateRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class TvUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val notes: String,
    val url: String,
)

internal sealed interface TvManualCheckResult {
    data class Available(val info: TvUpdateInfo) : TvManualCheckResult
    data class UpToDate(val currentVersion: String) : TvManualCheckResult
    data object Failed : TvManualCheckResult
}

internal sealed interface TvUpdateStatus {
    data object Checking : TvUpdateStatus
    data class Available(val version: String) : TvUpdateStatus
    data class UpToDate(val version: String) : TvUpdateStatus
    data object Failed : TvUpdateStatus
}

internal object TvUpdateChecker {
    private const val TAG = "TvUpdateChecker"
    private const val PREFS = "casthop_tv_updates"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_SKIPPED = "skipped_version"
    private const val KEY_LAST_MS = "last_check_ms"
    private const val INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val TIMEOUT_MS = 4000

    fun autoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_AUTO, on) }
    }

    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_SKIPPED, version.trim()) }
    }

    suspend fun checkIfDue(context: Context): TvUpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO, true)) return null
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_MS, 0L)
        if (last > 0L && (now - last) < INTERVAL_MS) return null
        val current = SemVer.parse(BuildConfig.VERSION_NAME) ?: return null
        if (current.prerelease && BuildConfig.VERSION_NAME.endsWith("-dev")) return null
        val release = fetchRelease() ?: return null
        prefs.edit { putLong(KEY_LAST_MS, now) }
        val candidate = SemVer.parse(release.version) ?: return null
        if (!candidate.isNewerThan(current)) return null
        if (prefs.getString(KEY_SKIPPED, "").orEmpty() == release.version) return null
        return TvUpdateInfo(BuildConfig.VERSION_NAME, release.version, release.notes, release.url)
    }

    suspend fun checkNow(context: Context): TvManualCheckResult {
        val current = SemVer.parse(BuildConfig.VERSION_NAME)
            ?: return TvManualCheckResult.UpToDate(BuildConfig.VERSION_NAME)
        val release = fetchRelease() ?: return TvManualCheckResult.Failed
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putLong(KEY_LAST_MS, System.currentTimeMillis()) }
        val candidate = SemVer.parse(release.version) ?: return TvManualCheckResult.Failed
        if (candidate.isNewerThan(current)) {
            prefs.edit { putString(KEY_SKIPPED, "") }
            return TvManualCheckResult.Available(
                TvUpdateInfo(BuildConfig.VERSION_NAME, release.version, release.notes, release.url),
            )
        }
        return TvManualCheckResult.UpToDate(BuildConfig.VERSION_NAME)
    }

    fun canOpenBrowser(context: Context, url: String): Boolean {
        if (!UpdateManifestParser.isSafeHttpsUrl(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        return intent.resolveActivity(context.packageManager) != null
    }

    fun openInBrowser(context: Context, url: String) {
        val safeUrl = if (UpdateManifestParser.isSafeHttpsUrl(url)) url else UpdateManifestParser.FALLBACK_RELEASE_URL
        val intent = Intent(Intent.ACTION_VIEW, safeUrl.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private suspend fun fetchRelease(): UpdateRelease? = withContext(Dispatchers.IO) {
        val conn = runCatching {
            (URL(UpdateManifestParser.DEFAULT_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CastHop-UpdateCheck")
            }
        }.getOrNull() ?: return@withContext null

        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val bytes = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (bytes.size() + n > UpdateManifestParser.MAX_BYTES) return@withContext null
                    bytes.write(buf, 0, n)
                }
            }
            val preferTr = Locale.getDefault().language.equals("tr", ignoreCase = true)
            UpdateManifestParser.parse(bytes.toString(Charsets.UTF_8.name()), "androidTv", preferTr)
        } catch (e: Exception) {
            Log.d(TAG, "TV güncelleme denetimi atlandı: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
