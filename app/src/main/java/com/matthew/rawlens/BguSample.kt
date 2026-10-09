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
        for (j in 0 until lowH) {
            for (i in 0 until lowW) {
                val qx = qx0 + i * lowStep
                val qy = qy0 + j * lowStep
                for (k in 0..3) {
                    val site = channels[k]
                    val sx = qx + site % 2
                    val sy = qy + site / 2
                    val offset = sy.toLong() * rowStride + sx.toLong() * pixelStride
                    if (offset < 0 || offset + 1 >= limit) return false
                    val code = buf.getShort(offset.toInt()).toInt() and 0xffff
                    val phase = (sy and 1) * 2 + (sx and 1)
                    var n = (code - levels[phase]) * invRange[phase]
                    if (!n.isFinite()) n = 0f
                    n = n.coerceIn(0f, 1f)
                    out[k * plane + j * lowW + i] = ((n * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte()
                }
            }
        }
        return true
    }
}
