// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogVideoLadderTest {
    private val sensor = listOf(
        LogVideoLadder.RawSize(4080, 3060), // 12.48 MP max
        LogVideoLadder.RawSize(3264, 2448), // 7.99 MP
        LogVideoLadder.RawSize(2048, 1536), // 3.15 MP
        LogVideoLadder.RawSize(1024, 768), // 0.79 MP
    )

    @Test
    fun fullResolvesToMax() {
        assertEquals(
            LogVideoLadder.RawSize(4080, 3060),
            LogVideoLadder.resolveSizes(sensor, LogVideoLadder.Rung.FULL)
        )
    }

    @Test
    fun highLandsNearPointSevenClass() {
        // Cap = 12.48M * 0.65 = 8.11M -> 3264x2448 (7.99M).
        assertEquals(
            LogVideoLadder.RawSize(3264, 2448),
            LogVideoLadder.resolveSizes(sensor, LogVideoLadder.Rung.HIGH)
        )
    }

    @Test
    fun balancedLandsNearThird() {
        // Cap = 12.48M * 0.35 = 4.37M -> 2048x1536 (3.15M).
        assertEquals(
            LogVideoLadder.RawSize(2048, 1536),
            LogVideoLadder.resolveSizes(sensor, LogVideoLadder.Rung.BALANCED)
        )
    }

    @Test
    fun efficientLandsNearFifth() {
        // Cap = 12.48M * 0.2 = 2.50M -> 1024x768 (0.79M).
        assertEquals(
            LogVideoLadder.RawSize(1024, 768),
            LogVideoLadder.resolveSizes(sensor, LogVideoLadder.Rung.EFFICIENT)
        )
    }

    @Test
    fun singleSizeSensorResolvesToMaxOnEveryRung() {
        val single = listOf(LogVideoLadder.RawSize(4080, 3060))
        for (rung in LogVideoLadder.Rung.entries) {
            assertEquals(
                LogVideoLadder.RawSize(4080, 3060),
                LogVideoLadder.resolveSizes(single, rung)
            )
        }
    }

    @Test
    fun unsortedInputStillPicksMax() {
        val shuffled = listOf(
            LogVideoLadder.RawSize(1024, 768),
            LogVideoLadder.RawSize(4080, 3060),
            LogVideoLadder.RawSize(2048, 1536)
        )
        assertEquals(
            LogVideoLadder.RawSize(4080, 3060),
            LogVideoLadder.resolveSizes(shuffled, LogVideoLadder.Rung.FULL)
        )
        assertEquals(
            LogVideoLadder.RawSize(2048, 1536),
            LogVideoLadder.resolveSizes(shuffled, LogVideoLadder.Rung.BALANCED)
        )
    }

    @Test
    fun megapixelsReadsSane() {
        assertEquals(12.4848, LogVideoLadder.megapixels(4080, 3060), 1e-4)
    }

    @Test
    fun exportDimsStepsDownEvenly() {
        val raw = LogVideoLadder.RawSize(4080, 3060)
        assertEquals(LogVideoLadder.RawSize(2040, 1530), LogVideoLadder.exportDims(raw, 2))
        assertEquals(LogVideoLadder.RawSize(1020, 765), LogVideoLadder.exportDims(raw, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportDimsRejectsOddStep() {
        LogVideoLadder.exportDims(LogVideoLadder.RawSize(4080, 3060), 3)
    }

    @Test
    fun heatDegradeStepsDownOneRungOnMultiSizeSensor() {
        val d = LogVideoLadder.heatDegrade(sensor, LogVideoLadder.Rung.FULL, 2, true)
        assertEquals(LogVideoLadder.Rung.HIGH, d.rung)
        assertEquals(2, d.step)
        assertEquals(true, d.preferMhc)
    }

    @Test
    fun heatDegradeSingleSizeSensorFallsToCheapSuperpixel() {
        val single = listOf(LogVideoLadder.RawSize(4080, 3060))
        val d = LogVideoLadder.heatDegrade(single, LogVideoLadder.Rung.FULL, 2, true)
        assertEquals(LogVideoLadder.Rung.FULL, d.rung)
        assertEquals(4, d.step)
        assertEquals(false, d.preferMhc)
    }

    @Test
    fun heatDegradeAtBottomKeepsCheapStep() {
        val d = LogVideoLadder.heatDegrade(sensor, LogVideoLadder.Rung.EFFICIENT, 4, false)
        assertEquals(LogVideoLadder.Rung.EFFICIENT, d.rung)
        assertEquals(4, d.step)
        assertEquals(false, d.preferMhc)
    }

    @Test
    fun cappedCropYieldsTrue4kWindowOnOpenGate() {
        assertEquals(
            LogVideoLadder.Crop(120, 450, 3840, 2160),
            LogVideoLadder.centerCropCapped16x9(4080, 3060)
        )
    }

    @Test
    fun cappedCropKeepsFullWidthFitOnSmallSensor() {
        assertEquals(
            LogVideoLadder.centerCrop16x9(2048, 1536),
            LogVideoLadder.centerCropCapped16x9(2048, 1536)
        )
    }

    @Test
    fun cappedCropHonorsCustomCap() {
        assertEquals(
            LogVideoLadder.Crop(1080, 990, 1920, 1080),
            LogVideoLadder.centerCropCapped16x9(4080, 3060, 1920, 1080)
        )
    }

    @Test
    fun centerCrop16x9IsExactAndEven() {
        val crop = LogVideoLadder.centerCrop16x9(4080, 3060)
        assertEquals(0, crop.left)
        assertEquals(4080, crop.width)
        // 16:9 height, even origin/dims (Bayer phase preserved).
        assertEquals(0, crop.top % 2)
        assertEquals(0, crop.height % 2)
        assertEquals(16.0 / 9.0, crop.width.toDouble() / crop.height, 0.002)
        // Centered within the 2px the double even-floor can cost.
        assertTrue(Math.abs(3060 - (crop.top * 2 + crop.height)) <= 2)
        // Step-2 export of the crop.
        val exp = LogVideoLadder.exportDims(
            LogVideoLadder.RawSize(crop.width, crop.height), 2
        )
        assertEquals(2040, exp.width)
        assertEquals(crop.height / 2, exp.height)
    }
}
