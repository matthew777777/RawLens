// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VfGpuImportTest {
    @Test fun `packed 16-bit layout with ES3 and native lib is eligible`() {
        val result = VfGpuImport.checkEligible(
            pixelStride = 2, rowStride = 8160, width = 4080,
            glEs3 = true, nativeAvailable = true, gpuDisabledForSession = false
        )
        assertTrue(result.reason, result.eligible)
    }

    @Test fun `eligibility rejects every non-importable configuration`() {
        fun check(pixelStride: Int = 2, rowStride: Int = 8160, glEs3: Boolean = true,
                  native: Boolean = true, disabled: Boolean = false) =
            VfGpuImport.checkEligible(pixelStride, rowStride, 4080, glEs3, native, disabled)
        assertFalse(check(pixelStride = 1).eligible)
        assertFalse(check(pixelStride = 4).eligible)
        assertFalse(check(rowStride = 8162).eligible)
        assertFalse(check(rowStride = 8000).eligible)
        assertFalse(check(glEs3 = false).eligible)
        assertFalse(check(native = false).eligible)
        assertFalse(check(disabled = true).eligible)
    }

    @Test fun `quad offsets match the CPU sampler channel mapping for all CFA patterns`() {
        for (cfa in 0..3) {
            val channels = RawPreviewGeometry.channels(cfa)
            val offsets = VfGpuImport.quadOffsets(channels)
            assertEquals(8, offsets.size)
            for (c in 0..3) {
                // Same derivation as RawPreviewSampler: site (channel % 2, channel / 2).
                assertEquals(channels[c] % 2, offsets[c * 2])
                assertEquals(channels[c] / 2, offsets[c * 2 + 1])
            }
        }
        // RGGB canonical order pins the exact sequence.
        assertArrayEquals(
            intArrayOf(0, 0, 1, 0, 0, 1, 1, 1),
            VfGpuImport.quadOffsets(intArrayOf(0, 1, 2, 3))
        )
    }

    @Test fun `CPU shader stays ESSL 1 point 00 sampling the RGBA texture`() {
        val shader = VfGpuImport.cpuFragmentShader()
        assertFalse(shader.contains("#version"))
        assertTrue(shader.contains("texture2D(raw, tex)"))
        assertTrue(shader.contains("gl_FragColor"))
    }

    @Test fun `GPU shader is ESSL 3 with integer Bayer fetch and highp unpack`() {
        val shader = VfGpuImport.gpuFragmentShader()
        assertTrue(shader.startsWith("#version 300 es"))
        assertTrue(shader.contains("usampler2D u_bayer"))
        assertTrue(shader.contains("texelFetch(u_bayer"))
        assertTrue(shader.contains("in highp vec2 tex"))
        assertTrue(shader.contains("uniform highp vec4 u_black"))
        assertTrue(shader.contains("uniform highp vec4 u_invRange"))
        assertTrue(shader.contains("out vec4 fragColor"))
        assertFalse(shader.contains("gl_FragColor"))
        assertFalse(shader.contains("texture2D"))
    }

    @Test fun `both shaders share the same WYSIWYG tonemap body`() {
        val cpu = VfGpuImport.cpuFragmentShader()
        val gpu = VfGpuImport.gpuFragmentShader()
        for (marker in listOf(
            "agxInset", "agxOutset", "agxSigmoid", "gamutScale", "srgbOetf",
            "u_jpeg", "u_agxContrast", "u_agxSaturation", "u_agxPurity",
            "u_agxHue", "u_agxShadowEv", "u_agxHighlightEv", "u_agxGamut",
            "u_exposureEv", "rec2020ToSrgb",
            "(b.g + b.b) * 0.5"
        )) {
            assertTrue("CPU missing $marker", cpu.contains(marker))
            assertTrue("GPU missing $marker", gpu.contains(marker))
        }
    }

    @Test fun `AgX tail applies adaptive exposure before the log domain and converts to sRGB`() {
        val tail = VfGpuImport.cpuFragmentShader()
        val evIndex = tail.indexOf("exp2(u_exposureEv)")
        assertTrue("tail must scale scene-linear rgb by the preview EV", evIndex >= 0)
        val logIndex = tail.indexOf("log2(max(v")
        assertTrue("tail must keep the AgX log domain", logIndex >= 0)
        assertTrue("exposure must apply before the log domain", evIndex < logIndex)
        val outIndex = tail.indexOf("rec2020ToSrgb() * v")
        assertTrue("tail must convert the working space to sRGB", outIndex >= 0)
        val gamutIndex = tail.indexOf("gamutScale(delta.r")
        assertTrue("tail must keep gamut compression", gamutIndex >= 0)
        assertTrue("sRGB conversion must precede gamut compression", outIndex < gamutIndex)
    }

    @Test fun `AgX matrices are neutral-preserving in GLSL column-major order`() {
        // GLSL mat3() fills column-major: literal (a0..a8) has rows
        // (a0,a3,a6), (a1,a4,a7), (a2,a5,a8). A row-ordered literal transposes
        // the matrix and tints neutrals (observed: B/R 1.5 from the outset).
        for (shader in listOf(VfGpuImport.cpuFragmentShader(), VfGpuImport.gpuFragmentShader())) {
            for (name in listOf("agxInset", "agxOutset", "rec2020ToSrgb")) {
                val literal = shader.substringAfter("$name() { return mat3(").substringBefore(");")
                val elements = literal.split(",").map { it.trim().toFloat() }
                assertEquals("$name must carry 9 elements", 9, elements.size)
                for (row in 0..2) {
                    val sum = elements[row] + elements[row + 3] + elements[row + 6]
                    assertEquals("$name row $row must sum to 1", 1f, sum, 0.01f)
                }
            }
        }
    }

    @Test fun `GPU vertex shader is ESSL 3 passing through UVs`() {
        val shader = VfGpuImport.gpuVertexShader()
        assertTrue(shader.startsWith("#version 300 es"))
        assertTrue(shader.contains("tex = uv"))
    }
}
