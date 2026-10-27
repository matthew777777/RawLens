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
    fun finalShadersTakeWhiteWindowUniformInsteadOfHardcodedThresholds() {
        // The CPU mirror (neutralizeCameraHighlight) is window-parameterized;
        // both final shaders must take the same u_white_window uniform so HDR
        // merges can narrow the blend to the exactly-1.0 clipped fallback.
        // A hardcoded smoothstep here would silently desaturate valid HDR
        // highlights even though the host binds the uniform.
        for (asset in listOf("amaze/final.glsl", "amaze/final_display.glsl")) {
            val shader = File("src/main/assets/shaders/$asset").readText()
            assertTrue("$asset declares u_white_window",
                shader.contains("uniform highp vec2 u_white_window;"))
            assertTrue("$asset blends with u_white_window",
                shader.contains("smoothstep(vec3(u_white_window.x), vec3(u_white_window.y), sensorRgb)"))
            assertTrue("$asset detects clipping before demosaic balance",
                shader.contains("sensorRgb = cameraRgb * u_demosaic_unbalance;"))
            assertTrue("$asset declares inverse balance",
                shader.contains("uniform highp vec3 u_demosaic_unbalance;"))
            assertTrue("$asset keeps no hardcoded white window",
                !shader.contains("smoothstep(vec3(0.70), vec3(0.99), cameraRgb)"))
        }
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
        assertTrue(AmazeUniform.CFA_PHASE in
            AmazePipelineContract.uniformsFor("amaze/hvwt.glsl"))
        assertTrue(AmazeUniform.CFA_PHASE in
            AmazePipelineContract.uniformsFor("amaze/final.glsl"))
        assertTrue(AmazePipelineContract.passes.all { AmazeUniform.SIZE in it.uniforms })
    }

    @Test
    fun everyPassUsingBayerHelpersReceivesTheCfaUniform() {
        // These helpers all reach FC()/u_fc in import_amaze. An unbound ivec4
        // defaults to zero: site_x then mistakes every even column for R/B.
        val bayerHelperCall = Regex("\\b(?:site_x|isG|FC)\\s*\\(")
        AmazePipelineContract.passes.filter { it.shader != "amaze/pad.glsl" }.forEach { pass ->
            val source = File("src/main/assets/shaders/${pass.shader}").readText()
            if (bayerHelperCall.containsMatchIn(source)) {
                assertTrue("${pass.shader} reads CFA phase but does not bind it",
                    AmazeUniform.CFA_PHASE in pass.uniforms)
            }
        }
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
        // RawTherapee's AMaZE intermediates are float. RawLens therefore uses RGBA32F vector
        // scratch rather than PhotonCamera's lower-precision RGBA16F adaptation.
        val scratch = 1120L * 1120L * (6L * 4L + 10L * 16L)
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
    fun clipThresholdMatchesBalancedNeutralAtSensorWhite() {
        // Daylight neutral (green clips first): pad.glsl's attenuate-only
        // balance leaves a neutral pixel at sensor white reading 0.5 in every
        // channel. The highlight guards must engage there — RawTherapee's
        // clip_pt = 1/initialGain is exactly this neutral level — otherwise
        // ratio interpolation runs through single-channel clipping and its
        // chroma overshoot reaches white balance as a purple halo.
        val white = floatArrayOf(0.5f, 1.0f, 0.67f)
        val balance = FloatArray(3) { white.minOrNull()!! / white[it] }
        val clip = AmazePipelineContract.balancedClipPoint(1f, balance)
        assertEquals(0.5f, clip, 1e-6f)
        // gradcd's 0.8x ratio-to-Hamilton-Adams fallback now engages for
        // clipped-neutral data (0.5 > 0.4); with the old unbalanced 1.0 it
        // never fired (0.5 > 0.8 is false).
        assertTrue(0.5f > 0.8f * clip)
    }

    @Test
    fun unityBalanceLeavesClipPointUntouched() {
        assertEquals(
            1f,
            AmazePipelineContract.balancedClipPoint(1f, floatArrayOf(1f, 1f, 1f)),
            0f
        )
        assertEquals(
            3f,
            AmazePipelineContract.balancedClipPoint(3f, floatArrayOf(1f, 1f, 1f)),
            0f
        )
    }

    @Test
    fun hdrClipPointScalesProportionallyWithBalance() {
        val balance = floatArrayOf(1f, 0.5f, 0.75f)
        assertEquals(2f, AmazePipelineContract.balancedClipPoint(4f, balance), 1e-6f)
    }

    @Test
    fun rejectsBadClipInputs() {
        val balance = floatArrayOf(1f, 0.5f, 0.75f)
        assertThrows(IllegalArgumentException::class.java) {
            AmazePipelineContract.balancedClipPoint(0f, balance)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmazePipelineContract.balancedClipPoint(Float.NaN, balance)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmazePipelineContract.balancedClipPoint(1f, floatArrayOf(1f, 0.5f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmazePipelineContract.balancedClipPoint(1f, floatArrayOf(1f, 0f, 0.75f))
        }
    }

    @Test
    fun amazePassesBindTheBalancedClipPoint() {
        // The u_clip guards compare balanced-basis interpolations, so the
        // executor must fold the balance minimum in. Binding the raw
        // clipPoint disables the highlight guards for single-channel
        // clipping (purple highlight halos).
        val source = File("src/main/java/com/matthew/rawlens/Gles31AmazeProcessor.kt").readText()
        assertTrue(source.contains("balancedClipPoint(clipPoint, demosaicBalance)"))
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
