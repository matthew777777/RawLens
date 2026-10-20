// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Photo-mode viewfinder box: the 3:4 portrait viewport seated between the
 * opaque top island and the bottom control panel, bottom-aligned so the
 * panel's rounded top corners fill with picture. Pure integer math so the
 * geometry is unit-testable; the activity falls back to full-bleed when
 * measurements are missing (null). RAW video / Direct-Log keep their own
 * full-bleed viewport and never use this.
 */
object PhotoViewfinderBox {
    /**
     * @param spare leftover pixels between the islands after seating the
     *   box ([available height][compute] minus [Box.height], never negative):
     *   the caller grows the bottom panel by it so the picture touches both
     *   islands with no void anywhere.
     */
    data class Box(val width: Int, val height: Int, val topMargin: Int, val spare: Int)

    /**
     * @param rootW root width px (>0)
     * @param rootH root height px (>0)
     * @param topBarH measured top-island height px (0 when not laid out yet)
     * @param panelH measured bottom-panel height px (0 when not laid out yet)
     * @param sealOverlap px the islands overlap the picture (corner radius)
     * @return the exact-pixel 3:4 box plus its top margin, or null when the
     *   measurements cannot seat a box yet.
     */
    fun compute(
        rootW: Int,
        rootH: Int,
        topBarH: Int,
        panelH: Int,
        sealOverlap: Int,
    ): Box? {
        if (rootW <= 0 || rootH <= 0) return null
        val availH = rootH - topBarH - panelH + 2 * sealOverlap
        if (availH <= 0) return null
        // Full-width 3:4 when it fits the available height (the portrait
        // norm: picture as large as possible); otherwise height-constrained,
        // centered horizontally by gravity.
        val (boxW, boxH) = if (rootW.toLong() * 4 <= availH.toLong() * 3) {
            rootW to (rootW.toLong() * 4 / 3).toInt()
        } else {
            ((availH.toLong() * 3 / 4).toInt() to availH)
        }
        if (boxW <= 0 || boxH <= 0) return null
        // Bottom-aligned: the picture tucks under the bottom island by the
        // seal overlap (its rounded top corners fill with picture, never
        // root black). The caller grows the bottom panel by [spare] so the
        // top tucks too and no void remains anywhere.
        val spare = (availH - boxH).coerceAtLeast(0)
        return Box(boxW, boxH, (topBarH - sealOverlap + spare).coerceAtLeast(0), spare)
    }
}
