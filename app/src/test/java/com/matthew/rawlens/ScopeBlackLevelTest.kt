// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class ScopeBlackLevelTest {
    @Test fun perSiteBlackLevelsNormalizeBothScopesWithoutFrameworkConstruction() {
        val black = intArrayOf(64, 65, 66, 67)
        val image = Mockito.mock(Image::class.java)
        val plane = Mockito.mock(Image.Plane::class.java)
        Mockito.`when`(image.width).thenReturn(2)
        Mockito.`when`(image.height).thenReturn(2)
        Mockito.`when`(plane.rowStride).thenReturn(4)
        Mockito.`when`(plane.pixelStride).thenReturn(2)
        for (atWhite in listOf(false, true)) {
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
            for (i in 0..3) buffer.putShort(i * 2, (if (atWhite) 1024 else black[i]).toShort())
            Mockito.`when`(plane.buffer).thenReturn(buffer)
            val histogram = RawHistogramSampler.sample(image, plane, 0, black, 1023, null, 0.0)!!
            val bin = if (atWhite) 63 else 0
            assertEquals(1, histogram.red[bin])
            assertEquals(2, histogram.green[bin])
            assertEquals(1, histogram.blue[bin])
            val waveform = RawWaveformSampler.sample(image, plane, 0, black, 1023, null, 0.0)!!
            val level = if (atWhite) RawWaveformSampler.LEVELS - 1 else 0
            assertEquals(1, waveform.red[level])
            assertEquals(1, waveform.green[level])
            val right = RawWaveformSampler.columnOf(1, 2) * RawWaveformSampler.LEVELS + level
            assertEquals(1, waveform.green[right])
            assertEquals(1, waveform.blue[right])
        }
    }
}
