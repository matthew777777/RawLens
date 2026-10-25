// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check against GitHub releases.
 *
 * No new dependencies: plain [HttpURLConnection] on a caller-provided
 * background thread, JSON via the platform `org.json` package. Release
 * tags follow the app version scheme (`1.0.153`, optional `v` prefix),
 * so a newer tag means a newer build.
 *
 * The update itself installs outside the app: the prompt opens the
 * release APK URL (or the release page when no APK asset is attached)
 * in the browser, where the system handles download and install.
 */
object UpdateChecker {

    const val PREFS_NAME = "rawlens_settings"
    const val KEY_AUTO_CHECK = "update_auto_check"
    const val KEY_LAST_CHECK_MS = "update_last_check_ms"
    const val KEY_SKIPPED_TAG = "update_skipped_tag"

    /** Minimum gap between automatic startup checks. */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val TAG = "UpdateChecker"
    private const val LATEST_URL =
        "https://api.github.com/repos/matthew777777/RawLens/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    data class ApkAsset(val name: String, val url: String?, val sizeBytes: Long)

    private val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * Release assets may carry per-ABI splits plus a universal APK.
     * Prefer the split for the device's primary ABI, then the
     * universal APK (it still carries the primary ABI's native code),
     * then any other supported-ABI split, then the first APK.
     */
    internal fun pickApkAsset(assets: List<ApkAsset>, supportedAbis: Array<String>): ApkAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        fun splitFor(abi: String) =
            apks.firstOrNull { it.name.endsWith("-$abi.apk", ignoreCase = true) }
        supportedAbis.firstOrNull()?.let { primary ->
            splitFor(primary)?.let { return it }
        }
        apks.firstOrNull { asset ->
            knownAbis.none { asset.name.endsWith("-$it.apk", ignoreCase = true) }
        }?.let { return it }
        for (abi in supportedAbis) {
            splitFor(abi)?.let { return it }
        }
        return apks.first()
    }

    data class UpdateInfo(
        val tag: String,
        val name: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val apkSizeBytes: Long,
        val body: String,
        val publishedAt: String,
    )

    sealed interface CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * Runs the full check on the calling thread: fetch the latest
     * release, compare against the installed version, and report.
     * Never throws; failures arrive as [CheckResult.Failed].
     */
    fun check(context: Context): CheckResult {
        val current = installedVersionName(context) ?: return CheckResult.Failed("unknown installed version")
        val latest = try {
            fetchLatest()
        } catch (e: IOException) {
            Log.i(TAG, "update check failed: ${e.message}")
            return CheckResult.Failed(e.message ?: "network error")
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: $e")
            return CheckResult.Failed("bad release response")
        } ?: return CheckResult.Failed("no releases published yet")
        return if (isNewerThan(current, latest.tag)) {
            CheckResult.UpdateAvailable(latest)
        } else {
            CheckResult.UpToDate
        }
    }

    /** True when an automatic startup check is due (enabled + interval elapsed). */
    fun isAutoCheckDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_CHECK, true)) return false
        return nowMs - prefs.getLong(KEY_LAST_CHECK_MS, 0L) >= AUTO_CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK_MS, nowMs).apply()
    }

    fun isSkipped(context: Context, tag: String): Boolean {
        val skipped = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED_TAG, null)
        return skipped == tag
    }

    fun skipTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIPPED_TAG, tag).apply()
    }

    fun installedVersionName(context: Context): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Numeric segment comparison: "1.0.184" > "1.0.153". A leading `v`
     * and any `-suffix` are ignored, so "v1.0.2-rc1" compares as 1.0.2.
     * Non-numeric segments compare as 0; unequal length pads with 0.
     */
    fun isNewerThan(current: String, latestTag: String): Boolean {
        val a = normalizeVersion(current)
        val b = normalizeVersion(latestTag)
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    private fun normalizeVersion(raw: String): List<Int> {
        return raw.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    @Throws(IOException::class)
    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RawLens-Android")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub API HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        if (json.optBoolean("draft", false)) return null
        val tag = json.optString("tag_name", "").trim()
        if (tag.isEmpty()) return null
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            val candidates = buildList {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    add(
                        ApkAsset(
                            asset.optString("name", ""),
                            asset.optString("browser_download_url", "").ifEmpty { null },
                            asset.optLong("size", 0L),
                        )
                    )
                }
            }
            pickApkAsset(candidates, Build.SUPPORTED_ABIS)?.let {
                apkUrl = it.url
                apkSize = it.sizeBytes
            }
        }
        return UpdateInfo(
            tag = tag,
            name = json.optString("name", tag).ifEmpty { tag },
            htmlUrl = json.optString("html_url", ""),
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            body = json.optString("body", "").trim(),
            publishedAt = json.optString("published_at", ""),
        )
    }
}
