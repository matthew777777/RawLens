// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Fresh viewfinder geometry for the BGU engine (no shared code with the legacy
 * path): guide-quad coverage of the sensor crop, low-res mapping, and
 * display-rotation UVs. Pure and unit-tested.
 */
internal data class BguGuideGeo(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val step: Int
)

internal object BguGeometry {
    /** Low-res downsample factor: one low texel per 8x8 guide quads. */
    const val LOW_DF = 8

    /**
     * Quad grid covering [crop] ([l, t, r, b], null = full frame) with roughly
     * [longEdge] quads on the long side. The step is the smallest even integer
     * holding the budget (2 minimum); extents are even (odd widths sample a
     * half-texel edge column on some drivers) and at least 2.
     */
    fun guideGeometry(imgW: Int, imgH: Int, crop: IntArray?, longEdge: Int): BguGuideGeo {
        require(imgW >= 2 && imgH >= 2 && longEdge >= 1)
        require(crop == null || crop.size == 4)
        val left = (((crop?.get(0) ?: 0).coerceIn(0, imgW - 2) / 2) * 2)
        val top = (((crop?.get(1) ?: 0).coerceIn(0, imgH - 2) / 2) * 2)
        val right = (crop?.get(2) ?: imgW).coerceIn(left + 2, imgW)
        val bottom = (crop?.get(3) ?: imgH).coerceIn(top + 2, imgH)
        val span = maxOf(right - left, bottom - top)
        var step = (span + longEdge - 1) / longEdge
        if (step % 2 != 0) step++
        step = maxOf(2, step)
        var width = (right - left) / step
        var height = (bottom - top) / step
        width -= width % 2
        height -= height % 2
        return BguGuideGeo(left, top, maxOf(2, width), maxOf(2, height), step)
    }

    /** Low-res extents covering a [guideW] x [guideH] guide at [LOW_DF]. */
    fun lowDims(guideW: Int, guideH: Int, df: Int = LOW_DF): IntArray {
        require(guideW >= 1 && guideH >= 1 && df >= 1)
        return intArrayOf((guideW + df - 1) / df, (guideH + df - 1) / df)
    }

    /**
     * Sensor coords of the quad origin for low texel ([i], [j]): the guide
     * texel ([i]*df, [j]*df) at [guideStep] sensor px per guide texel from
     * ([baseL], [baseT]).
     */
    fun lowQuadOrigin(
        i: Int, j: Int, baseL: Int, baseT: Int, guideStep: Int, df: Int = LOW_DF
    ): IntArray = intArrayOf(baseL + i * df * guideStep, baseT + j * df * guideStep)

    /**
     * Display UVs for the fullscreen strip ([BL, BR, TL, TR] vertices) showing
     * the guide upright for [rotation] (sensor orientation, clockwise degrees)
     * and [mirrored] (front camera). Base: texture row 0 (sensor top) at the
     * screen top. Returns 8 floats, always a permutation of the unit corners.
     */
    fun displayUv(rotation: Int, mirrored: Boolean): FloatArray {
        val corners = floatArrayOf(
            0f, 1f, // BL
            1f, 1f, // BR
            0f, 0f, // TL
            1f, 0f // TR
        )
        if (mirrored) {
            for (v in 0..3) corners[v * 2] = 1f - corners[v * 2]
        }
        // SENSOR_ORIENTATION is the clockwise angle the sensor image needs to
        // appear upright: displayed content rotates clockwise, so each screen
        // corner samples the texture point one quarter-turn counter-clockwise
        // per turn: (x, y) -> (y, 1 - x). Pointwise (not a value permutation)
        // so mirror-then-rotate composes correctly.
        var turns = ((rotation % 360) + 360) % 360 / 90
        while (turns-- > 0) {
            for (v in 0..3) {
                val x = corners[v * 2]
                val y = corners[v * 2 + 1]
                corners[v * 2] = y
                corners[v * 2 + 1] = 1f - x
            }
        }
        return corners
    }

    /**
     * Canonical quad order [R, Gr, Gb, B] to sensor Bayer sites for Camera2
     * CFA arrangement [cfa] (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR). Site s sits at
     * quad-local (s % 2, s / 2).
     */
    fun cfaSites(cfa: Int): IntArray = when (cfa) {
        0 -> intArrayOf(0, 1, 2, 3)
        1 -> intArrayOf(1, 0, 3, 2)
        2 -> intArrayOf(2, 3, 0, 1)
        3 -> intArrayOf(3, 2, 1, 0)
        else -> throw IllegalArgumentException("Unsupported Bayer layout $cfa")
    }
}
