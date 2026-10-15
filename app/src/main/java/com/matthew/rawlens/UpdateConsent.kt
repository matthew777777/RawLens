// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * One-time update-check consent, asked on the start screen.
 *
 * GitHub/Telegram builds ask once whether RawLens may check for
 * updates on startup; the switch defaults to on and the choice can be
 * flipped later under Settings → About. Off means fully offline: no
 * automatic network activity of any kind (the updater is the app's
 * only network user). A manual "Check for updates" tap is always an
 * explicit per-tap decision and still works when automatic checks are
 * off.
 *
 * F-Droid builds ([BuildConfig.ENABLE_UPDATE_CHECK] false) never ask
 * and never check — the store owns updates there.
 */
object UpdateConsent {

    const val KEY_CONSENT_ASKED = "update_consent_asked"

    /**
     * Pure dialog-visibility decision: ask exactly once, and only in
     * builds whose updater exists. Covered by unit test.
     */
    fun shouldShow(updaterEnabled: Boolean, consentAsked: Boolean): Boolean =
        updaterEnabled && !consentAsked

    /** True only after the user opted into automatic checks. */
    fun isAutoCheckAllowed(context: Context): Boolean {
        if (!BuildConfig.ENABLE_UPDATE_CHECK) return false
        val prefs = context.getSharedPreferences(
            UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE
        )
        return prefs.getBoolean(KEY_CONSENT_ASKED, false) &&
            prefs.getBoolean(UpdateChecker.KEY_AUTO_CHECK, false)
    }

    /**
     * Shows the consent dialog when due. [onDecided] fires with the
     * user's choice once they confirm (true = automatic checks on).
     */
    fun showIfNeeded(activity: Activity, onDecided: (Boolean) -> Unit = {}) {
        if (!BuildConfig.ENABLE_UPDATE_CHECK || activity.isFinishing) return
        val prefs = activity.getSharedPreferences(
            UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE
        )
        if (!shouldShow(true, prefs.getBoolean(KEY_CONSENT_ASKED, false))) return
        val toggle = Switch(activity).apply {
            text = "Check for updates on startup"
            isChecked = true
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(toggle)
            addView(TextView(activity).apply {
                text = "RawLens compares the installed version against the " +
                    "latest GitHub release (matthew777777/RawLens), at most " +
                    "once a day. Only the public release info is fetched; " +
                    "nothing is uploaded.\n\n" +
                    "Off means fully offline: the app never contacts the " +
                    "network on its own. You can change this anytime under " +
                    "Settings → About."
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, pad / 2, 0, 0)
            })
        }
        AlertDialog.Builder(activity)
            .setTitle("Keep RawLens up to date?")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Confirm") { _, _ ->
                val enabled = toggle.isChecked
                prefs.edit()
                    .putBoolean(KEY_CONSENT_ASKED, true)
                    .putBoolean(UpdateChecker.KEY_AUTO_CHECK, enabled)
                    .apply()
                onDecided(enabled)
            }
            .show()
    }
}
