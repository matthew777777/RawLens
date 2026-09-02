// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegOutputSettingsTest {
    @Test
    fun purityBoostIsBoundedAndDefaultsToPinnedAgxBase() {
        assertEquals(1f, JpegOutputSettings().resolvedForPlatform().agxPurityBoost, 0f)
        assertEquals(0f, JpegOutputSettings(agxPurityBoost = -1f).resolvedForPlatform().agxPurityBoost, 0f)
        assertEquals(2f, JpegOutputSettings(agxPurityBoost = 3f).resolvedForPlatform().agxPurityBoost, 0f)
        assertEquals(
            1f,
            JpegOutputSettings(agxPurityBoost = Float.NaN).resolvedForPlatform().agxPurityBoost,
            0f
        )
    }

    @Test
    fun extendedAgxControlsResolveIntoSafeShaderRanges() {
        val resolved = JpegOutputSettings(
            agxContrast = 9f,
            agxSaturation = -1f,
            agxHuePreservation = 3f,
            agxShadowEv = 1f,
            agxHighlightEv = 20f,
            agxGamutCompression = Float.NaN
        ).resolvedForPlatform()
        assertEquals(1.5f, resolved.agxContrast, 0f)
        assertEquals(0f, resolved.agxSaturation, 0f)
        assertEquals(1f, resolved.agxHuePreservation, 0f)
        assertEquals(4f, resolved.agxShadowEv, 0f)
        assertEquals(10f, resolved.agxHighlightEv, 0f)
        assertEquals(0f, resolved.agxGamutCompression, 0f)
    }

    @Test
    fun fullGamutCompressionFitsBoostedColorWithoutChangingNeutral() {
        val boosted = JpegOutputSettings(agxPurityBoost = 2f, agxGamutCompression = 1f)
        val color = AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(0f, 1f, 0f), boosted)
        assertTrue(color.all { it in -1e-6f..1.000001f })

        val neutral = floatArrayOf(0.18f, 0.18f, 0.18f)
        val base = AgxDisplayTransform.acescgToOutputLinearSrgb(neutral)
        val compressed = AgxDisplayTransform.acescgToOutputLinearSrgb(neutral, boosted)
        assertArrayEquals(base, compressed, 2e-4f)
    }

    @Test
    fun displayP3KeepsD65NeutralNeutral() {
        val value = floatArrayOf(0.18f, 0.18f, 0.18f)
        val p3 = AgxDisplayTransform.acescgToOutputLinearDisplayP3(value)
        assertTrue(p3.all(Float::isFinite))
        assertArrayEquals(floatArrayOf(p3[0], p3[0], p3[0]), p3, 2e-4f)
    }

    @Test
    fun displayP3UsesWiderPrimaryTransformThanSrgb() {
        val saturated = floatArrayOf(1f, 0f, 0f)
        val srgb = AgxDisplayTransform.acescgToOutputLinearSrgb(saturated)
        val p3 = AgxDisplayTransform.acescgToOutputLinearDisplayP3(saturated)
        assertTrue(srgb.zip(p3).any { (a, b) -> kotlin.math.abs(a - b) > 1e-4f })
    }

    @Test
    fun ultraHdrBudgetUsesQuarterResolutionGainmap() {
        val estimate = RawDevelopmentCoordinator.estimateMemory(4080, 3060, ultraHdr = true)
        val sdr = RawDevelopmentCoordinator.estimateMemory(4080, 3060)
        val gainPixels = 1020L * 765L
        assertTrue(estimate.ultraHdrGainmapBytes == gainPixels * 12L)
        // AMaZE's tiled working set remains the global peak on this sensor; the output phase
        // nevertheless accounts for the gainmap explicitly instead of hiding its allocation.
        assertTrue(sdr.ultraHdrGainmapBytes == 0L)
    }
}
