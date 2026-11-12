// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * EXPERIMENT (debug.rawlens.merge_vf_quiet=1): while an LK-R merge runs, the
 * RAW stream keeps feeding ZSL but the RAW VF, scopes, peaking and exposure
 * assist skip every frame, so the merge has the GPU alone. Measures how much
 * of the in-app vs headless gap is viewfinder GPU contention.
 */
internal object MergeGpuQuiet {
    @Volatile var active = false


    fun enabled(): Boolean = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.rawlens.merge_vf_quiet") == "1"
    } catch (_: Throwable) { false }

    inline fun <T> during(block: () -> T): T {
        val on = enabled()
        if (on) { active = true; Log.i("LkrMerge", "merge_vf_quiet: VF GPU work paused for merge") }
        try { return block() } finally { if (on) active = false }
    }
}
