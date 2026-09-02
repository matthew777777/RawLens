// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazePipelineContractTest {
    @Test
    fun chromaStencilKeepsRawTherapeeP1InNortheastQuadrant() {
        val shader = File("src/main/assets/shaders/amaze/chroma.glsl").readText()
        assertTrue(shader.contains(
            "wtne * (1.325 * DC(s + ivec2(1, -1)) - 0.175 * DC(s + ivec2(3, -3))"
        ))
        assertTrue(shader.contains(
            "wtsw * (1.325 * DC(s + ivec2(-1, 1)) - 0.175 * DC(s + ivec2(-3, 3))"
        ))
        assertTrue(shader.contains(
            "abs(DC(s + ivec2(1, 1)) - DC(s + ivec2(-3, 3)))"
        ))
    }

    @Test
    fun rbVarianceUsesTheCorrectCenterForBothBayerRowPhases() {
        val shader = File("src/main/assets/shaders/amaze/rbpm.glsl").readText()
        assertTrue(shader.contains("float c1 = Cf(q + ivec2(1, 0));"))
        assertTrue(shader.contains("float c0 = Cf(q);"))
        assertTrue(shader.contains(
            "sq1p = sq(c0 - Cf(q + ivec2(-1, 1))) + sq(c0 - Cf(q + ivec2(1, -1)));"
        ))
        assertTrue(shader.contains(
            "sq1m = sq(c0 - Cf(q + ivec2(-1, -1))) + sq(c0 - Cf(q + ivec2(1, 1)));"
        ))
    }

    @Test
    fun gcorrPreservesMeasuredGreenSamplesLikeRawTherapee() {
        val shader = File("src/main/assets/shaders/amaze/gcorr.glsl").readText()
        assertTrue(shader.contains("float green = s == p ? Cf(p) + d0 : Cf(p);"))
        assertTrue(!shader.contains("s == p ? d0 : d0_new(p)"))
    }

    @Test
    fun passGraphMatchesPhotonCameraAmazeNode() {
        assertEquals(13, AmazePipelineContract.passes.size)
        assertEquals("amaze/pad.glsl", AmazePipelineContract.passes.first().shader)
        assertEquals("amaze/final.glsl", AmazePipelineContract.passes.last().shader)
        assertEquals(listOf("grad", "cdA", "cdB"), AmazePipelineContract.passes[1].outputs)
    }

    @Test
    fun compilerOptimizedCommonUniformsAreBoundOnlyByPassesThatUseThem() {
        val clipPasses = AmazePipelineContract.passes
            .filter { AmazeUniform.CLIP_POINT in it.uniforms }
            .map(AmazePass::shader)
        assertEquals(
            listOf(
                "amaze/gradcd.glsl",
                "amaze/bound.glsl",
                "amaze/rbpm.glsl",
                "amaze/gcorr.glsl"
            ),
            clipPasses
        )
        assertTrue(AmazeUniform.CFA_PHASE !in
            AmazePipelineContract.uniformsFor("amaze/hvwt.glsl"))
        assertTrue(AmazeUniform.CFA_PHASE in
            AmazePipelineContract.uniformsFor("amaze/final.glsl"))
        assertTrue(AmazePipelineContract.passes.all { AmazeUniform.SIZE in it.uniforms })
    }

    @Test
    fun allBayerPatternsProduceExactFcUniform() {
        assertArrayEquals(intArrayOf(0, 1, 1, 2), AmazePipelineContract.cfaUniform(BayerPattern.RGGB))
        assertArrayEquals(intArrayOf(1, 0, 2, 1), AmazePipelineContract.cfaUniform(BayerPattern.GRBG))
        assertArrayEquals(intArrayOf(1, 2, 0, 1), AmazePipelineContract.cfaUniform(BayerPattern.GBRG))
        assertArrayEquals(intArrayOf(2, 1, 1, 0), AmazePipelineContract.cfaUniform(BayerPattern.BGGR))
    }

    @Test
    fun memoryBudgetIncludesFixedScratchAndFullFrameTextures() {
        val scratch = 1120L * 1120L * (6L * 4L + 10L * 8L)
        val fullFrame = 8000L * 6000L * 12L
        assertEquals(scratch + fullFrame, AmazePipelineContract.estimatedGpuBytes(8000, 6000))
    }

    @Test
    fun capabilityGateReportsEachHardRequirement() {
        val supported = limits()
        assertTrue(AmazePipelineContract.evaluate(supported, 4000, 3000) is AmazeCapability.Supported)
        assertTrue(
            AmazePipelineContract.evaluate(supported.copy(minorVersion = 0), 4000, 3000) is
                AmazeCapability.Unsupported
        )
        assertTrue(
            AmazePipelineContract.evaluate(supported.copy(maxImageUnits = 2), 4000, 3000) is
                AmazeCapability.Unsupported
        )
        assertTrue(
            AmazePipelineContract.evaluate(supported.copy(halfFloatColorBuffer = false), 4000, 3000) is
                AmazeCapability.Unsupported
        )
        assertTrue(
            AmazePipelineContract.evaluate(supported, 8000, 6000, maxGpuBytes = 512L * 1024L * 1024L) is
                AmazeCapability.Unsupported
        )
    }

    @Test
    fun rejectsOddOrTooSmallCfaBeforeGpuAllocation() {
        val input = UnpackedRawCfa(
            3, 4, BayerPattern.RGGB, FloatArray(12), RawCrop(0, 0, 3, 4)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AmazePipelineContract.validateInput(input)
        }
    }

    private fun limits() = AmazeGpuLimits(
        majorVersion = 3,
        minorVersion = 1,
        maxTextureSize = 16384,
        maxImageUnits = 8,
        maxWorkGroupInvocations = 128,
        maxWorkGroupSizeX = 128,
        maxWorkGroupSizeY = 128,
        halfFloatColorBuffer = true,
        versionString = "OpenGL ES 3.1 test"
    )
}
