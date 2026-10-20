// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * In-app update UI: consent, startup/manual GitHub release checks, the
 * DownloadManager progress dialog, and handing the APK to the system
 * installer (PackageInstaller via ACTION_VIEW; unknown-sources grant per
 * https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES).
 * Extracted verbatim from MainActivity; network/install logic lives in
 * [UpdateChecker] and [UpdateInstaller].
 *
 * @param requestCode activity request code for the unknown-sources screen;
 *   the host forwards that result to [onInstallSourcesResult].
 */
class UpdateFlow(
    private val activity: Activity,
    private val requestCode: Int,
    private val setStatus: (String) -> Unit,
) {
    /** Unknown-sources grant pending: install this file on return. */
    private var pendingInstallFile: File? = null

    /** First-run consent deferred until lens discovery leaves the screen. */
    var pendingConsent = false

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** One-time update consent dialog. Enabling checks immediately instead of waiting. */
    fun showConsent() {
        UpdateConsent.showIfNeeded(activity) { enabled ->
            if (enabled) maybeCheckOnStartup()
        }
    }

    /** Fires the deferred first-run consent once discovery is off screen. */
    fun showPendingConsent() {
        if (!pendingConsent) return
        pendingConsent = false
        showConsent()
    }

    /**
     * Startup update check: at most once per 24h, off the main thread,
     * silent unless a newer GitHub release exists. Never blocks camera
     * startup and never shows anything on failure.
     */
    fun maybeCheckOnStartup() {
        // F-Droid builds leave updates to the store, and everyone else
        // stays fully offline until the start-screen consent opts in.
        if (!UpdateConsent.isAutoCheckAllowed(activity)) return
        if (!UpdateChecker.isAutoCheckDue(activity)) return
        Thread {
            val result = UpdateChecker.check(activity)
            UpdateChecker.markChecked(activity)
            if (result is UpdateChecker.CheckResult.UpdateAvailable &&
                !UpdateChecker.isSkipped(activity, result.info.tag)
            ) {
                activity.runOnUiThread { if (!activity.isFinishing) showUpdateDialog(result.info) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Manual check from Settings → About: always reports the outcome. */
    fun checkManually() {
        if (!BuildConfig.ENABLE_UPDATE_CHECK) return
        setStatus("CHECKING FOR UPDATES…")
        Thread {
            val result = UpdateChecker.check(activity)
            UpdateChecker.markChecked(activity)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when (result) {
                    is UpdateChecker.CheckResult.UpdateAvailable ->
                        showUpdateDialog(result.info)
                    is UpdateChecker.CheckResult.UpToDate ->
                        setStatus("RAWLENS IS UP TO DATE")
                    is UpdateChecker.CheckResult.Failed ->
                        setStatus("UPDATE CHECK FAILED • ${result.reason.uppercase()}")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val current = UpdateChecker.installedVersionName(activity) ?: "?"
        val size = if (info.apkSizeBytes > 0) {
            "%.0f MB".format(Locale.US, info.apkSizeBytes / 1048576.0)
        } else null
        val notes = info.body.ifEmpty { "See the release page for details." }
            .take(1200)
        val message = StringBuilder("RawLens ${info.tag} is available " +
            "(installed: $current)")
        if (size != null) message.append(" • APK $size")
        message.append("\n\n").append(notes)
        // A download that finished before e.g. an app restart installs
        // directly instead of downloading again.
        val staged = UpdateInstaller.stagedApk(activity, info.tag)
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(message.toString())
            .setPositiveButton(if (staged != null) "Install" else "Download") { _, _ ->
                if (staged != null) attemptInstall(staged) else downloadAndInstallUpdate(info)
            }
            .setNeutralButton("Skip this version") { _, _ ->
                UpdateChecker.skipTag(activity, info.tag)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Fully in-app update: downloads the release APK with the system
     * DownloadManager (progress dialog here, system notification too)
     * and fires the system installer on completion. Falls back to the
     * browser only when the release carries no APK asset.
     */
    private fun downloadAndInstallUpdate(info: UpdateChecker.UpdateInfo) {
        if (info.apkUrl.isNullOrEmpty()) {
            openUpdateInBrowser(info.htmlUrl.ifEmpty { null })
            return
        }
        val downloadId = try {
            UpdateInstaller.startDownload(activity, info)
        } catch (e: Exception) {
            setStatus("UPDATE DOWNLOAD FAILED")
            return
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val statusText = TextView(activity).apply {
            text = "Starting download…"
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(statusText)
        }
        var cancelled = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading RawLens ${info.tag}")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                cancelled = true
                UpdateInstaller.cancel(activity, downloadId)
                setStatus("UPDATE DOWNLOAD CANCELLED")
            }
            .show()
        Thread {
            try {
                while (!cancelled) {
                    val progress = try {
                        UpdateInstaller.query(activity, downloadId)
                    } catch (_: Exception) {
                        null
                    }
                    if (progress == null) {
                        Thread.sleep(500)
                        continue
                    }
                    val doneMb = progress.downloadedBytes / 1048576.0
                    val totalMb = progress.totalBytes / 1048576.0
                    activity.runOnUiThread {
                        if (progress.totalBytes > 0) {
                            bar.isIndeterminate = false
                            bar.max = 100
                            bar.progress = ((100L * progress.downloadedBytes) /
                                progress.totalBytes).toInt().coerceIn(0, 100)
                            statusText.text = "%.0f of %.0f MB".format(
                                Locale.US, doneMb, totalMb
                            )
                        } else {
                            statusText.text = "%.0f MB downloaded".format(Locale.US, doneMb)
                        }
                    }
                    if (progress.isFinished) {
                        val ok = progress.status == DownloadManager.STATUS_SUCCESSFUL
                        val file = UpdateInstaller.apkFile(activity, info.tag)
                        activity.runOnUiThread {
                            dialog.dismiss()
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (ok && file.exists() && file.length() > 0) {
                                attemptInstall(file)
                            } else {
                                setStatus("UPDATE DOWNLOAD FAILED")
                            }
                        }
                        return@Thread
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
                // Cancel path; dialog already dismissed by the button.
            }
        }.apply { isDaemon = true }.start()
    }

    fun attemptInstall(file: File) {
        when (val outcome = UpdateInstaller.install(activity, file)) {
            is UpdateInstaller.InstallOutcome.Launched -> {
                pendingInstallFile = null
                setStatus("INSTALLING UPDATE…")
            }
            is UpdateInstaller.InstallOutcome.NeedsUnknownSources -> {
                // First in-app update on this install: Android requires a
                // per-app unknown-sources grant; install on return.
                pendingInstallFile = file
                try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(
                        UpdateInstaller.unknownSourcesIntent(activity),
                        requestCode
                    )
                } catch (_: Exception) {
                    pendingInstallFile = null
                    setStatus("CANNOT OPEN INSTALL SETTINGS")
                }
            }
            is UpdateInstaller.InstallOutcome.Failed ->
                setStatus("UPDATE INSTALL FAILED • ${outcome.reason.uppercase()}")
        }
    }

    /** Last resort when a release has no APK asset: open its page in the browser. */
    private fun openUpdateInBrowser(url: String?) {
        if (url.isNullOrEmpty()) {
            setStatus("UPDATE LINK UNAVAILABLE")
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            setStatus("NO BROWSER TO OPEN UPDATE")
        }
    }

    /**
     * The unknown-sources screen returns no reliable result; just retry:
     * granted now, the installer fires, else we re-prompt.
     */
    fun onInstallSourcesResult() {
        pendingInstallFile?.let { attemptInstall(it) }
    }
}
