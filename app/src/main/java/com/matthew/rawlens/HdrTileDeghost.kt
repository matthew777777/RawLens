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
 *   for comparison only. Reconstruction uses the physical exposure ratio,
 *   so local matching corrections cannot change the reference brightness.
 * - Noise comes from each frame's DNG Poisson-Gaussian profile
 *   ([CfaNoiseModel]) with a fixed mid-ISO fallback when metadata is absent.
 * - One weight per bin shared by all four Bayer channels, taken as the
 *   trimmed mid-mean (mean of the middle two, like hdr-plus-swift) so no
 *   channel fringes and one bad channel cannot veto the bin.
 * - Static/motion adaptation via a per-tile mismatch score (Liba et al. 2019
 *   §"Spatially varying temporal merging"): static tiles boost the Wiener
 *   noise term up to [MAX_MOTION_NORM]x for harder averaging (shadow
 *   denoise), motion tiles cut back to 1x so ghosts collapse to the sharp
 *   reference. Bins where the alternate is sharper get a Delbracio-style
 *   magnitude preference, and the blended spectrum gets a mild
 *   mismatch-gated deconvolution lift (both capped, DC excluded).
 * - Highlight rescue is localized to each Bayer quad during synthesis:
 *   clipped reference quads hand off to the alternate without disabling
 *   motion rejection in surrounding water or foliage. Strong spatial
 *   mismatch rejects all frequency bands, including DC.
 *
 * Tiling is 16x16 Bayer (8x8 per channel, [TILE]) with a full half-tile
 * phase grid and Hann analysis plus triangular synthesis windows,
 * normalized by the analytic window-product sum — matching hdr-plus-swift's
 * `tile_size_merge = 8` for tight ghost/misregistration localization and
 * small ringing halos. A [TILE_LARGE] (16/channel) fallback pass keeps
 * local means stable under heavy handshake blur in long exposures; select
 * via [deghost]'s `tileSize`. Residual subpixel registration (after
 * translation pre-align + FlowNet and the merge's same-colour bilinear
 * warp) is absorbed inside each tile by a Fourier phase-ramp search over
 * +-0.5 channel-px (hdr-plus-swift `merge/frequency.metal` idea, 3x3
 * candidate grid on the already-computed spectra — no extra FFTs): the
 * shift minimizing the noise-normalized spectral residual is applied to
 * the alternate spectrum before Wiener weighting, so subpixel residuals
 * average instead of forcing HF rejection.
 */
object HdrTileDeghost {
    /**
     * Default per-channel tile edge (px); Bayer tiles are [TILE]*2 = 16px.
     * Matches hdr-plus-swift's `tile_size_merge = 8`: finer tiles localize
     * ghosts/misregistration (small ghosts fill the tile and reject
     * cleanly) and shrink ringing halos, while the Phase-2 gain/mismatch
     * machinery keeps local means stable.
     */
    const val TILE = 8
    /**
     * Fallback per-channel tile edge for heavy blur (handshake-smeared long
     * exposures whose clean low frequencies span wider than an 8px tile).
     * Select via [deghost]'s `tileSize`.
     */
    const val TILE_LARGE = 16
    /** Default Bayer tile edge (px); the large pass uses [TILE_LARGE]*2. */
    const val BAYER_TILE = TILE * 2
    /** Soft highlight ramp on the reference side (highlights only). */
    private const val REF_HI_LO = 0.90f
    private const val REF_HI_HI = 0.99f
    /** Smooth ramp on the gain-matched alternate side (upstream 0.5..0.99). */
    private const val MOV_HI_LO = 0.5f
    private const val MOV_HI_SPAN = 0.49f
    /**
     * Static/motion adaptation ceiling (hdr-plus-swift `max_motion_norm`;
     * upstream robustness presets span 1/6/14/25 — 6 is the balanced middle).
     * Static tiles (mismatch <= 0.02) multiply the Wiener noise term by this
     * for harder averaging; motion tiles (mismatch >= 0.17) cut to 1x.
     */
    private const val MAX_MOTION_NORM = 6f
    /**
     * Mismatch sigma-to-unit scale: a static tile's mean abs residual sits
     * near E|N(0,1)| ~= 0.8 noise sigmas, which maps to upstream's
     * mean-normalized 0.12 operating point (their thresholds 0.02/0.17 for
     * motion and 0.2/0.3 for magnitude/deconv gating keep their meaning).
     */
    private const val MISMATCH_SIGMA_TO_UNIT = 0.15f
    /** Per-tile gain may stray this fraction from the global gain. */
    private const val TILE_GAIN_SLACK = 1f / 3f
    /** Below this tile-mean level, ratios are noise; keep global gain. */
    private const val TILE_GAIN_FLOOR = 0.005f
    /** Moving sites at/above this level count as clipped for gain trust. */
    private const val TILE_GAIN_CLIP = 0.98f
    /**
     * Warped (resampled) moving pixels carry correlated noise: the merge's
     * same-colour bilinear warp averages neighbours, so per-pixel variance
     * understates the bin residual. Inflate the moving term of the
     * difference-bin variance so Wiener strength stays calibrated on warped
     * frames (the reference is always the unwarped identity).
     */
    private const val WARP_VARIANCE_INFLATION = 1.25f
    /**
     * Fourier subpixel search half-range in channel px (hdr-plus-swift uses
     * +-0.5 at 1/6 steps over 49 candidates; we score the 3x3 grid at
     * +-0.5/0 on the already-computed spectra — no extra FFTs — which
     * captures the aligner residual at ~1/9th the upstream cost).
     */
    private const val SUBPIX_STEP = 0.5f
    /**
     * Tiles whose reference AC energy sits below this multiple of the
     * expected noise-bin energy skip refinement (flat tiles have no
     * matchable phase; searching would only lock onto noise).
     */
    private const val SUBPIX_ENERGY_GATE = 2.0f

    /**
     * All tile-size-derived state (lengths, FFT twiddles, subpixel/window
     * tables, deconvolution gains, overlap-add normalization). Two
     * singletons ([PASS] 8px default, [PASS_LARGE] 16px fallback) so the
     * per-tile hot loop only threads one reference.
     *
     * Windowing: raised-cosine (Hann) analysis before the forward FFT —
     * spectral leakage otherwise biases every Wiener residual — with
     * triangular synthesis. Overlap-add divides by the analytic
     * synthesis×analysis product sum, so identical content reconstructs
     * exactly for any window pair (verified by passthrough tests).
     */
    internal class TilePass(val t: Int) {
        init { require(t == TILE || t == TILE_LARGE) { "tile edge must be $TILE or $TILE_LARGE" } }
        val bt = t * 2
        val phase = bt / 2
        /** Windowed-DFT bin variance scale: pixel variance × [winPower]. */
        val winPower: Float
        val twRe = FloatArray(t / 2) { k -> cos(2.0 * PI * k / t).toFloat() }
        val twIm = FloatArray(t / 2) { k -> -sin(2.0 * PI * k / t).toFloat() }
        val subCos: Array<FloatArray> = Array(3) { si ->
            val shift = (si - 1) * SUBPIX_STEP
            FloatArray(t) { f ->
                val signed = if (f <= t / 2) f else f - t
                cos(2.0 * PI * signed * shift / t).toFloat()
            }
        }
        val subSin: Array<FloatArray> = Array(3) { si ->
            val shift = (si - 1) * SUBPIX_STEP
            FloatArray(t) { f ->
                val signed = if (f <= t / 2) f else f - t
                (-sin(2.0 * PI * signed * shift / t)).toFloat()
            }
        }
        /** Hann analysis taps over channel coords. */
        val hann = FloatArray(t) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * (i + 0.5) / t)).toFloat()
        }
        /** Triangular synthesis taps over Bayer coords. */
        val tri = FloatArray(bt) { i -> min(i + 1, bt - i) / phase.toFloat() }
        /** Wide raised-cosine taps for the mismatch support window. */
        val rc = FloatArray(bt) { i ->
            (0.5f - 0.17f * cos(2.0 * PI * (i + 0.5) / bt).toFloat())
        }
        /** Upstream deconvolution gains for this tile size. */
        val deconvCw: FloatArray = if (t == 8) floatArrayOf(
            0f, 0.02f, 0.04f, 0.08f, 0.04f, 0.08f, 0.04f, 0.02f
        ) else floatArrayOf(
            0f, 0.01f, 0.02f, 0.03f, 0.04f, 0.06f, 0.08f, 0.06f,
            0.04f, 0.06f, 0.08f, 0.06f, 0.04f, 0.03f, 0.02f, 0.01f
        )
        /** Analytic synthesis×analysis overlap-add norms, per CFA pattern. */
        val normLut: Map<BayerPattern, FloatArray>

        init {
            var power = 0.0
            for (i in 0 until t) power += hann[i] * hann[i]
            winPower = (power * power).toFloat()
            normLut = BayerPattern.entries.associateWith { pattern -> buildNormLut(pattern) }
        }

        private fun buildNormLut(pattern: BayerPattern): FloatArray {
            // Period-bt steady state over the full half-tile phase grid:
            // norm[x,y] = sum_phases tri*tri*hann*hann at the covering
            // within-tile sites. Hann indexes channel coords, so map each
            // period pixel through its channel's quad offset.
            val lut = FloatArray(bt * bt)
            for (y in 0 until bt) for (x in 0 until bt) {
                var sum = 0f
                for (py in 0..1) for (px in 0..1) {
                    val ix = Math.floorMod(x - px * phase, bt)
                    val iy = Math.floorMod(y - py * phase, bt)
                    val slot = when (pattern.colorAt(ix, iy)) {
                        CfaColor.RED -> 0
                        CfaColor.BLUE -> 3
                        CfaColor.GREEN -> if ((iy and 1) == 0) 1 else 2
                    }
                    val (dx, dy) = pattern.quadOffset(slot)
                    val cx = (ix - dx) / 2
                    val cy = (iy - dy) / 2
                    sum += tri[ix] * tri[iy] * hann[cx] * hann[cy]
                }
                lut[y * bt + x] = max(sum, 1e-6f)
            }
            return lut
        }

        fun normAt(lut: FloatArray, x: Int, y: Int): Float {
            var lx = x % bt
            if (lx < 0) lx += bt
            var ly = y % bt
            if (ly < 0) ly += bt
            return lut[ly * bt + lx]
        }
    }

    private val PASS = TilePass(TILE)
    private val PASS_LARGE = TilePass(TILE_LARGE)

    /**
     * Deghosts [movingCfa] (already warped onto [reference]) against
     * [reference] and returns the result in the moving frame's own exposure
     * domain. Pure function of its inputs; deterministic.
     *
     * @param strength Base Wiener tuning (upstream `robustness_norm` role):
     *   mismatched bins collapse to the reference regardless; the effective
     *   noise term is `strength * motion * magnitude * highlights`, so
     *   static tiles average up to [MAX_MOTION_NORM]x harder while motion,
     *   blur-inferior, and clipped-alternate bins are protected.
     * @param tileSize Per-channel tile edge: [TILE] (8, default — upstream's
     *   `tile_size_merge`, best localization) or [TILE_LARGE] (16, fallback
     *   for heavy blur). Must be one of the two.
     */
    fun deghost(
        reference: HdrMergeFrame,
        moving: HdrMergeFrame,
        movingCfa: UnpackedRawCfa,
        strength: Float = 8f,
        tileSize: Int = TILE
    ): UnpackedRawCfa {
        require(strength.isFinite() && strength > 0f)
        require(tileSize == TILE || tileSize == TILE_LARGE) {
            "tileSize must be $TILE or $TILE_LARGE, was $tileSize"
        }
        val p = if (tileSize == TILE) PASS else PASS_LARGE
        val ref = reference.cfa
        require(movingCfa.width == ref.width && movingCfa.height == ref.height)
        require(movingCfa.pattern == ref.pattern)
        val width = ref.width
        val height = ref.height

        // Gain from the warped samples actually compared below (not the
        // unwarped frame payload), so large flows can't bias the ratio.
        val gain = matchGain(reference, moving.copy(cfa = movingCfa))
        // Fit gains only for correspondence. Reconstructed reference samples
        // must use the same radiometric conversion as the downstream merge.
        val exposureGain = HdrRawMerge.calibration(moving) / HdrRawMerge.calibration(reference)
        require(exposureGain.isFinite() && exposureGain > 0f)
        val out = FloatArray(width * height)
        val normLut = p.normLut.getValue(ref.pattern)
        // Full half-tile phase grid; tiles within a phase are disjoint, so
        // each phase parallelizes over tile rows with deterministic output.
        for (phase in 0..3) {
            // Origins start one tile off-image so border pixels are covered
            // by all phases (reads clamp; out-of-image writes are skipped).
            val startOx = (phase and 1) * p.phase - p.bt
            val startOy = ((phase shr 1) and 1) * p.phase - p.bt
            val tilesX = ceilDiv(width - startOx, p.bt)
            val tilesY = ceilDiv(height - startOy, p.bt)
            parallelRows(tilesY) { ty0, ty1 ->
                val tile = TileScratch(p.t)
                for (ty in ty0 until ty1) for (tx in 0 until tilesX) {
                    processTile(
                        ref.values, movingCfa.values, width, height, ref.pattern,
                        startOx + tx * p.bt, startOy + ty * p.bt,
                        gain, exposureGain, reference.noiseModel, moving.noiseModel,
                        strength, out, width, tile, p
                    )
                }
            }
        }
        // Analytic synthesis×analysis overlap-add normalization (period-bt
        // LUT, exact under clamped border reads). Row-striped: disjoint
        // writes, same values.
        parallelRows(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                out[y * width + x] /= p.normAt(normLut, x, y)
            }
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
        refMean: FloatArray, movMean: FloatArray, globalGain: Float, movClipped: Int,
        tileSites: Int = TILE * TILE * 4
    ): Float {
        // A few clipped sites barely move the means, but past ~2% the ratio
        // understates the signal; sunlit tiles then keep global gain.
        if (movClipped > tileSites / 50) return globalGain
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
        pattern: BayerPattern, ox: Int, oy: Int, gain: Float, exposureGain: Float,
        refNoise: CfaNoiseModel?, movNoise: CfaNoiseModel?,
        strength: Float, out: FloatArray, outStride: Int, s: TileScratch, p: TilePass
    ) {
        val t = p.t
        // Channel quad offsets for this pattern: slot 0=R,1=G-top,2=G-bottom,3=B.
        var movClipped = 0
        var movHi = 0.0
        for (c in 0..3) {
            val (dx, dy) = s.quadOff[c] ?: pattern.quadOffset(c).also { s.quadOff[c] = it }
            var refMean = 0.0
            var movMean = 0.0
            for (ly in 0 until t) for (lx in 0 until t) {
                val x = (ox + lx * 2 + dx).coerceIn(dx, width - 2 + dx)
                val y = (oy + ly * 2 + dy).coerceIn(dy, height - 2 + dy)
                val r = ref[y * width + x]
                val raw = mov[y * width + x]
                val m = raw * gain
                s.refRe[c][ly * t + lx] = r
                s.movRe[c][ly * t + lx] = m
                if (raw >= TILE_GAIN_CLIP) movClipped++
                movHi += ((m - MOV_HI_LO) / MOV_HI_SPAN).coerceIn(0f, 1f)
                refMean += r
                movMean += m
            }
            refMean /= (t * t)
            movMean /= (t * t)
            s.refMean[c] = refMean.toFloat()
            s.movMean[c] = movMean.toFloat()
        }
        // Highlight rescue is applied per Bayer quad during synthesis. A
        // specular site must not disable rejection across an entire FFT tile.
        val tileSites = (t * t * 4).toDouble()
        // Per-tile gain refinement around the global gain: blur and local
        // exposure bias shift tile means by a few percent; correcting them
        // keeps the long exposure's clean low frequencies (shadows) instead
        // of forcing DC rejection. Clamped tight so genuine ghosts, which
        // shift means far, still mismatch and collapse to the reference.
        val tileGain = refineTileGain(s.refMean, s.movMean, gain, movClipped, t * t * 4)
        if (tileGain != gain) {
            val f = tileGain / gain
            for (c in 0..3) {
                val plane = s.movRe[c]
                for (i in plane.indices) plane[i] *= f
                s.movMean[c] *= f
            }
        }
        // Alternate-side highlight discount (upstream `highlights_norm`): maps
        // the gain-matched alternate into reference brightness and smoothly
        // discounts tiles piling near white, where a clipped alternate would
        // inject color casts. Only applies when the alternate is the darker
        // frame (tileGain > 1); a brighter alternate that clips is already
        // rejected bin-wise by its large spectral residual. Clean tiles stay
        // at exactly 1.
        val movFrac = (movHi / tileSites).toFloat()
        val altShrink = if (tileGain > 1.001f)
            ((1f - movFrac) * (1f - movFrac))
                .coerceIn(0.04f / min(tileGain, 4f), 1f)
        else 1f
        // Mismatch residual on the refined spatial planes (before the FFTs
        // consume them): raised-cosine-weighted mean abs difference in
        // noise-sigma units. Gain refinement already absorbed the best
        // global DC fit, so what remains is motion/blur/ghost energy.
        var absNum = 0.0
        var absDen = 0.0
        for (c in 0..3) {
            val (dx, dy) = s.quadOff[c]!!
            val rp = s.refRe[c]
            val mp = s.movRe[c]
            for (ly in 0 until t) for (lx in 0 until t) {
                val w = p.rc[lx * 2 + dx] * p.rc[ly * 2 + dy]
                absNum += w * abs(rp[ly * t + lx] - mp[ly * t + lx])
                absDen += w
            }
        }
        // Raised-cosine analysis window, in place on the scratch copies:
        // both sides are windowed identically so the Wiener weights are
        // unaffected, while spectral leakage stops biasing every residual.
        // Mismatch and noise levels above stay in unwindowed units.
        for (c in 0..3) {
            val rp = s.refRe[c]
            val mp = s.movRe[c]
            for (ly in 0 until t) {
                val wy = p.hann[ly]
                for (lx in 0 until t) {
                    val w = wy * p.hann[lx]
                    val b = ly * t + lx
                    rp[b] *= w
                    mp[b] *= w
                }
            }
        }
        var refVarAvg = 0.0
        var movVarAvg = 0.0
        for (c in 0..3) {
            // Per-channel difference-bin variance: a windowed DFT scales
            // pixel variance by the window power sum ([winPower]) per bin.
            // The moving term is inflated by WARP_VARIANCE_INFLATION: it is
            // always the resampled (warped) frame, whose bilinear taps
            // correlate noise.
            val refVar = pixelVariance(refNoise, c, s.refMean[c])
            val movVar = pixelVariance(
                movNoise, c, s.movMean[c] / tileGain.coerceAtLeast(1e-9f)
            ) * tileGain * tileGain * WARP_VARIANCE_INFLATION
            refVarAvg += refVar
            movVarAvg += movVar
            s.binNoise[c] = p.winPower * (refVar + movVar).coerceAtLeast(1e-12f)
        }
        refVarAvg /= 4.0
        movVarAvg /= 4.0
        // Static tiles sit near ~0.8 sigma mean-abs (E|N(0,1)|) which maps to
        // upstream's 0.12 operating point; ghosts land far above and clamp.
        val mismatch = mismatchFromStats(
            (absNum / absDen.coerceAtLeast(1e-12)).toFloat(),
            sqrt(0.5 * (refVarAvg + movVarAvg) + 1e-12).toFloat()
        )
        val motion = motionNorm(mismatch)
        // A broken correspondence must reject every band, including DC.
        // Independent Wiener bins otherwise keep unrelated low frequencies
        // from moving water or a blurred branch and form tile-shaped holes.
        val motionReject = ((mismatch - 0.3f) / 0.3f).coerceIn(0f, 1f)
        for (c in 0..3) {
            // The scratch FFT planes are reused across tiles: reset the
            // imaginary inputs, otherwise the previous tile's spectrum feeds
            // back as input and grows geometrically to Inf/NaN within ~a
            // dozen tiles, silently voiding every later tile's writes.
            s.refIm[c].fill(0f)
            s.movIm[c].fill(0f)
            fft2D(s.refRe[c], s.refIm[c], false, p, s.tmpRe, s.tmpIm)
            fft2D(s.movRe[c], s.movIm[c], false, p, s.tmpRe, s.tmpIm)
        }
        // Fourier subpixel refinement: absorb the aligner residual (typically
        // <0.5 channel-px after translation pre-align + FlowNet) with a phase
        // ramp on the alternate spectrum before Wiener weighting. Without
        // this, subpixel residuals inflate every HF bin residual and force
        // collapse to the reference (lost averaging/detail).
        refineSubpixel(s, p)
        // Shared per-bin Wiener weight (trimmed mid-mean over the four Bayer
        // channels, i.e. mean of the middle two: hdr-plus-swift found this
        // slightly more robust than max, which lets one bad channel —
        // chroma aberration, clip, spike — veto the whole bin), then
        // pairwise blend. The noise term carries the full upstream stack:
        // caller strength x per-tile motion boost (static averages harder)
        // x per-bin magnitude preference (sharper alternate earns weight)
        // x alternate highlight discount. Highlight rescue happens locally
        // during synthesis, after motion rejection.
        // Per-bin scratch (squared difference energy per channel).
        val binD = s.binD
        val bins = t * t
        for (b in 0 until bins) {
            var sumSqR = 0f
            var sumSqA = 0f
            for (c in 0..3) {
                val dr = s.refRe[c][b] - s.movRe[c][b]
                val di = s.refIm[c][b] - s.movIm[c][b]
                binD[c] = dr * dr + di * di
                sumSqR += s.refRe[c][b] * s.refRe[c][b] + s.refIm[c][b] * s.refIm[c][b]
                sumSqA += s.movRe[c][b] * s.movRe[c][b] + s.movIm[c][b] * s.movIm[c][b]
            }
            // Magnitude preference needs ratio^4 = (sumA/sumR)^2: no roots.
            val mag = magnitudeNorm(sumSqA / (sumSqR + 1e-24f), mismatch, b == 0)
            val denScale = mag * motion * altShrink * strength
            var wMin = Float.MAX_VALUE
            var wMax = -Float.MAX_VALUE
            var wSum = 0f
            for (c in 0..3) {
                val wc = binD[c] / (binD[c] + denScale * s.binNoise[c])
                wSum += wc
                if (wc < wMin) wMin = wc
                if (wc > wMax) wMax = wc
            }
            s.weight[b] = ((wSum - wMin - wMax) * 0.5f).coerceIn(0f, 1f)
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
            lfSum += s.weight[dn * t + dm]
            lfN++
        }
        s.weight[0] = (lfSum / lfN).coerceIn(0f, 1f)
        val invGain = 1f / tileGain
        // Undo only the matching transform on the alternate. The reference
        // enters the blend in the calibrated alternate exposure domain, not
        // scaled by local tile means (which change around displaced edges).
        val referenceScale = tileGain / exposureGain
        for (c in 0..3) {
            val (dx, dy) = s.quadOff[c]!!
            for (b in 0 until bins) {
                val w = max(s.weight[b], motionReject)
                s.movRe[c][b] = w * s.refRe[c][b] * referenceScale + (1f - w) * s.movRe[c][b]
                s.movIm[c][b] = w * s.refIm[c][b] * referenceScale + (1f - w) * s.movIm[c][b]
            }
            // Mismatch-gated deconvolution lift (upstream
            // `deconvolute_frequency_domain`, applied here per pairwise
            // blend rather than once on the final sum): AC bins well below
            // the DC magnitude earn up to ~1.17x HF lift; dominant bins
            // earn nothing, so noise-like content is untouched and DC
            // (tile brightness) is never touched.
            if (mismatch < 0.3f) {
                val mw = (1f - 10f * (mismatch - 0.2f)).coerceIn(0f, 1f)
                if (mw > 0f) {
                    val dcR = s.movRe[c][0]
                    val dcI = s.movIm[c][0]
                    val magDc = sqrt(dcR * dcR + dcI * dcI)
                    if (magDc > 0f && magDc.isFinite()) {
                        val re = s.movRe[c]
                        val im = s.movIm[c]
                        for (b in 1 until bins) {
                            val magBin = sqrt(re[b] * re[b] + im[b] * im[b])
                            val dw = mw * (1.25f - 25f * magBin / magDc).coerceIn(0f, 1f)
                            if (dw > 0f) {
                                val g = (1f + dw * p.deconvCw[b % t]) *
                                    (1f + dw * p.deconvCw[b / t])
                                re[b] *= g
                                im[b] *= g
                            }
                        }
                    }
                }
            }
            fft2D(s.movRe[c], s.movIm[c], true, p, s.tmpRe, s.tmpIm)
            for (ly in 0 until t) for (lx in 0 until t) {
                val x = ox + lx * 2 + dx
                val y = oy + ly * 2 + dy
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                // Triangular synthesis window in Bayer coords; channel sites
                // inherit it. Pairs with the Hann analysis window via the
                // analytic product-sum normalization in deghost().
                val wx = p.tri[lx * 2 + dx]
                val wy = p.tri[ly * 2 + dy]
                // Frequency-dependent mixing is not a convex spatial blend:
                // ringing can invent dark pinholes and bright/coloured spikes.
                // Bound each CFA site by its actual contributing samples in
                // the moving exposure domain, before overlap-add. Bounds live
                // in the windowed domain (scaled by this site's Hann tap):
                // clamping to unwindowed bounds would strip the analysis
                // window while the normalization still divides it out (~2.2x
                // inflation). Identical content stays bit-consistent.
                val index = y * width + x
                val r = ref[index] / exposureGain
                val m = mov[index]
                val hw = p.hann[lx] * p.hann[ly]
                val lo = min(r, m) * hw
                val hi = max(r, m) * hw
                // All colours in a Bayer quad share highlight trust, but
                // neighbouring unclipped detail retains motion protection.
                val qx = x and -2
                val qy = y and -2
                val peak = max(max(ref[qy * width + qx], ref[qy * width + qx + 1]),
                    max(ref[(qy + 1) * width + qx], ref[(qy + 1) * width + qx + 1]))
                val trust = 1f - ((peak - REF_HI_LO) / (REF_HI_HI - REF_HI_LO)).coerceIn(0f, 1f)
                val filtered = (s.movRe[c][ly * t + lx] * invGain).coerceIn(lo, hi)
                val v = trust * trust * filtered + (1f - trust * trust) * m * hw
                if (v.isFinite()) out[y * outStride + x] += v * wx * wy
            }
        }
    }

    /**
     * Fourier subpixel refinement (hdr-plus-swift `merge/frequency.metal`
     * idea, reduced grid): score the 3x3 shift grid {-[SUBPIX_STEP], 0,
     * +[SUBPIX_STEP]}^2 in channel px by the noise-normalized spectral
     * residual, applying each candidate as a phase ramp
     * `exp(-2pi*i*(u*sx+v*sy)/TILE)` to the alternate spectrum. The best
     * shift is baked into [s] alternate planes in place; the zero shift
     * always scores first, so flat/noisy tiles keep identity.
     *
     * No extra FFTs: works on the already-computed spectra. Per-bin trig is
     * avoided via precomputed per-shift/per-frequency cos/sin tables; the
     * hot loop is multiply-add only.
     */
    private fun refineSubpixel(s: TileScratch, p: TilePass) {
        // Energy gate: flat tiles carry no phase to lock onto. Compare
        // reference AC energy against the expected noise floor.
        val bins = p.t * p.t
        var acEnergy = 0.0
        var noiseFloor = 0.0
        for (c in 0..3) {
            val re = s.refRe[c]
            val im = s.refIm[c]
            for (b in 1 until bins) {
                acEnergy += (re[b] * re[b] + im[b] * im[b]).toDouble()
            }
            noiseFloor += s.binNoise[c].toDouble()
        }
        if (acEnergy < SUBPIX_ENERGY_GATE * noiseFloor) return
        var bestSx = 0
        var bestSy = 0
        var bestScore = scoreShift(s, p, 0, 0)
        for (sy in -1..1) for (sx in -1..1) {
            if (sx == 0 && sy == 0) continue
            val score = scoreShift(s, p, sx, sy)
            if (score < bestScore) {
                bestScore = score
                bestSx = sx
                bestSy = sy
            }
        }
        if (bestSx == 0 && bestSy == 0) return
        applyShift(s, p, bestSx, bestSy)
    }

    /** Noise-normalized spectral residual under candidate shift ([sx],[sy] in grid steps). */
    private fun scoreShift(s: TileScratch, p: TilePass, sx: Int, sy: Int): Double {
        val t = p.t
        val bins = t * t
        if (sx == 0 && sy == 0) {
            var total = 0.0
            for (c in 0..3) {
                val inv = 1.0 / s.binNoise[c].toDouble()
                val rr = s.refRe[c]
                val ri = s.refIm[c]
                val mr = s.movRe[c]
                val mi = s.movIm[c]
                for (b in 0 until bins) {
                    val dr = rr[b] - mr[b]
                    val di = ri[b] - mi[b]
                    total += (dr * dr + di * di) * inv
                }
            }
            return total
        }
        val cosX = p.subCos[sx + 1]
        val sinX = p.subSin[sx + 1]
        val cosY = p.subCos[sy + 1]
        val sinY = p.subSin[sy + 1]
        var total = 0.0
        for (c in 0..3) {
            val inv = 1.0 / s.binNoise[c].toDouble()
            val rr = s.refRe[c]
            val ri = s.refIm[c]
            val mr = s.movRe[c]
            val mi = s.movIm[c]
            for (v in 0 until t) {
                val cv = cosY[v]
                val sv = sinY[v]
                for (u in 0 until t) {
                    // Combined ramp: angle = (u*sx + v*sy); cos/sin via
                    // angle-addition of the per-axis tables.
                    val cu = cosX[u]
                    val su = sinX[u]
                    val wr = cu * cv - su * sv
                    val wi = su * cv + cu * sv
                    val b = v * t + u
                    val mvr = mr[b]
                    val mvi = mi[b]
                    val sr = wr * mvr - wi * mvi
                    val si = wr * mvi + wi * mvr
                    val dr = rr[b] - sr
                    val di = ri[b] - si
                    total += (dr * dr + di * di) * inv
                }
            }
        }
        return total
    }

    /** Bakes grid-step shift ([sx],[sy]) into the alternate spectra in place. */
    private fun applyShift(s: TileScratch, p: TilePass, sx: Int, sy: Int) {
        val t = p.t
        val cosX = p.subCos[sx + 1]
        val sinX = p.subSin[sx + 1]
        val cosY = p.subCos[sy + 1]
        val sinY = p.subSin[sy + 1]
        for (c in 0..3) {
            val mr = s.movRe[c]
            val mi = s.movIm[c]
            for (v in 0 until t) {
                val cv = cosY[v]
                val sv = sinY[v]
                for (u in 0 until t) {
                    val cu = cosX[u]
                    val su = sinX[u]
                    val wr = cu * cv - su * sv
                    val wi = su * cv + cu * sv
                    val b = v * t + u
                    val mvr = mr[b]
                    val mvi = mi[b]
                    mr[b] = wr * mvr - wi * mvi
                    mi[b] = wr * mvi + wi * mvr
                }
            }
        }
    }

    /**
     * Per-tile mismatch in upstream units: noise-normalized mean abs
     * residual scaled so static content lands at ~0.12 (hdr-plus-swift
     * `normalize_mismatch` target), keeping upstream's motion thresholds
     * (0.02/0.17) and magnitude/deconv gating (0.2/0.3) meaningful.
     */
    internal fun mismatchFromStats(meanAbsDiff: Float, noiseScale: Float): Float {
        if (!meanAbsDiff.isFinite() || !noiseScale.isFinite() || noiseScale <= 0f) return 1f
        return (meanAbsDiff / noiseScale * MISMATCH_SIGMA_TO_UNIT).coerceIn(0f, 1f)
    }

    /**
     * Static/motion adaptation (hdr-plus-swift `motion_norm`, Liba et al.
     * 2019 Fig. 9f shape): static tiles return [MAX_MOTION_NORM] for harder
     * averaging, motion tiles ramp down to 1 (no boost, ghosts collapse).
     */
    internal fun motionNorm(mismatch: Float): Float {
        if (!mismatch.isFinite()) return 1f
        return (MAX_MOTION_NORM - (mismatch - 0.02f) *
            (MAX_MOTION_NORM - 1f) / 0.15f).coerceIn(1f, MAX_MOTION_NORM)
    }

    /**
     * Sharper-alternate preference (hdr-plus-swift `magnitude_norm`,
     * Delbracio et al. 2015 eq. 3 idea): [sqRatio] is
     * (|Alt|²/|Ref|²) summed over channels, so ratio^4 = [sqRatio]² with no
     * square roots. Gated to AC bins with low mismatch like upstream
     * (DC and mismatch >= 0.3 return 1). The uniform-exposure gate is
     * intentionally dropped: our spectra are gain-matched, so magnitudes
     * are comparable across brackets.
     */
    internal fun magnitudeNorm(sqRatio: Float, mismatch: Float, isDc: Boolean): Float {
        if (isDc || !sqRatio.isFinite() || mismatch >= 0.3f) return 1f
        val mw = (1f - 10f * (mismatch - 0.2f)).coerceIn(0f, 1f)
        return 1f + mw * ((sqRatio * sqRatio).coerceIn(0.5f, 3f) - 1f)
    }

    private fun pixelVariance(noise: CfaNoiseModel?, channel: Int, level: Float): Float {
        if (noise == null) {
            // Uncalibrated fallback: single canonical source shared with
            // CfaNoiseModel (mid-ISO mobile RAW ballpark in normalized units).
            return CfaNoiseModel.FALLBACK_SCALE * max(level, 0f) + CfaNoiseModel.FALLBACK_OFFSET
        }
        return noise.scale[channel] * max(level, 0f) + noise.offset[channel]
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

    private fun parallelRows(height: Int, block: (y0: Int, y1: Int) -> Unit) =
        HdrPools.runStriped(height, 8, block)

    private class TileScratch(t: Int) {
        val refRe = Array(4) { FloatArray(t * t) }
        val refIm = Array(4) { FloatArray(t * t) }
        val movRe = Array(4) { FloatArray(t * t) }
        val movIm = Array(4) { FloatArray(t * t) }
        val refMean = FloatArray(4)
        val movMean = FloatArray(4)
        val binNoise = FloatArray(4)
        val weight = FloatArray(t * t)
        val binD = FloatArray(4)
        val quadOff = arrayOfNulls<Pair<Int, Int>>(4)
        // Reused 1D FFT column scratch: sized for the large pass so both
        // tile sizes share the code path with zero per-tile allocation.
        // (The old fft2D allocated two TILE-float arrays per call, ~500k
        // small allocations per 12MP frame. Same math.)
        val tmpRe = FloatArray(TILE_LARGE)
        val tmpIm = FloatArray(TILE_LARGE)
    }

    // ---- tiny radix-2 complex FFT (N power of 2; 8 default, 16 fallback) ----

    /** In-place 1D FFT of [re]/[im] ([off], length [TILE]); inverse if set. */
    internal fun fft1D(re: FloatArray, im: FloatArray, off: Int, inverse: Boolean) {
        fft1Dn(re, im, off, inverse, PASS)
    }

    private fun fft1Dn(re: FloatArray, im: FloatArray, off: Int, inverse: Boolean, p: TilePass) {
        val n = p.t
        val bits = 31 - Integer.numberOfLeadingZeros(n)
        require(n == 1 shl bits) { "tile edge must be a power of 2" }
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                var tmp = re[off + i]; re[off + i] = re[off + j]; re[off + j] = tmp
                tmp = im[off + i]; im[off + i] = im[off + j]; im[off + j] = tmp
            }
        }
        var half = 1
        var step = 2
        while (step <= n) {
            val twStep = n / step
            var k = 0
            while (k < n) {
                var j = 0
                while (j < half) {
                    val tw = j * twStep
                    var wr = p.twRe[tw]
                    var wi = p.twIm[tw]
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
        if (inverse) for (i in 0 until n) {
            re[off + i] /= n
            im[off + i] /= n
        }
    }

    /** In-place 2D FFT over [TILE]x[TILE] row-major complex planes. */
    internal fun fft2D(re: FloatArray, im: FloatArray, inverse: Boolean) {
        fft2D(re, im, inverse, PASS, FloatArray(TILE), FloatArray(TILE))
    }

    /** Scratch-reusing overload for the per-tile hot loop (identical math). */
    internal fun fft2D(
        re: FloatArray, im: FloatArray, inverse: Boolean,
        p: TilePass, tmpRe: FloatArray, tmpIm: FloatArray
    ) {
        val n = p.t
        if (!inverse) {
            for (r in 0 until n) fft1Dn(re, im, r * n, false, p)
            for (c in 0 until n) {
                for (r in 0 until n) {
                    tmpRe[r] = re[r * n + c]
                    tmpIm[r] = im[r * n + c]
                }
                fft1Dn(tmpRe, tmpIm, 0, false, p)
                for (r in 0 until n) {
                    re[r * n + c] = tmpRe[r]
                    im[r * n + c] = tmpIm[r]
                }
            }
        } else {
            for (c in 0 until n) {
                for (r in 0 until n) {
                    tmpRe[r] = re[r * n + c]
                    tmpIm[r] = im[r * n + c]
                }
                fft1Dn(tmpRe, tmpIm, 0, true, p)
                for (r in 0 until n) {
                    re[r * n + c] = tmpRe[r]
                    im[r * n + c] = tmpIm[r]
                }
            }
            for (r in 0 until n) fft1Dn(re, im, r * n, true, p)
        }
    }
}
