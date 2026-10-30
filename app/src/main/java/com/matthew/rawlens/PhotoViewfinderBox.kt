// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Viewfinder box (photo, RAW video and Direct-Log): the 3:4 portrait viewport seated between the
 * opaque top island and the bottom control panel, bottom-aligned so the
 * panel's rounded top corners fill with picture. Pure integer math so the
 * geometry is unit-testable. Width always fills the root; on shorter screens
 * the picture extends behind the opaque chrome. The activity falls back to full-bleed when
 * measurements are missing (null).
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
     * @return the full-width 3:4 box (height rounded up to a pixel) plus its top margin, or null when the
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
        // Width is authoritative: fitting to the height leaves side gutters
        // when larger controls make the available space slightly too short.
        // Keep 3:4 (rounded up to a pixel) and tuck excess height behind the
        // opaque top island instead of narrowing or stretching the image.
        val boxW = rootW
        val boxH = ((rootW.toLong() * 4 + 2) / 3).toInt()
        val spare = (availH - boxH).coerceAtLeast(0)
        return Box(boxW, boxH, (rootH - panelH + sealOverlap - boxH).coerceAtLeast(0), spare)
    }
}
