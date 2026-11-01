// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLetterboxTest {
    // 3:4 portrait view holding the full 4:3 sensor frame.
    private val viewW = 1080f
    private val viewH = 1440f
    private val ratio = (16.0 / 9.0).toFloat()

    @Test fun unrotatedSensorDrawsTopBottomBars() {
        val bars = VideoLetterbox.compute(viewW, viewH, ratio, 0)!!
        assertTrue(bars.topBottom)
        assertEquals((viewH - viewW / ratio) / 2f, bars.bar, 0.001f)
    }

    @Test fun oppositeSensorMountMatchesUnrotatedSensor() {
        val bars = VideoLetterbox.compute(viewW, viewH, ratio, 180)!!
        assertTrue(bars.topBottom)
        assertEquals((viewH - viewW / ratio) / 2f, bars.bar, 0.001f)
    }

    @Test fun portraitMountedSensorDrawsSideBars() {
        for (degrees in listOf(90, 270)) {
            val bars = VideoLetterbox.compute(viewW, viewH, ratio, degrees)!!
            assertFalse("degrees=$degrees", bars.topBottom)
            assertEquals("degrees=$degrees", (viewW - viewH / ratio) / 2f, bars.bar, 0.001f)
        }
    }

    @Test fun portraitVisibleWidthMatchesEncoderCrop() {
        // The side-bar overlay must frame exactly what the encoder keeps: the
        // visible view-width fraction equals the cropped sensor-height
        // fraction (sensor rows map to view columns at a 90-degree mount).
        val bars = VideoLetterbox.compute(viewW, viewH, ratio, 90)!!
        val resolved = VideoCrop.WIDE_16_9.resolve(4080, 3060)
        val visibleFraction = (viewW - 2f * bars.bar) / viewW
        assertEquals(resolved.height.toFloat() / 3060f, visibleFraction, 0.01f)
    }

    @Test fun fullFrameCropDrawsNothing() {
        assertNull(VideoLetterbox.compute(viewW, viewH, 0.5f, 0))
        assertNull(VideoLetterbox.compute(viewW, viewH, 0.5f, 90))
        assertNull(VideoLetterbox.compute(0f, viewH, ratio, 0))
        assertNull(VideoLetterbox.compute(viewW, 0f, ratio, 90))
    }

    @Test fun portraitGuidesMatchRawAndEncodedVideoAspectCrops() {
        val sensorW = 3264
        val sensorH = 2448
        for (rotation in listOf(90, 270)) {
            val frame = VfAspectFit.fill(viewW.toInt(), viewH.toInt(), sensorW, sensorH, rotation)
            for (crop in listOf(VideoCrop.WIDE_16_9, VideoCrop.SCOPE_2_39, VideoCrop.UNIVISIUM_2_00)) {
                val bars = VideoLetterbox.compute(frame[2].toFloat(), frame[3].toFloat(), crop.ratio!!.toFloat(), rotation)!!
                assertFalse("Portrait sensor rows must map to side bars: $crop", bars.topBottom)
                val keptFraction = (frame[2] - 2f * bars.bar) / frame[2]
                val raw = crop.resolve(sensorW, sensorH)
                val log = EncodedVideoLadder.encodedVideoCrop(sensorW, sensorH, crop)
                assertEquals("RAW $crop", raw.height.toFloat() / sensorH, keptFraction, .003f)
                assertEquals("Encoded Video $crop", log.height.toFloat() / sensorH, keptFraction, .003f)
            }
        }
    }

    @Test fun landscapeMountedSensorKeepsTopAndBottomCrop() {
        for (rotation in listOf(0, 180)) {
            val bars = VideoLetterbox.compute(1440f, 1080f, ratio, rotation)!!
            assertTrue(bars.topBottom)
            assertEquals(135f, bars.bar, .001f)
        }
    }

    @Test fun guidesUseTheCoveredSensorFrameRatherThanTheViewportAspect() {
        val frame = VfAspectFit.fill(400, 600, 3264, 2448, 0)
        assertEquals(-200, frame[0])
        val bars = VideoLetterbox.compute(frame[2].toFloat(), frame[3].toFloat(), ratio, 0)!!
        assertTrue(bars.topBottom)
        assertEquals(75f, bars.bar, .001f)
        val raw = VideoCrop.WIDE_16_9.resolve(3264, 2448)
        assertEquals(raw.height.toFloat() / 2448, (frame[3] - bars.bar * 2) / frame[3], .001f)
    }

}
