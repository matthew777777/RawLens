package com.rawlens.srpipeline.stages

import kotlin.math.abs
import kotlin.math.sqrt

/** Blind fallback for DNGs without NoiseProfile. Same-CFA diagonal Haar
 * coefficients cancel constant/linear scene content. Brightness-bin MADs
 * suppress texture outliers; heavy texture can still overestimate noise.
 * Uses the app's HdrPlusSceneNoise approach on normalized RGGB floats.
 */
object SceneNoise {
    fun estimate(image: Array<FloatArray>): Pair<DoubleArray, DoubleArray> {
        val h = image.size
        val w = image.firstOrNull()?.size ?: 0
        require(h >= 4 && w >= 4 && image.all { it.size == w }) {
            "Noise estimation requires a rectangular frame of at least 4x4; supply --noise-ab a,b"
        }
        val alpha = DoubleArray(4)
        val beta = DoubleArray(4)
        // RGGB raster phases -> R,G1,B,G2 model order.
        for ((plane, phase) in intArrayOf(0, 1, 3, 2).withIndex()) {
            val samples = ArrayList<Pair<Double, Double>>()
            val nx = minOf(128, w / 4)
            val ny = minOf(128, h / 4)
            for (gy in 0 until ny) for (gx in 0 until nx) {
                val x = gx * (w / 4) / nx * 4 + phase % 2
                val y = gy * (h / 4) / ny * 4 + phase / 2
                val a = image[y][x].toDouble()
                val b = image[y][x + 2].toDouble()
                val c = image[y + 2][x].toDouble()
                val d = image[y + 2][x + 2].toDouble()
                if (listOf(a, b, c, d).any { !it.isFinite() || it <= 0.0 || it >= 1.0 }) continue
                samples.add((a + b + c + d) / 4.0 to (a - b - c + d) / 2.0)
            }
            require(samples.size >= 512) { "Insufficient unclipped noise samples; supply --noise-ab a,b" }
            samples.sortBy { it.first }
            val bins = minOf(32, samples.size / 64)
            var sxx = 0.0; var sx1 = 0.0; var s11 = 0.0; var sx = 0.0; var s1 = 0.0
            var used = 0
            for (bin in 0 until bins) {
                val from = bin * samples.size / bins
                val to = (bin + 1) * samples.size / bins
                val coefficients = DoubleArray(to - from) { samples[from + it].second }
                coefficients.sort()
                val center = median(coefficients)
                for (i in coefficients.indices) coefficients[i] = abs(coefficients[i] - center)
                coefficients.sort()
                val sigma = median(coefficients) / 0.6744897501960817
                val variance = sigma * sigma
                if (!(variance > 0.0)) continue
                val level = samples[(from + to) / 2].first
                val u = level / variance
                val v = 1.0 / variance
                sxx += u * u; sx1 += u * v; s11 += v * v; sx += u; s1 += v
                used++
            }
            require(used >= 3) { "Cannot estimate noise from quantized/flat data; supply --noise-ab a,b" }
            val det = sxx * s11 - sx1 * sx1
            var a = if (det > 1e-12 * sxx * s11) (sx * s11 - s1 * sx1) / det else -1.0
            var b = if (det > 1e-12 * sxx * s11) (s1 * sxx - sx * sx1) / det else -1.0
            if (a < 0.0 || b < 0.0) {
                if (b < 0.0 && a > 0.0) { a = sx / sxx; b = 0.0 }
                else { a = 0.0; b = s1 / s11 }
            }
            require(a.isFinite() && b.isFinite() && a >= 0 && b >= 0 && sqrt(a + b) > 0) {
                "Noise estimate failed; supply --noise-ab a,b"
            }
            alpha[plane] = a
            beta[plane] = b
        }
        return alpha to beta
    }

    private fun median(sorted: DoubleArray) = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
}
