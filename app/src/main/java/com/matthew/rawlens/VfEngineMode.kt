// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * RAW viewfinder engine override, switched by tapping the RAW VF debug
 * overlay. AUTO (default) routes down the priority ladder — legacy GPU,
 * then legacy CPU (NEON), then BGU GPU, then BGU CPU — with automatic
 * fallback; GPU/CPU/BGU/BGU_CPU force one rung for A/B comparison, and
 * BGU_CPU forces the BGU engine onto its NEON guide path (same engine,
 * CPU guide + GL slice). Forced modes never fall back (a dead forced
 * engine shows as starved).
 */
enum class VfEngineMode {
    AUTO,
    BGU,
    BGU_CPU,
    GPU,
    CPU;

    /** Cycle order for overlay taps: AUTO -> GPU -> CPU -> BGU -> BGU_CPU -> AUTO. */
    fun next(): VfEngineMode = when (this) {
        AUTO -> GPU
        GPU -> CPU
        CPU -> BGU
        BGU -> BGU_CPU
        BGU_CPU -> AUTO
    }

    /** Short overlay label: actual path + override stay distinguishable. */
    fun shortLabel(): String = when (this) {
        AUTO -> "AUTO"
        // Same engine rendering as BGU; the GPU/NEON path prefix in the
        // overlay line (from the snapshot's gpu flag) carries the path.
        BGU, BGU_CPU -> "BGU!"
        GPU -> "GPU!"
        CPU -> "CPU!"
    }

    companion object {
        fun fromPreference(value: String?): VfEngineMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * Pure VF route decision: true routes offers + visibility to the BGU engine,
 * false to legacy. Forced modes never fall back (a dead forced engine shows
 * as starved). AUTO walks the ladder legacy-first: legacy while it is
 * present and alive, BGU only once legacy is dead or absent, legacy again
 * when BGU is also dead (its internal CPU rung is the last resort).
 */
internal fun resolveVfRoute(
    mode: VfEngineMode,
    legacyDead: Boolean = false,
    bguDead: Boolean = false,
    hasBgu: Boolean = true,
    hasLegacy: Boolean = true
): Boolean = when (mode) {
    VfEngineMode.BGU, VfEngineMode.BGU_CPU -> hasBgu
    VfEngineMode.GPU, VfEngineMode.CPU -> false
    VfEngineMode.AUTO -> when {
        hasLegacy && !legacyDead -> false
        hasBgu && !bguDead -> true
        else -> false
    }
}

/**
 * Map a resolved VF route onto nullable engine views. Recording must feed
 * the same engine visibility shows: BGU when routed there (legacy only if
 * BGU is absent), otherwise legacy.
 */
internal fun <T : Any> selectRoutedViewfinder(isBgu: Boolean, bgu: T?, legacy: T?): T? =
    if (isBgu) bgu ?: legacy else legacy
