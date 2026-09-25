// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Bayer full/quad/plane coordinates shared by the CPU twin and GLES.
// Pure JVM port of the RawSensorUnpacker CFA semantics: identical pattern,
// shift, and black-tap rules so merges stay comparable.
package com.matthew.burstrecon

/** Bayer color of one sensor site. */
enum class CfaColor { RED, GREEN, BLUE }

/**
 * Standard 2x2 Bayer arrangements expressed at the full-sensor origin.
 * Cell order is row-major: top-left, top-right, bottom-left, bottom-right.
 */
enum class BayerPattern(private val cells: List<CfaColor>) {
    RGGB(listOf(CfaColor.RED, CfaColor.GREEN, CfaColor.GREEN, CfaColor.BLUE)),
    GRBG(listOf(CfaColor.GREEN, CfaColor.RED, CfaColor.BLUE, CfaColor.GREEN)),
    GBRG(listOf(CfaColor.GREEN, CfaColor.BLUE, CfaColor.RED, CfaColor.GREEN)),
    BGGR(listOf(CfaColor.BLUE, CfaColor.GREEN, CfaColor.GREEN, CfaColor.RED));

    /**
     * Color at absolute full-sensor coordinates [sensorX], [sensorY] (pixels).
     */
    fun colorAt(sensorX: Int, sensorY: Int): CfaColor =
        cells[((sensorY and 1) shl 1) or (sensorX and 1)]

    /**
     * Pattern of a crop whose stored pixel (0, 0) sits at full-sensor
     * ([sensorX], [sensorY]). Matches DNG ActiveArea phase handling.
     */
    fun shifted(sensorX: Int, sensorY: Int): BayerPattern {
        val shiftedCells = List(4) { index ->
            colorAt(sensorX + (index and 1), sensorY + (index shr 1))
        }
        return entries.first { it.cells == shiftedCells }
    }

    companion object {
        /** DNG CFAPattern order is row-major over the 2x2 repeat. */
        fun fromDngCfa(cfa: IntArray): BayerPattern {
            require(cfa.size == 4)
            val mapped = cfa.map {
                when (it) {
                    0 -> CfaColor.RED
                    1 -> CfaColor.GREEN
                    2 -> CfaColor.BLUE
                    else -> throw UnsupportedOperationException("Non-Bayer CFA value $it")
                }
            }
            return entries.first { it.cells == mapped }
        }

        fun fromCamera2(value: Int): BayerPattern = when (value) {
            0 -> RGGB
            1 -> GRBG
            2 -> GBRG
            3 -> BGGR
            else -> throw UnsupportedOperationException("Unknown CFA arrangement $value")
        }
    }
}

/** Which of the two green sites inside one Bayer quad. */
enum class GreenSite { FIRST, SECOND }

/**
 * Split-plane index inside one half-resolution quad texel:
 * 0 = R site, 1 = first green site, 2 = B site, 3 = second green site.
 * The mapping is pattern-dependent; never assume RGGB positions.
 */
typealias CfaPlaneMap = IntArray

/**
 * Full-RAW pixel (x, y) to Bayer-quad (qx, qy) plus intra-quad plane.
 *
 * @param x full-RAW stored pixel column.
 * @param y full-RAW stored pixel row.
 * @return Triple(qx, qy, planeIndex) where planeIndex follows [CfaPlaneMap].
 */
object BayerCoords {
    /** Quad column for full-RAW column [x] (pixels). */
    fun quadX(x: Int): Int = Math.floorDiv(x, 2)

    /** Quad row for full-RAW row [y] (pixels). */
    fun quadY(y: Int): Int = Math.floorDiv(y, 2)

    /** Half-resolution quad width for a full-RAW width (pixels, must be even). */
    fun quadWidth(fullWidth: Int): Int {
        require(fullWidth % 2 == 0) { "full width must be even" }
        return fullWidth / 2
    }

    /** Half-resolution quad height for a full-RAW height (pixels, must be even). */
    fun quadHeight(fullHeight: Int): Int {
        require(fullHeight % 2 == 0) { "full height must be even" }
        return fullHeight / 2
    }

    /**
     * Four-entry CFA-plane mapping for [pattern] at sensor origin
     * ([sensorLeft], [sensorTop]) in full-sensor pixels.
     * Entry order is intra-quad (top-left, top-right, bottom-left, bottom-right);
     * values are 0=R, 1=G1, 2=B, 3=G2 plane slots.
     *
     * P2 convention (finding 2): production callers pass the STORED pattern
     * (already shifted at admission) with 0,0. Passing a sensor pattern
     * together with a nonzero origin shifts twice. The defaults are kept
     * for source compatibility; new code must pass origins explicitly.
     */
    fun planeMap(pattern: BayerPattern, sensorLeft: Int = 0, sensorTop: Int = 0): CfaPlaneMap {
        val map = IntArray(4)
        for (qy in 0..1) for (qx in 0..1) {
            val color = pattern.colorAt(sensorLeft + qx, sensorTop + qy)
            val slot = when (color) {
                CfaColor.RED -> 0
                CfaColor.BLUE -> 2
                CfaColor.GREEN ->
                    if ((qx == 0 && qy == 0) || (qx == 1 && qy == 1 && pattern == BayerPattern.BGGR) ||
                        (pattern.greenFirstAt(sensorLeft, sensorTop, qx, qy))
                    ) 1 else 3
            }
            map[qy * 2 + qx] = slot
        }
        // Disambiguate the two greens: exactly one G1 and one G2.
        val greens = map.indices.filter { map[it] == 1 || map[it] == 3 }
        require(greens.size == 2) { "pattern $pattern must contain two greens" }
        // Canonicalize: first green in scan order is G1.
        map[greens[0]] = 1
        map[greens[1]] = 3
        return map
    }

    /**
     * Plane slot (0=R, 1=G1, 2=B, 3=G2) for full-RAW stored pixel (x, y)
     * under [pattern] with sensor origin ([sensorLeft], [sensorTop]).
     */
    fun planeAt(
        pattern: BayerPattern,
        sensorLeft: Int,
        sensorTop: Int,
        x: Int,
        y: Int
    ): Int {
        val map = planeMap(pattern, sensorLeft, sensorTop)
        return map[(y and 1) * 2 + (x and 1)]
    }

    /**
     * Intra-quad site offset of plane [slot] (0=R, 1=G1, 2=B, 3=G2) in QUAD
     * pixels under the STORED [pattern] (already shifted at admission; pass
     * 0,0 origins implicitly by using the stored pattern).
     *
     * Native CFA geometry: plane texel (qx, qy) holds the scene at full-RAW
     * pixel (2·qx+ix, 2·qy+iy), where (ix, iy) is the slot's intra-quad tap
     * from [planeMap]. In quad units that site sits at (qx+ix/2, qy+iy/2),
     * so a reference-to-source displacement field D over physical positions
     * must be evaluated at (x+ix/2, y+iy/2) — not at the quad node — and the
     * source plane array sampled at (x + Dx, y + Dy) in its own lattice.
     * For same-pattern bursts (admission guarantee) the lattice offsets
     * cancel in the array index; only the flow evaluation point shifts.
     * RGGB example: R=(0,0), G1=(0.5,0), B=(0.5,0.5), G2=(0,0.5).
     */
    fun planeSiteOffset(pattern: BayerPattern, slot: Int): Pair<Float, Float> {
        require(slot in 0..3) { "plane slot must be 0..3" }
        val map = planeMap(pattern)
        val k = map.indexOf(slot)
        require(k in 0..3) { "pattern $pattern must contain plane slot $slot" }
        return Pair((k % 2) / 2f, (k / 2) / 2f)
    }

    /**
     * Full-RAW coordinates (pixels) for quad (qx, qy) intra-quad tap (ix, iy).
     * Centralized conversion: full = quad * 2 + intra.
     */
    fun fullFromQuad(qx: Int, qy: Int, ix: Int, iy: Int): Pair<Int, Int> {
        require(ix in 0..1 && iy in 0..1) { "intra-quad tap must be 0..1" }
        return Pair(qx * 2 + ix, qy * 2 + iy)
    }

    /** Requires an even Bayer crop of at least 4x4, mirroring AMaZE admission. */
    fun requireEvenCrop(width: Int, height: Int) {
        require(width >= 4 && height >= 4 && width % 2 == 0 && height % 2 == 0) {
            "Bayer crop must be even and at least 4x4 (got ${width}x$height)"
        }
    }
}

private fun BayerPattern.greenFirstAt(sensorLeft: Int, sensorTop: Int, qx: Int, qy: Int): Boolean {
    // First green in scan order within this quad under the shifted pattern.
    val shifted = shifted(sensorLeft, sensorTop)
    var seenGreen = false
    for (sy in 0..1) for (sx in 0..1) {
        if (shifted.colorAt(sx, sy) == CfaColor.GREEN) {
            if (sx == qx && sy == qy) return !seenGreen
            seenGreen = true
        }
    }
    return false
}
