// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/** Bounded same-CFA Haar MAD estimate. References and limitations:
 * docs/refactor/hdrplus-scene-auto.md. No ISO or scene-label lookup. */
object HdrPlusSceneNoise {
    private const val GRID = 64
    private const val GAUSSIAN_MAD = 0.6744897501960817
    // RawLens policy: 2% relative noise is one unit of denoising demand.
    // This is a tunable image-quality budget, not a sensor calibration.
    private const val RELATIVE_NOISE_BUDGET = 0.02

    fun measure(packed: ByteBuffer, width: Int, height: Int,
                black: FloatArray, white: Float): Double {
        if (width < 4 || height < 4 || black.size != 4 || !white.isFinite() ||
            black.any { !it.isFinite() || it >= white } ||
            width.toLong() * height > packed.remaining() / 2) return Double.NaN
        val raw = packed.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val nx = minOf(GRID, width / 4)
        val ny = minOf(GRID, height / 4)
        val values = DoubleArray(nx * ny * 4)
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
                val signal = (a + b + c + d) / 4.0 - black[phase]
                // Clipping invalidates the Gaussian residual; near-black codes
                // cannot provide a stable relative estimate.
                if (minOf(a, b, c, d) <= black[phase] ||
                    maxOf(a, b, c, d) >= white || signal <= 1.0) continue
                // Unit-energy diagonal Haar: sum of squared coefficients = 1.
                values[count++] = (a - b - c + d) / (2.0 * signal)
            }
        }
        if (count < 64) return Double.NaN
        val valid = values.copyOf(count)
        valid.sort()
        val center = median(valid)
        for (i in valid.indices) valid[i] = abs(valid[i] - center)
        valid.sort()
        return median(valid) / GAUSSIAN_MAD
    }

    /** Logarithmic demand fits the exponential native slider response. This
     * policy is deliberately separate from the statistical noise estimator. */
    fun strength(relativeNoise: List<Double>): Float {
        // A partial burst estimate is not evidence about the missing frames.
        if (relativeNoise.isEmpty() || relativeNoise.any { !it.isFinite() || it < 0.0 })
            return HdrPlusSettings.DEFAULT_STRENGTH
        val sorted = relativeNoise.sorted().toDoubleArray()
        val demand = median(sorted) / RELATIVE_NOISE_BUDGET
        return (HdrPlusSettings.MIN_STRENGTH + ln(1.0 + demand * demand) / ln(2.0))
            .coerceIn(HdrPlusSettings.MIN_STRENGTH.toDouble(),
                HdrPlusAutoTuning.AUTO_STRENGTH_MAX.toDouble()).toFloat()
    }

    private fun median(sorted: DoubleArray): Double =
        (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
}
