// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log2

class RawAverageMeterTest {
    private val settings = RawExposureAssistSettings(meterMode = RawExposureMeterMode.AVERAGE)
    private fun frame(w: Int = 256, h: Int = 192, pattern: Int = 0, black: DoubleArray = DoubleArray(4),
                      crop: IntArray? = null, config: RawExposureAssistSettings = settings,
                      code: RawExposureCodeReader = RawExposureCodeReader { _, _ -> 256 }) =
        RawExposureAssistSampler.sampleCodes(w, h, pattern, black, 1024, 0, false, 1,
            config, meterCrop = crop, code = code)

    @Test fun meanIsComputedBeforeLogAndIncludesBothGreens() {
        for (pattern in 0..3) {
            val channels = RawEttrSampler.phaseChannelLut(pattern)
            val levels = intArrayOf(1024, 128, 256, 512)
            val f = frame(pattern = pattern, code = RawExposureCodeReader { x, y -> levels[channels[(y and 1) * 2 + (x and 1)]] })!!
            val a = f.average!!
            assertEquals(log2((1024.0 + 128 + 256 + 512) / (4 * 1024)), a.ev, 1e-12)
            assertEquals(100.0, a.clippedPercent, 0.0)
            assertEquals(MagicLanternRawExposure.RED, a.clippedChannels)
            assertNull(f.spot); assertNull(f.colors)
        }
    }

    @Test fun clippedAreaCountsQuadsOnceEvenWhenSeveralChannelsClip() {
        val a = frame(code = RawExposureCodeReader { x, _ -> if (x < 64) 1024 else 256 })!!.average!!
        assertEquals(25.0, a.clippedPercent, 0.0)
        assertEquals(7, a.clippedChannels)
        assertEquals(log2(0.25 + 0.75 * 0.25), a.ev, 1e-12)
    }

    @Test fun eitherGreenAndBlueCanIndependentlyReportClipping() {
        for (pattern in 0..3) for (selected in listOf(1, 2, 3)) {
            val channels = RawEttrSampler.phaseChannelLut(pattern)
            val a = frame(pattern = pattern, code = RawExposureCodeReader { x, y ->
                if (channels[(y and 1) * 2 + (x and 1)] == selected) 1024 else 256
            })!!.average!!
            assertEquals(if (selected == 3) 1 else 2, a.clippedChannels)
            assertEquals(100.0, a.clippedPercent, 0.0)
        }
    }

    @Test fun phaseBlackLevelsAreNormalizedBeforeAreaAverage() {
        val black = doubleArrayOf(0.0, 64.0, 128.0, 256.0)
        val a = frame(black = black, code = RawExposureCodeReader { x, y ->
            val b = black[(y and 1) * 2 + (x and 1)].toInt(); b + (1024 - b) / 4
        })!!.average!!
        assertEquals(-2.0, a.ev, 0.0)
        assertEquals(0.0, a.clippedPercent, 0.0)
    }

    @Test fun recordingCropExcludesClippedLetterboxAndAlignsInward() {
        val f = frame(crop = intArrayOf(65, 33, 126, 126), code = RawExposureCodeReader { x, y ->
            if (x in 66 until 190 && y in 34 until 158) 256 else 1024
        })!!
        val a = f.average!!
        assertEquals(-2.0, a.ev, 0.0); assertEquals(0, a.clippedCells)
        assertEquals(66f / 256, a.left, 0f); assertEquals(158f / 192, a.bottom, 0f)
        assertNull(frame(crop = intArrayOf(240, 0, 32, 32)))
        assertNull(frame(crop = intArrayOf(0, 0, 1, 1)))
    }

    @Test fun sampledMeanMatchesIndependentDenseRampAndReadBudgetIsBounded() {
        val w = 4080; val h = 3060
        var reads = 0
        val a = frame(w, h, code = RawExposureCodeReader { x, y -> reads++; 32 + x / 8 + y / 16 })!!.average!!
        val denseMean = 32 + (0 until w).sumOf { it / 8 }.toDouble() / w + (0 until h).sumOf { it / 16 }.toDouble() / h
        assertEquals(log2(denseMean / 1024), a.ev, 0.002)
        assertEquals(0, a.clippedCells)
        assertTrue("Average must not scan a full 12 MP image: $reads", reads <= 65_536)
        assertEquals(4 * a.sampledCells, reads)
        var offReads = 0
        assertNull(frame(config = settings.copy(spotMeter = false), code = RawExposureCodeReader { _, _ -> offReads++; 256 }))
        assertEquals(0, offReads)
    }

    @Test fun averageIsIndependentOfOverlayGridAndPhotoSpotLocation() {
        val average = frame()!!.average
        for (mode in RawExposureOverlayMode.entries) {
            assertEquals(average, frame(config = settings.copy(overlay = mode))!!.average)
        }
        assertEquals(RawExposureMeterMode.AVERAGE, RawExposureAssistSettings().meterMode)
        assertEquals(RawExposureMeterMode.AVERAGE, RawExposureMeterMode.fromName("invalid"))
        assertEquals(RawExposureMeterMode.AVERAGE, RawExposureMeterMode.fromName(null))
        assertEquals(RawExposureMeterMode.SPOT, RawExposureMeterMode.fromName("SPOT"))
    }

    @Test fun compactTextDoesNotHideRareClippingOrReportBlackAsZeroEv() {
        fun reading(ev: Double = -3.2, clip: Int = 0, count: Int = 10_000, black: Boolean = false) =
            RawAverageReading(ev, black, if (clip == 0) 0 else 7, clip, count, 0f, 0f, 1f, 1f)
        assertEquals("AVG —", RawAverageMeterText.format(null, true))
        assertEquals("AVG −3.2EV 0%", RawAverageMeterText.format(reading(), true))
        assertEquals("AVG −3.2EV <0.1%", RawAverageMeterText.format(reading(clip = 1), true))
        assertEquals("AVG −3.2EV 0.4%", RawAverageMeterText.format(reading(clip = 40), true))
        assertEquals("AVG ≤−10.0EV 0%", RawAverageMeterText.format(reading(-10.0, black = true), true))
        assertTrue(RawAverageMeterText.format(reading(clip = 1)).contains("estimated"))
    }
}
