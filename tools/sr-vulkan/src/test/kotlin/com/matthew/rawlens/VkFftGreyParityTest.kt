// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU/CPU contract for the FFT grey pass (`rawsr/fft_stage.glsl` +
 * `rawsr/fft_remap.glsl` driven by `VkRawSrProcessor.fftGreyGpu`): the
 * resident grey must reproduce the double [RawSrAlignment.fftGrey]
 * within float32 tolerance and the float [RawSrFftF32] oracle tightly
 * (same algorithm, same order — the tight bound pins the
 * transcription). The stage-level test pins the shader/host wiring
 * (uniform names, twiddle tables, axis semantics, gather order) beneath
 * the end-to-end grey. Needs `:tools:sr-vulkan:compileShaders` first
 * (reads `build/resources/main`).
 */
class VkFftGreyParityTest {
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

    @Test fun fftStagePassMatchesHandComputedDft() {
        // One row of 6 values; stage (m=6, p=2) by hand:
        // L0 = [x0+x3, x1+x4, x2+x5, x0-x3, (x1-x4)W6, (x2-x5)W6^2],
        // then stage (m=3, p=3) + the mode-2 gather must equal the CPU
        // DFT. A twiddle/uniform/axis mixup fails here, beneath the
        // grey-level tests below.
        val x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val w = 6
        val h = 1
        session { arena, uploads ->
            val src = arena.texture(w, h, GLES30.GL_R32F)
            src.uploadR32f(FloatArray(w * h) { x[it % w] }, uploads)
            val twM = arena.texture(6, 1, GLES30.GL_RGBA32F)
            twM.uploadRgba32f(RawSrFftPlan.twiddleRow(6, false), uploads)
            val twP = arena.texture(2, 1, GLES30.GL_RGBA32F)
            twP.uploadRgba32f(RawSrFftPlan.twiddleRow(2, false), uploads)
            val reOut = arena.texture(w, h, GLES30.GL_R32F)
            val imOut = arena.texture(w, h, GLES30.GL_R32F)
            pass("rawsr/fft_stage.glsl") {
                sampler("u_re_in", src)
                sampler("u_im_in", src)
                sampler("u_tw_m", twM)
                sampler("u_tw_p", twP)
                ivec2("u_size", w, h)
                integer("u_axis", 0)
                integer("u_m", 6)
                integer("u_radix", 2)
                integer("u_first", 1)
                integer("u_flip", 0)
                image(0, reOut, GLES30.GL_R32F)
                image(1, imOut, GLES30.GL_R32F)
                dispatch(w, h, 8, 8)
            }
            val gotRe = reOut.downloadR32f()
            val gotIm = imOut.downloadR32f()
            // W6 = 0.5 - 0.866i; W6^2 = -0.5 - 0.866i; (x1-x4) =
            // (x2-x5) = -3.
            val wantRe = floatArrayOf(5f, 7f, 9f, -3f, -1.5f, 1.5f)
            val wantIm = floatArrayOf(0f, 0f, 0f, 0f, 2.598076f, 2.598076f)
            var worst = 0f
            for (i in wantRe.indices) {
                worst = maxOf(worst, abs(gotRe[i] - wantRe[i]))
                worst = maxOf(worst, abs(gotIm[i] - wantIm[i]))
            }
            assertTrue("stage worst=$worst", worst < 1e-4f)
            val twM3 = arena.texture(3, 1, GLES30.GL_RGBA32F)
            twM3.uploadRgba32f(RawSrFftPlan.twiddleRow(3, false), uploads)
            val re2 = arena.texture(w, h, GLES30.GL_R32F)
            val im2 = arena.texture(w, h, GLES30.GL_R32F)
            pass("rawsr/fft_stage.glsl") {
                sampler("u_re_in", reOut)
                sampler("u_im_in", imOut)
                sampler("u_tw_m", twM3)
                sampler("u_tw_p", twM3)
                ivec2("u_size", w, h)
                integer("u_axis", 0)
                integer("u_m", 3)
                integer("u_radix", 3)
                integer("u_first", 0)
                integer("u_flip", 0)
                image(0, re2, GLES30.GL_R32F)
                image(1, im2, GLES30.GL_R32F)
                dispatch(w, h, 8, 8)
            }
            val reP = arena.texture(w, h, GLES30.GL_R32F)
            val imP = arena.texture(w, h, GLES30.GL_R32F)
            pass("rawsr/fft_remap.glsl") {
                sampler("u_re_in", re2)
                sampler("u_im_in", im2)
                ivec2("u_size", w, h)
                integer("u_mode", 2)
                integer("u_axis", 0)
                integer("u_levels", 2)
                floats("u_factors[0]", FloatArray(16) { if (it == 0) 2f else if (it == 1) 3f else 0f })
                float("u_scale", 1f)
                image(0, reP, GLES30.GL_R32F)
                image(1, imP, GLES30.GL_R32F)
                dispatch(w, h, 8, 8)
            }
            val dre = DoubleArray(6) { x[it].toDouble() }
            val dim = DoubleArray(6)
            RawSrFft.fft1d(dre, dim, 6, false)
            val gotPRe = reP.downloadR32f()
            val gotPIm = imP.downloadR32f()
            var worstAxis = 0.0
            for (i in 0 until 6) {
                worstAxis = maxOf(worstAxis, abs(gotPRe[i] - dre[i]))
                worstAxis = maxOf(worstAxis, abs(gotPIm[i] - dim[i]))
            }
            assertTrue("axis worst=$worstAxis", worstAxis < 1e-4)
        }
    }

    private fun texturedMosaic(w: Int, h: Int, seed: Long): FloatArray {
        val random = java.util.Random(seed)
        return FloatArray(w * h) {
            (0.2 + 0.6 * random.nextDouble() + 0.05 * sin(it * 0.37)).toFloat()
        }
    }

    private fun checkGrey(w: Int, h: Int, flip: RawSrCfaOrientation.Flip, seed: Long) {
        val mosaic = texturedMosaic(w, h, seed)
        val flipInt = when (flip) {
            RawSrCfaOrientation.Flip.IDENTITY -> 0
            RawSrCfaOrientation.Flip.HFLIP -> 1
            RawSrCfaOrientation.Flip.VFLIP -> 2
            RawSrCfaOrientation.Flip.ROT180 -> 3
        }
        val processing = RawSrCfaOrientation.toProcessingSpace(mosaic, w, h, flip)
        val wantDouble = RawSrAlignment.fftGrey(processing, w, h)
        val wantFloat = RawSrFftF32.fftGrey(mosaic, w, h, flipInt)
        SrVulkan.open().use { vk ->
            val context = android.content.Context(File("build/resources/main"))
            VkProgramCache(vk, context.assets).use { programs ->
                VkArena(vk).use { arena ->
                    val uploads = UploadBuffers()
                    val session = VkSession(vk, programs, uploads)
                    val processor = VkRawSrProcessor(context)
                    try {
                        val t0 = System.nanoTime()
                        val grey = processor.fftGreyGpu(mosaic, w, h, flip, session, arena)
                        val ms = (System.nanoTime() - t0) / 1e6
                        println("fftGreyGpu ${w}x$h flip=$flip: %.1fms".format(ms))
                        try {
                            val got = grey.downloadR32f()
                            var worstDouble = 0f
                            var worstFloat = 0f
                            var sumDouble = 0.0
                            for (i in got.indices) {
                                worstDouble = maxOf(worstDouble, abs(got[i] - wantDouble.values[i]))
                                worstFloat = maxOf(worstFloat, abs(got[i] - wantFloat.values[i]))
                                sumDouble += abs(got[i] - wantDouble.values[i])
                            }
                            println("  vs double: worst=$worstDouble mean=${sumDouble / got.size} " +
                                "vs float: worst=$worstFloat")
                            assertTrue("${w}x$h $flip vs double worst=$worstDouble",
                                worstDouble < 1e-4f)
                            assertTrue("${w}x$h $flip vs float worst=$worstFloat",
                                worstFloat < 1e-5f)
                        } finally {
                            arena.release(grey)
                        }
                    } finally {
                        processor.close()
                    }
                }
            }
        }
    }

    @Test fun gpuGreyMatchesCpuGrey() {
        // Even, odd, and 13/17/19-factor dims (mixed-radix coverage),
        // plus one realistic-size case (timing sample included).
        checkGrey(64, 48, RawSrCfaOrientation.Flip.IDENTITY, 1L)
        checkGrey(125, 65, RawSrCfaOrientation.Flip.IDENTITY, 2L)
        checkGrey(136, 114, RawSrCfaOrientation.Flip.IDENTITY, 3L)
        checkGrey(640, 480, RawSrCfaOrientation.Flip.IDENTITY, 4L)
    }

    @Test fun gpuGreyFusesAllFlips() {
        for (flip in RawSrCfaOrientation.Flip.values()) {
            checkGrey(96, 64, flip, 10L + flip.ordinal)
        }
    }
}
