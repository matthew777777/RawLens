// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * CPU twin of the Direct-Log record superpixel (vf_superpixel_f16.comp,
 * shading off): step 2 is CENTRE-ALIGNED (R and B bilinearly resampled
 * to the quad centre, green the Gr/Gb mean), larger steps are the block
 * mean of every quad. Same Q6 integer normalize as the shader, same
 * clamped-quad edges. Output is canonical R, Gr, Gb, B per texel (the
 * RGBA16F export the grade reads), frame row-major.
 */
internal object RecordSuperpixel {
    @Suppress("LongParameterList")
    fun develop(
        codes: IntArray, pitch: Int,
        channels: IntArray,
        quadBaseX: Int, quadBaseY: Int,
        frameW: Int, frameH: Int, step: Int,
        levels: FloatArray, white: Float,
    ): FloatArray {
        require(channels.size == 4 && step >= 2 && step % 2 == 0)
        val (blackQ, denQ) = VfLevels.toFixedQ6(levels, white)
        val hq = step / 2
        val quadsX = frameW * hq
        val quadsY = frameH * hq

        fun fetchN(qx: Int, qy: Int, ch: Int): Float {
            val code = codes[(qy + (ch shr 1 and 1)) * pitch + qx + (ch and 1)]
            val num = code * 64 - blackQ[ch]
            if (num <= 0) return 0f
            if (num >= denQ[ch]) return 1f
            return num.toFloat() / denQ[ch].toFloat()
        }

        fun quadN(kx: Int, ky: Int, slot: Int): Float {
            val qx = quadBaseX + 2 * kx.coerceIn(0, quadsX - 1)
            val qy = quadBaseY + 2 * ky.coerceIn(0, quadsY - 1)
            return fetchN(qx, qy, channels[slot])
        }

        val out = FloatArray(frameW * frameH * 4)
        val drx = 1 - 2 * (channels[0] and 1)
        val dry = 1 - 2 * (channels[0] shr 1 and 1)
        val dbx = 1 - 2 * (channels[3] and 1)
        val dby = 1 - 2 * (channels[3] shr 1 and 1)
        for (j in 0 until frameH) {
            for (i in 0 until frameW) {
                val kx = i * hq
                val ky = j * hq
                val o = (j * frameW + i) * 4
                if (hq == 1) {
                    out[o] = (9f * quadN(kx, ky, 0) + 3f * quadN(kx + drx, ky, 0) +
                        3f * quadN(kx, ky + dry, 0) + quadN(kx + drx, ky + dry, 0)) * (1f / 16f)
                    out[o + 1] = quadN(kx, ky, 1)
                    out[o + 2] = quadN(kx, ky, 2)
                    out[o + 3] = (9f * quadN(kx, ky, 3) + 3f * quadN(kx + dbx, ky, 3) +
                        3f * quadN(kx, ky + dby, 3) + quadN(kx + dbx, ky + dby, 3)) * (1f / 16f)
                } else {
                    for (s in 0..3) {
                        var sum = 0f
                        for (y in 0 until hq) for (x in 0 until hq) sum += quadN(kx + x, ky + y, s)
                        out[o + s] = sum / (hq * hq)
                    }
                }
            }
        }
        return out
    }
}
