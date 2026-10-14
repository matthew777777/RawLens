// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * Fresh low-res Bayer sampler for the BGU engine: point-samples one quad per
 * low texel straight from the sensor plane (no demosaic, no copies beyond the
 * tiny output). The low texel (i, j) samples the sensor quad at
 * ([qx0] + i * [lowStep], [qy0] + j * [lowStep]) — the same quad the guide
 * texel (i * 8, j * 8) renders, so the CPU fit input and the GPU guide agree.
 * Pure ByteBuffer core (framework-free) + unit tests; the engine passes the
 * Image plane buffer (absolute reads only — no position mutation).
 */
internal object BguSample {
    /**
     * Samples a [lowW] x [lowH] quad frame into [out] (Halide planar u8:
     * index ch * lowW * lowH + y * lowW + x). [channels] maps canonical
     * [R, Gr, Gb, B] to sensor sites (see [BguGeometry.cfaSites]);
     * [levels]/[invRange] are per sensor phase (index (y % 2) * 2 + x % 2).
     * Returns false without further writes when any read would go out of
     * bounds (the caller drops the frame).
     */
    fun sampleLowFrame(
        buf: ByteBuffer, rowStride: Int, pixelStride: Int,
        qx0: Int, qy0: Int, lowStep: Int, lowW: Int, lowH: Int,
        channels: IntArray, levels: FloatArray, invRange: FloatArray,
        out: ByteArray
    ): Boolean {
        require(lowW > 0 && lowH > 0 && lowStep >= 2)
        require(channels.size == 4 && levels.size == 4 && invRange.size == 4)
        require(out.size == lowW * lowH * 4)
        if (rowStride <= 0 || pixelStride <= 0) return false
        val limit = buf.limit()
        val plane = lowW * lowH
        // Hot path (camera handler, 30 fps): int offsets, per-row bounds
        // precheck, and per-row phase hoisting (lowStep is even in every
        // geometry this engine builds, so site parity is row-constant;
        // the phase formula below stays correct even if it were not,
        // because it is recomputed from the row's first quad origin).
        val dx = IntArray(4) { channels[it] % 2 }
        val dy = IntArray(4) { channels[it] / 2 }
        val stepBytes = lowStep * pixelStride
        for (j in 0 until lowH) {
            val qy = qy0 + j * lowStep
            val rowBase = qy * rowStride + qx0 * pixelStride
            val row1Base = rowBase + rowStride
            // Widest read in this row: last quad, site dx=1, dy=1.
            val maxOffset = row1Base + (lowW - 1) * stepBytes + pixelStride
            if (rowBase < 0 || maxOffset + 1 >= limit) return false
            // Phase per channel for even/odd i (sy parity is row-constant;
            // sx parity flips with i only when lowStep is odd).
            val phases = Array(2) { parity ->
                IntArray(4) { k ->
                    val sx = qx0 + parity * lowStep + dx[k]
                    val sy = qy + dy[k]
                    (sy and 1) * 2 + (sx and 1)
                }
            }
            val outRow = j * lowW
            for (i in 0 until lowW) {
                val colBase = rowBase + i * stepBytes
                val col1Base = row1Base + i * stepBytes
                val rowPhases = phases[i and 1]
                for (k in 0..3) {
                    val offset = (if (dy[k] == 0) colBase else col1Base) + dx[k] * pixelStride
                    val code = buf.getShort(offset).toInt() and 0xffff
                    val phase = rowPhases[k]
                    var n = (code - levels[phase]) * invRange[phase]
                    if (!n.isFinite()) n = 0f
                    n = n.coerceIn(0f, 1f)
                    out[k * plane + outRow + i] = ((n * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte()
                }
            }
        }
        return true
    }

    /**
     * Guide sharpness score from the sampled low frame: mean absolute
     * neighbour gradient of the Gr plane, normalized to 0..1 (0 = flat,
     * ~0.5+ = hard checkerboard). Blur telemetry: a textured scene with a
     * near-zero score means the guide content itself is soft; a healthy
     * score with a soft screen points at the slice/display upscale. Flat
     * scenes (wall, sky) legitimately score low — the number is only
     * meaningful against the scene the reporter was looking at.
     */
    fun guideSharpness(quad: ByteArray, lowW: Int, lowH: Int): Float {
        if (lowW < 2 || lowH < 2 || quad.size < lowW * lowH * 4) return 0f
        val plane = lowW * lowH
        val base = plane // Gr plane (canonical R, Gr, Gb, B order)
        var sum = 0L
        var count = 0L
        for (j in 0 until lowH) {
            val row = base + j * lowW
            for (i in 0 until lowW) {
                val v = quad[row + i].toInt() and 0xFF
                if (i + 1 < lowW) {
                    sum += kotlin.math.abs(v - (quad[row + i + 1].toInt() and 0xFF))
                    count++
                }
                if (j + 1 < lowH) {
                    sum += kotlin.math.abs(v - (quad[row + lowW + i].toInt() and 0xFF))
                    count++
                }
            }
        }
        if (count == 0L) return 0f
        return (sum.toDouble() / count / 255.0).toFloat()
    }
}
