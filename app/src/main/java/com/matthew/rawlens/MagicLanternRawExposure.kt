// SPDX-FileCopyrightText: 2009 Trammell Hudson <hudson+ml@osresearch.net>
// SPDX-FileCopyrightText: 2013 Magic Lantern Team
// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-2.0-or-later
package com.matthew.rawlens

import kotlin.math.log2
import kotlin.math.pow

/**
 * Kotlin port of Magic Lantern @ 142472e95054d73640f50789ac0fd66325499d82:
 * src/raw.c raw_to_ev / ev_to_raw and src/zebra.c zebra_rgb_solid_color.
 * Android adapts per-CFA black/white levels before these normalized rules.
 * Canon ExpSim and alternating-row dual ISO do not apply to Camera2 samples.
 * See docs/refactor/raw-exposure-assist-audit.md for source lines and changes.
 */
internal object MagicLanternRawExposure {
    const val RED = 4
    const val GREEN = 2
    const val BLUE = 1
    const val PREVIEW_RANGE_EV = 10.0 // raw.c raw_preview_fast_ex: raw_to_ev + 10.
    const val SPOT_HALF_WIDTH = 5.0 / 720.0 // zebra.c spotmeter_size=5, bitmap width 720.

    /** raw_to_ev: clamp black-subtracted input to one code through white before log2. */
    fun rawToEv(normalized: Double, oneCode: Double): Double =
        log2(normalized.coerceIn(oneCode, 1.0))

    /** ev_to_raw in black-subtracted, white-normalized units. */
    fun evToLevel(ev: Double): Double = 2.0.pow(ev)

    /** Port of the original priority and RGB clipping-mask switch; Android ARGB colours. */
    fun zebraColor(underexposed: Boolean, clipR: Boolean, clipG: Boolean, clipB: Boolean): Int {
        if (underexposed) return 0xfff0f0f0.toInt() // ML palette 79: almost white.
        return when ((if (clipR) RED else 0) or (if (clipG) GREEN else 0) or (if (clipB) BLUE else 0)) {
            7 -> 0xff000000.toInt()
            6 -> 0xffffff00.toInt()
            5 -> 0xffff00ff.toInt()
            3 -> 0xff00ffff.toInt()
            4 -> 0xffff0000.toInt()
            1 -> 0xff0000ff.toInt()
            2 -> 0xff00ff00.toInt()
            else -> 0
        }
    }
}
