// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Direct-Log post-record stabilization output: which MP4s survive a take.
 *
 * The gyro sidecar is recorded whenever the mode is not [OG_ONLY]; the
 * Vulkan warp pass runs on the stop path and the staged original is kept
 * or deleted per the mode. Persisted by name (see `KEY_VIDEO_STAB_OUTPUT`);
 * unknown/blank heals to [OG_ONLY] so an upgrade never deletes originals.
 */
enum class StabOutputMode(
    /** Short settings label. */
    val label: String,
) {
    /** Today's behavior: original MP4 only, no gyro recorded, no warp pass. */
    OG_ONLY("OG only"),

    /** Stabilized MP4 only: the staged original is deleted after a verified warp. */
    STABILIZED_ONLY("Stabilized only"),

    /** Both MP4s: original plus the stabilized re-encode. */
    BOTH("Both");

    /** Next mode in settings cycle order (OG -> stabilized -> both -> OG). */
    fun next(): StabOutputMode {
        val entries = entries
        return entries[(entries.indexOf(this) + 1) % entries.size]
    }

    companion object {
        /** Parses a persisted mode name; unknown/blank heals to [OG_ONLY]. */
        fun fromName(name: String?): StabOutputMode =
            try {
                if (name.isNullOrBlank()) OG_ONLY else valueOf(name)
            } catch (_: IllegalArgumentException) {
                OG_ONLY
            }
    }
}
