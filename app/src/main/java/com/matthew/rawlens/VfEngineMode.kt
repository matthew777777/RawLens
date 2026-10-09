// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * RAW viewfinder engine override, switched by tapping the RAW VF debug
 * overlay. AUTO (default) routes to the preferred engine with automatic
 * fallback; BGU/GPU/CPU force one engine for A/B comparison, and BGU_CPU
 * forces the BGU engine onto its NEON guide path (same engine, CPU guide +
 * GL slice — the manual form of the automatic CPU fallback). Forced modes
 * never fall back (a dead forced engine shows as starved).
 */
enum class VfEngineMode {
    AUTO,
    BGU,
    BGU_CPU,
    GPU,
    CPU;

    /** Cycle order for overlay taps: AUTO -> BGU -> BGU_CPU -> GPU -> CPU -> AUTO. */
    fun next(): VfEngineMode = when (this) {
        AUTO -> BGU
        BGU -> BGU_CPU
        BGU_CPU -> GPU
        GPU -> CPU
        CPU -> AUTO
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
 * as starved); AUTO prefers BGU only while it is alive and preferred.
 */
internal fun resolveVfRoute(
    mode: VfEngineMode, autoPrefersBgu: Boolean, bguDead: Boolean, hasBgu: Boolean
): Boolean = when (mode) {
    VfEngineMode.BGU, VfEngineMode.BGU_CPU -> hasBgu
    VfEngineMode.GPU, VfEngineMode.CPU -> false
    VfEngineMode.AUTO -> !bguDead && autoPrefersBgu && hasBgu
}
