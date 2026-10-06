// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU/GPU contract for `rawsr/merge_accumulate` (bilinear-everywhere
 * port): the shader blends flow bilinearly at the raw source and snaps
 * robustness to the reference nearest quad, exactly like the CPU oracle
 * ([RawSrBayerMerge]), including the chroma latch guard
 * ([RawSrBayerMerge.CHROMA_SIGMA_MPY]). Each test dispatches the moving
 * accumulate (r step / uniform r), the reference accumulate, and finalize
 * with synthetic textures, then pins full-field CPU/GPU parity (float32
 * vs float64 twin tolerance) — on the r-step render as well as the
 * uniform renders. Needs `:tools:sr-vulkan:compileShaders` first (reads
 * `build/resources/main`).
 */
class VkMergeAccumulateBlendTest {
    private val w = 32
    private val h = 24
    private val qw = w / 2
    private val qh = h / 2

    private fun ramp(sx: Int, color: CfaColor): Float =
        0.1f + 0.6f * sx.toFloat() / w + color.ordinal * 0.05f

    private fun cpuFrame(
        rAt: (qx: Int) -> Float,
        dxAt: (tx: Int, ty: Int) -> Float = { _, _ -> 2f }
    ): RawSrBayerMerge.MergeFrame {
        val samples = FloatArray(w * h) { i ->
            ramp(i % w, BayerPattern.RGGB.colorAt(i % w, i / w))
        }
        // Raw-lattice field shared by both sides: dx=2 raw px matches
        // the GPU flow texel R=2 verbatim (raw units, source += shift).
        val flow = RawSrAlignmentField(w, h, 16, 2, 2,
            List(4) { i -> RawSrTileFlow(0f, 0f, dxAt(i % 2, i / 2), 0f, 0f, true) })
        val robust = RawSrRobustness.FrameRobustness(
            qw, qh, FloatArray(qw * qh) { i -> rAt(i % qw) }, IntArray(qw * qh))
        return RawSrBayerMerge.MergeFrame(w, h, samples, BayerPattern.RGGB, 0, 0,
            RawSrKernelCovariance.MatrixField(qw, qh, FloatArray(qw * qh * 4) { i ->
                if (i % 4 == 0 || i % 4 == 3) 1.0f else 0f
            }), flow, robust)
    }

    private fun cpuMerge(
        rAt: (qx: Int) -> Float,
        scale: RawSrLinearScale = RawSrLinearScale.X1,
        dxAt: (tx: Int, ty: Int) -> Float = { _, _ -> 2f }
    ): RawSrBayerMerge.MergeResult {
        val ref = cpuFrame({ _ -> 1f }).copy(flow = null, robustness = null)
        // Default latch guard on both sides (the GPU binds u_chroma_z_scale
        // from the same merge default below).
        return RawSrBayerMerge.merge(ref, listOf(cpuFrame(rAt, dxAt)), scale = scale)
    }

    /** Full GPU merge (moving + reference accumulate, finalize); returns merged RGBA + fallback. */
    private fun gpuMerge(
        rValues: FloatArray,
        scale: RawSrLinearScale = RawSrLinearScale.X1,
        flowGw: Int = w,
        flowGh: Int = h,
        flowTs: Int = 1,
        dxAt: (tx: Int, ty: Int) -> Float = { _, _ -> 2f }
    ): Pair<FloatArray, FloatArray> {
        SrVulkan.open().use { vk ->
            val assets = android.content.Context(File("build/resources/main")).assets
            VkProgramCache(vk, assets).use { programs ->
                VkArena(vk).use { arena ->
                    val uploads = UploadBuffers()
                    fun r32f(ww: Int, hh: Int, values: FloatArray): VkImage =
                        arena.texture(ww, hh, GLES30.GL_R32F).also {
                            it.uploadR32f(values, uploads)
                        }
                    fun rgba(ww: Int, hh: Int, values: FloatArray): VkImage =
                        arena.texture(ww, hh, GLES30.GL_RGBA32F).also {
                            it.uploadRgba32f(values, uploads)
                        }
                    val samples = FloatArray(w * h) { i ->
                        ramp(i % w, BayerPattern.RGGB.colorAt(i % w, i / w))
                    }
                    val unitCov = FloatArray(qw * qh * 4) { i ->
                        if (i % 4 == 0 || i % 4 == 3) 1.0f else 0f
                    }
                    val (outW, outH) = RawSrBayerMerge.planTarget(w, h, scale)
                    val cfa = r32f(w, h, samples)
                    // Flow texel R=2: dx=2 raw px on the raw lattice,
                    // matching the CPU field verbatim (shader:
                    // source += shift). Per-pixel tiles (grid w x h,
                    // tile 1) so every output reads the same vector.
                    val flow = rgba(flowGw, flowGh, FloatArray(flowGw * flowGh * 4) { i ->
                        if (i % 4 == 0) dxAt((i / 4) % flowGw, (i / 4) / flowGw) else 0f
                    })
                    val covariance = rgba(qw, qh, unitCov)
                    val r = r32f(qw, qh, rValues)
                    val num = rgba(outW, outH, FloatArray(outW * outH * 4))
                    val den = rgba(outW, outH, FloatArray(outW * outH * 4))
                    val oob = r32f(outW, outH, FloatArray(outW * outH))
                    val refNum = rgba(outW, outH, FloatArray(outW * outH * 4))
                    val refDen = rgba(outW, outH, FloatArray(outW * outH * 4))
                    val dummy = rgba(1, 1, floatArrayOf(0f, 0f, 0f, 1f))
                    val fc = AmazePipelineContract.cfaUniform(BayerPattern.RGGB)
                    val session = VkSession(vk, programs, uploads)
                    fun accumulate(
                        cfaTex: VkImage, flowTex: VkImage, covTex: VkImage, rTex: VkImage,
                        tileGridW: Int, tileGridH: Int, tileSize: Int,
                        useR: Boolean, isReference: Boolean, numTex: VkImage, denTex: VkImage
                    ) {
                        session.pass("rawsr/merge_accumulate.glsl") {
                            sampler("u_cfa", cfaTex); sampler("u_flow", flowTex)
                            sampler("u_covariance", covTex); sampler("u_r", rTex)
                            sampler("u_num", numTex); sampler("u_den", denTex); sampler("u_oob", oob)
                            ivec2("u_size", outW, outH)
                            ivec2("u_tile_grid", tileGridW, tileGridH)
                            integer("u_tile_size", tileSize)
                            float("u_upscale", scale.factor.toFloat())
                            ivec2("u_guide_size", qw, qh)
                            ivec4("u_fc", fc)
                            integer("u_is_reference", if (isReference) 1 else 0)
                            integer("u_use_r", if (useR) 1 else 0)
                            integer("u_use_chroma", 0)
                            float("u_green_noise_s", 0f)
                            float("u_green_noise_o", 0f)
                            float("u_chroma_z_scale",
                                RawSrCoreKernel.chromaZScale(RawSrBayerMerge.CHROMA_SIGMA_MPY).toFloat())
                            image(0, numTex, GLES30.GL_RGBA32F)
                            image(1, denTex, GLES30.GL_RGBA32F)
                            image(2, oob, GLES30.GL_R32F)
                            dispatch(outW, outH, 8, 8)
                        }
                    }
                    accumulate(cfa, flow, covariance, r, flowGw, flowGh, flowTs, useR = true, isReference = false, num, den)
                    accumulate(cfa, dummy, covariance, dummy, 1, 1, 1, useR = false, isReference = true, refNum, refDen)
                    val merged = arena.texture(outW, outH, GLES30.GL_RGBA32F)
                    val fallback = arena.texture(outW, outH, GLES30.GL_R32F)
                    session.pass("rawsr/merge_finalize.glsl") {
                        sampler("u_num", num); sampler("u_den", den)
                        sampler("u_ref_num", refNum); sampler("u_ref_den", refDen)
                        ivec2("u_size", outW, outH)
                        image(0, merged, GLES30.GL_RGBA32F)
                        image(1, fallback, GLES30.GL_R32F)
                        dispatch(outW, outH, 8, 8)
                    }
                    return merged.downloadRgba32f() to fallback.downloadR32f()
                }
            }
        }
    }

    @Test
    fun gpuRobustnessStepMatchesCpuSnap() {
        fun rStep(qx: Int) = if (qx < 8) 0f else 1f
        val rValues = FloatArray(qw * qh) { i -> rStep(i % qw) }
        val (gpuMixed, gpuFallback) = gpuMerge(rValues)
        assertTrue("GPU fallback must be clean", gpuFallback.all { it == 0f })
        val oracle = cpuMerge(::rStep)
        assertTrue("oracle fallback must be clean", oracle.fallback.none { it })
        // Uniform-r renders carry no step, so CPU/GPU must agree full-field
        // (float32 vs float64 twin tolerance) ...
        val cpuA = cpuMerge({ _ -> 0f })
        val cpuB = cpuMerge({ _ -> 1f })
        val (gpuA, _) = gpuMerge(FloatArray(qw * qh))
        val (gpuB, _) = gpuMerge(FloatArray(qw * qh) { 1f })
        assertCpuGpuParity(cpuA, gpuA, w, h, "uniform-r0")
        assertCpuGpuParity(cpuB, gpuB, w, h, "uniform-r1")
        // ... and the step render agrees full-field too: both sides snap
        // each output pixel to its nearest quad (quad 7 → r = 0, quad 8 →
        // r = 1), so the old blend zone (columns 17..18) is plain parity.
        assertCpuGpuParity(oracle, gpuMixed, w, h, "step")
        // Snap check: the step render equals its own uniform endpoint
        // column by column (exact on CPU, twin tolerance on GPU).
        for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
            val expected = (if (x <= 17) cpuA else cpuB).rgb[(y * w + x) * 3 + c].toDouble()
            val o = oracle.rgb[(y * w + x) * 3 + c].toDouble()
            assertEquals("CPU must snap to its endpoint ($x,$y,$c)", expected, o, 0.0)
            val g = gpuMixed[(y * w + x) * 4 + c].toDouble()
            assertTrue("GPU must snap to its endpoint ($x,$y,$c) cpu=$o gpu=$g",
                abs(g - o) <= 1e-4 + 1e-4 * abs(o))
        }
    }

    /** Full-field CPU/GPU parity (float32 shader vs float64 oracle twin). */
    private fun assertCpuGpuParity(
        cpu: RawSrBayerMerge.MergeResult, gpu: FloatArray, outW: Int, outH: Int, label: String
    ) {
        var maxAbs = 0.0
        for (y in 0 until outH) for (x in 0 until outW) for (c in 0..2) {
            val o = cpu.rgb[(y * outW + x) * 3 + c].toDouble()
            val g = gpu[(y * outW + x) * 4 + c].toDouble()
            val d = abs(g - o)
            maxAbs = maxOf(maxAbs, d)
            assertTrue("$label CPU/GPU mismatch at ($x,$y,$c) cpu=$o gpu=$g",
                d <= 1e-4 + 1e-4 * abs(o))
        }
        assertTrue("$label maxAbs=$maxAbs too large", maxAbs < 1e-3)
    }

    @Test
    fun gpuSrGridMatchesOracleFullField() {
        // Shared √2 output grid on GPU (44x32 here): planned lattice,
        // source-anchored taps, matching the CPU oracle within float32
        // tolerance on every pixel, including across the r step.
        fun rStep(qx: Int) = if (qx < 8) 0f else 1f
        val rValues = FloatArray(qw * qh) { i -> rStep(i % qw) }
        val (gpu, gpuFallback) = gpuMerge(rValues, scale = RawSrLinearScale.SR)
        val (outW, outH) = RawSrBayerMerge.planTarget(w, h, RawSrLinearScale.SR)
        assertEquals(44, outW)
        assertEquals(32, outH)
        assertEquals(outW * outH * 4, gpu.size)
        assertTrue("GPU fallback must be clean", gpuFallback.all { it == 0f })
        val oracle = cpuMerge(::rStep, scale = RawSrLinearScale.SR)
        assertEquals(outW, oracle.width)
        assertEquals(outH, oracle.height)
        val cpuA = cpuMerge({ _ -> 0f }, scale = RawSrLinearScale.SR)
        val cpuB = cpuMerge({ _ -> 1f }, scale = RawSrLinearScale.SR)
        val (gpuA, _) = gpuMerge(FloatArray(qw * qh), scale = RawSrLinearScale.SR)
        val (gpuB, _) = gpuMerge(FloatArray(qw * qh) { 1f }, scale = RawSrLinearScale.SR)
        assertCpuGpuParity(cpuA, gpuA, outW, outH, "SR uniform-r0")
        assertCpuGpuParity(cpuB, gpuB, outW, outH, "SR uniform-r1")
        assertCpuGpuParity(oracle, gpu, outW, outH, "SR step")
    }

    @Test
    fun gpuBilinearSmoothMatchesCpuOracle() {
        // 0.5px step between the 2x2 tile columns: both sides blend
        // across the step unconditionally (bilinear-everywhere, no
        // gates); uniform r = 1 isolates the flow path. Parity first,
        // then non-vacuity: the stepped render must differ from the
        // uniform render (the step fixture actually moves the warp).
        val dxAt = { tx: Int, _: Int -> if (tx == 0) 2.0f else 2.5f }
        val rValues = FloatArray(qw * qh) { 1f }
        val (gpu, gpuFallback) = gpuMerge(rValues, flowGw = 2, flowGh = 2, flowTs = 16,
            dxAt = dxAt)
        assertTrue("GPU fallback must be clean", gpuFallback.all { it == 0f })
        val oracle = cpuMerge({ _ -> 1f }, dxAt = dxAt)
        assertCpuGpuParity(oracle, gpu, w, h, "bilinear-smooth")
        val uniform = cpuMerge({ _ -> 1f })
        var maxDiff = 0.0
        for (i in oracle.rgb.indices)
            maxDiff = maxOf(maxDiff, abs(oracle.rgb[i] - uniform.rgb[i]).toDouble())
        assertTrue("step must move the render (maxDiff=$maxDiff)", maxDiff > 1e-3)
    }
}
