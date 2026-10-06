// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Sensor→RGGB processing-space orientation (reference `cfa_to_rggb`,
 * applied inside `load_dng_burst`): the reference aligns in RGGB space,
 * so the alignment grey is mapped there before [RawSrAlignment.fftGrey]
 * and the resulting flow field is mapped back to sensor space at the
 * alignment boundary (merge, robustness, and rejection stay sensor-space,
 * exactly as today).
 *
 * Why this matters: the Gaussian pyramid (valid convolution +
 * stride-from-0) and the circular pad are NOT flip-invariant — running
 * them in sensor space on a non-RGGB sensor samples the complementary
 * stride phase and pads the wrong scene end, flipping coarse near-tie
 * winners that the fine level cannot recover (±1.5px tile chatter that
 * imprints the 16px quilt). In RGGB space our winners match the
 * reference to ~1e-3px.
 *
 * Mirrored byte-identical in app/ and tools/sr-vulkan/ (see the parity
 * script); no Android/GL dependencies.
 */
object RawSrCfaOrientation {
    /** Sensor→processing flip. Every flip is its own inverse. */
    enum class Flip { IDENTITY, HFLIP, VFLIP, ROT180 }

    /**
     * Reference `cfa_to_rggb`, matched by CFA matrix (names differ
     * between trees; matrices don't): RGGB identity, BGGR rot180,
     * GRBG hflip, GBRG vflip.
     */
    fun forPattern(pattern: BayerPattern): Flip {
        val a = pattern.colorAt(0, 0).ordinal
        val b = pattern.colorAt(1, 0).ordinal
        val c = pattern.colorAt(0, 1).ordinal
        val d = pattern.colorAt(1, 1).ordinal
        if (a == 0 && b == 1 && c == 1 && d == 2) return Flip.IDENTITY
        if (a == 2 && b == 1 && c == 1 && d == 0) return Flip.ROT180
        if (a == 1 && b == 0 && c == 2 && d == 1) return Flip.HFLIP
        if (a == 1 && b == 2 && c == 0 && d == 1) return Flip.VFLIP
        throw IllegalArgumentException("Not a Bayer CFA permutation: [$a $b; $c $d]")
    }

    /**
     * Sensor-order mosaic in RGGB processing space. IDENTITY returns the
     * input array itself under a read-only contract (the sole consumer,
     * [RawSrAlignment.fftGrey], never mutates its input); other flips
     * return a fresh copy.
     */
    fun toProcessingSpace(values: FloatArray, width: Int, height: Int, flip: Flip): FloatArray {
        require(width > 0 && height > 0 && values.size == width * height)
        if (flip == Flip.IDENTITY) return values
        val out = FloatArray(values.size)
        for (y in 0 until height) {
            val sy = if (flip == Flip.VFLIP || flip == Flip.ROT180) height - 1 - y else y
            for (x in 0 until width) {
                val sx = if (flip == Flip.HFLIP || flip == Flip.ROT180) width - 1 - x else x
                out[y * width + x] = values[sy * width + sx]
            }
        }
        return out
    }

    /**
     * Processing-space flow field back in sensor space ([alignPair]
     * output convention: canonical tile centers, raw-pixel flows).
     * Mirror flips negate the mirrored flow component (vflip negates
     * dy, hflip negates dx, rot180 negates both); residuals and
     * reliability ride with their tile. IDENTITY returns the field
     * itself. Dims are unchanged (flips preserve width/height).
     *
     * The source tile is the processing tile holding the majority of
     * the flipped sensor tile (located via its flipped center,
     * clamped into the image) — NOT `rows - 1 - ty`. The mirror index
     * is off by one tile row whenever the image height is not a
     * multiple of the tile size (the 3060-row burst at ts 16: sensor
     * ty <- proc 190 - ty, not 191 - ty), shifting every sampled flow
     * by 3/4 tile and warping/merging the wrong tile at flow
     * discontinuities (blinds pit, tile quilt). On divisible dims the
     * majority rule reproduces the exact mirror.
     *
     * [sensorWidth]/[sensorHeight] are the UNPADDED raw dims: the
     * field's own dims are the padded alignment size (a tile
     * multiple), and deriving the flip from them reproduces the
     * off-by-one. The returned field keeps the input dims.
     */
    fun remapFieldToSensor(
        field: RawSrAlignmentField,
        flip: Flip,
        sensorWidth: Int,
        sensorHeight: Int
    ): RawSrAlignmentField {
        if (flip == Flip.IDENTITY) return field
        require(sensorWidth > 0 && sensorHeight > 0)
        require(sensorWidth <= field.imageWidth && sensorHeight <= field.imageHeight)
        val cols = field.columns
        val rows = field.rows
        val ts = field.tileSize
        val vflip = flip == Flip.VFLIP || flip == Flip.ROT180
        val hflip = flip == Flip.HFLIP || flip == Flip.ROT180
        // Flipped-center source row/col per sensor tile row/col: the
        // vflip of sensor rows [t*ts, min(t*ts+ts-1, H-1)] is
        // processing rows [H-1-hi, H-1-lo]; its middle row's tile
        // holds the majority (exact mirror on divisible dims).
        val srcRow = IntArray(rows) { ty ->
            if (!vflip) ty else {
                val lo = ty * ts
                val hi = minOf(lo + ts - 1, sensorHeight - 1)
                ((sensorHeight - 1 - (lo + hi) / 2) / ts).coerceIn(0, rows - 1)
            }
        }
        val srcCol = IntArray(cols) { tx ->
            if (!hflip) tx else {
                val lo = tx * ts
                val hi = minOf(lo + ts - 1, sensorWidth - 1)
                ((sensorWidth - 1 - (lo + hi) / 2) / ts).coerceIn(0, cols - 1)
            }
        }
        val tiles = ArrayList<RawSrTileFlow>(cols * rows)
        for (ty in 0 until rows) {
            val py = srcRow[ty]
            for (tx in 0 until cols) {
                val px = srcCol[tx]
                val t = field.tiles[py * cols + px]
                val dx = if (flip == Flip.HFLIP || flip == Flip.ROT180) -t.dx else t.dx
                val dy = if (flip == Flip.VFLIP || flip == Flip.ROT180) -t.dy else t.dy
                tiles.add(RawSrTileFlow(
                    centerX = tx * ts + ts / 2f,
                    centerY = ty * ts + ts / 2f,
                    dx = dx, dy = dy,
                    residual = t.residual, reliable = t.reliable))
            }
        }
        return RawSrAlignmentField(field.imageWidth, field.imageHeight, ts, cols, rows, tiles)
    }
}
