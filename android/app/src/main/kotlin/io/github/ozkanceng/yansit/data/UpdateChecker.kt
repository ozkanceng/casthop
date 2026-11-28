package io.github.ozkanceng.yansit.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import io.github.ozkanceng.yansit.BuildConfig
import io.github.ozkanceng.yansit.protocol.SemVer
import io.github.ozkanceng.yansit.protocol.UpdateManifestParser
import io.github.ozkanceng.yansit.protocol.UpdateRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Kullanıcıya gösterilecek güncelleme durumu. */
data class UpdateBannerInfo(
    val currentVersion: String,
    val latestVersion: String,
    val notes: String,
    val url: String,
    val fromPlayStore: Boolean,
)

sealed interface ManualCheckResult {
    data class Available(val info: UpdateBannerInfo) : ManualCheckResult
    data class UpToDate(val currentVersion: String) : ManualCheckResult
    data object Failed : ManualCheckResult
}

/** Ayarlar ekranında configuration-aware `stringResource` ile gösterilecek durum. */
sealed interface UpdateStatusUi {
    data object Checking : UpdateStatusUi
    data class Available(val version: String) : UpdateStatusUi
    data class UpToDate(val version: String) : UpdateStatusUi
    data object Failed : UpdateStatusUi
}

/**
 * Arka planda `version.json` üzerinden yeni CastHop sürümünü denetler.
 * - Google Play kurulumlarında yalnız Play Store sayfasına yönlendirir; dış APK linki asla göstermez.
 * - Günde en fazla 1 kez otomatik denetler; çevrimdışı/okul güvenlik duvarında sessizce atlar.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val TIMEOUT_MS = 4000
    private const val PLAY_STORE_INSTALLER = "com.android.vending"

    fun isInstalledFromPlayStore(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE_INSTALLER
    }.getOrDefault(false)

    suspend fun checkIfDue(context: Context, settings: AppSettings): UpdateBannerInfo? {
        if (!settings.autoCheckUpdates) return null
        val now = System.currentTimeMillis()
        if (settings.lastUpdateCheckMs > 0L && (now - settings.lastUpdateCheckMs) < CHECK_INTERVAL_MS) {
            return null
        }
        val current = SemVer.parse(BuildConfig.VERSION_NAME) ?: return null
        if (current.prerelease && BuildConfig.VERSION_NAME.endsWith("-dev")) return null
        val fromPlay = isInstalledFromPlayStore(context)
        val release = fetchRelease(channel = if (fromPlay) "androidPlay" else "androidApk") ?: return null
        settings.lastUpdateCheckMs = now
        val candidate = SemVer.parse(release.version) ?: return null
        if (!candidate.isNewerThan(current)) return null
        if (settings.skippedUpdateVersion == release.version) return null
        return UpdateBannerInfo(
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = release.version,
            notes = release.notes,
            url = release.url,
            fromPlayStore = fromPlay,
        )
    }

    suspend fun checkNow(context: Context, settings: AppSettings): ManualCheckResult {
        val current = SemVer.parse(BuildConfig.VERSION_NAME)
            ?: return ManualCheckResult.UpToDate(BuildConfig.VERSION_NAME)
        val fromPlay = isInstalledFromPlayStore(context)
        val release = fetchRelease(channel = if (fromPlay) "androidPlay" else "androidApk")
            ?: return ManualCheckResult.Failed
        settings.lastUpdateCheckMs = System.currentTimeMillis()
        val candidate = SemVer.parse(release.version) ?: return ManualCheckResult.Failed
        if (candidate.isNewerThan(current)) {
            settings.skippedUpdateVersion = ""
            return ManualCheckResult.Available(
                UpdateBannerInfo(
                    currentVersion = BuildConfig.VERSION_NAME,
                    latestVersion = release.version,
                    notes = release.notes,
                    url = release.url,
                    fromPlayStore = fromPlay,
                ),
            )
        }
        return ManualCheckResult.UpToDate(BuildConfig.VERSION_NAME)
    }

    fun openUpdateDestination(context: Context, info: UpdateBannerInfo) {
        if (info.fromPlayStore) {
            val market = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(market) }.isSuccess) return
        }
        val safeUrl = if (UpdateManifestParser.isSafeHttpsUrl(info.url)) info.url else UpdateManifestParser.FALLBACK_RELEASE_URL
        val browser = Intent(Intent.ACTION_VIEW, safeUrl.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(browser) }
    }

    private suspend fun fetchRelease(channel: String): UpdateRelease? = withContext(Dispatchers.IO) {
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
            UpdateManifestParser.parse(bytes.toString(Charsets.UTF_8.name()), channel, preferTr)
        } catch (e: Exception) {
            Log.d(TAG, "güncelleme denetimi atlandı: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
