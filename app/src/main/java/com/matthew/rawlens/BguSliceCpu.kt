// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * CPU fallback for the BGU slice (step 7): the same slice math as the GL
 * shader ([BguSlice]), evaluated on the CPU over a CPU superpixel guide
 * ([VfCpuNeon]) for devices without working Vulkan. Output feeds the SAME GL
 * present path: RGBA bytes in quad layout (R, G, G, B) uploaded as the guide
 * texture with an identity grid, so the shader's green-merge and affine
 * become transparent and rotation/upscale/present are reused untouched.
 *
 * [sliceScalar] is the pure reference (JVM-tested); [slice] runs the native
 * NEON implementation (instrumented-parity-tested against the scalar).
 */
internal object BguSliceCpu {
    /** Same .so as the CPU guide; fallback needs both or neither. */
    val available: Boolean = VfCpuNeon.available

    /** 1x1x1 identity grid: every cell maps (r, g, b) to itself. */
    fun identityGrid(): BguGrid {
        val coeffs = FloatArray(12)
        coeffs[0] = 1f
        coeffs[5] = 1f
        coeffs[10] = 1f
        return BguGrid(1, 1, 1, coeffs)
    }

    /**
     * Reference slice over interleaved RGBA quad [guide] ([w] x [h], bytes
     * R/Gr/Gb/B per texel): guide merge, luma, trilinear grid lookup with
     * GL CLAMP_TO_EDGE semantics, affine, clamp, optional IGN dither.
     * [scale] = (lowW / s, lowH / s, 1 / r), same values as the GL uniforms.
     * [step] nearest-subsamples the guide (2 = half-res fallback output).
     * Returns interleaved (R, G, G, B) bytes for the identity-grid present.
     */
    fun sliceScalar(
        guide: ByteArray, w: Int, h: Int, grid: BguGrid,
        scaleX: Float, scaleY: Float, scaleZ: Float, dither: Boolean,
        step: Int = 1
    ): ByteArray {
        require(w > 0 && h > 0 && guide.size == w * h * 4)
        require(scaleX > 0f && scaleY > 0f && scaleZ > 0f)
        require(step == 1 || step == 2)
        require(w % step == 0 && h % step == 0)
        val ow = w / step
        val oh = h / step
        val out = ByteArray(ow * oh * 4)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                val gi = ((oy * step) * w + ox * step) * 4
                val oi = (oy * ow + ox) * 4
                val gr = (guide[gi].toInt() and 0xff) / 255f
                val gg = ((guide[gi + 1].toInt() and 0xff) + (guide[gi + 2].toInt() and 0xff)) * 0.5f / 255f
                val gb = (guide[gi + 3].toInt() and 0xff) / 255f
                val luma = (0.25f * gr + 0.5f * gg + 0.25f * gb).coerceIn(0f, 1f)
                // Texel-center coords over the OUTPUT span (GL texel
                // (ox+0.5)/ow); u_texel = cell.
                val cellX = (ox + 0.5f) / ow * scaleX
                val cellY = (oy + 0.5f) / oh * scaleY
                val cellZ = luma * scaleZ
                val (x0, x1, fx) = clampAxis(cellX, grid.gw)
                val (y0, y1, fy) = clampAxis(cellY, grid.gh)
                val (z0, z1, fz) = clampAxis(cellZ, grid.gz)
                var r0 = FloatArray(4)
                var r1 = FloatArray(4)
                var r2 = FloatArray(4)
                for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
                    val wt = (if (dx == 0) 1 - fx else fx) *
                        (if (dy == 0) 1 - fy else fy) * (if (dz == 0) 1 - fz else fz)
                    if (wt == 0f) continue
                    val cx = if (dx == 0) x0 else x1
                    val cy = if (dy == 0) y0 else y1
                    val cz = if (dz == 0) z0 else z1
                    for (k in 0..3) {
                        r0[k] += wt * grid.at(cx, cy, cz, 0, k)
                        r1[k] += wt * grid.at(cx, cy, cz, 1, k)
                        r2[k] += wt * grid.at(cx, cy, cz, 2, k)
                    }
                }
                var er = (gr * r0[0] + gg * r0[1] + gb * r0[2] + r0[3]).coerceIn(0f, 1f)
                var eg = (gr * r1[0] + gg * r1[1] + gb * r1[2] + r1[3]).coerceIn(0f, 1f)
                var eb = (gr * r2[0] + gg * r2[1] + gb * r2[2] + r2[3]).coerceIn(0f, 1f)
                if (dither) {
                    // Same IGN as the shader; output coords stand in for
                    // gl_FragCoord (pattern differs, still white noise).
                    er = (er + (ign(ox + 0.5f, oy + 0.5f, 0f) - 0.5f) / 255f).coerceIn(0f, 1f)
                    eg = (eg + (ign(ox + 0.5f, oy + 0.5f, 1f) - 0.5f) / 255f).coerceIn(0f, 1f)
                    eb = (eb + (ign(ox + 0.5f, oy + 0.5f, 2f) - 0.5f) / 255f).coerceIn(0f, 1f)
                }
                out[oi] = ((er * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte()
                out[oi + 1] = ((eg * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte()
                out[oi + 2] = out[oi + 1]
                out[oi + 3] = ((eb * 255f + 0.5f).toInt().coerceIn(0, 255)).toByte()
            }
        }
        return out
    }

    /** GL LINEAR + CLAMP_TO_EDGE on one axis: (i0, i1, frac) in texel units. */
    internal fun clampAxis(cell: Float, dim: Int): Triple<Int, Int, Float> {
        if (cell <= 0f || dim <= 1) return Triple(0, 0, 0f)
        if (cell >= dim - 1) {
            val i = dim - 1
            return Triple(i, i, 0f)
        }
        val i0 = cell.toInt()
        return Triple(i0, i0 + 1, cell - i0)
    }

    /** Interleaved gradient noise, same constants as the slice shader. */
    internal fun ign(px: Float, py: Float, ch: Float): Float {
        val d = (px + ch * 19.19f) * 0.06711056f + (py + ch * 19.19f) * 0.00583715f
        val f1 = d - kotlin.math.floor(d)
        val m = 52.9829189f * f1
        return m - kotlin.math.floor(m)
    }

    /**
     * Native slice into [out] (direct RGBA bytes, ow*oh*4 remaining for
     * ow=w/[step], oh=h/[step]). [gridCoeffs] is gw*gh*gz*12 planar
     * (x-fastest, ch=row*4+col). Returns 0 on success, else a
     * [VfCpuNeon]-style code.
     */
    fun slice(
        guide: ByteBuffer, w: Int, h: Int,
        gridCoeffs: FloatArray, gw: Int, gh: Int, gz: Int,
        scaleX: Float, scaleY: Float, scaleZ: Float, dither: Boolean,
        out: ByteBuffer, step: Int = 1
    ): Int {
        require(w > 0 && h > 0 && gw > 0 && gh > 0 && gz > 0)
        require(scaleX > 0f && scaleY > 0f && scaleZ > 0f)
        require(step == 1 || step == 2)
        require(w % step == 0 && h % step == 0)
        require(guide.isDirect && out.isDirect)
        require(guide.remaining() >= w * h * 4)
        require(out.remaining() >= (w / step) * (h / step) * 4)
        require(gridCoeffs.size == gw * gh * gz * 12)
        if (!available) return VfCpuNeon.NOT_DIRECT
        return sliceNative(
            guide, guide.position(), w, h,
            gridCoeffs, gw, gh, gz, scaleX, scaleY, scaleZ,
            if (dither) 1 else 0, out, out.position(), step
        )
    }

    private external fun sliceNative(
        guide: ByteBuffer, guideOffset: Int, w: Int, h: Int,
        gridCoeffs: FloatArray, gw: Int, gh: Int, gz: Int,
        scaleX: Float, scaleY: Float, scaleZ: Float, dither: Int,
        out: ByteBuffer, outOffset: Int, step: Int
    ): Int
}
