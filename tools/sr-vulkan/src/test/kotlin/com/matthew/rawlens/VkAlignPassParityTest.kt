// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU/CPU contract for the rewritten 1:1 alignment passes: dispatching
 * `rawsr/flow_upscale` and `rawsr/pyramid_downsample` must reproduce the
 * CPU oracle ([RawSrCoreAlign.upsampleFlow], [RawSrAlignment.downsample])
 * within float32 tolerance, and `rawsr/flow_deflip` must reproduce
 * [RawSrCfaOrientation.remapFieldToSensor] exactly. This pins the shader/host wiring (uniform
 * names, axis semantics, grid math) that unit tests of either side alone
 * cannot see: an unbound size uniform or a strided-vs-dense axis mixup
 * fails here. Needs `:tools:sr-vulkan:compileShaders` first (reads
 * `build/resources/main`).
 */
class VkAlignPassParityTest {
    private fun session(block: VkSession.(VkArena, UploadBuffers) -> Unit) {
        SrVulkan.open().use { vk ->
            val assets = android.content.Context(File("build/resources/main")).assets
            VkProgramCache(vk, assets).use { programs ->
                VkArena(vk).use { arena ->
                    val uploads = UploadBuffers()
                    VkSession(vk, programs, uploads).block(arena, uploads)
                }
            }
        }
    }

    @Test fun flowUpscaleMatchesCpuAllModes() {
        // Distinct prior so nearest/bilinear/bicubic disagree with each
        // other: a mode mixup or a coordinate bug fails loudly.
        val sw = 3
        val sh = 2
        val priorValues = DoubleArray(sw * sh * 2) { i -> 0.11 + i * 0.37 }
        val prior = RawSrAlignment.LevelFlow(sw, sh, priorValues)
        // repeat 2, factor 4 (newTs/prevTs = 2): 6x4 full plus a 7x5
        // oversized grid (zero-pad past the footprint on GPU, crop by
        // loop bounds on CPU — identical by construction).
        val grids = listOf(6 to 4, 7 to 5)
        for (mode in RawSrAlignmentConfig.FlowUpscaleMode.values()) {
            val uMode = when (mode) {
                RawSrAlignmentConfig.FlowUpscaleMode.NEAREST -> 0
                RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR -> 1
                RawSrAlignmentConfig.FlowUpscaleMode.BICUBIC -> 2
            }
            for ((dw, dh) in grids) {
                val want = RawSrCoreAlign.upsampleFlow(prior, dw, dh, 4, 16, 8, mode)
                var maxAbs = 0.0
                session { arena, uploads ->
                    val tex = arena.texture(sw, sh, GLES30.GL_RGBA32F)
                    tex.uploadRgba32f(FloatArray(sw * sh * 4) { i ->
                        if (i % 4 < 2) priorValues[(i / 4) * 2 + i % 2].toFloat() else 0f
                    }, uploads)
                    val out = arena.texture(dw, dh, GLES30.GL_RGBA32F)
                    pass("rawsr/flow_upscale.glsl") {
                        sampler("u_prior", tex)
                        ivec2("u_src_grid", sw, sh)
                        ivec2("u_dst_grid", dw, dh)
                        integer("u_repeat", 2)
                        integer("u_factor", 4)
                        integer("u_mode", uMode)
                        image(0, out, GLES30.GL_RGBA32F); dispatch(dw, dh, 1, 1)
                    }
                    val got = out.downloadRgba32f()
                    for (i in 0 until dw * dh) {
                        for (c in 0..1) {
                            val e = want.values[i * 2 + c]
                            val g = got[i * 4 + c].toDouble()
                            maxAbs = maxOf(maxAbs, abs(g - e))
                            assertTrue("$mode grid ${dw}x$dh tile $i ch $c: cpu=$e gpu=$g",
                                abs(g - e) <= 2e-5 + 2e-5 * abs(e))
                        }
                    }
                }
                assertTrue("$mode grid ${dw}x$dh maxAbs=$maxAbs too large", maxAbs < 1e-4)
            }
        }
    }

    @Test fun pyramidDownsampleMatchesCpu() {
        // Textured ramp: every tap contributes, so a wrong axis stride
        // or OOB tap pattern fails loudly.
        val w = 40
        val h = 36
        val grey = RawSrGrayImage(w, h, FloatArray(w * h) { i ->
            (0.13 + 0.61 * (i % w).toDouble() / w + 0.07 * ((i / w) % 5)).toFloat()
        })
        for (factor in listOf(2, 4)) {
            val want = RawSrAlignment.downsample(grey, factor)
            val kernel = RawSrAlignment.gaussianKernel1d(factor)
            val weights = kernel.map { it.toFloat() }.toFloatArray()
            val radius = kernel.size / 2
            val convW = w - 2 * radius
            var maxAbs = 0.0
            session { arena, uploads ->
                val src = arena.texture(w, h, GLES30.GL_R32F)
                src.uploadR32f(grey.values, uploads)
                // Axis 0: dense x-conv to convW x h (host pyramid chain).
                val horizontal = arena.texture(convW, h, GLES30.GL_R32F)
                pass("rawsr/pyramid_downsample.glsl") {
                    sampler("u_source", src)
                    ivec2("u_size", convW, h)
                    integer("u_factor", factor); integer("u_axis", 0)
                    floats("u_weights[0]", weights)
                    image(0, horizontal, GLES30.GL_R32F); dispatch(convW, h, 8, 8)
                }
                // Axis 1: y-conv fused with the both-axes strided take.
                val out = arena.texture(want.width, want.height, GLES30.GL_R32F)
                pass("rawsr/pyramid_downsample.glsl") {
                    sampler("u_source", horizontal)
                    ivec2("u_size", want.width, want.height)
                    integer("u_factor", factor); integer("u_axis", 1)
                    floats("u_weights[0]", weights)
                    image(0, out, GLES30.GL_R32F); dispatch(want.width, want.height, 8, 8)
                }
                val got = out.downloadR32f()
                for (i in want.values.indices) {
                    val e = want.values[i].toDouble()
                    val g = got[i].toDouble()
                    maxAbs = maxOf(maxAbs, abs(g - e))
                    assertTrue("factor $factor px $i: cpu=$e gpu=$g",
                        abs(g - e) <= 2e-5 + 2e-5 * abs(e))
                }
            }
            assertTrue("factor $factor maxAbs=$maxAbs too large", maxAbs < 1e-4)
        }
    }

    @Test fun flowRegularizeMatchesCpu() {
        // Small grid with a sub-sigma ripple (blends), an isolated spike
        // (holds: bilateral self-weight), and a supra-sigma wall (holds),
        // so a box-blur mixup or a missing range kernel fails loudly.
        // CPU and GPU both accumulate float32; exp() implementations may
        // differ by ulps, pinned at 1e-4 abs.
        val gw = 6
        val gh = 5
        val dxAt = { tx: Int, ty: Int ->
            when {
                tx == 4 && ty == 2 -> 3f // isolated spike
                tx >= 5 -> 5f // supra-sigma wall (dx step 5)
                else -> (tx * 0.1f + ty * 0.05f) // gentle ripple
            }
        }
        val tiles = List(gw * gh) { i ->
            val tx = i % gw
            val ty = i / gw
            RawSrTileFlow(0f, 0f, dxAt(tx, ty), ty * 0.07f - 0.1f, i * 0.01f, i % 3 != 0)
        }
        val field = RawSrAlignmentField(gw * 16, gh * 16, 16, gw, gh, tiles)
        val want = field.bilateralFiltered(1f)
        session { arena, uploads ->
            val tex = arena.texture(gw, gh, GLES30.GL_RGBA32F)
            tex.uploadRgba32f(FloatArray(gw * gh * 4) { i ->
                val t = tiles[i / 4]
                when (i % 4) {
                    0 -> t.dx
                    1 -> t.dy
                    2 -> t.residual
                    else -> if (t.reliable) 1f else 0f
                }
            }, uploads)
            val out = arena.texture(gw, gh, GLES30.GL_RGBA32F)
            pass("rawsr/flow_regularize.glsl") {
                sampler("u_flow", tex)
                float("u_sigma", 1f)
                image(0, out, GLES30.GL_RGBA32F); dispatch(gw, gh, 8, 8)
            }
            val got = out.downloadRgba32f()
            var maxAbs = 0.0
            for (i in 0 until gw * gh) {
                val t = want.tiles[i]
                val e = doubleArrayOf(t.dx.toDouble(), t.dy.toDouble(),
                    t.residual.toDouble(), if (t.reliable) 1.0 else 0.0)
                for (c in 0..3) {
                    val d = abs(got[i * 4 + c].toDouble() - e[c])
                    maxAbs = maxOf(maxAbs, d)
                    assertTrue("tile $i ch $c: cpu=${e[c]} gpu=${got[i * 4 + c]}", d < 1e-4)
                }
            }
        }
    }

    @Test fun flowDeflipMatchesCpu() {
        // Non-square grid with distinct values per texel and channel, so
        // an axis mixup or a dropped sign fails loudly. Remap is exact
        // (moves + negation), pinned at 0 tolerance. The non-multiple
        // height case pins the majority-tile rule (grid-1-t is off by
        // one tile row there); the divisible case pins the exact mirror.
        val cases = listOf(
            Triple(5, 3, 48), // 80x48 divisible
            Triple(5, 2, 20) // 80x20: H % 16 != 0
        )
        val modes = mapOf(
            1 to RawSrCfaOrientation.Flip.HFLIP,
            2 to RawSrCfaOrientation.Flip.VFLIP,
            3 to RawSrCfaOrientation.Flip.ROT180)
        for ((gw, gh, imgH) in cases) {
            val imgW = gw * 16
            val tiles = List(gw * gh) { i ->
                RawSrTileFlow(0f, 0f, i * 1.5f + 0.25f, -(i * 0.7f + 0.5f), i * 0.01f, i % 2 == 0)
            }
            // Production shape: the field carries padded (tile-multiple)
            // dims; the remap takes the unpadded sensor dims.
            val field = RawSrAlignmentField(imgW, gh * 16, 16, gw, gh, tiles)
            for ((uMode, flip) in modes) {
                val want = RawSrCfaOrientation.remapFieldToSensor(field, flip, imgW, imgH)
                session { arena, uploads ->
                    val tex = arena.texture(gw, gh, GLES30.GL_RGBA32F)
                    tex.uploadRgba32f(FloatArray(gw * gh * 4) { i ->
                        val t = tiles[i / 4]
                        when (i % 4) {
                            0 -> t.dx
                            1 -> t.dy
                            2 -> t.residual
                            else -> if (t.reliable) 1f else 0f
                        }
                    }, uploads)
                    val out = arena.texture(gw, gh, GLES30.GL_RGBA32F)
                    pass("rawsr/flow_deflip.glsl") {
                        sampler("u_flow", tex)
                        integer("u_mode", uMode)
                        integer("u_img_w", imgW)
                        integer("u_img_h", imgH)
                        integer("u_tile", 16)
                        image(0, out, GLES30.GL_RGBA32F); dispatch(gw, gh, 8, 8)
                    }
                    val got = out.downloadRgba32f()
                    for (i in 0 until gw * gh) {
                        val t = want.tiles[i]
                        val e = doubleArrayOf(t.dx.toDouble(), t.dy.toDouble(),
                            t.residual.toDouble(), if (t.reliable) 1.0 else 0.0)
                        for (c in 0..3) {
                            val g = got[i * 4 + c].toDouble()
                            assertTrue("case ${imgW}x$imgH mode $uMode tile $i ch $c: cpu=${e[c]} gpu=$g",
                                abs(g - e[c]) == 0.0)
                        }
                    }
                }
            }
        }
    }
}
