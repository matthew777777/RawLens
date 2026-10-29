// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Paint

/**
 * Shared look for the live-view overlays (focus/metering marks, level, guides, RAW spot meter).
 *
 * Thin translucent white strokes over a soft shadow instead of hard black/white double lines,
 * with the warm accent reserved for locked or tracked states. Widths are in dp.
 */
object OverlayStyle {
    /** Primary marks: AF box, level line, spot meter (white at ~90%). */
    const val STROKE = 0xE6FFFFFF.toInt()
    /** Secondary marks: AE ring, faces, guide edges (white at ~70%). */
    const val STROKE_SECONDARY = 0xB3FFFFFF.toInt()
    /** Composition grid (white at ~25%). */
    const val GRID = 0x40FFFFFF
    /** Locked / tracked accent. */
    const val ACCENT = 0xFFDDC08D.toInt()
    /** Dark ink drawn on top of the accent (lock keyhole). */
    const val ACCENT_INK = 0xFF241B0C.toInt()
    /** Letterbox / framing-guide mask. */
    const val MASK = 0xCC000000.toInt()
    /** Soft shadow that keeps white marks legible over bright scenes. */
    const val SHADOW = 0x73000000

    const val STROKE_DP = 1.5f
    const val STROKE_THIN_DP = 1.25f
    const val HAIRLINE_DP = 1f
    const val SHADOW_RADIUS_DP = 2f
    const val CORNER_DP = 4f

    /** Round-capped anti-aliased stroke with the shared soft shadow. */
    fun stroke(color: Int, widthDp: Float, density: Float, shadow: Boolean = true) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            strokeWidth = widthDp * density
            if (shadow) setShadowLayer(SHADOW_RADIUS_DP * density, 0f, 0.5f * density, SHADOW)
        }

    fun fill(color: Int, density: Float, shadow: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            if (shadow) setShadowLayer(SHADOW_RADIUS_DP * density, 0f, 0.5f * density, SHADOW)
        }
}
