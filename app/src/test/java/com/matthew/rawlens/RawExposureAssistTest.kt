// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log2

class RawExposureAssistTest {
    private val both = RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS, true, RawExposureMeterMode.SPOT)
    private fun sample(settings: RawExposureAssistSettings = both, black: DoubleArray = DoubleArray(4),
                       pattern: Int = 0, range: Double = Double.NaN, focus: Pair<Float, Float>? = null,
                       code: (Int, Int) -> Int = { _, _ -> 256 }) =
        RawExposureAssistSampler.sampleCodes(256, 192, pattern, black, 1024, 0, false, 100L,
            settings, focus, range, code = RawExposureCodeReader(code))!!

    @Test fun portHasAllEightOriginalClippingColoursAndUnderPriority() {
        val expected = intArrayOf(0, 0xff0000ff.toInt(), 0xff00ff00.toInt(), 0xff00ffff.toInt(),
            0xffff0000.toInt(), 0xffff00ff.toInt(), 0xffffff00.toInt(), 0xff000000.toInt())
        for (mask in 0..7) {
            assertEquals(expected[mask], MagicLanternRawExposure.zebraColor(false,
                mask and 4 != 0, mask and 2 != 0, mask and 1 != 0))
            assertEquals(0xfff0f0f0.toInt(), MagicLanternRawExposure.zebraColor(true,
                mask and 4 != 0, mask and 2 != 0, mask and 1 != 0))
        }
    }

    @Test fun rawEvAnchorsAndBlackCodeFloorMatchThePort() {
        assertEquals(0.0, MagicLanternRawExposure.rawToEv(1.0, 1.0 / 4096), 0.0)
        assertEquals(-1.0, MagicLanternRawExposure.rawToEv(0.5, 1.0 / 4096), 0.0)
        assertEquals(-2.0, MagicLanternRawExposure.rawToEv(0.25, 1.0 / 4096), 0.0)
        assertEquals(-12.0, MagicLanternRawExposure.rawToEv(0.0, 1.0 / 4096), 0.0)
        assertEquals(0.25, MagicLanternRawExposure.evToLevel(-2.0), 0.0)
    }

    @Test fun flatQuarterWhiteReadsMinusTwoAtEveryCfaAndPhaseBlackLevel() {
        val black = doubleArrayOf(0.0, 256.0, 512.0, 768.0)
        for (pattern in 0..3) {
            val f = sample(black = black, pattern = pattern) { x, y ->
                val b = black[(y and 1) * 2 + (x and 1)]
                (b + (1024 - b) / 4).toInt()
            }
            assertEquals(-2.0, f.spot!!.ev, 1e-12)
            assertEquals(0, f.spot.clippedChannels)
            assertTrue(f.colors!!.all { it == 0 })
        }
    }

    @Test fun exactSensorWhiteIsClippedEvenIfOnlyOneGreenPhaseClips() {
        // Phase (0,1) is Gb on RGGB: the two greens must not be averaged before checking saturation.
        val f = sample { x, y -> if (x % 2 == 0 && y % 2 == 1) 1024 else 256 }
        assertEquals(MagicLanternRawExposure.GREEN, f.spot!!.clippedChannels)
        assertTrue(f.colors!!.any { it == 0xff00ff00.toInt() })
    }

    @Test fun blueAndRedClippingColoursStayCorrectForEveryCfa() {
        // Independently stated Camera2 CFA phase layouts (R/G/G/B), not the sampler's lookup helper.
        val layouts = arrayOf(intArrayOf(4, 2, 2, 1), intArrayOf(2, 4, 1, 2),
            intArrayOf(2, 1, 4, 2), intArrayOf(1, 2, 2, 4))
        for (pattern in 0..3) for (channel in listOf(1, 4)) {
            val f = sample(pattern = pattern) { x, y ->
                if (layouts[pattern][(y and 1) * 2 + (x and 1)] == channel) 1024 else 256
            }
            assertEquals(channel, f.spot!!.clippedChannels)
            val expected = if (channel == 4) 0xffff0000.toInt() else 0xff0000ff.toInt()
            assertTrue(f.colors!!.any { it == expected })
        }
    }

    @Test fun spotAveragesRawBeforeLogAndReportsClippedChannelSeparately() {
        // Centre 5x5 ROI has one white R sample: mean is 1/25, while mean(log) is very different.
        val f = sample { x, y -> if (x == 128 && y == 96) 1024 else 0 }
        assertEquals(log2(1.0 / 25), f.spot!!.ev, 1e-12)
        assertEquals(MagicLanternRawExposure.RED, f.spot.clippedChannels)
    }

    @Test fun underFloorUsesNoiseDrWhenKnownAndBlackOnlyOtherwise() {
        val dark = sample { _, _ -> 1 }
        assertTrue(dark.colors!!.all { it == 0 })
        val noise = sample(range = 9.0) { _, _ -> 1 }
        assertTrue(noise.underUsesNoiseProfile)
        assertTrue(noise.colors!!.any { it == 0xfff0f0f0.toInt() })
        val black = sample { _, _ -> 0 }
        assertTrue(black.spot!!.atBlack)
        assertEquals(-10.0, black.spot.ev, 0.0)
    }

    @Test fun tapMapsBackThroughRotationAndMirroringAndClipsRoiAtEdges() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            for ((u, v) in listOf(0f to 0f, 0.2f to 0.7f, 1f to 1f)) {
                val view = FocusPeakingGeometry.viewPoint(u, v, rotation, mirror)
                val sensor = RawExposureAssistSampler.sensorPoint(view.first, view.second, rotation, mirror)
                assertEquals(u, sensor.first, 1e-6f); assertEquals(v, sensor.second, 1e-6f)
            }
        }
        val f = sample(focus = 0f to 0f)
        assertEquals(0f, f.spot!!.left, 0f); assertEquals(0f, f.spot.top, 0f)
        assertTrue(f.spot.right > 0 && f.spot.bottom > 0)
    }

    @Test fun falseColourUsesRawEvAndDoesNotNeedAnyLookOrWhiteBalance() {
        val settings = RawExposureAssistSettings(RawExposureOverlayMode.FALSE_COLOR, false)
        for ((value, ev) in listOf(1024 to 0.0, 256 to -2.0, 1 to -10.0)) {
            val f = sample(settings = settings) { _, _ -> value }
            val expected = TurboColormap.argb((ev + 10) / 10)
            assertTrue(f.colors!!.all { it == expected })
            assertNull(f.spot)
        }
        // Upstream polynomial anchors, rounded to ARGB8. Guard against swapped coefficients/channels.
        assertEquals(0xff23171b.toInt(), TurboColormap.argb(0.0))
        assertEquals(0xff900d00.toInt(), TurboColormap.argb(1.0))
    }

    @Test fun disabledAssistsReadNoRawAndSpotOnlyAllocatesNoOverlayGrid() {
        var calls = 0
        val result = RawExposureAssistSampler.sampleCodes(256, 192, 0, DoubleArray(4), 1024,
            0, false, 0L, RawExposureAssistSettings(spotMeter = false)) { _, _ -> calls++; 0 }
        assertNull(result); assertEquals(0, calls)
        val spot = sample(RawExposureAssistSettings(spotMeter = true, meterMode = RawExposureMeterMode.SPOT))
        assertNull(spot.colors); assertNotNull(spot.spot)
        assertTrue(RawExposureAssistSettings().spotMeter)
        assertEquals(RawExposureOverlayMode.OFF, RawExposureAssistSettings().overlay)
    }

    @Test fun mainCycleNeverChangesTheIndependentDefaultOnSpotMeter() {
        var settings = RawExposureAssistSettings()
        for (expected in listOf(RawExposureOverlayMode.ZEBRAS, RawExposureOverlayMode.FALSE_COLOR, RawExposureOverlayMode.OFF)) {
            settings = settings.copy(overlay = settings.overlay.next())
            assertEquals(expected, settings.overlay); assertTrue(settings.spotMeter)
        }
    }

    @Test fun topHudHasSignedEvBlackFloorAndSeparateChannelClipping() {
        fun spot(ev: Double, black: Boolean = false, clip: Int = 0) = RawSpotReading(ev, black, clip, 0f, 0f, 1f, 1f)
        assertEquals("RAW SPOT  —", RawSpotMeterText.format(null))
        assertEquals("RAW SPOT  −3.2 EV", RawSpotMeterText.format(spot(-3.24)))
        assertEquals("RAW SPOT  0.0 EV · CLIP RG", RawSpotMeterText.format(spot(0.0, clip = 6)))
        assertEquals("RAW SPOT  ≤−12.0 EV", RawSpotMeterText.format(spot(-12.0, black = true)))
        assertEquals("RAW SPOT  0.0 EV", RawSpotMeterText.format(spot(-0.01)))
    }

    @Test fun invalidCalibrationRefusesTheFrameInsteadOfFabricatingEv() {
        val f = RawExposureAssistSampler.sampleCodes(256, 192, 0, doubleArrayOf(0.0, 0.0, 1024.0, 0.0),
            1024, 0, false, 0, both) { _, _ -> 123 }
        assertNull(f)
    }
}
