// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Fully in-app update install: downloads the release APK with the
 * system [DownloadManager] (retries, background, ~90 MB safe) into the
 * app-private external files dir, then hands it to the system installer
 * through [FileProvider]. The user still confirms every install, and on
 * first use Android asks to allow installs from RawLens
 * ([Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]).
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val PREFS_NAME = "rawlens_settings"
    private const val KEY_DOWNLOAD_ID = "update_download_id"
    private const val KEY_DOWNLOAD_TAG = "update_download_tag"

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val status: Int,
    ) {
        val isFinished: Boolean
            get() = status == DownloadManager.STATUS_SUCCESSFUL ||
                status == DownloadManager.STATUS_FAILED
    }

    sealed interface InstallOutcome {
        /** System installer is now showing; the update proceeds there. */
        data object Launched : InstallOutcome
        /** Caller must send the user to [unknownSourcesIntent] first. */
        data object NeedsUnknownSources : InstallOutcome
        data class Failed(val reason: String) : InstallOutcome
    }

    /** Staging name for a release tag. Pure: covered by unit test. */
    fun apkFileName(tag: String): String {
        val safe = tag.trim().ifEmpty { "unknown" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return "RawLens-$safe-update.apk"
    }

    fun apkFile(context: Context, tag: String): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFileName(tag))

    /**
     * Enqueues the APK download and returns its DownloadManager id.
     * Drops any previous staged APK first. Throws on failure; the
     * caller reports it (DownloadManager missing, bad URL, no dest).
     */
    fun startDownload(context: Context, info: UpdateChecker.UpdateInfo): Long {
        val apkUrl = info.apkUrl?.ifEmpty { null }
            ?: throw IllegalArgumentException("release has no APK asset")
        val dm = downloadManager(context)
            ?: throw IllegalStateException("DownloadManager unavailable")
        deleteStaleApks(context, keepTag = info.tag)
        val dest = apkFile(context, info.tag)
        if (dest.exists() && !dest.delete()) {
            throw IllegalStateException("cannot clear previous download")
        }
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("RawLens ${info.tag}")
            setDescription("Downloading update")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setAllowedOverMetered(true)
            setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, apkFileName(info.tag)
            )
        }
        val id = dm.enqueue(request)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_DOWNLOAD_TAG, info.tag)
            .apply()
        return id
    }

    /** Current progress, or null when the id is unknown/forgotten. */
    fun query(context: Context, id: Long): Progress? {
        val dm = downloadManager(context) ?: return null
        dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            )
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
            ))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
            ))
            return Progress(done, total, status)
        }
    }

    fun cancel(context: Context, id: Long) {
        try {
            downloadManager(context)?.remove(id)
        } catch (_: Exception) {
            // Best effort; the id may already be gone.
        }
    }

    /**
     * A fully downloaded APK for this tag that survived e.g. an app
     * restart mid-download, or null. Lets the update dialog offer
     * "Install" directly instead of re-downloading.
     */
    fun stagedApk(context: Context, tag: String): File? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DOWNLOAD_TAG, null) != tag) return null
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return null
        val progress = try {
            query(context, id)
        } catch (_: Exception) {
            null
        } ?: return null
        if (progress.status != DownloadManager.STATUS_SUCCESSFUL) return null
        val file = apkFile(context, tag)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Fires the system installer for a downloaded APK. Returns
     * [InstallOutcome.NeedsUnknownSources] when the caller must route
     * through [unknownSourcesIntent] first (minSdk 30, so
     * `canRequestPackageInstalls` always exists).
     */
    fun install(context: Context, file: File): InstallOutcome {
        if (!file.exists() || file.length() <= 0) {
            return InstallOutcome.Failed("downloaded file missing")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return InstallOutcome.NeedsUnknownSources
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Resolve check: a device without any installer must not crash.
            if (intent.resolveActivity(context.packageManager) == null) {
                return InstallOutcome.Failed("no system installer")
            }
            context.startActivity(intent)
            InstallOutcome.Launched
        } catch (e: Exception) {
            Log.i(TAG, "install failed: $e")
            InstallOutcome.Failed(e.message ?: "cannot open installer")
        }
    }

    /** System screen where the user allows installs from this app. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    private fun downloadManager(context: Context): DownloadManager? =
        context.getSystemService(DownloadManager::class.java)

    private fun deleteStaleApks(context: Context, keepTag: String) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return
            val keep = apkFileName(keepTag)
            dir.listFiles { f ->
                f.isFile && f.name.startsWith("RawLens-") &&
                    f.name.endsWith("-update.apk") && f.name != keep
            }?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
            // Cleanup must never break an update.
        }
    }
}
