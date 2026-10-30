// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Camera-thread budget, retained across opens; only real frames or user selection reset it. */
internal class DeviceFaultRecovery {
    private val failures = mutableMapOf<String, Int>()

    fun next(failed: String, verifiedCandidates: List<String>): String? {
        val count = (failures[failed] ?: 0) + 1
        failures[failed] = count
        if (count < 2) return failed
        return verifiedCandidates.firstOrNull { it != failed && (failures[it] ?: 0) < 2 }
    }

    fun reset() = failures.clear()
}
