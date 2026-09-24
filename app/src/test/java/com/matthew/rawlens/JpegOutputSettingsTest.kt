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
            agxGamutCompression = Float.NaN,
            highlightHeadroom = 9f,
            highlightSoftHeadroom = 0f,
            highlightShoulder = -1f
        ).resolvedForPlatform()
        assertEquals(1.5f, resolved.agxContrast, 0f)
        assertEquals(0f, resolved.agxSaturation, 0f)
        assertEquals(1f, resolved.agxHuePreservation, 0f)
        assertEquals(4f, resolved.agxShadowEv, 0f)
        assertEquals(10f, resolved.agxHighlightEv, 0f)
        assertEquals(0f, resolved.agxGamutCompression, 0f)
        assertEquals(1.5f, resolved.highlightHeadroom, 0f)
        assertEquals(0.6f, resolved.highlightSoftHeadroom, 0f)
        assertEquals(0f, resolved.highlightShoulder, 0f)
    }

    @Test
    fun highlightControlsDefaultToSkySafeAndShoulderOn() {
        val resolved = JpegOutputSettings().resolvedForPlatform()
        assertEquals(1f, resolved.highlightHeadroom, 0f)
        assertEquals(0.85f, resolved.highlightSoftHeadroom, 0f)
        assertEquals(1f, resolved.highlightShoulder, 0f)
    }

    @Test
    fun shoulderOffReproducesPinnedAgxWhite() {
        val white = floatArrayOf(16f, 16f, 16f)
        val pinned = AgxDisplayTransform.acescgToOutputLinearSrgb(
            white, JpegOutputSettings(highlightShoulder = 0f)
        ).average().toFloat()
        // Pinned Filament Base maps 16x to near-white; full shoulder compresses to ~0.70.
        assertTrue("shoulder-off should stay near white, was $pinned", pinned > 0.9f)
        val soft = AgxDisplayTransform.acescgToOutputLinearSrgb(
            white, JpegOutputSettings(highlightShoulder = 1f)
        ).average().toFloat()
        assertTrue("shoulder-on should compress sun, was $soft", soft < pinned - 0.1f)
        // Midtones are bit-exact either way.
        val mid = floatArrayOf(0.18f, 0.18f, 0.18f)
        val midOff = AgxDisplayTransform.acescgToOutputLinearSrgb(mid, JpegOutputSettings(highlightShoulder = 0f))
        val midOn = AgxDisplayTransform.acescgToOutputLinearSrgb(mid, JpegOutputSettings(highlightShoulder = 1f))
        assertArrayEquals(midOff, midOn, 1e-6f)
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
