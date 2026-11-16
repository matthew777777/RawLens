// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2

/** Basic's display shoulder must not hide an exposure overshoot after WB/calibration.
 * Keep the existing sensor meter for brightness placement, adding a global calibrated
 * p95/p99 peak guards. No spatial filtering and no change to the source RGB or DNG. */
internal object BasicRgbExposureGuard {
    fun limitEv(rgb: ByteBuffer, width: Int, height: Int, matrix: DoubleArray, headroom: Double, spikeHeadroom: Double = 1.0, displayP3: Boolean = false): Double {
        require(matrix.size == 9 && matrix.all(Double::isFinite))
        val pixels = Math.multiplyExact(width, height)
        require(rgb.remaining() >= Math.multiplyExact(pixels, 12))
        val input = rgb.duplicate().order(ByteOrder.nativeOrder())
        val origin = input.position()
        val stride = maxOf(1, (pixels + 16383) / 16384)
        val values = FloatArray((pixels + stride - 1) / stride)
        var count = 0
        val camera = FloatArray(3)
        val aces = FloatArray(3)
        for (p in 0 until pixels step stride) {
            for (c in 0..2) camera[c] = input.getFloat(origin + p * 12 + c * 4)
            if (!camera.all(Float::isFinite)) continue
            for (r in 0..2) aces[r] = (0..2).sumOf { matrix[r*3+it] * camera[it] }.toFloat()
            val peak = AgxDisplayTransform.sceneOutput(aces, displayP3).max()
            if (peak > 1e-6f && peak.isFinite()) values[count++] = peak
        }
        if (count == 0) return Double.POSITIVE_INFINITY
        java.util.Arrays.sort(values, 0, count)
        val broad = values[((count - 1) * .95).toInt()].toDouble()
        val spike = values[((count - 1) * .99).toInt()].toDouble()
        return minOf(log2(headroom.coerceIn(.6, 1.0) / broad),
            log2(spikeHeadroom.coerceIn(.5, 1.5) / spike))
    }
}
