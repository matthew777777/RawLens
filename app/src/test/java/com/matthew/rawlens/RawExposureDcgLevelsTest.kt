// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawExposureDcgLevelsTest {
    private fun levels(black: FloatArray? = null, white: Int? = null, dcg: Boolean = true) =
        RawExposureAssistSampler.levels(DoubleArray(4) { 64.0 }, 1023, black, white, dcg)

    @Test fun unmatchedDcgFrameUsesReadoutLevelsInsteadOfStockRaw10() {
        val (black, white) = levels()
        assertArrayEquals(DoubleArray(4) { 1024.0 }, black, 0.0)
        assertEquals(16383, white)
        val (staleBlack, staleWhite) = levels(FloatArray(4) { 64f }, 1023)
        assertArrayEquals(black, staleBlack, 0.0)
        assertEquals(white, staleWhite)
    }

    @Test fun matchedDcgDynamicWhiteIsPreservedAndOrdinaryRawIsUnchanged() {
        val (black, white) = levels(FloatArray(4) { 1024f }, 15424)
        assertEquals(15424, white)
        assertEquals(1024.0, black[0], 0.0)
        val normal = levels(dcg = false)
        assertEquals(1023, normal.second)
        assertArrayEquals(DoubleArray(4) { 64.0 }, normal.first, 0.0)
    }

    private fun sample(code: Int, mode: RawExposureOverlayMode, dynamicWhite: Int? = null): RawExposureAssistFrame {
        val (black, white) = levels(FloatArray(4) { 1024f }, dynamicWhite)
        val buffer = ByteBuffer.allocate(64 * 48 * 2).order(ByteOrder.nativeOrder())
        repeat(64 * 48) { buffer.putShort(it * 2, code.toShort()) }
        return requireNotNull(RawExposureAssistSampler.sampleBuffer(buffer, 64, 48, 128, 2,
            ImageFormat.RAW_SENSOR, 2, black, white, 90, false, 123,
            RawExposureAssistSettings(mode, true, RawExposureMeterMode.SPOT)))
    }

    @Test fun dcgMidtonesAreNotZebraClippedWhenResultHasNotArrived() {
        val frame = sample(2000, RawExposureOverlayMode.ZEBRAS)
        assertTrue(frame.colors!!.all { it == 0 })
        assertEquals(0, frame.spot!!.clippedChannels)
        assertTrue(frame.spot.ev < 0)
    }

    @Test fun falseColorAndZebrasShareTheActualDcgClipAndBlackLevels() {
        val middle = sample(8224, RawExposureOverlayMode.FALSE_COLOR, 15424)
        assertEquals(-1.0, middle.spot!!.ev, 1e-12)
        assertEquals(0, middle.spot.clippedChannels)
        assertTrue(middle.colors!!.all { it == TurboColormap.argb(.9) })
        assertEquals(7, sample(15424, RawExposureOverlayMode.ZEBRAS, 15424).spot!!.clippedChannels)
        assertTrue(sample(1024, RawExposureOverlayMode.FALSE_COLOR, 15424).spot!!.atBlack)
    }
}
