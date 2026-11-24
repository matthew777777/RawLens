// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawNoiseModelTest {
    @Test fun readoutsDoNotShareTrainingAndStockKeyStaysCompatible() {
        val m = mock(RawFrameMetadata::class.java)
        `when`(m.cameraId).thenReturn("0")
        `when`(m.sensitivityIso).thenReturn(400)
        `when`(m.imageWidth).thenReturn(4080)
        `when`(m.imageHeight).thenReturn(3060)
        val stock = requireNotNull(RawNoiseModel.Source.of(m)).rawModeKey
        assertFalse(stock.contains("/readout:"))
        `when`(m.sensorReadoutProfile).thenReturn("dcg14")
        val dcg = requireNotNull(RawNoiseModel.Source.of(m)).rawModeKey
        assertEquals("$stock/readout:dcg14", dcg)
        `when`(m.sensorReadoutProfile).thenReturn("2xdsg14")
        assertNotEquals(dcg, requireNotNull(RawNoiseModel.Source.of(m)).rawModeKey)
        `when`(m.compositeUsedCount).thenReturn(3)
        assertNull(RawNoiseModel.Source.of(m))
    }

    @Test fun exposureZebrasUseBlindEstimateRefinedBySharedPrior() {
        val random = java.util.Random(123)
        val w = 256; val h = 256
        val pixels = ByteBuffer.allocate(w*h*2).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val level = .02 + .6 * x / w
            val value = 64 + 959 * (level + random.nextGaussian() * kotlin.math.sqrt(.002*level+.00002))
            pixels.putShort((y*w+x)*2, value.toInt().coerceIn(0,1023).toShort())
        }
        var calls = 0
        val prior = RawNoisePrior { a, _, _ -> calls++; a to .0001 }
        val frame = requireNotNull(RawExposureAssistSampler.sampleBuffer(pixels,w,h,w*2,2,
            android.graphics.ImageFormat.RAW_SENSOR,0,DoubleArray(4) {64.0},1023,0,false,123,
            RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS),noisePrior=prior))
        assertEquals(1,calls)
        assertTrue(frame.underUsesNoiseProfile)
    }
}
