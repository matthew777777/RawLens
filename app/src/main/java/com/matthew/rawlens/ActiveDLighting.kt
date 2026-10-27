// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Clean-room behaviour, not Nikon calibration. Sources and gates: docs/refactor/nikon-metering-audit.md. */
enum class ActiveDLighting(val label: String, val protectionEv: Double) {
    OFF("Off", 0.0), LOW("Low", 0.25), NORMAL("Normal", 0.5),
    HIGH("High", 0.75), EXTRA_HIGH("Extra High", 1.0);

    /** One absolute ETTR target; never add a second bias to an existing safer target. */
    fun ettrHeadroom(requested: Float): Float =
        if (this == OFF) requested else maxOf(requested, (0.5 + protectionEv).toFloat())

    companion object {
        const val PREFERENCE_KEY = "active_d_lighting"
        fun fromName(name: String?): ActiveDLighting = values().firstOrNull { it.name == name } ?: OFF
    }
}
