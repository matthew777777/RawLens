// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * RAW viewfinder engine override, switched by tapping the RAW VF debug
 * overlay. AUTO (default) runs zero-copy GPU and falls back to the native
 * NEON CPU sampler automatically when the GPU tiers fail; GPU/CPU force one
 * engine for A/B comparison. Forced GPU never falls back (a dead GPU shows as
 * starved); forced CPU never attempts the GPU path.
 */
enum class VfEngineMode {
    AUTO,
    GPU,
    CPU;

    /** Cycle order for overlay taps: AUTO -> GPU -> CPU -> AUTO. */
    fun next(): VfEngineMode = when (this) {
        AUTO -> GPU
        GPU -> CPU
        CPU -> AUTO
    }

    /** Short overlay label: actual path + override stay distinguishable. */
    fun shortLabel(): String = when (this) {
        AUTO -> "AUTO"
        GPU -> "GPU!"
        CPU -> "CPU!"
    }

    companion object {
        fun fromPreference(value: String?): VfEngineMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
