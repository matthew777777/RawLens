package com.rawlens.srpipeline.diag

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Desktop oracle for one fixed-flow, green-only LK-R data step. */
internal object GreenResidualFit {
    data class Registration(val flow: Array<Array<FloatArray>>, val robustness: FloatArray)

    fun apply(
        base: Array<Array<FloatArray>>,
        frames: List<Array<FloatArray>>,
        registration: List<Registration>,
        scale: Int,
        tile: Int,
        alpha: Float,
        beta: Float,
        wb: FloatArray,
        step: Float,
        textureGate: Boolean,
    ): Array<Array<FloatArray>> {
        val height = base.size
        val width = base[0].size
        val rawH = height / scale
        val rawW = width / scale
        require(scale in 1..2 && frames.size == registration.size)
        val residual = FloatArray(width * height)
        val mass = FloatArray(width * height)
        val shift = FloatArray(2)
        val bx = FloatArray(scale + 1)
        val by = FloatArray(scale + 1)
        val wx = FloatArray(4)
        val wy = FloatArray(4)
        val sigma = sqrt(maxOf(alpha * 0.42f + beta, 1e-8f))
        for ((k, frame) in frames.withIndex()) {
            val reg = registration[k]
            for (y in 2 until rawH - 2) for (x in (3 - (y and 1)) until rawW - 2 step 2) {
                val sample = frame[y][x]
                if (!sample.isFinite() || sample >= 0.98f) continue
                val mx = x + 0.5f
                val my = y + 0.5f
                var rx = mx
                var ry = my
                // Invert the reference-to-frame flow at the sample centre.
                if (k > 0) repeat(2) {
                    flowAt(reg.flow, rx, ry, tile, wx, wy, shift)
                    rx = mx - shift[0]
                    ry = my - shift[1]
                }
                if (rx < 1f || ry < 1f || rx >= rawW - 1f || ry >= rawH - 1f) continue
                val r = reg.robustness[(ry.toInt() / 2) * (rawW / 2) + rx.toInt() / 2]
                if (r <= 0f) continue
                // At 1x this is bilinear sampling; at 2x it equals the mean
                // of the LK-R bilinear taps at sensor-centre offsets ±1/4.
                val x0 = footprint(rx, scale, bx)
                val y0 = footprint(ry, scale, by)
                var predicted = 0f
                for (j in by.indices) for (i in bx.indices) {
                    predicted += bx[i] * by[j] * base[y0 + j][x0 + i][1]
                }
                val e = sample - predicted
                // Taper large disagreements instead of restoring rejected motion.
                val noise = sqrt(maxOf(alpha * maxOf(predicted, 0f) + beta, 1e-8f))
                val confidence = ((6f * noise - abs(e)) / (3f * noise)).coerceIn(0f, 1f)
                val acceptance = if (textureGate) r * r else r
                for (j in by.indices) for (i in bx.indices) {
                    val index = (y0 + j) * width + x0 + i
                    val weight = acceptance * confidence * bx[i] * by[j]
                    residual[index] += weight * e
                    mass[index] += weight
                }
            }
        }
        return Array(height) { y -> Array(width) { x ->
            val index = y * width + x
            val rgb = base[y][x]
            val gate = if (textureGate) {
                val left = base[y][(x - scale).coerceAtLeast(0)][1]
                val right = base[y][(x + scale).coerceAtMost(width - 1)][1]
                val up = base[(y - scale).coerceAtLeast(0)][x][1]
                val down = base[(y + scale).coerceAtMost(height - 1)][x][1]
                val noise = sqrt(maxOf(alpha * maxOf(rgb[1], 0f) + beta, 1e-8f))
                val contrast = (abs(left - rgb[1]) + abs(right - rgb[1]) +
                    abs(up - rgb[1]) + abs(down - rgb[1])) * 0.25f / noise
                val t = ((contrast - 0.65f) / 0.85f).coerceIn(0f, 1f)
                t * t * (3f - 2f * t)
            } else 1f
            val correction = (step * residual[index] / (mass[index] + 0.25f))
                .coerceIn(-2f * sigma, 2f * sigma) * gate
            floatArrayOf(rgb[0] + correction / wb[0], rgb[1] + correction, rgb[2] + correction / wb[1])
        } }
    }

    private fun footprint(center: Float, scale: Int, weights: FloatArray): Int {
        val start = (center - 0.5f) * scale
        val index = floor(start).toInt()
        val fraction = start - index
        weights[0] = (1f - fraction) / scale
        for (i in 1 until scale) weights[i] = 1f / scale
        weights[scale] = fraction / scale
        return index
    }

    private fun basis(t: Float, out: FloatArray) {
        val u = 1f - t
        out[0] = u * u * u / 6f
        out[1] = (4f - 6f * t * t + 3f * t * t * t) / 6f
        out[2] = (1f + 3f * t + 3f * t * t - 3f * t * t * t) / 6f
        out[3] = t * t * t / 6f
    }

    private fun flowAt(
        flow: Array<Array<FloatArray>>, x: Float, y: Float, tile: Int,
        wx: FloatArray, wy: FloatArray, shift: FloatArray,
    ) {
        val gx = x / tile - 0.5f
        val gy = y / tile - 0.5f
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        basis(gx - ix, wx)
        basis(gy - iy, wy)
        shift.fill(0f)
        for (j in 0..3) for (i in 0..3) {
            val row = flow[(iy + j - 1).coerceIn(0, flow.lastIndex)]
            val sample = row[(ix + i - 1).coerceIn(0, row.lastIndex)]
            val weight = wx[i] * wy[j]
            shift[0] += weight * sample[0]
            shift[1] += weight * sample[1]
        }
    }
}
