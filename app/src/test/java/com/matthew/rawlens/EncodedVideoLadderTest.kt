// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoLadderTest {
    private val sensor = listOf(
        EncodedVideoLadder.RawSize(4080, 3060), // 12.48 MP max
        EncodedVideoLadder.RawSize(3264, 2448), // 7.99 MP
        EncodedVideoLadder.RawSize(2048, 1536), // 3.15 MP
        EncodedVideoLadder.RawSize(1024, 768), // 0.79 MP
    )

    @Test
    fun fullResolvesToMax() {
        assertEquals(
            EncodedVideoLadder.RawSize(4080, 3060),
            EncodedVideoLadder.resolveSizes(sensor, EncodedVideoLadder.Rung.FULL)
        )
    }

    @Test
    fun highLandsNearPointSevenClass() {
        // Cap = 12.48M * 0.65 = 8.11M -> 3264x2448 (7.99M).
        assertEquals(
            EncodedVideoLadder.RawSize(3264, 2448),
            EncodedVideoLadder.resolveSizes(sensor, EncodedVideoLadder.Rung.HIGH)
        )
    }

    @Test
    fun balancedLandsNearThird() {
        // Cap = 12.48M * 0.35 = 4.37M -> 2048x1536 (3.15M).
        assertEquals(
            EncodedVideoLadder.RawSize(2048, 1536),
            EncodedVideoLadder.resolveSizes(sensor, EncodedVideoLadder.Rung.BALANCED)
        )
    }

    @Test
    fun efficientLandsNearFifth() {
        // Cap = 12.48M * 0.2 = 2.50M -> 1024x768 (0.79M).
        assertEquals(
            EncodedVideoLadder.RawSize(1024, 768),
            EncodedVideoLadder.resolveSizes(sensor, EncodedVideoLadder.Rung.EFFICIENT)
        )
    }

    @Test
    fun singleSizeSensorResolvesToMaxOnEveryRung() {
        val single = listOf(EncodedVideoLadder.RawSize(4080, 3060))
        for (rung in EncodedVideoLadder.Rung.entries) {
            assertEquals(
                EncodedVideoLadder.RawSize(4080, 3060),
                EncodedVideoLadder.resolveSizes(single, rung)
            )
        }
    }

    @Test
    fun unsortedInputStillPicksMax() {
        val shuffled = listOf(
            EncodedVideoLadder.RawSize(1024, 768),
            EncodedVideoLadder.RawSize(4080, 3060),
            EncodedVideoLadder.RawSize(2048, 1536)
        )
        assertEquals(
            EncodedVideoLadder.RawSize(4080, 3060),
            EncodedVideoLadder.resolveSizes(shuffled, EncodedVideoLadder.Rung.FULL)
        )
        assertEquals(
            EncodedVideoLadder.RawSize(2048, 1536),
            EncodedVideoLadder.resolveSizes(shuffled, EncodedVideoLadder.Rung.BALANCED)
        )
    }

    @Test
    fun megapixelsReadsSane() {
        assertEquals(12.4848, EncodedVideoLadder.megapixels(4080, 3060), 1e-4)
    }

    @Test
    fun exportDimsStepsDownEvenly() {
        val raw = EncodedVideoLadder.RawSize(4080, 3060)
        assertEquals(EncodedVideoLadder.RawSize(2040, 1530), EncodedVideoLadder.exportDims(raw, 2))
        assertEquals(EncodedVideoLadder.RawSize(1020, 765), EncodedVideoLadder.exportDims(raw, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportDimsRejectsOddStep() {
        EncodedVideoLadder.exportDims(EncodedVideoLadder.RawSize(4080, 3060), 3)
    }

    @Test
    fun heatDegradeStepsDownOneRungOnMultiSizeSensor() {
        val d = EncodedVideoLadder.heatDegrade(sensor, EncodedVideoLadder.Rung.FULL, 2, true)
        assertEquals(EncodedVideoLadder.Rung.HIGH, d.rung)
        assertEquals(2, d.step)
        assertEquals(true, d.preferMhc)
    }

    @Test
    fun heatDegradeSingleSizeSensorFallsToCheapSuperpixel() {
        val single = listOf(EncodedVideoLadder.RawSize(4080, 3060))
        val d = EncodedVideoLadder.heatDegrade(single, EncodedVideoLadder.Rung.FULL, 2, true)
        assertEquals(EncodedVideoLadder.Rung.FULL, d.rung)
        assertEquals(4, d.step)
        assertEquals(false, d.preferMhc)
    }

    @Test
    fun heatDegradeAtBottomKeepsCheapStep() {
        val d = EncodedVideoLadder.heatDegrade(sensor, EncodedVideoLadder.Rung.EFFICIENT, 4, false)
        assertEquals(EncodedVideoLadder.Rung.EFFICIENT, d.rung)
        assertEquals(4, d.step)
        assertEquals(false, d.preferMhc)
    }

    @Test
    fun cappedCropYieldsTrue4kWindowOnOpenGate() {
        assertEquals(
            EncodedVideoLadder.Crop(120, 450, 3840, 2160),
            EncodedVideoLadder.centerCropCapped16x9(4080, 3060)
        )
    }

    @Test
    fun cappedCropKeepsFullWidthFitOnSmallSensor() {
        assertEquals(
            EncodedVideoLadder.centerCrop16x9(2048, 1536),
            EncodedVideoLadder.centerCropCapped16x9(2048, 1536)
        )
    }

    @Test
    fun cappedCropHonorsCustomCap() {
        assertEquals(
            EncodedVideoLadder.Crop(1080, 990, 1920, 1080),
            EncodedVideoLadder.centerCropCapped16x9(4080, 3060, 1920, 1080)
        )
    }

    @Test
    fun centerCrop16x9IsExactAndEven() {
        val crop = EncodedVideoLadder.centerCrop16x9(4080, 3060)
        assertEquals(0, crop.left)
        assertEquals(4080, crop.width)
        // 16:9 height, even origin/dims (Bayer phase preserved).
        assertEquals(0, crop.top % 2)
        assertEquals(0, crop.height % 2)
        assertEquals(16.0 / 9.0, crop.width.toDouble() / crop.height, 0.002)
        // Centered within the 2px the double even-floor can cost.
        assertTrue(Math.abs(3060 - (crop.top * 2 + crop.height)) <= 2)
        // Step-2 export of the crop.
        val exp = EncodedVideoLadder.exportDims(
            EncodedVideoLadder.RawSize(crop.width, crop.height), 2
        )
        assertEquals(2040, exp.width)
        assertEquals(crop.height / 2, exp.height)
    }
    @org.junit.Test fun encodedVideoWidescreenGeometry() {
        for (crop in listOf(VideoCrop.WIDE_16_9, VideoCrop.SCOPE_2_39, VideoCrop.UNIVISIUM_2_00)) {
            val c = EncodedVideoLadder.encodedVideoCrop(4080, 3060, crop)
            org.junit.Assert.assertEquals(0, c.top % 2)
            org.junit.Assert.assertEquals(0, c.left % 2)
            org.junit.Assert.assertEquals(crop.ratio!!, c.width.toDouble()/c.height, 0.003)
            org.junit.Assert.assertTrue(c.top + c.height <= 3060)
            org.junit.Assert.assertEquals(0, EncodedVideoLadder.encodedVideoHeight(1920, crop) % 2)
        }
    }

}
