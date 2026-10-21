// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tiled highlight pre-stages (accumulator, density, downsample) for ROIs
// that do not fit the Java heap untiled. Same public port functions as
// the untiled chain, applied per tile window with halo context:
//
// - Tile interiors partition the ROI exactly; every window is the
//   interior grown by HALO px, clamped to the ROI. Interior pixels see
//   identical blur windows to the untiled run (halo covers the box
//   radii), and clamped ROI borders reproduce the untiled edge shrink.
// - Window origins/dims snap to the 32-grid, so boxblur_resamp's
//   8-column blocked vertical path covers the same columns in the same
//   order as the untiled run (bit-exact, no scalar-remainder columns).
// - Downsample cells stitch by global grid coordinates; cells whose
//   source center lies in the tile interior are written exactly once,
//   and the global fringe stays zero like the untiled pad.
//
// The only intentional divergence from untiled: hipassSum accumulates in
// tile order rather than row-major order, so hipassAve can differ by ~1
// ulp and flip gate pixels sitting exactly on the threshold (measured
// and gated in HlTiledPrestageTest). The directional scans stay full-map
// (quarter-res fits) with the TRUE map dims — never the 8x8 corner.
package com.matthew.rawlens

internal object HlTiledPrestage {
    /** Tile interior edge; interiors partition the ROI on this grid. */
    const val TILE = 512

    /**
     * Halo context each side (covers box radii 4/1/2 with margin, and is
     * itself 32-aligned so snapped windows stay on the 32-grid).
     */
    const val HALO = 32

    /**
     * Full pre-stage chain over [roi]: tiled accumulate/gate/downsample,
     * stitched quarter-res planes, then full-map directional scans with
     * true map dims. Returns the same [HlMaps] shape the reconstruct arms
     * take.
     */
    fun run(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        thresh: FloatArray,
        maxF: FloatArray,
        roi: HlRoi,
        /** Row/tile worker pool; null runs sequentially (unit tests). */
        workers: java.util.concurrent.ExecutorService? = null
    ): HlMaps {
        val tileList = tiles(roi)
        val t0 = android.os.SystemClock.elapsedRealtime()
        // Pass 1: global hipass reduce. Tile windows overlap at the halos,
        // so each window's accumulation would double-count the overlap —
        // instead re-evaluate the hipass predicate (mirrors
        // accumulateRgb exactly) only for interior pixels, which partition
        // the ROI: every ROI pixel counts once. The reduce runs in tile
        // order, so parallel execution stays bit-identical to sequential.
        // Variation is a pure function of the window, so pass 1 keeps each
        // tile's copy for pass 2 instead of recomputing it (boxBlur2 x3 was
        // ~20 % of the pre-stage). Parked off the Java heap (direct
        // buffers, ~65 MB at 12 MP) so the cache adds no heap pressure;
        // values round-trip exactly.
        val variationCache = arrayOfNulls<java.nio.FloatBuffer>(tileList.size)
        fun reduceOne(index: Int, window: HlRoi, interior: HlRoi): Pair<Double, Int> {
            var sum = 0.0
            var norm = 0
            val variation = RtHighlightMap.computeVariation(red, green, blue, window)
            val parked = java.nio.ByteBuffer.allocateDirect(window.width * window.height * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            for (row in variation) parked.put(row)
            variationCache[index] = parked
            for (i in 0 until window.height) {
                val gy = window.minY + i
                if (gy < interior.minY || gy > interior.maxY) continue
                for (j in 0 until window.width) {
                    val gx = window.minX + j
                    if (gx < interior.minX || gx > interior.maxX) continue
                    val r = red[gy][gx]
                    val g = green[gy][gx]
                    val b = blue[gy][gx]
                    if ((r > thresh[0] || g > thresh[1] || b > thresh[2]) &&
                        r < maxF[0] && g < maxF[1] && b < maxF[2]
                    ) {
                        sum += variation[i][j].toDouble()
                        norm++
                    }
                }
            }
            return sum to norm
        }
        var sum = 0.0
        var norm = 0
        if (workers == null) {
            for ((index, tile) in tileList.withIndex()) {
                val (s, n) = reduceOne(index, tile.first, tile.second)
                sum += s
                norm += n
            }
        } else {
            val futures = tileList.mapIndexed { index, (window, interior) ->
                workers.submit<Pair<Double, Int>> { reduceOne(index, window, interior) }
            }
            for (f in futures) {
                val (s, n) = f.get()
                sum += s
                norm += n
            }
        }
        val ave = (2.0 * sum / (norm + RtHighlightMap.EPSILON.toDouble())).toFloat()
        val t1 = android.os.SystemClock.elapsedRealtime()
        // Pass 2: gate + downsample per tile, stitch by global grid.
        // Tiles write disjoint hil cells — no synchronization needed.
        val hfw = (roi.width - roi.width % 4) / 4
        val hfh = (roi.height - roi.height % 4) / 4
        val hil = Array(4) { Array(hfh + 1) { FloatArray(hfw + 1) } }
        fun gateOne(index: Int, window: HlRoi, interior: HlRoi) {
            val parked = checkNotNull(variationCache[index]).also { it.rewind() }
            variationCache[index] = null
            val variation = Array(window.height) { FloatArray(window.width).also { row -> parked.get(row) } }
            val acc = RtHighlightMap.accumulateRgb(red, green, blue, variation, thresh, maxF, window)
            val ww = window.width
            val wh = window.height
            val density = RtBoxBlur.boxBlur2(acc.full[3], 0, 0, wh, ww, 1)
            RtHighlightMap.applyGates(acc.full, variation, density, ave)
            val small = RtHighlightMap.downsample(acc.full)
            stitch(small, window, interior, hil)
        }
        if (workers == null) {
            for ((index, tile) in tileList.withIndex()) gateOne(index, tile.first, tile.second)
        } else {
            val futures = tileList.mapIndexed { index, (window, interior) ->
                workers.submit { gateOne(index, window, interior) }
            }
            for (f in futures) f.get()
        }
        val t2 = android.os.SystemClock.elapsedRealtime()
        val maps = RtHighlightMap.directionalExtend(hil, hfw, hfh)
        android.util.Log.i(
            HlReconstructionStage.LOG_TAG,
            "hlprestage tiles=${tileList.size} reduce=${t1 - t0}ms gate=${t2 - t1}ms " +
                "scans=${android.os.SystemClock.elapsedRealtime() - t2}ms"
        )
        return maps
    }

    /**
     * Tile windows over [roi]: interiors partition the ROI on the
     * [TILE]-grid; each window is the interior grown by [HALO], clamped to
     * the ROI, with the origin snapped down to the 32-grid (best effort —
     * the ROI-edge clamp wins when they conflict, costing only
     * block-phase alignment on that edge tile).
     */
    internal fun forEachTile(roi: HlRoi, body: (window: HlRoi, interior: HlRoi) -> Unit) {
        for ((window, interior) in tiles(roi)) body(window, interior)
    }

    /**
     * Tile (window, interior) pairs in row-major order. Interiors partition
     * [roi] on the [tile]-grid; each window grows the interior by [halo]
     * per side (clamped to the ROI, origin snapped to the 32-grid).
     */
    internal fun tiles(roi: HlRoi, tile: Int = TILE, halo: Int = HALO): List<Pair<HlRoi, HlRoi>> {
        val out = ArrayList<Pair<HlRoi, HlRoi>>()
        var ty = roi.minY
        while (ty <= roi.maxY) {
            val iy1 = minOf(ty + tile - 1, roi.maxY)
            var tx = roi.minX
            while (tx <= roi.maxX) {
                val ix1 = minOf(tx + tile - 1, roi.maxX)
                // Window origin snaps down to the 32-grid (rows: 4-grid for
                // the dst lattice); it may dip below the ROI by less than
                // [halo] then — legal frame reads, smooth context — but
                // never below zero. Widths stay 32-multiples while the ROI
                // is.
                val bx0 = maxOf(roi.minX, tx - halo)
                val wx0 = maxOf(0, bx0 - bx0 % 32)
                val by0 = maxOf(roi.minY, ty - halo)
                val wy0 = maxOf(0, by0 - by0 % 4)
                val wx1 = minOf(roi.maxX, ix1 + halo)
                val wy1 = minOf(roi.maxY, iy1 + halo)
                out.add(HlRoi(wx0, wy0, wx1, wy1) to HlRoi(tx, ty, ix1, iy1))
                tx = ix1 + 1
            }
            ty = iy1 + 1
        }
        return out
    }

    /**
     * GPU reconstruct tile interior: 1536 halves the tile/dispatch count
     * vs 1024 at the same per-pixel math (halos cover the guided radii, so
     * interiors still match a full-ROI run) with better dispatch occupancy.
     * Transient per tile stays bounded (~38 MB interleave + staging +
     * download vs ~17 MB at 1024 — far under the untiled full-ROI staging
     * that OOMs the develop).
     */
    const val GTILE = 1536

    /** GPU tile halo: covers guided radii (3, 1) + resample footprints. */
    const val GHALO = 32

    /**
     * Slices a tile window out of full-ROI [maps]: map cell (i, j) covers
     * ROI pixels (mx0+i)*4.. — caller passes window-relative map origin
     * (requires 4-aligned window bounds, enforced by require). The hilite
     * planes ride along by reference (the reconstruct arms never upload
     * them).
     */
    internal fun sliceMaps(maps: HlMaps, mx0: Int, my0: Int, mw: Int, mh: Int): HlMaps {
        require(mx0 >= 0 && my0 >= 0 && mw > 0 && mh > 0) { "empty map slice" }
        require(mx0 + mw <= maps.hfw && my0 + mh <= maps.hfh) { "map slice outside maps" }
        fun sliceY(src: Array<FloatArray>): Array<FloatArray> =
            Array(mh) { i -> src[my0 + i].copyOfRange(mx0, mx0 + mw) }
        fun sliceX(src: Array<FloatArray>): Array<FloatArray> =
            Array(mw) { i -> src[mx0 + i].copyOfRange(my0, my0 + mh) }
        return HlMaps(
            hilite = maps.hilite,
            dir = Array(8) { c -> sliceY(maps.dir[c]) },
            dir0 = Array(4) { c -> sliceX(maps.dir0[c]) },
            dir4 = Array(4) { c -> sliceX(maps.dir4[c]) },
            hfw = mw,
            hfh = mh
        )
    }

    /**
     * Stitches one tile's downsampled planes into the global [hil]: tile
     * dst cell (i, j) sits at global (wy0/4+i, wx0/4+j) — exact while the
     * window origin is 4-aligned — and is written only when its source
     * center pixel lies in the tile interior, so every global cell is
     * written exactly once and the fringe stays zero.
     */
    internal fun stitch(
        small: Array<Array<FloatArray>>,
        window: HlRoi,
        interior: HlRoi,
        hil: Array<Array<FloatArray>>
    ) {
        if (window.minX % 4 != 0 || window.minY % 4 != 0) return
        val gx0 = window.minX / 4
        val gy0 = window.minY / 4
        for (m in 0 until 4) {
            val dst = small[m]
            for (i in dst.indices) {
                val gi = gy0 + i
                if (gi >= hil[m].size) continue
                val srcY = window.minY + i * 4
                for (j in dst[0].indices) {
                    val gj = gx0 + j
                    if (gj >= hil[m][0].size) continue
                    val srcX = window.minX + j * 4
                    if (srcX in interior.minX..interior.maxX && srcY in interior.minY..interior.maxY) {
                        hil[m][gi][gj] = dst[i][j]
                    }
                }
            }
        }
    }
}
