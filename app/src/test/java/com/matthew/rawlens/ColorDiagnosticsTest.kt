// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorDiagnosticsTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun statics(neutral: DoubleArray?, gains: FloatArray?, forward: DoubleArray?) =
        SceneLinearColorMetadata(
            asShotNeutral = neutral?.let(::ImmutableDoubleValues),
            wbGains = gains?.let(::ImmutableFloatValues),
            cameraCalibration1 = null,
            cameraCalibration2 = null,
            forwardMatrix1 = forward?.let(::ImmutableDoubleValues),
            forwardMatrix2 = null,
            colorMatrix1 = null,
            colorMatrix2 = null,
            referenceIlluminant1 = 21,
            referenceIlluminant2 = null
        )

    @Test fun `line names route, every result and the resolved white balance`() {
        val used = ColorDiagnostics.ResultColor("used(logical)", floatArrayOf(2f, 1f, 1f, 1.5f),
            doubleArrayOf(0.5, 1.0, 0.6667), null, 1, 2)
        val phys = ColorDiagnostics.ResultColor("phys:4", null, null, null, null, null)
        val line = ColorDiagnostics.format("VF color first", "focal=9.0", used, listOf(phys),
            statics(used.neutral, used.gains, identity))

        assertTrue(line, line.startsWith("VF color first route=focal=9.0 used(logical)[gains=2.0000,1.0000,1.0000,1.5000"))
        assertTrue(line, "neutral=0.5000,1.0000,0.6667 awb=1/2 ccm=-]" in line)
        assertTrue(line, "phys:4[gains=- neutral=- awb=-/- ccm=-]" in line)
        assertTrue(line, "ill=21/- cm1=- " in line)
        assertTrue(line, "wb=AS_SHOT_NEUTRAL xyz=FORWARD_MATRIX" in line)
    }

    @Test fun `missing neutral and gains surface as the unity fallback`() {
        val text = ColorDiagnostics.resolution(statics(null, null, identity))
        assertTrue(text, text.startsWith("wb=UNITY_FALLBACK"))
        assertTrue(text, "warnings=[" in text)
    }

    @Test fun `missing matrices report the failed resolve instead of throwing`() {
        val text = ColorDiagnostics.resolution(statics(doubleArrayOf(1.0, 1.0, 1.0), null, null))
        assertTrue(text, text.startsWith("resolve=FAILED("))
        assertTrue(text, text.endsWith("halCcmFallback"))
    }

    @Test fun `null values print as a dash`() {
        assertEquals("-", ColorDiagnostics.values(null))
        assertEquals("0.1235,-1.0000", ColorDiagnostics.values(doubleArrayOf(0.123456, -1.0)))
    }
}
