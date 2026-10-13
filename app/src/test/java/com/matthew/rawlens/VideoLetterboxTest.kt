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

    @Test fun portraitDrawsTopBottomBars() {
        val bars = VideoLetterbox.compute(viewW, viewH, ratio, 0)!!
        assertTrue(bars.topBottom)
        assertEquals((viewH - viewW / ratio) / 2f, bars.bar, 0.001f)
    }

    @Test fun upsideDownMatchesPortrait() {
        val bars = VideoLetterbox.compute(viewW, viewH, ratio, 180)!!
        assertTrue(bars.topBottom)
        assertEquals((viewH - viewW / ratio) / 2f, bars.bar, 0.001f)
    }

    @Test fun landscapeDrawsSideBars() {
        for (degrees in listOf(90, 270)) {
            val bars = VideoLetterbox.compute(viewW, viewH, ratio, degrees)!!
            assertFalse("degrees=$degrees", bars.topBottom)
            assertEquals("degrees=$degrees", (viewW - viewH / ratio) / 2f, bars.bar, 0.001f)
        }
    }

    @Test fun landscapeVisibleWidthMatchesEncoderCrop() {
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
}
