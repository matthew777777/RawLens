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
                // Same derivation as VfCpuNeon: site (channel % 2, channel / 2).
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

    @Test fun `both shaders declare the lens green-row uniform they use`() {
        // The ESSL3 lens-gain block reads u_lensGreenRow; the GPU shader once
        // omitted its declaration, failing compile on every startup (Mali:
        // "Undeclared variable 'u_lensGreenRow'") and silently killing the
        // EGL-direct fallback tier.
        assertTrue(VfGpuImport.cpuFragmentShader().contains("uniform int u_lensGreenRow"))
        assertTrue(VfGpuImport.gpuFragmentShader().contains("uniform int u_lensGreenRow"))
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
        val outIndex = tail.indexOf("outMat * v")
        assertTrue("tail must convert the working space via the selected output matrix", outIndex >= 0)
        assertTrue("tail must offer the sRGB output matrix", tail.contains("outMat = rec2020ToSrgb()"))
        assertTrue(
            "tail must follow the Display P3 target",
            tail.contains("if (u_displayP3 != 0) { outMat = rec2020ToDisplayP3(); }")
        )
        val gamutIndex = tail.indexOf("gamutScale(delta.r")
        assertTrue("tail must keep gamut compression", gamutIndex >= 0)
        assertTrue("output conversion must precede gamut compression", outIndex < gamutIndex)
    }

    @Test fun `AgX matrices are neutral-preserving in GLSL column-major order`() {
        // GLSL mat3() fills column-major: literal (a0..a8) has rows
        // (a0,a3,a6), (a1,a4,a7), (a2,a5,a8). A row-ordered literal transposes
        // the matrix and tints neutrals (observed: B/R 1.5 from the outset).
        for (shader in listOf(VfGpuImport.cpuFragmentShader(), VfGpuImport.gpuFragmentShader())) {
            for (name in listOf("agxInset", "agxOutset", "acesCgToRec2020", "rec2020ToSrgb", "rec2020ToDisplayP3")) {
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

    @Test fun `ESSL 1 lens clamp avoids integer max overload`() {
        // ESSL 1.00 defines max/min only for float types; max(int, int) fails
        // Mali compile ("No matching overload") and blacks the VF, which renders
        // through the ESSL 1.00 program on every tier. The active-array clamp
        // must stay in float. (The ESSL 3.00 Bayer program may use int max.)
        val cpu = VfGpuImport.cpuFragmentShader()
        assertFalse("int max() breaks ESSL 1.00 compile", cpu.contains("max(u_lensActive"))
        assertTrue(cpu.contains("max(float(u_lensActive"))
    }

    @Test fun `lens content key is stable and size-sensitive`() {
        val gains = FloatArray(17 * 17 * 4) { 1f + (it % 7) * 0.1f }
        val base = VfGpuImport.lensContentKey(17, 17, gains)
        assertEquals(base, VfGpuImport.lensContentKey(17, 17, gains.copyOf()))
        assertFalse(base == VfGpuImport.lensContentKey(16, 17, gains))
        assertFalse(base == VfGpuImport.lensContentKey(17, 16, gains))
        val drifted = gains.copyOf().also { it[500] += 0.5f }
        assertFalse(base == VfGpuImport.lensContentKey(17, 17, drifted))
    }

    @Test fun `Vulkan recovery backoff doubles then caps`() {
        assertEquals(5000L, VfGpuImport.VULKAN_RECOVER_INITIAL_DELAY_MS)
        assertEquals(30000L, VfGpuImport.VULKAN_RECOVER_MAX_DELAY_MS)
        // 5s -> 10s -> 20s -> capped at 30s; a dead GPU is re-probed, never spun on.
        assertEquals(10000L, VfGpuImport.nextVulkanRecoverDelayMs(5000L))
        assertEquals(20000L, VfGpuImport.nextVulkanRecoverDelayMs(10000L))
        assertEquals(30000L, VfGpuImport.nextVulkanRecoverDelayMs(20000L))
        assertEquals(30000L, VfGpuImport.nextVulkanRecoverDelayMs(30000L))
    }

    @Test fun `Vulkan recovery backoff rejects non-positive delays`() {
        assertThrows(IllegalArgumentException::class.java) {
            VfGpuImport.nextVulkanRecoverDelayMs(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VfGpuImport.nextVulkanRecoverDelayMs(-1000L)
        }
    }

    @Test fun `both shaders share the calibrated JPEG color path`() {
        val cpu = VfGpuImport.cpuFragmentShader()
        val gpu = VfGpuImport.gpuFragmentShader()
        for (marker in listOf(
            "u_camToAces",
            "u_cameraWhite",
            "u_displayP3",
            "acesCgToRec2020",
            "rec2020ToDisplayP3",
            "smoothstep(vec3(0.70), vec3(0.99)"
        )) {
            assertTrue("CPU missing $marker", cpu.contains(marker))
            assertTrue("GPU missing $marker", gpu.contains(marker))
        }
    }

    @Test fun `VF JPEG tail matches save-path floors and dithered output`() {
        val tail = VfGpuImport.cpuFragmentShader()
        assertTrue("tail must use the save-path log floor", tail.contains("log2(max(v, vec3(1e-10)))"))
        assertTrue("tail must keep the save-path luma guard", tail.contains("sceneLuma > 1e-9"))
        assertTrue("tail must dither 8-bit output", tail.contains("gl_FragCoord"))
        assertTrue("tail must quantize with one-LSB dither", tail.contains("/ 255.0"))
    }

    @Test fun `both shaders select lens green parity from the CFA pattern`() {
        // Canonical Gr sits on the quad's even row only for RGGB/GRBG; GBRG/BGGR
        // carry it on the odd row, so the G-even/G-odd swap must account for the
        // pattern instead of assuming quad-relative row 0 (else greens get the
        // wrong vignette gain and color flings toward the corners).
        val cpu = VfGpuImport.cpuFragmentShader()
        val gpu = VfGpuImport.gpuFragmentShader()
        assertTrue("CPU missing u_lensGreenRow", cpu.contains("u_lensGreenRow"))
        assertTrue("GPU missing u_lensGreenRow", gpu.contains("u_lensGreenRow"))
        assertTrue(
            "CPU must offset the green swap by the Gr row",
            cpu.contains("mod(float(q.y + u_lensGreenRow), 2.0)")
        )
        assertTrue(
            "GPU must offset the green swap by the Gr row",
            gpu.contains("(q.y + u_lensGreenRow) & 1")
        )
    }

    @Test fun `lens green row matches the Gr site for every CFA pattern`() {
        // Pinned: RGGB/GRBG carry Gr on the quad's even row, GBRG/BGGR on odd.
        val expected = intArrayOf(0, 0, 1, 1)
        for (cfa in 0..3) {
            val channels = RawPreviewGeometry.channels(cfa)
            assertEquals("CFA $cfa", expected[cfa], VfGpuImport.lensGreenRow(channels))
            // The single uniform relies on Gb taking the complementary row.
            assertEquals("CFA $cfa Gb row", 1 - expected[cfa], channels[2] / 2)
            // Mirror of the shader swap rule: Gr/Gb must land on G-even exactly
            // on even absolute rows, like LensShadingModel.gainAt (save path).
            for (quadRow in 0..1) {
                val swap = (quadRow + expected[cfa]) % 2 == 1
                val grRow = quadRow + expected[cfa]
                val gbRow = quadRow + (1 - expected[cfa])
                assertEquals("CFA $cfa Gr parity", grRow % 2 == 0, !swap)
                assertEquals("CFA $cfa Gb parity", gbRow % 2 == 0, swap)
            }
        }
    }

    @Test fun `lens green row rejects non-quad channel maps`() {
        assertThrows(IllegalArgumentException::class.java) {
            VfGpuImport.lensGreenRow(intArrayOf(0, 1, 2))
        }
    }

    @Test fun `lens recheck always runs without cache or across lenses`() {
        // No snapshot yet: every frame checks, so a late-appearing HAL map is
        // picked up immediately, never after a throttle delay.
        assertTrue(VfGpuImport.shouldRecheckLensMap(1000L, 999L, true, false))
        // Lens switch (new characteristics): recheck even inside the window.
        assertTrue(VfGpuImport.shouldRecheckLensMap(1000L, 999L, false, true))
    }

    @Test fun `lens recheck throttles to 1Hz once cached`() {
        assertEquals(1000L, VfGpuImport.LENS_RECHECK_MS)
        // 500 ms after verify: serve the cache, skip the gain-copy + hash.
        assertFalse(VfGpuImport.shouldRecheckLensMap(1500L, 1000L, true, true))
        // Window elapsed: re-verify the static map as a backstop.
        assertTrue(VfGpuImport.shouldRecheckLensMap(2000L, 1000L, true, true))
    }

    @Test fun `smooth approach converges monotonically without overshoot`() {
        var value = 0f
        var previous = value
        repeat(60) {
            value = VfGpuImport.smoothToward(value, 1.5f, 33L)
            assertTrue("monotonic up", value >= previous)
            assertTrue("no overshoot", value <= 1.5f)
            previous = value
        }
        assertEquals(1.5f, value, 1e-3f)
        repeat(60) {
            value = VfGpuImport.smoothToward(value, -1f, 33L)
            assertTrue("monotonic down", value <= previous)
            assertTrue("no undershoot", value >= -1f)
            previous = value
        }
        assertEquals(-1f, value, 1e-3f)
    }

    @Test fun `smooth approach holds on zero time and snaps on huge gaps`() {
        assertEquals(0.25f, VfGpuImport.smoothToward(0.25f, 1.5f, 0L), 0f)
        assertEquals(0.25f, VfGpuImport.smoothToward(0.25f, 1.5f, -10L), 0f)
        assertEquals(1.5f, VfGpuImport.smoothToward(0f, 1.5f, 2000L), 0f)
        assertEquals(1.5f, VfGpuImport.smoothToward(1.5f, 1.5f, 33L), 0f)
    }
}
