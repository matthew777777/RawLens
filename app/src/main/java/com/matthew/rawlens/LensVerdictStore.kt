// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context

/**
 * Verdict for one Camera2 route, proven by real camera calls rather than
 * characteristics. VERIFIED means a session on this route delivered RAW
 * frames at least once (strongest); OPENABLE means the device opened at
 * least once; DEAD means the service rejected it as an unknown device
 * (deterministic per open ID, e.g. hidden vendor IDs on some ROMs).
 * UNKNOWN means nothing was ever proven.
 */
enum class LensVerdict { UNKNOWN, OPENABLE, DEAD, VERIFIED }

/** Passive stream-probe record for one discovery run. */
data class LensVerdicts(
    val openable: Set<String> = emptySet(),
    val dead: Set<String> = emptySet(),
    /** Route identities whose session delivered RAW frames on this device. */
    val verified: Set<String> = emptySet(),
    /**
     * Route identities whose HAL ran no AWB (unity gains, no neutral) for a
     * whole settled session: usable, but colour comes from RawLens'
     * estimate, so the picker prefers another route to the same lens.
     */
    val noHalAwb: Set<String> = emptySet(),
    /** Routes with inactive, motionless AF as well as missing HAL AWB. */
    val noHalAf: Set<String> = emptySet()
) {
    companion object {
        val EMPTY = LensVerdicts()
    }
}

/**
 * Persists open-time verdicts across restarts so re-running lens discovery
 * neither resurrects proven-dead routes as equals nor hides proven-good
 * ones. A later successful open always clears a DEAD mark (self-healing:
 * system updates do fix HALs), and explicit re-discovery still clears the
 * in-memory denylist in [RawCameraController.reloadLenses].
 */
class LensVerdictStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): LensVerdicts = LensVerdicts(openableIds(), deadIds(), verifiedIds(), noHalAwbIds(), noHalAfIds())

    fun noHalAfIds(): Set<String> =
        preferences.getStringSet(KEY_NO_HAL_AF, emptySet()).orEmpty().toSet()

    fun markHalAf(identity: String, working: Boolean) {
        if (identity.isBlank()) return
        val current = noHalAfIds()
        val next = if (working) current - identity else capped(current + identity)
        if (next != current) preferences.edit().putStringSet(KEY_NO_HAL_AF, next).apply()
    }

    fun noHalAwbIds(): Set<String> =
        preferences.getStringSet(KEY_NO_HAL_AWB, emptySet()).orEmpty().toSet()

    /**
     * Records whether this route's HAL ran AWB in a settled session. A later
     * session with working AWB clears the mark (firmware updates do fix it).
     */
    fun markHalAwb(identity: String, working: Boolean) {
        if (identity.isBlank()) return
        val current = noHalAwbIds()
        val next = if (working) current - identity else capped(current + identity)
        if (next != current) preferences.edit().putStringSet(KEY_NO_HAL_AWB, next).apply()
    }

    fun openableIds(): Set<String> =
        preferences.getStringSet(KEY_OPENABLE, emptySet()).orEmpty().toSet()

    fun deadIds(): Set<String> =
        preferences.getStringSet(KEY_DEAD, emptySet()).orEmpty().toSet()

    fun verifiedIds(): Set<String> =
        preferences.getStringSet(KEY_VERIFIED, emptySet()).orEmpty().toSet()

    /**
     * A key proven openable: an open ID, a route identity, or both (the
     * controller records both, so discovery matches exact identities).
     * Also clears any older DEAD mark for the same key.
     */
    fun markOpenable(key: String) {
        if (key.isBlank()) return
        preferences.edit()
            .putStringSet(KEY_OPENABLE, capped(openableIds() + key))
            .putStringSet(KEY_DEAD, deadIds() - key)
            .apply()
    }

    /**
     * A session on this route identity delivered RAW frames: the strongest
     * verdict. Also clears any stale DEAD mark for the same key.
     */
    fun markVerified(identity: String) {
        if (identity.isBlank()) return
        preferences.edit()
            .putStringSet(KEY_VERIFIED, capped(verifiedIds() + identity))
            .putStringSet(KEY_DEAD, deadIds() - identity)
            .apply()
    }

    /**
     * Unknown-device rejections are deterministic: remember the key (open
     * ID, route identity, or both).
     */
    fun markDead(key: String) {
        // A key that proved openable before reporting unknown-device is a
        // camera service hiccup, not a bad lens: never pin it dead.
        if (key.isBlank() || key in openableIds()) return
        preferences.edit()
            .putStringSet(KEY_DEAD, capped(deadIds() + key))
            .apply()
    }

    private fun capped(ids: Set<String>): Set<String> =
        if (ids.size <= MAX_IDS) ids else ids.take(MAX_IDS).toSet()

    companion object {
        const val PREFS_NAME = "rawlens_lens_verdicts"
        const val KEY_OPENABLE = "openable_ids"
        const val KEY_DEAD = "dead_ids"
        const val KEY_VERIFIED = "verified_ids"
        const val KEY_NO_HAL_AWB = "no_hal_awb_ids"
        const val KEY_NO_HAL_AF = "no_hal_af_ids"
        const val MAX_IDS = 64
    }
}
