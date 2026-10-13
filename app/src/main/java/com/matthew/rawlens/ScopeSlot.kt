// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Pure seating math for the shared histogram/waveform slot.
 *
 * The scope rotates as a whole view with the device. On a quarter turn its
 * visual height equals its measured width, so the slot needs an extra
 * `(width - height) / 2` lift or the rotated scope dips behind the capture
 * panel. The margin is recomputed idempotently from the base position every
 * time — never accumulated — so repeated rotations cannot drift.
 */
object ScopeSlot {
    /**
     * Size to seat with: the measured size wins; before the first layout
     * pass (or right after GONE -> VISIBLE, when width/height are still 0)
     * fall back to the fixed layout slot so rotation never parks the scope
     * behind the panel for lack of measurements.
     */
    fun sizePx(measuredPx: Int, layoutPx: Int): Int =
        measuredPx.takeIf { it > 0 } ?: layoutPx.takeIf { it > 0 } ?: 0

    /**
     * Bottom margin seating the scope above the capture panel.
     *
     * @param quarterTurn true when the device is at 90/270 degrees.
     * @param widthPx heightPx seating sizes from [sizePx].
     * @param basePx panel height + gap above the panel (+ floating-row lift).
     */
    fun bottomMarginPx(
        quarterTurn: Boolean,
        widthPx: Int,
        heightPx: Int,
        basePx: Int,
    ): Int {
        val inset = if (quarterTurn && widthPx > heightPx) (widthPx - heightPx) / 2 else 0
        return basePx + inset
    }
}
