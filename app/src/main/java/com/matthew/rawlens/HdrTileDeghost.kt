// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HDR+-style robust deghost for exposure brackets: pairwise frequency-domain
 * Wiener merge of each alternate frame toward the reference.
 *
 * Follows Hasinoff et al. 2016 ("Burst photography for high dynamic range and
 * low-light imaging", §5 robust temporal merge) and the hdr-plus-swift port
 * (martin-marek, `merge/frequency.metal`): per 8x8 tile and frequency bin, the
 * alternate spectrum blends toward the reference spectrum by
 * `w = D / (D + strength * noiseBin)`, where `D = |Ref - Alt|^2` and
 * `noiseBin` is the expected difference-bin variance. Matched bins keep the
 * alternate (denoise through darktable's photon weighting downstream);
 * mismatched bins — motion blur in a long exposure, ghosts, misregistration —
 * collapse to the sharp reference instead of smearing.
 *
 * Bracket adaptations (vs uniform-exposure HDR+):
 * - The alternate is gain-matched to the reference (EXIF ratio refined by a
 *   data-driven median ratio, plus a tightly clamped per-tile refinement)
 *   before comparison and unmatched back to its own exposure afterwards, so
 *   darktable's per-frame calibration still applies.
 * - Noise comes from each frame's DNG Poisson-Gaussian profile
 *   ([CfaNoiseModel]) with a fixed mid-ISO fallback when metadata is absent.
 * - One weight per bin shared by all four Bayer channels (Liba et al. 2019),
 *   taken as the channel maximum so no channel fringes.
 *
 * Tiling is 32x32 Bayer (16x16 per channel) with two diagonal half-tile
 * phases and a triangular window, normalized by the analytic window sum. The
 * larger tile (vs HDR+'s 8) keeps local means stable under a few px of motion
 * blur, so a blurred long exposure still contributes its clean low
 * frequencies (shadows) while its broken high frequencies collapse to the
 * reference. The Fourier subpixel search of hdr-plus-swift is intentionally
 * omitted: geometry is the aligner's job (translation pre-align + FlowNet),
 * the Wiener stage only absorbs the residual.
 */
object HdrTileDeghost {
    /** Per-channel tile edge (px); Bayer tiles are [TILE]*2. Must stay a power of 2. */
    const val TILE = 16
    /** Bayer tile edge (px). */
    const val BAYER_TILE = TILE * 2
    /** Half-tile phase offsets (full-res px) for overlap-add. */
    private const val PHASE = BAYER_TILE / 2
    /**
     * Reference tiles at/above this maximum bypass the Wiener blend
     * entirely: where the reference clips, only the (usually shorter)
     * alternate holds highlight detail, and blending toward the clipped
     * reference would destroy darktable's fallback rescue.
     */
    private const val BYPASS_REF_CLIP = 0.95f
    /** Per-tile gain may stray this fraction from the global gain. */
    private const val TILE_GAIN_SLACK = 1f / 3f
    /** Below this tile-mean level, ratios are noise; keep global gain. */
    private const val TILE_GAIN_FLOOR = 0.005f
    /** Moving sites at/above this level count as clipped for gain trust. */
    private const val TILE_GAIN_CLIP = 0.98f

    /**
     * Deghosts [movingCfa] (already warped onto [reference]) against
     * [reference] and returns the result in the moving frame's own exposure
     * domain. Pure function of its inputs; deterministic.
     *
     * @param strength Wiener tuning: mismatched bins collapse to the
     *   reference regardless; matched bins keep alternate frame with
     *   reference weight near `1 / (1 + strength)`.
     */
    fun deghost(
        reference: HdrMergeFrame,
        moving: HdrMergeFrame,
        movingCfa: UnpackedRawCfa,
        strength: Float = 8f
    ): UnpackedRawCfa {
        require(strength.isFinite() && strength > 0f)
        val ref = reference.cfa
        require(movingCfa.width == ref.width && movingCfa.height == ref.height)
        require(movingCfa.pattern == ref.pattern)
        val width = ref.width
        val height = ref.height

        // Gain from the warped samples actually compared below (not the
        // unwarped frame payload), so large flows can't bias the ratio.
        val gain = matchGain(reference, moving.copy(cfa = movingCfa))
        val out = FloatArray(width * height)
        // Two diagonal phases; tiles within a phase are disjoint, so each
        // phase parallelizes over tile rows with deterministic output.
        for (phase in 0..1) {
            // Origins start one tile off-image so border pixels are covered
            // by both phases (reads clamp; out-of-image writes are skipped).
            val startOx = phase * PHASE - BAYER_TILE
            val startOy = phase * PHASE - BAYER_TILE
            val tilesX = ceilDiv(width - startOx, BAYER_TILE)
            val tilesY = ceilDiv(height - startOy, BAYER_TILE)
            parallelRows(tilesY) { ty0, ty1 ->
                val tile = TileScratch()
                for (ty in ty0 until ty1) for (tx in 0 until tilesX) {
                    processTile(
                        ref.values, movingCfa.values, width, height, ref.pattern,
                        startOx + tx * BAYER_TILE, startOy + ty * BAYER_TILE,
                        gain, reference.noiseModel, moving.noiseModel,
                        strength, out, width, tile
                    )
                }
            }
        }
        // Analytic triangular-window normalization (period 16, exact under
        // clamped border reads).
        for (y in 0 until height) for (x in 0 until width) {
            out[y * width + x] /= normWeight(x, y)
        }
        return movingCfa.copy(values = out)
    }

    /** EXIF exposure ratio refined by a clamped data-driven median ratio. */
    internal fun matchGain(reference: HdrMergeFrame, moving: HdrMergeFrame): Float {
        val exif = (reference.exposureTimeNanos.toDouble() * reference.sensitivityIso /
            (reference.aperture.toDouble() * reference.aperture.toDouble()) /
            (moving.exposureTimeNanos.toDouble() * moving.sensitivityIso /
                (moving.aperture.toDouble() * moving.aperture.toDouble()))).toFloat()
        require(exif.isFinite() && exif > 0f)
        val ref = reference.cfa.values
        val mov = moving.cfa.values
        val w = reference.cfa.width
        val h = reference.cfa.height
        // Sampled median of ref/mov over midtones; robust to ghosts/clipping.
        // The grid stride keeps the sample count near the buffer size while
        // covering the whole frame uniformly (no top-heavy early stop).
        val ratios = FloatArray(4096)
        var n = 0
        val step = max(1, sqrt((w * h / 4096).toDouble()).toInt())
        var y = 0
        while (y < h) {
            var x = (y * 7) % step
            while (x < w) {
                if (n < ratios.size) {
                    val m = mov[y * w + x]
                    val r = ref[y * w + x]
                    if (m > 0.05f && m < 0.7f && r > 0.02f && r < 0.95f) {
                        ratios[n++] = r / m
                    }
                }
                x += step
            }
            y += step
        }
        if (n < 16) return exif
        ratios.sort(0, n)
        val median = ratios[n / 2]
        if (!median.isFinite() || median <= 0f) return exif
        // Trust EXIF within 2x; the median only trims systematic bias
        // (aperture rounding, flicker) that would inflate every bin residual.
        val fine = (median / exif).coerceIn(0.5f, 2f)
        return exif * fine
    }

    /**
     * Refines [globalGain] from this tile's channel means (median of valid
     * per-channel ref/mov ratios), clamped to ±[TILE_GAIN_SLACK]. Small
     * corrections absorb blur leakage and local exposure bias so clean
     * shadows average; large mean shifts (ghosts, heavily clipped moving
     * frame, near-black tiles) keep the global gain and stay rejected.
     *
     * @param movMean per-channel moving means in *globally matched* domain.
     * @param movClipped moving sites at/above [TILE_GAIN_CLIP] in this tile.
     */
    internal fun refineTileGain(
        refMean: FloatArray, movMean: FloatArray, globalGain: Float, movClipped: Int
    ): Float {
        // A few clipped sites barely move the means, but past ~2% the ratio
        // understates the signal; sunlit tiles then keep global gain.
        if (movClipped > TILE * TILE * 4 / 50) return globalGain
        var refAvg = 0f
        var movAvg = 0f
        for (c in 0..3) {
            refAvg += refMean[c]
            movAvg += movMean[c]
        }
        if (refAvg < TILE_GAIN_FLOOR * 4f || movAvg < TILE_GAIN_FLOOR * 4f) return globalGain
        val ratios = FloatArray(4)
        var n = 0
        for (c in 0..3) {
            val rm = refMean[c]
            val mm = movMean[c]
            if (rm > TILE_GAIN_FLOOR && mm > TILE_GAIN_FLOOR) {
                val r = globalGain * rm / mm
                if (r.isFinite() && r > 0f) ratios[n++] = r
            }
        }
        if (n < 2) return globalGain
        ratios.sort(0, n)
        val median = if (n % 2 == 1) ratios[n / 2]
        else (ratios[n / 2 - 1] + ratios[n / 2]) * 0.5f
        return median.coerceIn(
            globalGain * (1f - TILE_GAIN_SLACK), globalGain * (1f + TILE_GAIN_SLACK))
    }

    private fun processTile(
        ref: FloatArray, mov: FloatArray, width: Int, height: Int,
        pattern: BayerPattern, ox: Int, oy: Int, gain: Float,
        refNoise: CfaNoiseModel?, movNoise: CfaNoiseModel?,
        strength: Float, out: FloatArray, outStride: Int, s: TileScratch
    ) {
        // Channel quad offsets for this pattern: slot 0=R,1=G-top,2=G-bottom,3=B.
        var refMax = 0f
        var movClipped = 0
        for (c in 0..3) {
            val (dx, dy) = s.quadOff[c] ?: pattern.quadOffset(c).also { s.quadOff[c] = it }
            var refMean = 0.0
            var movMean = 0.0
            for (ly in 0 until TILE) for (lx in 0 until TILE) {
                val x = (ox + lx * 2 + dx).coerceIn(0, width - 1)
                val y = (oy + ly * 2 + dy).coerceIn(0, height - 1)
                val r = ref[y * width + x]
                val raw = mov[y * width + x]
                val m = raw * gain
                s.refRe[c][ly * TILE + lx] = r
                s.movRe[c][ly * TILE + lx] = m
                if (r > refMax) refMax = r
                if (raw >= TILE_GAIN_CLIP) movClipped++
                refMean += r
                movMean += m
            }
            refMean /= (TILE * TILE)
            movMean /= (TILE * TILE)
            s.refMean[c] = refMean.toFloat()
            s.movMean[c] = movMean.toFloat()
        }
        if (refMax >= BYPASS_REF_CLIP) {
            // Clipped reference: keep the alternate untouched so darktable's
            // short-exposure rescue survives (windowed for seamless add).
            accumulateWindowed(mov, width, height, ox, oy, out, outStride, s)
            return
        }
        // Per-tile gain refinement around the global gain: blur and local
        // exposure bias shift tile means by a few percent; correcting them
        // keeps the long exposure's clean low frequencies (shadows) instead
        // of forcing DC rejection. Clamped tight so genuine ghosts, which
        // shift means far, still mismatch and collapse to the reference.
        val tileGain = refineTileGain(s.refMean, s.movMean, gain, movClipped)
        if (tileGain != gain) {
            val f = tileGain / gain
            for (c in 0..3) {
                val plane = s.movRe[c]
                for (i in plane.indices) plane[i] *= f
                s.movMean[c] *= f
            }
        }
        for (c in 0..3) {
            // Per-channel difference-bin variance: unnormalized DFT scales
            // pixel variance by N^2 per bin.
            val refVar = pixelVariance(refNoise, c, s.refMean[c])
            val movVar = pixelVariance(
                movNoise, c, s.movMean[c] / tileGain.coerceAtLeast(1e-9f)
            ) * tileGain * tileGain
            s.binNoise[c] = (TILE * TILE) * (refVar + movVar).coerceAtLeast(1e-12f)
            // The scratch FFT planes are reused across tiles: reset the
            // imaginary inputs, otherwise the previous tile's spectrum feeds
            // back as input and grows geometrically to Inf/NaN within ~a
            // dozen tiles, silently voiding every later tile's writes.
            s.refIm[c].fill(0f)
            s.movIm[c].fill(0f)
            fft2D(s.refRe[c], s.refIm[c], false)
            fft2D(s.movRe[c], s.movIm[c], false)
        }
        // Shared per-bin Wiener weight (channel max), then pairwise blend.
        for (b in 0 until TILE * TILE) {
            var w = 0f
            for (c in 0..3) {
                val dr = s.refRe[c][b] - s.movRe[c][b]
                val di = s.refIm[c][b] - s.movIm[c][b]
                val d = dr * dr + di * di
                val wc = d / (d + strength * s.binNoise[c])
                if (wc > w) w = wc
            }
            s.weight[b] = w.coerceIn(0f, 1f)
        }
        // The DC bin carries N^4 leverage on tile-mean errors, so blur
        // leakage across tile borders (not a gain error — no gain can fix
        // it) would force DC rejection and discard the long exposure's
        // clean shadows. Decouple it: DC follows the low-frequency
        // consensus instead of its own residual. Blur/static tiles (LF
        // matched) average their brightness; ghost tiles (LF broken too)
        // still collapse to the reference. No threshold — soft by design.
        var lfSum = 0f
        var lfN = 0
        for (dn in 0..2) for (dm in 0..2) {
            if (dm == 0 && dn == 0) continue
            lfSum += s.weight[dn * TILE + dm]
            lfN++
        }
        s.weight[0] = (lfSum / lfN).coerceIn(0f, 1f)
        val invGain = 1f / tileGain
        for (c in 0..3) {
            val (dx, dy) = s.quadOff[c]!!
            for (b in 0 until TILE * TILE) {
                val w = s.weight[b]
                s.movRe[c][b] = w * s.refRe[c][b] + (1f - w) * s.movRe[c][b]
                s.movIm[c][b] = w * s.refIm[c][b] + (1f - w) * s.movIm[c][b]
            }
            fft2D(s.movRe[c], s.movIm[c], true)
            for (ly in 0 until TILE) for (lx in 0 until TILE) {
                val x = ox + lx * 2 + dx
                val y = oy + ly * 2 + dy
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                // Triangular window in Bayer coords; channel sites inherit it.
                val wx = triWeight(lx * 2 + dx)
                val wy = triWeight(ly * 2 + dy)
                val v = (s.movRe[c][ly * TILE + lx] * invGain).coerceIn(-1f, 2f)
                if (v.isFinite()) out[y * outStride + x] += v * wx * wy
            }
        }
    }

    private fun pixelVariance(noise: CfaNoiseModel?, channel: Int, level: Float): Float {
        if (noise == null) {
            // Uncalibrated fallback: modest photon slope + read floor in
            // normalized units (mid-ISO mobile RAW ballpark).
            return 4e-4f * max(level, 0f) + 4e-6f
        }
        return noise.scale[channel] * max(level, 0f) + noise.offset[channel]
    }

    /** Triangular window tap for a within-tile Bayer offset 0..15. */
    private fun triWeight(i: Int): Float = min(i + 1, BAYER_TILE - i) / PHASE.toFloat()

    /** Analytic overlap-add normalization at full-res (x, y). */
    internal fun normWeight(x: Int, y: Int): Float {
        var sum = 0f
        for (phase in 0..1) {
            val lx = Math.floorMod(x - phase * PHASE, BAYER_TILE)
            val ly = Math.floorMod(y - phase * PHASE, BAYER_TILE)
            sum += triWeight(lx) * triWeight(ly)
        }
        return max(sum, 1e-6f)
    }

    private fun BayerPattern.quadOffset(slot: Int): Pair<Int, Int> {
        for (qy in 0..1) for (qx in 0..1) {
            val s = when (colorAt(qx, qy)) {
                CfaColor.RED -> 0
                CfaColor.BLUE -> 3
                CfaColor.GREEN -> if (qy == 0) 1 else 2
            }
            if (s == slot) return qx to qy
        }
        error("unreachable")
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun parallelRows(height: Int, block: (y0: Int, y1: Int) -> Unit) {
        val cores = max(1, min(8, Runtime.getRuntime().availableProcessors()))
        if (cores == 1 || height < cores) {
            block(0, height)
            return
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(cores)
        try {
            val futures = (0 until cores).map { t ->
                pool.submit { block(t * height / cores, (t + 1) * height / cores) }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private class TileScratch {
        val refRe = Array(4) { FloatArray(TILE * TILE) }
        val refIm = Array(4) { FloatArray(TILE * TILE) }
        val movRe = Array(4) { FloatArray(TILE * TILE) }
        val movIm = Array(4) { FloatArray(TILE * TILE) }
        val refMean = FloatArray(4)
        val movMean = FloatArray(4)
        val binNoise = FloatArray(4)
        val weight = FloatArray(TILE * TILE)
        val quadOff = arrayOfNulls<Pair<Int, Int>>(4)
    }

    /** Windowed passthrough accumulate (bypass path; moving exposure domain). */
    private fun accumulateWindowed(
        mov: FloatArray, width: Int, height: Int,
        ox: Int, oy: Int, out: FloatArray, outStride: Int, s: TileScratch
    ) {
        for (c in 0..3) {
            val (dx, dy) = s.quadOff[c]!!
            for (ly in 0 until TILE) for (lx in 0 until TILE) {
                val x = ox + lx * 2 + dx
                val y = oy + ly * 2 + dy
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val wx = triWeight(lx * 2 + dx)
                val wy = triWeight(ly * 2 + dy)
                val v = mov[y * width + x]
                if (v.isFinite()) out[y * outStride + x] += v * wx * wy
            }
        }
    }

    // ---- tiny radix-2 complex FFT (N = TILE, power of 2) ----

    private val TW_RE = FloatArray(TILE / 2) { k -> cos(2.0 * PI * k / TILE).toFloat() }
    private val TW_IM = FloatArray(TILE / 2) { k -> -sin(2.0 * PI * k / TILE).toFloat() }
    private val FFT_BITS = 31 - Integer.numberOfLeadingZeros(TILE)

    /** In-place 1D FFT of [re]/[im] ([off], length [TILE]); inverse if set. */
    internal fun fft1D(re: FloatArray, im: FloatArray, off: Int, inverse: Boolean) {
        require(TILE == 1 shl FFT_BITS) { "TILE must be a power of 2" }
        for (i in 0 until TILE) {
            val j = Integer.reverse(i) ushr (32 - FFT_BITS)
            if (j > i) {
                var t = re[off + i]; re[off + i] = re[off + j]; re[off + j] = t
                t = im[off + i]; im[off + i] = im[off + j]; im[off + j] = t
            }
        }
        var half = 1
        var step = 2
        while (step <= TILE) {
            val twStep = TILE / step
            var k = 0
            while (k < TILE) {
                var j = 0
                while (j < half) {
                    val tw = j * twStep
                    var wr = TW_RE[tw]
                    var wi = TW_IM[tw]
                    if (inverse) wi = -wi
                    val a = off + k + j
                    val b = off + k + j + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    j++
                }
                k += step
            }
            half = step
            step *= 2
        }
        if (inverse) for (i in 0 until TILE) {
            re[off + i] /= TILE
            im[off + i] /= TILE
        }
    }

    /** In-place 2D FFT over [TILE]x[TILE] row-major complex planes. */
    internal fun fft2D(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val tmpRe = FloatArray(TILE)
        val tmpIm = FloatArray(TILE)
        if (!inverse) {
            for (r in 0 until TILE) fft1D(re, im, r * TILE, false)
            for (c in 0 until TILE) {
                for (r in 0 until TILE) {
                    tmpRe[r] = re[r * TILE + c]
                    tmpIm[r] = im[r * TILE + c]
                }
                fft1D(tmpRe, tmpIm, 0, false)
                for (r in 0 until TILE) {
                    re[r * TILE + c] = tmpRe[r]
                    im[r * TILE + c] = tmpIm[r]
                }
            }
        } else {
            for (c in 0 until TILE) {
                for (r in 0 until TILE) {
                    tmpRe[r] = re[r * TILE + c]
                    tmpIm[r] = im[r * TILE + c]
                }
                fft1D(tmpRe, tmpIm, 0, true)
                for (r in 0 until TILE) {
                    re[r * TILE + c] = tmpRe[r]
                    im[r * TILE + c] = tmpIm[r]
                }
            }
            for (r in 0 until TILE) fft1D(re, im, r * TILE, true)
        }
    }
}
