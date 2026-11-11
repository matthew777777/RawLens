// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * Poisson-Gaussian sensor noise in normalized raw units (black-subtracted
 * code over the code range): variance(x) = a*x + b. Same convention as the
 * learned per-device model (rawlens_noise). [levels] are the equal-population
 * signal levels the estimate was fitted on, i.e. the burst's own brightness
 * distribution; [bins] is their count (the estimate's confidence).
 */
class HdrPlusNoiseModel(val a: Double, val b: Double, val bins: Int, val levels: DoubleArray) {
    fun variance(level: Double): Double = a * maxOf(level, 0.0) + b
    fun sigma(level: Double): Double = sqrt(variance(level))
    fun with(a: Double, b: Double): HdrPlusNoiseModel = HdrPlusNoiseModel(a, b, bins, levels)
}

/** Learned prior refining a burst's blind estimate; null keeps the estimate. */
fun interface HdrPlusNoisePrior {
    fun refine(a: Double, b: Double, bins: Int): Pair<Double, Double>?
}

/**
 * Blind noise estimate of an HDR+ burst, measured from its own RAW frames
 * (never from Camera2/DNG noise profiles). References and limitations:
 * docs/refactor/hdrplus-scene-auto.md.
 */
object HdrPlusSceneNoise {
    /** Sampling budget per frame: at most GRID x GRID disjoint 4x4 patches. */
    private const val GRID = 64
    /** Gaussian sigma = MAD / this (median of |N(0,1)|). */
    private const val GAUSSIAN_MAD = 0.6744897501960817

    /** Same-CFA diagonal Haar samples of one frame: level and noise coefficient. */
    class Samples(val level: DoubleArray, val coefficient: DoubleArray)

    fun sample(packed: ByteBuffer, width: Int, height: Int,
               black: FloatArray, white: Float): Samples? {
        if (width < 4 || height < 4 || black.size != 4 || !white.isFinite() ||
            black.any { !it.isFinite() || it >= white } ||
            width.toLong() * height > packed.remaining() / 2) return null
        val raw = packed.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val nx = minOf(GRID, width / 4)
        val ny = minOf(GRID, height / 4)
        val level = DoubleArray(nx * ny * 4)
        val coefficient = DoubleArray(nx * ny * 4)
        var count = 0
        // Disjoint 4x4 patches sample all four CFA phases, including odd crops.
        for (gy in 0 until ny) for (gx in 0 until nx) {
            val x = (gx.toLong() * (width / 4) / nx).toInt() * 4
            val y = (gy.toLong() * (height / 4) / ny).toInt() * 4
            for (phase in 0..3) {
                val origin = (y + phase / 2) * width + x + phase % 2
                val a = (raw[origin].toInt() and 65535).toDouble()
                val b = (raw[origin + 2].toInt() and 65535).toDouble()
                val c = (raw[origin + 2 * width].toInt() and 65535).toDouble()
                val d = (raw[origin + 2 * width + 2].toInt() and 65535).toDouble()
                val range = white - black[phase].toDouble()
                // Clipping invalidates the Gaussian residual on either end.
                if (minOf(a, b, c, d) <= black[phase] || maxOf(a, b, c, d) >= white) continue
                level[count] = ((a + b + c + d) / 4.0 - black[phase]) / range
                // Unit-energy diagonal Haar: constant and linear scene content
                // cancel, independent noise keeps its standard deviation.
                coefficient[count++] = (a - b - c + d) / (2.0 * range)
            }
        }
        return Samples(level.copyOf(count), coefficient.copyOf(count))
    }

    /**
     * Fits variance = a*level + b over the pooled samples of the burst.
     * Samples are split into equal-population brightness bins (cube-root
     * rule, so both the bin count and the per-bin population grow with the
     * data); each bin's variance is its centered MAD, which ignores texture
     * as long as most of the bin is flat. Null when the burst cannot say.
     */
    fun fit(frames: List<Samples?>, prior: HdrPlusNoisePrior? = null): HdrPlusNoiseModel? {
        if (frames.isEmpty() || frames.any { it == null }) return null
        val total = frames.sumOf { it!!.level.size }
        val bins = cbrt(total.toDouble()).toInt()
        if (bins < MIN_BINS) return null
        // Sort by level without boxing: positive float bits order like the
        // floats, the low word carries the sample's position.
        val level = DoubleArray(total)
        val coefficient = DoubleArray(total)
        val order = LongArray(total)
        var n = 0
        for (f in frames) for (i in f!!.level.indices) {
            level[n] = f.level[i]
            coefficient[n] = f.coefficient[i]
            order[n] = (f.level[i].toFloat().toRawBits().toLong() shl 32) or n.toLong()
            n++
        }
        order.sort()
        fun at(rank: Int): Int = order[rank].toInt()
        val levels = DoubleArray(bins)
        val variances = DoubleArray(bins)
        for (bin in 0 until bins) {
            val from = (bin.toLong() * total / bins).toInt()
            val to = ((bin + 1).toLong() * total / bins).toInt()
            val coefficients = DoubleArray(to - from) { coefficient[at(from + it)] }
            coefficients.sort()
            val center = median(coefficients)
            for (i in coefficients.indices) coefficients[i] = abs(coefficients[i] - center)
            coefficients.sort()
            val sigma = median(coefficients) / GAUSSIAN_MAD
            levels[bin] = (level[at((from + to - 1) / 2)] + level[at((from + to) / 2)]) / 2.0
            variances[bin] = sigma * sigma
        }
        // Quantized flat data (MAD 0) carries no variance information.
        if (variances.any { !(it > 0.0) }) return null
        val (a, b) = fitLine(levels, variances) ?: return null
        val model = HdrPlusNoiseModel(a, b, bins, levels)
        val refined = prior?.refine(a, b, bins) ?: return model
        if (!refined.first.isFinite() || !refined.second.isFinite() ||
            refined.first < 0.0 || refined.second < 0.0 ||
            !(refined.first + refined.second > 0.0)) return model
        return model.with(refined.first, refined.second)
    }

    /** Relative least squares (every bin counts equally in log terms) with
     * the physical constraint a, b >= 0. */
    private fun fitLine(x: DoubleArray, y: DoubleArray): Pair<Double, Double>? {
        var sxx = 0.0; var sx1 = 0.0; var s11 = 0.0; var sx = 0.0; var s1 = 0.0
        for (i in x.indices) {
            val u = x[i] / y[i]
            val v = 1.0 / y[i]
            sxx += u * u; sx1 += u * v; s11 += v * v; sx += u; s1 += v
        }
        val det = sxx * s11 - sx1 * sx1
        var a = if (det > 0.0) (sx * s11 - s1 * sx1) / det else -1.0
        var b = if (det > 0.0) (s1 * sxx - sx * sx1) / det else -1.0
        if (!(a >= 0.0) || !(b >= 0.0)) {
            val shotOnly = if (sxx > 0.0) sx / sxx else Double.NaN
            val readOnly = if (s11 > 0.0) s1 / s11 else Double.NaN
            if (!(b >= 0.0) && shotOnly.isFinite() && shotOnly > 0.0) { a = shotOnly; b = 0.0 }
            else if (readOnly.isFinite() && readOnly > 0.0) { a = 0.0; b = readOnly }
            else return null
        }
        return a to b
    }

    /**
     * Mean noise sigma (normalized by [range]) over the motion meter's strided
     * sample grid, so it predicts the noise-only part of
     * [HdrPlusMotionMeter.meanAbsDiffNormalized] on the same samples.
     */
    fun frameSigma(packed: ByteBuffer, width: Int, height: Int, black: FloatArray,
                   range: Double, model: HdrPlusNoiseModel,
                   stride: Int = HdrPlusMotionMeter.SAMPLE_STRIDE): Double {
        if (!usable(packed, width, height, black, range) || stride <= 0) return Double.NaN
        val raw = packed.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < width * height) {
            sum += sigmaAt(raw, i, width, black, range, model)
            n++
            i += stride
        }
        return sum / n
    }

    /**
     * Mean noise sigma per strength-map cell ([HdrPlusMotionMeter.mapCells]
     * grid), from a lattice inside each cell that alternates CFA phases so
     * every color contributes like it does in the dense per-cell difference.
     */
    fun cellSigma(packed: ByteBuffer, width: Int, height: Int, black: FloatArray,
                  range: Double, model: HdrPlusNoiseModel): DoubleArray? {
        if (!usable(packed, width, height, black, range)) return null
        val raw = packed.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val cell = HdrPlusAutoTuning.MAP_BLOCK_PX
        val (mw, mh) = HdrPlusMotionMeter.mapCells(width, height)
        val step = cell / CELL_LATTICE
        return DoubleArray(mw * mh) { index ->
            val x0 = (index % mw) * cell
            val y0 = (index / mw) * cell
            var sum = 0.0
            var n = 0
            for (j in 0 until CELL_LATTICE) for (i in 0 until CELL_LATTICE) {
                val x = x0 + i * step + (i and 1)
                val y = y0 + j * step + (j and 1)
                if (x >= width || y >= height) continue
                sum += sigmaAt(raw, y * width + x, width, black, range, model)
                n++
            }
            if (n == 0) Double.NaN else sum / n
        }
    }

    /**
     * The spatial merge's own noise reference (native hdrp_noise): the mean
     * over CFA cells of the summed |pixel - same-color blur| of the reference
     * frame, here from the sampling grid and normalized by [range]. Unlike
     * the noise model it includes scene texture, exactly as the merge's does.
     */
    fun spatialNoiseReference(packed: ByteBuffer, width: Int, height: Int, range: Double): Double {
        if (width < 4 || height < 4 || !range.isFinite() || range <= 0.0 ||
            width.toLong() * height > packed.remaining() / 2) return Double.NaN
        val raw = packed.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val weights = HdrPlusMergeModel.blurWeights
        val radius = HdrPlusMergeModel.BLUR_RADIUS
        val nx = minOf(GRID, width / 4)
        val ny = minOf(GRID, height / 4)
        var sum = 0.0
        var n = 0
        for (gy in 0 until ny) for (gx in 0 until nx) for (phase in 0..3) {
            val x = (gx.toLong() * (width / 4) / nx).toInt() * 4 + phase % 2
            val y = (gy.toLong() * (height / 4) / ny).toInt() * 4 + phase / 2
            // Separable kernel, renormalized per axis at the borders like the shader.
            var total = 0.0
            var weightY = 0.0
            for (dy in -radius..radius) {
                val yy = y + 2 * dy
                if (yy < 0 || yy >= height) continue
                var rowTotal = 0.0
                var weightX = 0.0
                for (dx in -radius..radius) {
                    val xx = x + 2 * dx
                    if (xx < 0 || xx >= width) continue
                    rowTotal += weights[dx + radius] * (raw[yy * width + xx].toInt() and 65535)
                    weightX += weights[dx + radius]
                }
                total += weights[dy + radius] * rowTotal / weightX
                weightY += weights[dy + radius]
            }
            sum += abs((raw[y * width + x].toInt() and 65535) - total / weightY)
            n++
        }
        return HdrPlusMergeModel.DIFF_CELL_PIXELS * sum / (n * range)
    }

    private fun usable(packed: ByteBuffer, width: Int, height: Int, black: FloatArray,
                       range: Double): Boolean =
        width > 0 && height > 0 && black.size == 4 && range.isFinite() && range > 0.0 &&
            width.toLong() * height <= packed.remaining() / 2

    private fun sigmaAt(raw: ShortBuffer, index: Int, width: Int, black: FloatArray,
                        range: Double, model: HdrPlusNoiseModel): Double {
        val phase = ((index / width) and 1) * 2 + ((index % width) and 1)
        return model.sigma(((raw[index].toInt() and 65535) - black[phase]) / range)
    }

    private fun median(sorted: DoubleArray): Double =
        (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0

    /** A line needs two points; one more makes the fit checkable (matches
     * rawlens_noise::kMinBins). */
    private const val MIN_BINS = 3
    /** Samples per cell side; 4 covers each CFA phase 4 times per cell. */
    private const val CELL_LATTICE = 4
}
