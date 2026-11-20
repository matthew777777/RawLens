// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Optional asks moved out of first-run setup to the moment their feature
 * is first used, so install -> viewfinder is a single camera grant.
 *
 * - Location: once, right after the first capture saves.
 * - Photo folder: once, when the first ZSL burst (the only path that
 *   writes gyro sidecars) starts saving.
 * - Updates (updater builds): once, from the [UPDATE_ASK_LAUNCH]th launch.
 *
 * Pure decisions only; MainActivity owns prefs and dialogs. Covered by
 * unit test.
 */
object FirstUseAsks {

    const val KEY_LOCATION_ASKED = "first_use_location_asked"
    const val KEY_LAUNCH_COUNT = "first_use_launch_count"

    /** Launch from which the keep-up-to-date ask may appear. */
    const val UPDATE_ASK_LAUNCH = 3

    /** Status line of a finished capture save (photo, burst, merge). */
    fun isCaptureSaved(status: String): Boolean {
        val s = status.uppercase()
        return "SAVED" in s && "FOLDER" !in s && "NOT SAVED" !in s && "TEST" !in s
    }

    /** Status line of a ZSL burst starting its save (sidecars follow). */
    fun isSidecarBurstSave(status: String): Boolean =
        status.uppercase().startsWith("SAVING ZSL")

    fun shouldAskLocation(status: String, asked: Boolean, gpsOn: Boolean): Boolean =
        !asked && !gpsOn && isCaptureSaved(status)

    fun shouldAskUpdates(launchCount: Int): Boolean = launchCount >= UPDATE_ASK_LAUNCH
}
