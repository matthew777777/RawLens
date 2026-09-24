// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Non-circular noise cross-check: measures the shot/read noise actually present
 * in a reference frame and compares it against the OEM profile that feeds
 * [RawSrKernelNetAniso.sigmaFor].
 *
 * Why this exists: [RawSrNoiseLut] simulates patch statistics *from* the OEM
 * profile, so comparing the LUT against [RawSrKernelNetAniso.sigmaFor] compares
 * the profile against itself and cannot detect an over-conservative OEM. This
 * meter instead estimates noise from real pixel data, so a measured/profile
 * ratio well below 1.0 means the model is told the frame is noisier than it is
 * (and over-smooths accordingly).
 *
 * Method, per CFA phase (measuring across phases would mistake the mosaic for
 * noise — the same trap as feeding per-pixel luma to KernelNet):
 * - Partition the phase sub-lattice into square blocks. Each block's noise
 *   variance comes from a cross-Laplacian (`N+S+E+W-4C`, whose variance is
 *   exactly 20x the iid pixel variance), so linear gradients — in-block signal
 *   ramps, vignetting — cancel instead of biasing the estimate. Ordinary block
 *   variance would mistake any ramp for noise.
 * - Two-level robustness. Within a block, taps are sigma-clipped at 25x the
 *   tap median before averaging: one edge or hot-pixel tap reads hundreds of
 *   times hotter than noise and would otherwise own the block. Across blocks,
 *   bins take the median block variance, rejecting blocks texture still owns.
 *   Saturated blocks are dropped outright: clipping suppresses variance.
 * - Fit `var = alpha*mean + beta` over the bin points with non-negative least
 *   squares (exact 2-variable NNLS: unconstrained optimum when feasible, else
 *   the better of the two clamped edges).
 *
 * Two honest biases, both small next to the 2x+ conservatism signal this hunts:
 * clipping plus median selection reads ~5% low in total, and the fit needs
 * real brightness spread — a flat test chart yields one bin and the phase
 * reports unmeasurable instead of a number. Measure pre-lens-shading frames:
 * LSC gains rescale noise (var' = g^2*var) while the profile describes the raw
 * sensor.
 *
 * Pure Kotlin/JVM with no Android dependencies; runs in unit tests.
 */
object RawSrFrameNoiseMeter {
    /** Phase-lattice sites per block edge (block = 2x this in pixels). */
    const val DEFAULT_BLOCK_PHASE = 8
    /** Brightness bins over [0, 1). */
    const val DEFAULT_BINS = 12
    /** Per-bin noise pick: the median tolerates <50% contaminated blocks. */
    const val DEFAULT_PERCENTILE = 0.5
    /** Blocks brighter than this are clipped highlights, not noise data. */
    const val DEFAULT_SATURATION = 0.95
    /** Fewer populated bins than this leaves the phase unmeasurable. */
    const val DEFAULT_MIN_BINS = 2
    /** Bins with fewer blocks than this are skipped as unstable. */
    const val DEFAULT_MIN_BLOCKS_PER_BIN = 8

    /** Laplacian energy ratio: Var(N+S+E+W-4C) = 20 * pixel variance. */
    private const val LAPLACIAN_GAIN = 20.0
    /**
     * Tap clip factor: taps with L^2 above this multiple of the block's tap
     * median are edge/hot-pixel pollution, not noise. 25 keeps 99.9% of clean
     * chi^2 taps (~-1% bias) while strong edges read hundreds of times hotter.
     */
    private const val TAP_CLIP_K = 25.0
    /** Blocks keeping fewer taps than this are dropped as untrusted. */
    private const val MIN_KEPT_TAPS = 4

    /** One measurable phase: fitted noise without any profile input. */
    data class PhaseFit(
        val phase: Int,
        val alpha: Float,
        val beta: Float,
        val bins: Int,
        val blocks: Int
    )

    /** One measurable phase: fitted noise plus the profile pair it checks. */
    data class PhaseRow(
        val phase: Int,
        val alpha: Float,
        val beta: Float,
        val profileAlpha: Double,
        val profileBeta: Double,
        val bins: Int,
        val blocks: Int
    )

    /**
     * Frame-level verdict. Sigmas are evaluated at brightness 0.5 — the exact
     * operating point of [RawSrKernelNetAniso.sigmaFor], so [ratio] answers
     * "what fraction of the claimed noise is really there".
     */
    data class Report(
        val phases: List<PhaseRow>,
        val measuredSigmaAtHalf: Float,
        val profileSigmaAtHalf: Float
    ) {
        /** Measured/profile sigma; << 1 means an over-conservative profile. */
        val ratio: Float get() = measuredSigmaAtHalf / profileSigmaAtHalf

        fun logLine(label: String): String {
            val perPhase = phases.joinToString(" ") {
                "p${it.phase}=(a=${it.alpha},b=${it.beta} vs ${it.profileAlpha},${it.profileBeta} n=${it.blocks})"
            }
            return "noisecheck $label phases=${phases.size} " +
                "sigma@0.5 measured=$measuredSigmaAtHalf profile=$profileSigmaAtHalf ratio=$ratio $perPhase"
        }
    }

    /**
     * Profile S/O pair governing one crop-local phase. Mirrors
     * [RawSrCovarianceGuide]'s selection (sensor raster order for 8
     * coefficients, CFA color for 6) without its code-domain rescale: the
     * bytes carried here are normalized-domain pairs (real OEM tags read
     * S~1e-3, O~1e-7 — code-domain offsets would imply sub-milli-DN read
     * noise, which no sensor has).
     */
    internal fun profilePairFor(profile: DoubleArray, phase: Int, cfa: UnpackedRawCfa): Pair<Double, Double> {
        val sx = cfa.sensorCropLeft + (phase and 1)
        val sy = cfa.sensorCropTop + ((phase shr 1) and 1)
        return if (profile.size == 8) {
            val raster = ((sy and 1) shl 1) or (sx and 1)
            profile[raster * 2] to profile[raster * 2 + 1]
        } else {
            val channel = when (cfa.pattern.colorAt(sx, sy)) {
                CfaColor.RED -> 0
                CfaColor.GREEN -> 1
                CfaColor.BLUE -> 2
            }
            profile[channel * 2] to profile[channel * 2 + 1]
        }
    }

    /** Exact non-negative least squares for `y = alpha*x + beta`. */
    internal fun fitNonNegative(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for ((x, y) in points) {
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }
        val n = points.size.toDouble()
        val denom = n * sxx - sx * sx
        if (denom > 0.0) {
            val a = (n * sxy - sx * sy) / denom
            val b = (sy - a * sx) / n
            if (a >= 0.0 && b >= 0.0) return a to b
        }
        // Infeasible interior (or singular spread): the better clamped edge.
        val meanY = if (points.isEmpty()) 0.0 else sy / n
        val edgeA = if (sxx > 0.0) (sxy / sxx).coerceAtLeast(0.0) else 0.0
        var sseA = 0.0
        var sseB = 0.0
        val betaEdge = meanY.coerceAtLeast(0.0)
        for ((x, y) in points) {
            val ra = y - edgeA * x
            val rb = y - betaEdge
            sseA += ra * ra
            sseB += rb * rb
        }
        return if (sseA <= sseB) edgeA to 0.0 else 0.0 to betaEdge
    }

    /**
     * Per-phase noise fits for the measurable phases (possibly empty).
     * [cfa] values must be normalized sensor values before lens-shading gains.
     */
    fun measure(
        cfa: UnpackedRawCfa,
        blockPhase: Int = DEFAULT_BLOCK_PHASE,
        bins: Int = DEFAULT_BINS,
        percentile: Double = DEFAULT_PERCENTILE,
        saturation: Double = DEFAULT_SATURATION,
        minBins: Int = DEFAULT_MIN_BINS,
        minBlocksPerBin: Int = DEFAULT_MIN_BLOCKS_PER_BIN
    ): List<PhaseFit> {
        require(cfa.values.size == cfa.width * cfa.height) { "CFA values must cover the frame" }
        require(blockPhase >= 2) { "Blocks must span lattice sites (blockPhase >= 2)" }
        require(bins >= 1 && percentile in 0.0..1.0 && minBins >= 1 && minBlocksPerBin >= 1) {
            "Invalid meter parameters"
        }
        return (0..3).mapNotNull { phase ->
            fitPhase(cfa, phase, blockPhase, bins, percentile, saturation, minBins, minBlocksPerBin)
        }
    }

    private fun fitPhase(
        cfa: UnpackedRawCfa,
        phase: Int,
        blockPhase: Int,
        bins: Int,
        percentile: Double,
        saturation: Double,
        minBins: Int,
        minBlocksPerBin: Int
    ): PhaseFit? {
        val dx0 = phase and 1
        val dy0 = (phase shr 1) and 1
        val w = cfa.width
        val values = cfa.values
        // Phase-lattice extents: sites with x%2==dx0, y%2==dy0.
        val nx = (w - dx0 + 1) / 2
        val ny = (cfa.height - dy0 + 1) / 2
        if (nx < 3 || ny < 3) return null
        val blocksX = nx / blockPhase
        val blocksY = ny / blockPhase
        if (blocksX < 1 || blocksY < 1) return null
        // Block by block over interior lattice sites; the partial edge strip
        // (sites past blocksX/Y*blockPhase) is skipped, never partial.
        val binned = Array(bins) { mutableListOf<Pair<Double, Double>>() }
        for (by in 0 until blocksY) for (bx in 0 until blocksX) {
            var sum = 0.0
            var centers = 0
            val tap2 = mutableListOf<Double>()
            val gx0 = maxOf(bx * blockPhase, 1)
            val gx1 = minOf((bx + 1) * blockPhase, nx - 1)
            val gy0 = maxOf(by * blockPhase, 1)
            val gy1 = minOf((by + 1) * blockPhase, ny - 1)
            for (gy in gy0 until gy1) for (gx in gx0 until gx1) {
                val cx = dx0 + gx * 2
                val cy = dy0 + gy * 2
                val c = values[cy * w + cx].toDouble()
                if (!c.isFinite()) continue
                sum += c
                centers++
                val lap = values[cy * w + cx - 2].toDouble() + values[cy * w + cx + 2] +
                    values[(cy - 2) * w + cx] + values[(cy + 2) * w + cx] - 4.0 * c
                if (lap.isFinite()) tap2.add(lap * lap)
            }
            if (centers == 0 || tap2.size < MIN_KEPT_TAPS) continue
            // Tap-level sigma clip: one edge/hot tap reads hundreds of times
            // hotter than noise and would own a plain mean. The median stays
            // clean under <50% pollution, so clipping at 25x it cuts pollution
            // while keeping 99.9% of clean taps.
            tap2.sort()
            val median = if (tap2.size % 2 == 1) tap2[tap2.size / 2]
                else (tap2[tap2.size / 2 - 1] + tap2[tap2.size / 2]) / 2.0
            val limit = TAP_CLIP_K * median
            var keptSum = 0.0
            var kept = 0
            for (t in tap2) {
                if (median <= 0.0 || t <= limit) {
                    keptSum += t
                    kept++
                }
            }
            if (kept < MIN_KEPT_TAPS) continue
            val mean = sum / centers
            val variance = keptSum / kept / LAPLACIAN_GAIN
            if (!mean.isFinite() || !variance.isFinite() || mean > saturation) continue
            binned[floor(mean * bins).toInt().coerceIn(0, bins - 1)].add(mean to variance)
        }
        val points = mutableListOf<Pair<Double, Double>>()
        var usedBlocks = 0
        for (bin in binned) {
            if (bin.size < minBlocksPerBin) continue
            val sorted = bin.map { it.second }.sorted()
            val pick = sorted[(percentile * (sorted.size - 1)).toInt()]
            points.add(bin.map { it.first }.average() to pick)
            usedBlocks += bin.size
        }
        if (points.size < minBins) return null
        val (alpha, beta) = fitNonNegative(points)
        if (!alpha.isFinite() || !beta.isFinite()) return null
        return PhaseFit(phase, alpha.toFloat(), beta.toFloat(), points.size, usedBlocks)
    }

    /**
     * Measured-vs-profile verdict, or null when the profile is unusable (same
     * validity rule as [CfaNoiseModel]) or no phase is measurable. The profile
     * sigma is [RawSrKernelNetAniso.sigmaFor] itself, so the ratio faults the
     * exact scalar the model consumes.
     */
    fun compare(
        cfa: UnpackedRawCfa,
        noiseProfile: ImmutableDoubleValues?,
        blockPhase: Int = DEFAULT_BLOCK_PHASE,
        bins: Int = DEFAULT_BINS,
        percentile: Double = DEFAULT_PERCENTILE,
        saturation: Double = DEFAULT_SATURATION,
        minBins: Int = DEFAULT_MIN_BINS,
        minBlocksPerBin: Int = DEFAULT_MIN_BLOCKS_PER_BIN
    ): Report? {
        val raw = noiseProfile?.toDoubleArray()
        if (raw == null || (raw.size != 6 && raw.size != 8) || raw.any { !it.isFinite() || it < 0.0 }) return null
        val fits = measure(cfa, blockPhase, bins, percentile, saturation, minBins, minBlocksPerBin)
        if (fits.isEmpty()) return null
        val joined = fits.map { fit ->
            val (pa, pb) = profilePairFor(raw, fit.phase, cfa)
            PhaseRow(fit.phase, fit.alpha, fit.beta, pa, pb, fit.bins, fit.blocks)
        }
        val avgAlpha = joined.map { it.alpha.toDouble() }.average()
        val avgBeta = joined.map { it.beta.toDouble() }.average()
        val measured = sqrt(avgAlpha * 0.5 + avgBeta).toFloat()
        val profile = RawSrKernelNetAniso.sigmaFor(noiseProfile)
        if (!measured.isFinite() || profile <= 0f || !profile.isFinite()) return null
        return Report(joined, measured, profile)
    }
}
