// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraHdrGainmapTest {
    @Test
    fun defaultsMirrorRawrVerdicts() {
        val settings = JpegOutputSettings()
        assertEquals(92, settings.gainmapQuality)
        assertEquals(1f, settings.hdrExposure, 0f)
        assertEquals(0f, settings.gainMapMinLog2, 0f)
        assertEquals(4.7090998f, settings.gainMapMaxLog2, 1e-6f)
        assertEquals(1f, settings.gainmapGamma, 0f)
        assertEquals(0.015625f, settings.gainmapOffsetSdr, 0f)
        assertEquals(0.015625f, settings.gainmapOffsetHdr, 0f)
        assertEquals(0.6f, settings.satProtect, 0f)
        assertTrue(settings.multiChannelMap)
        assertEquals(0.25f, settings.gainMapFitMargin, 0f)
        assertEquals(0.25f, settings.gainMapFitStep, 0f)
        assertEquals(1f, settings.gainMapFitFloor, 0f)
        assertEquals(1.45f, settings.gainMapDisplayCap, 0f)
        assertEquals(0.5f, settings.shoulderWidth, 0f)
        assertEquals(0.5f, settings.shoulderKneeBelowMax, 0f)
        assertEquals(0.75f, settings.baseShare, 0f)
        assertEquals(0.4f, settings.baseExponent, 0f)
        assertEquals(0.05f, settings.chromaKnee, 0f)
    }

    @Test
    fun resolvedBoundsGainmapParamsIntoSafeRanges() {
        val resolved = JpegOutputSettings(
            gainmapQuality = 500,
            hdrExposure = -2f,
            gainMapMinLog2 = Float.NaN,
            gainMapMaxLog2 = 0f,
            gainmapGamma = 0f,
            gainmapOffsetSdr = -1f,
            gainmapOffsetHdr = Float.POSITIVE_INFINITY,
            satProtect = 7f,
            gainMapFitMargin = -1f,
            gainMapFitStep = 0f,
            gainMapFitFloor = -3f,
            gainMapDisplayCap = 99f,
            shoulderWidth = -1f,
            shoulderKneeBelowMax = Float.NaN,
            baseShare = 9f,
            baseExponent = 0f,
            chromaKnee = 0f,
        ).resolvedForPlatform()
        assertEquals(100, resolved.gainmapQuality)
        assertEquals(1e-6f, resolved.hdrExposure, 0f)
        assertEquals(0f, resolved.gainMapMinLog2, 0f)
        // Ceiling stays strictly above the floor.
        assertTrue(resolved.gainMapMaxLog2 > resolved.gainMapMinLog2)
        assertEquals(0.1f, resolved.gainmapGamma, 0f)
        assertEquals(0f, resolved.gainmapOffsetSdr, 0f)
        assertEquals(0.015625f, resolved.gainmapOffsetHdr, 0f)
        assertEquals(1f, resolved.satProtect, 0f)
        assertEquals(0f, resolved.gainMapFitMargin, 0f)
        assertEquals(0.05f, resolved.gainMapFitStep, 0f)
        assertEquals(0f, resolved.gainMapFitFloor, 0f)
        assertEquals(8f, resolved.gainMapDisplayCap, 0f)
        assertEquals(0f, resolved.shoulderWidth, 0f)
        assertEquals(0.5f, resolved.shoulderKneeBelowMax, 0f)
        assertEquals(2f, resolved.baseShare, 0f)
        assertEquals(0.05f, resolved.baseExponent, 0f)
        assertEquals(0.001f, resolved.chromaKnee, 0f)
    }

    @Test
    fun wildFloorCannotInvertCeilingRange() {
        val resolved = JpegOutputSettings(gainMapMinLog2 = 100f).resolvedForPlatform()
        assertTrue(resolved.gainMapMaxLog2 > resolved.gainMapMinLog2)
    }

    @Test
    fun identicalHdrAndSdrEncodeAtIdentityFloor() {
        val mid = floatArrayOf(0.4f, 0.4f, 0.4f)
        val codes = UltraHdrGainmapMath.encodeTexel(mid, mid)
        assertTrue("mid-gray identity mapped to ${codes.toList()}", codes.all { it == 0 })
        val dark = floatArrayOf(0.02f, 0.02f, 0.02f)
        val darkCodes = UltraHdrGainmapMath.encodeTexel(dark, dark)
        assertTrue("shadow identity mapped to ${darkCodes.toList()}", darkCodes.all { it == 0 })
    }

    @Test
    fun highlightHeadroomGrowsMonotonically() {
        val sdr = floatArrayOf(0.8f, 0.8f, 0.8f)
        val low = UltraHdrGainmapMath.encodeTexel(floatArrayOf(1.2f, 1.2f, 1.2f), sdr)
        val high = UltraHdrGainmapMath.encodeTexel(floatArrayOf(4f, 4f, 4f), sdr)
        assertTrue("low headroom ${low.toList()} should be positive", low.all { it > 0 })
        assertTrue(
            "more headroom should map higher: ${low.toList()} vs ${high.toList()}",
            high.zip(low).all { (h, l) -> h > l }
        )
    }

    @Test
    fun multiChannelMapPreservesWarmLampHue() {
        // Warm lamp: red headroom over a near-white SDR base.
        val hdr = floatArrayOf(3f, 1.5f, 0.8f)
        val sdr = floatArrayOf(0.95f, 0.9f, 0.85f)
        val rgb = UltraHdrGainmapMath.encodeTexel(hdr, sdr, JpegOutputSettings(multiChannelMap = true))
        assertTrue(
            "warm lamp should keep R above B in the map: ${rgb.toList()}",
            rgb[0] > rgb[2]
        )
        val gray = UltraHdrGainmapMath.encodeTexel(hdr, sdr, JpegOutputSettings(multiChannelMap = false))
        assertEquals(gray[0], gray[1])
        assertEquals(gray[1], gray[2])
    }

    @Test
    fun saturationProtectionTamesColoredBrights() {
        val hdr = floatArrayOf(3f, 0.4f, 0.2f)
        val sdr = floatArrayOf(0.95f, 0.25f, 0.1f)
        val guarded = UltraHdrGainmapMath.encodeTexel(hdr, sdr, JpegOutputSettings(satProtect = 0.6f))
        val raw = UltraHdrGainmapMath.encodeTexel(hdr, sdr, JpegOutputSettings(satProtect = 0f))
        assertTrue(
            "satProtect should attenuate the saturated bright: ${guarded.toList()} vs ${raw.toList()}",
            guarded[0] < raw[0]
        )
    }

    @Test
    fun mapCodesDecodeBackThroughSharedMetadata() {
        val codes = UltraHdrGainmapMath.encodeTexel(floatArrayOf(2f, 2f, 2f), floatArrayOf(0.7f, 0.7f, 0.7f))
        val settings = JpegOutputSettings()
        val log = UltraHdrGainmapMath.decodeLog2(codes[0], settings)
        assertTrue("decoded log2 gain $log should carry headroom", log > 0.2f)
        assertTrue(log <= settings.gainMapMaxLog2)
    }

    @Test
    fun hdrExposureMatchesSdrDomain() {
        // Doubling both taps is a no-op: the quotient is exposure-invariant.
        val hdr = floatArrayOf(2f, 2f, 2f)
        val sdr = floatArrayOf(0.7f, 0.7f, 0.7f)
        val plain = UltraHdrGainmapMath.encodeTexel(hdr, sdr, JpegOutputSettings(hdrExposure = 1f))
        val exposed = UltraHdrGainmapMath.encodeTexel(
            floatArrayOf(4f, 4f, 4f), sdr, JpegOutputSettings(hdrExposure = 0.5f)
        )
        assertEquals(plain.toList(), exposed.toList())
    }
}
