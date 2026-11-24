// RawTherapee Capture Sharpening, Vulkan host (rtengine/capturesharpening.cc,
// rtengine/rt_algo.cc). Copyright (c) 2019 Ingo Weyrich, Jacques Desmis
// 2024-2025 (RawTherapee); port RawLens contributors 2026.
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import com.rawlens.srpipeline.stages.RtCaptureSharpen
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Daily-driver capture sharpening for the MFSR merge: RawTherapee's defaults
 * (auto radius, auto contrast, and the iteration check), with
 * the Richardson-Lucy work on the GPU (spirv/rtcs.vk.glsl). The CPU only
 * computes luminance per band, the auto contrast threshold (block sums, one
 * read of the image) and the final RGB ratio Ynew / Yold.
 *
 * [RtCaptureSharpen] is the CPU reference of the same algorithm.
 */
internal object RtCaptureSharpenVulkan {
    private const val BAND = 1024
    private const val HALO = 48 // >= tile + border shift, mask support (2 + 6) and deconv border (8)
    private const val UBO = 176

    data class Stats(val radius: Float, val kernelRadius: Int, val contrastThreshold: Float,
                     val gpuMs: Long, val cpuMs: Long)

    /** RT kernel class -> (shader radius, tile, border); see tools/build_rtcs_shader.sh. */
    private fun variant(sigma: Float): Triple<Int, Int, Int> = when {
        sigma < 0.6f -> Triple(1, 32, 5)
        sigma <= 0.84f -> Triple(2, 32, 5)
        sigma <= 1.15f -> Triple(3, 32, 5)
        sigma <= 1.5f -> Triple(4, 24, 8)
        else -> Triple(6, 24, 8)
    }

    private fun gauss1d(sigma: Float, r: Int): FloatArray {
        val k = FloatArray(2 * r + 1) { val t = (it - r) / sigma; exp(-0.5f * t * t) }
        val s = k.sum()
        return FloatArray(k.size) { k[it] / s }
    }

    /**
     * Sharpens [rgb] (interleaved linear camera RGB, w x h, white = 1) in
     * place. [radius]: RT auto radius in output pixels ([RtCaptureSharpen.autoRadiusBayer]
     * x scale); [wbR], [wbB]: white-balance gains relative to green, used for
     * luminance only. [spirv] loads `rtcs_r<R>.spv`.
     */
    fun apply(vk: VulkanHub.Handle, spirv: (String) -> ByteArray, rgb: FloatBuffer, w: Int, h: Int,
              radius: Float, wbR: Float, wbB: Float, iterations: Int = 20): Stats? {
        if (radius.isNaN() || radius <= 0f) return null
        val sigma = min(radius, 2f)
        val (kr, tile, border) = variant(sigma)
        if (w < 2 * border + tile || h < 2 * border + tile) return null
        val tCpu0 = System.nanoTime()
        val stride = w * 3
        val luma = Luma(wbR, wbB)
        val contrast = autoContrast(rgb, w, h, luma)
        var gpuNs = 0L

        val module = vk.loadModule(spirv("rtcs_r$kr.spv"))
        val pipeline = try {
            vk.createPipeline(module, 4, UBO, intArrayOf(0, 1, 2, 3), intArrayOf(1, 1, 1, 1))
        } catch (t: Throwable) { vk.destroyModule(module); throw t }
        val maxRows = min(h, BAND + 2 * HALO)
        val images = LongArray(4)
        try {
            for (i in 0 until 4) images[i] = vk.createImage(w, maxRows, 0)
            for (i in 0 until 4) vk.bindImage(pipeline, i, images[i])
            val yBuf = ByteBuffer.allocateDirect(w * maxRows * 4).order(ByteOrder.nativeOrder())
            val lBuf = ByteBuffer.allocateDirect(w * maxRows * 4).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(w * BAND * 4).order(ByteOrder.nativeOrder())
            val ubo = ByteBuffer.allocateDirect(UBO).order(ByteOrder.nativeOrder())
            val k = gauss1d(sigma, kr)
            val g = gauss1d(2f, 6)
            var saved: FloatArray? = null // original pixels of the previous band's last HALO rows
            var savedFrom = 0
            var cs = border
            while (cs < h - border) {
                val ce = min(h - border, cs + BAND)
                val lo = max(0, cs - HALO)
                val hi = min(h, ce + HALO)
                val rows = hi - lo
                // Luminance of the original pixels for [lo, hi).
                val yf = yBuf.asFloatBuffer(); val lf = lBuf.asFloatBuffer()
                val prev = saved; val prevFrom = savedFrom
                parallelRows(lo, hi) { from, to ->
                    val row = FloatArray(stride)
                    val yr = FloatArray(w)
                    val lr = FloatArray(w)
                    val input = rgb.duplicate()
                    val dy = yf.duplicate().apply { position((from - lo) * w) }
                    val dl = lf.duplicate().apply { position((from - lo) * w) }
                    for (y in from until to) {
                        if (prev != null && y < cs) {
                            prev.copyInto(row, 0, (y - prevFrom) * stride, (y - prevFrom + 1) * stride)
                        } else {
                            input.position(y * stride)
                            input.get(row)
                        }
                        luma.row(row, w, yr, lr)
                        dy.put(yr)
                        dl.put(lr)
                    }
                }
                val t0 = System.nanoTime()
                vk.writeImage(images[0], yBuf.apply { rewind() })
                vk.writeImage(images[1], lBuf.apply { rewind() })
                fun dispatch(mode: Int, gx: Int, gy: Int, tilesX: Int = 0) {
                    ubo.clear()
                    ubo.putInt(w).putInt(rows).putInt(mode).putInt(tilesX)
                    ubo.putInt(cs - lo).putInt(ce - lo).putInt(0).putInt(0)
                    ubo.putFloat(contrast).putFloat(iterations.toFloat()).putFloat(1f).putFloat(0f)
                    for (i in 0 until 16) ubo.putFloat(if (i < k.size) k[i] else 0f)
                    for (i in 0 until 16) ubo.putFloat(if (i < g.size) g[i] else 0f)
                    ubo.flip(); vk.writeUniforms(pipeline, ubo)
                    vk.dispatch(pipeline, gx, gy, 1)
                }
                dispatch(0, (w + 255) / 256, rows)
                if (contrast > 0f) dispatch(1, (w + 255) / 256, rows)
                dispatch(2, (w + 255) / 256, rows)
                val tilesX = (w - 2 * border + tile - 1) / tile
                val tilesY = (ce - cs + tile - 1) / tile
                dispatch(3, tilesX, tilesY, tilesX)
                outBuf.clear()
                // The native read wants a buffer of exactly the region's size.
                vk.readImageRegion(images[1], 0, cs - lo, w, ce - cs,
                    outBuf.duplicate().apply { limit(w * (ce - cs) * 4) }.slice())
                gpuNs += System.nanoTime() - t0
                // Snapshot the original rows the next band's halo needs, then write.
                savedFrom = max(cs, ce - HALO)
                saved = FloatArray((ce - savedFrom) * stride).also { s ->
                    val d = rgb.duplicate(); d.position(savedFrom * stride); d.get(s)
                }
                val nf = outBuf.asFloatBuffer()
                parallelRows(cs, ce) { from, to ->
                    val row = FloatArray(stride)
                    val input = rgb.duplicate().apply { position(from * stride) }
                    val output = rgb.duplicate().apply { position(from * stride) }
                    val gains = nf.duplicate().apply { position((from - cs) * w) }
                    repeat(to - from) {
                        input.get(row)
                        for (x in 0 until w) {
                            val f = gains.get()
                            row[3 * x] *= f
                            row[3 * x + 1] *= f
                            row[3 * x + 2] *= f
                        }
                        output.put(row)
                    }
                }
                cs = ce
            }
        } finally {
            for (img in images) if (img != 0L) vk.destroyImage(img)
            vk.destroyPipeline(pipeline)
            vk.destroyModule(module)
            vk.releaseStaging()
        }
        val totalNs = System.nanoTime() - tCpu0
        return Stats(sigma, kr, contrast, gpuNs / 1_000_000, (totalNs - gpuNs) / 1_000_000)
    }

    private fun parallelRows(from: Int, to: Int, block: (Int, Int) -> Unit) {
        val rowsPerTask = 16
        IntStream.range(0, (to - from + rowsPerTask - 1) / rowsPerTask).parallel().forEach { task ->
            val start = from + task * rowsPerTask
            block(start, min(start + rowsPerTask, to))
        }
    }

    /** RT RGB2Y (Rec.2020, clamped) and RGB2L (sRGB Y -> CIE L, 0..32768), on 0..65535 data. */
    class Luma(private val wbR: Float, private val wbB: Float) {
        fun row(px: FloatArray, w: Int, y: FloatArray?, l: FloatArray?) {
            for (x in 0 until w) {
                val r0 = px[3 * x]; val g0 = px[3 * x + 1]; val b0 = px[3 * x + 2]
                val rr = r0 * wbR * 65535f; val gg = g0 * 65535f; val bb = b0 * wbB * 65535f
                if (l != null) l[x] = labL(0.212671f * rr + 0.715160f * gg + 0.072169f * bb)
                if (y != null) {
                    val yv = 0.2627f * max(rr, 0f) + 0.6780f * max(gg, 0f) + 0.0593f * max(bb, 0f)
                    // Sign bit marks clipping in any channel.
                    y[x] = if (max(r0, max(g0, b0)) >= 0.95f) -max(yv, 1e-20f) else yv
                }
            }
        }
        private fun labL(y: Float): Float {
            if (y < 0f) return 327.68f * (24389f / 27f * y / 65535f)
            val t = y / 65535f
            val f = if (t > 216f / 24389f) kotlin.math.cbrt(t) else (24389f / 27f * t + 16f) / 116f
            return 327.68f * (116f * f - 16f)
        }
    }

    // ---- RT buildBlendMask autoContrast, with block sums instead of rescans.

    private fun lumaRows(rgb: FloatBuffer, w: Int, y0: Int, y1: Int, luma: Luma): Array<FloatArray> {
        val row = FloatArray(w * 3)
        val input = rgb.duplicate().apply { position(y0 * row.size) }
        return Array(y1 - y0) {
            input.get(row)
            FloatArray(w).also { luma.row(row, w, null, it) }
        }
    }

    private fun blendFactor(v: Float, t: Float): Float {
        val x = -16f + (16f / t) * v
        return 0.5f * (1f + x / kotlin.math.sqrt(1f + x * x))
    }

    /** RT calcContrastThreshold on an L tile at (0, 0) of [l] (columns from [x0]). */
    private fun contrastThreshold(l: Array<FloatArray>, x0: Int, ts: Int): Float {
        val n = ts - 4
        val c = FloatArray(n * n)
        for (j in 2 until ts - 2) for (i in 2 until ts - 2) {
            val x = x0 + i
            val a = l[j][x + 1] - l[j][x - 1]; val b = l[j + 1][x] - l[j - 1][x]
            val e = l[j][x + 2] - l[j][x - 2]; val d = l[j + 2][x] - l[j - 2][x]
            c[(j - 2) * n + i - 2] = kotlin.math.sqrt(a * a + b * b + e * e + d * d) * (0.0625f / 327.68f)
        }
        val limit = n * n / 100f
        var k = 1
        while (k < 100) {
            val t = k / 100f
            var sum = 0f
            for (v in c) sum += blendFactor(v, t)
            if (sum <= limit) break
            k++
        }
        return (k + 1) / 100f
    }

    /** RT tile variance (normalised by the mean) or +inf when excluded. */
    private fun variance(sum: Double, sumSq: Double, n: Int): Float {
        val avg = sum / n
        if (avg < 2000.0 || avg > 20000.0) return Float.POSITIVE_INFINITY
        val v = ((sumSq - sum * avg) / (n * avg)).toFloat()
        return if (v < 0.5f) Float.POSITIVE_INFINITY else v
    }

    fun autoContrast(rgb: FloatBuffer, w: Int, h: Int, luma: Luma): Float {
        // 10 x 10 block sums of L (and L^2): pass 0 tiles (80) are 8 x 8
        // blocks, pass 1 tiles (40, skip 10) are 4 x 4 blocks.
        val bw = w / 10; val bh = h / 10
        val s1 = DoubleArray(bw * bh); val s2 = DoubleArray(bw * bh)
        IntStream.range(0, bh).parallel().forEach { by ->
            val l = lumaRows(rgb, w, by * 10, by * 10 + 10, luma)
            for (bx in 0 until bw) {
                var a = 0.0; var b = 0.0
                for (r in 0 until 10) for (x in bx * 10 until bx * 10 + 10) { val v = l[r][x].toDouble(); a += v; b += v * v }
                s1[by * bw + bx] = a; s2[by * bw + bx] = b
            }
        }
        fun tile(by: Int, bx: Int, nb: Int): Float {
            var a = 0.0; var b = 0.0
            for (y in by until by + nb) for (x in bx until bx + nb) { a += s1[y * bw + x]; b += s2[y * bw + x] }
            return variance(a, b, nb * nb * 100)
        }
        // Pass 0: 80 px tiles on an 80 px grid.
        var best = Float.POSITIVE_INFINITY; var bi = 0; var bj = 0
        for (i in 0 until h / 80) for (j in 0 until w / 80) {
            val v = tile(i * 8, j * 8, 8)
            if (v < best) { best = v; bi = i; bj = j }
        }
        if (best <= 1f) return contrastThreshold(lumaRows(rgb, w, bi * 80, bi * 80 + 80, luma), bj * 80, 80)
        // Pass 1: 40 px tiles on a 10 px grid, then every 1 px offset within +-10.
        best = Float.POSITIVE_INFINITY; bi = 0; bj = 0
        for (i in 0 until h / 10 - 3) for (j in 0 until w / 10 - 3) {
            val v = tile(i, j, 4)
            if (v < best) { best = v; bi = i; bj = j }
        }
        val minY = 10 * bi; val minX = 10 * bj
        val ys = max(minY - 10, 0); val xs = max(minX - 10, 0)
        val ye = min(minY + 10, h - 40); val xe = min(minX + 10, w - 40)
        val l = lumaRows(rgb, w, ys, ye + 40, luma)
        var mv = Float.POSITIVE_INFINITY; var my = ys; var mx = xs
        for (ty in ys..ye) for (tx in xs..xe) {
            var a = 0.0; var b = 0.0
            for (r in ty - ys until ty - ys + 40) for (x in tx until tx + 40) { val v = l[r][x].toDouble(); a += v; b += v * v }
            val v = variance(a, b, 1600)
            if (v < mv) { mv = v; my = ty; mx = tx }
        }
        return if (mv <= 8f) contrastThreshold(Array(40) { l[my - ys + it] }, mx, 40) else 0f
    }
}
