// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/**
 * Merged noise model tests. The contract: support measures fusion gain,
 * scaling is exact division per plane, and anything unmeasurable omits the
 * tag instead of fabricating a profile.
 */
class RawSrMergedNoiseTest {
    @Test fun meanSupportIsOnePlusMeanRc() {
        assertEquals(1.0, RawSrMergedNoise.meanSupport(floatArrayOf(0f, 0f, 0f, 0f)), 0.0)
        assertEquals(2.0, RawSrMergedNoise.meanSupport(floatArrayOf(1f, 1f)), 0.0)
        assertEquals(2.5, RawSrMergedNoise.meanSupport(floatArrayOf(0f, 1f, 2f, 3f)), 0.0)
    }

    @Test fun meanSupportSanitizesNonFinite() {
        assertEquals(
            4.0 / 3.0, RawSrMergedNoise.meanSupport(floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY)), 0.0)
    }

    @Test fun meanSupportRejectsEmpty() {
        try {
            RawSrMergedNoise.meanSupport(FloatArray(0))
            fail("expected size contract")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun effectiveFramesClampsToUnity() {
        assertEquals(1.0, RawSrMergedNoise.effectiveFrames(1.0), 0.0)
        assertEquals(3.7, RawSrMergedNoise.effectiveFrames(3.7), 0.0)
        assertEquals(1.0, RawSrMergedNoise.effectiveFrames(0.5), 0.0)
        assertEquals(1.0, RawSrMergedNoise.effectiveFrames(Double.NaN), 0.0)
        assertEquals(1.0, RawSrMergedNoise.effectiveFrames(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test fun meanSupportCapsAtBurstSize() {
        // Sabre maximumSupport analogue: per-quad support never exceeds the
        // true burst size, so the profile cannot understate noise.
        assertEquals(3.0, RawSrMergedNoise.meanSupport(floatArrayOf(5f), 3.0), 0.0)
        assertEquals(2.5, RawSrMergedNoise.meanSupport(floatArrayOf(0f, 1f, 2f, 3f), 4.0), 0.0)
        assertEquals(1.0, RawSrMergedNoise.meanSupport(floatArrayOf(Float.NaN), 3.0), 0.0)
    }

    @Test fun effectiveFramesCeilingsAtAcceptedBurst() {
        assertEquals(4.0, RawSrMergedNoise.effectiveFrames(9.0, 4.0), 0.0)
        assertEquals(2.5, RawSrMergedNoise.effectiveFrames(2.5, 4.0), 0.0)
        assertEquals(1.0, RawSrMergedNoise.effectiveFrames(9.0, 0.5), 0.0)
        assertEquals(1.0, RawSrMergedNoise.effectiveFrames(Double.NaN, 4.0), 0.0)
    }

    @Test fun scaleProfileDividesEightCoefPerPlane() {
        // RGGB raster phases: R(0.02,1) G(0.03,2) G(0.04,3) B(0.05,4); green
        // keeps the larger-slope pair, exactly DngNoiseProfile.toRgb.
        val scaled = RawSrMergedNoise.scaleProfile(
            doubleArrayOf(0.02, 1.0, 0.03, 2.0, 0.04, 3.0, 0.05, 4.0),
            BayerPattern.RGGB, 2.0)
        assertArrayEquals(doubleArrayOf(0.01, 0.5, 0.02, 1.5, 0.025, 2.0), scaled!!, 0.0)
    }

    @Test fun scaleProfilePassesSixCoefThrough() {
        val scaled = RawSrMergedNoise.scaleProfile(
            doubleArrayOf(0.02, 1.0, 0.03, 2.0, 0.04, 3.0),
            BayerPattern.GRBG, 4.0)
        assertArrayEquals(doubleArrayOf(0.005, 0.25, 0.0075, 0.5, 0.01, 0.75), scaled!!, 0.0)
    }

    @Test fun unityScaleIsIdentity() {
        val values = doubleArrayOf(0.02, 1.0, 0.03, 2.0, 0.04, 3.0, 0.05, 4.0)
        assertArrayEquals(
            DngNoiseProfile.toRgb(values, BayerPattern.RGGB)!!,
            RawSrMergedNoise.scaleProfile(values, BayerPattern.RGGB, 1.0)!!, 0.0)
    }

    @Test fun scaleProfileOmitsInsteadOfFabricating() {
        val good = doubleArrayOf(0.02, 1.0, 0.03, 2.0, 0.04, 3.0, 0.05, 4.0)
        assertNull(RawSrMergedNoise.scaleProfile(null, BayerPattern.RGGB, 2.0))
        assertNull(RawSrMergedNoise.scaleProfile(doubleArrayOf(0.02, 1.0), BayerPattern.RGGB, 2.0))
        assertNull(RawSrMergedNoise.scaleProfile(
            doubleArrayOf(0.0, 1.0, 0.03, 2.0, 0.04, 3.0, 0.05, 4.0), BayerPattern.RGGB, 2.0))
        assertNull(RawSrMergedNoise.scaleProfile(good, BayerPattern.RGGB, 0.5))
        assertNull(RawSrMergedNoise.scaleProfile(good, BayerPattern.RGGB, Double.NaN))
    }

    @Test fun formatEffectiveFramesIsLocaleFree() {
        assertEquals("1.0", LinearRgbDngWriter.formatEffectiveFrames(1.0))
        assertEquals("2.5", LinearRgbDngWriter.formatEffectiveFrames(2.5))
        assertEquals("2.6667", LinearRgbDngWriter.formatEffectiveFrames(2.66666))
        try {
            LinearRgbDngWriter.formatEffectiveFrames(0.5)
            fail("expected count contract")
        } catch (_: IllegalArgumentException) {
        }
    }
}
