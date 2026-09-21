// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePreviewStrengthTest {
    @Test fun `strength rule matches saves in every exposure mode`() {
        val auto = JpegOutputSettings(adaptiveExposureAuto = true)
        assertEquals(1f, adaptivePreviewStrength(CaptureExposureMode.AUTO, auto), 0f)
        assertEquals(1f, adaptivePreviewStrength(CaptureExposureMode.ZSL, auto), 0f)
        val off = JpegOutputSettings(adaptiveExposureAuto = false)
        assertEquals(0f, adaptivePreviewStrength(CaptureExposureMode.AUTO, off), 0f)
        assertEquals(0f, adaptivePreviewStrength(CaptureExposureMode.ZSL, off), 0f)
        val program = JpegOutputSettings(adaptiveExposureProgramStrength = 0.25f)
        assertEquals(0.25f, adaptivePreviewStrength(CaptureExposureMode.PROGRAM, program), 0f)
        assertEquals(0f, adaptivePreviewStrength(CaptureExposureMode.MANUAL, auto), 0f)
    }
}
