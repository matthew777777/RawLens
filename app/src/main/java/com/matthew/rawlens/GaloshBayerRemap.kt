// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Lossless Bayer reshuffle to/from RGGB order for the GALOSH o32 shaders,
 * which hardcode an RGGB slot/sign map (cf. PhotonCamera's remosaic
 * normalization). Any Bayer pattern at any crop origin parity is a uniform
 * 2x2 arrangement over an even crop, so the remap is a pure permutation:
 * physical R/Gr/Gb/B identity is preserved (Gr = green in an R row, Gb =
 * green in a B row — row parity from the sensor pattern, not the quad),
 * and [fromRgbb] ∘ [toRgbb] is the identity.
 *
 * Kept free of Android APIs so unit tests cover the exact permutation the
 * device path uses.
 */
object GaloshBayerRemap {
    /**
     * Stored quad index (TL=0, TR=1, BL=2, BR=3) -> RGGB slot for quads at
     * sensor origin ([originX], [originY]). [pattern] must be the SENSOR
     * pattern (unshifted); callers holding a local (crop-shifted) pattern
     * must un-shift it first — see GaloshDenoiser. Identity iff the crop
     * already reads RGGB.
     */
    fun toRgbbPerm(pattern: BayerPattern, originX: Int, originY: Int): IntArray {
        var rRow = 0
        outer@ for (py in 0..1) for (px in 0..1) {
            if (pattern.colorAt(px, py) == CfaColor.RED) {
                rRow = py
                break@outer
            }
        }
        return IntArray(4) { k ->
            val sx = originX + (k and 1)
            val sy = originY + (k shr 1)
            when (pattern.colorAt(sx, sy)) {
                CfaColor.RED -> 0
                CfaColor.BLUE -> 3
                // Gr lives in R rows, Gb in B rows.
                CfaColor.GREEN -> if ((sy and 1) == rRow) 1 else 2
            }
        }
    }

    fun isIdentity(perm: IntArray): Boolean {
        if (perm.size != 4) return false
        for (i in 0..3) if (perm[i] != i) return false
        return true
    }

    /**
     * [toRgbbPerm] for a [UnpackedRawCfa], whose [UnpackedRawCfa.pattern] is
     * local (already shifted to the crop origin): un-shifts it back to the
     * sensor pattern first. A 2x2 shift is self-inverse, so shifting the
     * local pattern by the origin again recovers the sensor pattern.
     * Callers must use this instead of [toRgbbPerm] directly — passing the
     * local pattern scrambles colors whenever the crop origin is odd.
     */
    fun permForCfa(pattern: BayerPattern, originX: Int, originY: Int): IntArray =
        toRgbbPerm(pattern.shifted(originX, originY), originX, originY)

    /** Stored mosaic layout -> RGGB-ordered mosaic (same dims). */
    fun toRgbb(values: FloatArray, width: Int, height: Int, perm: IntArray): FloatArray {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer crop must have even dimensions" }
        require(values.size == width * height) { "CFA buffer size mismatch" }
        require(perm.size == 4) { "Permutation must have 4 entries" }
        // Slot s holds stored quad index inv[s].
        val inv = IntArray(4)
        for (k in 0..3) inv[perm[k]] = k
        val out = FloatArray(values.size)
        // Quad index -> row-major offset within the 2x2 block.
        val off = intArrayOf(0, 1, width, width + 1)
        val w2 = width / 2
        val h2 = height / 2
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val base = qy * 2 * width + qx * 2
            for (s in 0..3) out[base + off[s]] = values[base + off[inv[s]]]
        }
        return out
    }

    /** Inverse of [toRgbb]: RGGB-ordered mosaic -> stored mosaic layout. */
    fun fromRgbb(rgbb: FloatArray, width: Int, height: Int, perm: IntArray): FloatArray {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer crop must have even dimensions" }
        require(rgbb.size == width * height) { "CFA buffer size mismatch" }
        require(perm.size == 4) { "Permutation must have 4 entries" }
        val out = FloatArray(rgbb.size)
        val off = intArrayOf(0, 1, width, width + 1)
        val w2 = width / 2
        val h2 = height / 2
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val base = qy * 2 * width + qx * 2
            // Stored quad k holds RGGB slot perm[k].
            for (k in 0..3) out[base + off[k]] = rgbb[base + off[perm[k]]]
        }
        return out
    }
}
