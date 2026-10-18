package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.core.ComplexSpectrum
import com.rawlens.srpipeline.core.Fft
import com.rawlens.srpipeline.core.Shifts

/**
 * L2 block-matching stage via the FFT correlation path.
 *
 * Mirrors `align_lvl_block_matching_L2` in
 * `handheld_super_resolution/block_matching.py`:
 * - search patches of `2R+T` centred on `tile*T + round(flow)`, clamped to
 *   the borders (cf. `extract_flow_patches`; patch extraction reuses
 *   [extractFlowPatches]);
 * - cross-correlation `irfft2(conj(refTileFft) * movingFft)` (`rfft2` on the
 *   zero-padded reference tile, `fftshift`, centre crop to `2R+1`);
 * - box-filtered moving-window energies `L2_search`
 *   (`conv2d(square, ones(T,T), valid)`);
 * - error map `L2_search - 2*corr`, `argmin` (first minimum in row-major
 *   scan order), `flow += (dx, dy)` preserving the fractional flow.
 *
 * The reference tile enters the correlation zero-padded with an `R` border
 * (cf. `unfold` + constant-0 pad in `init_alignment`); the full-complex
 * FFT here is the exact equivalent of the `rfft2` path. Scores are computed
 * in double precision (JTransforms) rather than torch float32, so the error
 * map matches within float tolerance while the `argmin` contract itself is
 * exact. Depends on `core` ([Fft], [Shifts]) only.
 */
object L2 {

    /**
     * L2 error map `[2R+1][2R+1]` (`[sy+R][sx+R]`) for one tile, cf.
     * `L2_search - 2*corrs`.
     *
     * @param refTile the `TxT` reference tile (zero padding to the
     *   `2R+T` correlation window happens inside).
     * @param searchArea the `(2R+T)x(2R+T)` clamped moving search patch.
     */
    fun correlationErrors(
        refTile: Array<FloatArray>,
        searchArea: Array<FloatArray>,
        tileSize: Int,
        searchRadius: Int,
    ): Array<FloatArray> {
        require(tileSize >= 1) { "tileSize must be >= 1 (got $tileSize)" }
        require(searchRadius >= 0) { "searchRadius must be >= 0 (got $searchRadius)" }
        val window = 2 * searchRadius + tileSize
        val corrSize = 2 * searchRadius + 1
        require(refTile.size == tileSize && refTile.all { it.size == tileSize }) {
            "refTile must be ${tileSize}x$tileSize"
        }
        require(searchArea.size == window && searchArea.all { it.size == window }) {
            "searchArea must be ${window}x$window"
        }

        val refPad = FloatArray(window * window)
        for (ty in 0 until tileSize) {
            for (tx in 0 until tileSize) {
                refPad[(ty + searchRadius) * window + (tx + searchRadius)] = refTile[ty][tx]
            }
        }
        val searchFlat = FloatArray(window * window) { searchArea[it / window][it % window] }

        val refSpectrum = Fft.forward2D(refPad, window, window)
        val searchSpectrum = Fft.forward2D(searchFlat, window, window)
        val corrReal = FloatArray(window * window) { i ->
            refSpectrum.real[i] * searchSpectrum.real[i] + refSpectrum.imag[i] * searchSpectrum.imag[i]
        }
        val corrImag = FloatArray(window * window) { i ->
            refSpectrum.real[i] * searchSpectrum.imag[i] - refSpectrum.imag[i] * searchSpectrum.real[i]
        }
        val corr = Fft.inverse2D(ComplexSpectrum(window, window, corrReal, corrImag))
        val shifted = Shifts.fftShift(corr, window, window)

        // Centre crop to +-R, cf. `corrs[..., crop+1:crop+corr_size+1, ...]`.
        val crop = (window - 1 - corrSize) / 2
        // Valid TxT box sums of the squared search area, cf. `conv2d(valid)`.
        return Array(corrSize) { syi ->
            FloatArray(corrSize) { sxi ->
                var energy = 0f
                for (wy in 0 until tileSize) {
                    for (wx in 0 until tileSize) {
                        val v = searchArea[syi + wy][sxi + wx]
                        energy += v * v
                    }
                }
                energy - 2f * shifted[(crop + 1 + syi) * window + (crop + 1 + sxi)]
            }
        }
    }

    /**
     * L2 block matching over the tile grid, cf.
     * `align_lvl_block_matching_L2`. Updates [flow] in place, preserving
     * fractional flow (the winning integer shift is *added*, as in Python).
     */
    fun alignLvl(
        ref: Array<FloatArray>,
        moving: Array<FloatArray>,
        flow: Array<Array<FloatArray>>,
        tileSize: Int,
        searchRadius: Int,
    ) {
        requireRectImage(ref, "ref")
        requireRectImage(moving, "moving")
        val patches = extractFlowPatches(moving, flow, tileSize, searchRadius)
        val nTy = flow.size
        val nTx = flow[0].size
        for (tileY in 0 until nTy) {
            for (tileX in 0 until nTx) {
                val refTile = Array(tileSize) { ty ->
                    FloatArray(tileSize) { tx -> ref[tileY * tileSize + ty][tileX * tileSize + tx] }
                }
                val scores = correlationErrors(refTile, patches[tileY][tileX], tileSize, searchRadius)
                var best = Float.POSITIVE_INFINITY
                var bestSx = 0
                var bestSy = 0
                for (syi in scores.indices) {
                    for (sxi in scores[syi].indices) {
                        if (scores[syi][sxi] < best) {
                            best = scores[syi][sxi]
                            bestSx = sxi - searchRadius
                            bestSy = syi - searchRadius
                        }
                    }
                }
                flow[tileY][tileX][0] += bestSx
                flow[tileY][tileX][1] += bestSy
            }
        }
    }
}
