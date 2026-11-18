// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class Aces2ToneMapperTest {
    @Test fun separateOptionPersistsWithoutChangingExistingModes() {
        assertEquals(PhotoToneMapper.ACES_2, PhotoToneMapper.fromPreference("ACES_2"))
        assertEquals(3, PhotoToneMapper.ACES_2.shaderMode)
        assertEquals(0, PhotoToneMapper.AGX.shaderMode)
        assertEquals(1, PhotoToneMapper.BASIC.shaderMode)
        assertEquals(2, PhotoToneMapper.SIGMOID.shaderMode)
    }

    @Test fun referenceMiddleGreyAndNeutralRamp() {
        for (p3 in listOf(false, true)) {
            val grey = Aces2DisplayTransform.map(floatArrayOf(.18f,.18f,.18f), p3)
            // ACES 2 maps 18% to 10 nits on the 100 nit output.
            for (c in grey) assertEquals(.1f, c, .003f)
            var previous = -1f
            for (i in 0..256) {
                val x = i / 16f
                val result = Aces2DisplayTransform.map(floatArrayOf(x,x,x), p3)
                assertTrue(result.all { it.isFinite() && it in 0f..1f })
                assertTrue(result[0] >= previous - 1e-6f)
                assertEquals(result[0],result[1],.003f)
                assertEquals(result[0],result[2],.003f)
                previous = result[0]
            }
        }
    }

    @Test fun referenceColoursAndNegativeAp1AreFinite() {
        // Independent OpenColorIO 2.6 ACES 2 reference, sRGB encoded output.
        val input = floatArrayOf(4f,1f,.1f)
        val output = Aces2DisplayTransform.map(input)
        val encoded = FloatArray(3) { if(output[it] <= .0031308f) output[it]*12.92f else 1.055f*output[it].pow(1f/2.4f)-.055f }
        assertArrayEquals(floatArrayOf(1f,.6623908f,.4765179f),encoded,.02f)
        for (p3 in listOf(false,true)) {
            val negative = Aces2DisplayTransform.map(floatArrayOf(.7f,.2f,-.01f),p3)
            assertTrue(negative.all { it.isFinite() && it in 0f..1f })
        }
        val settings = JpegOutputSettings(toneMapper = PhotoToneMapper.ACES_2)
        assertArrayEquals(output, AgxDisplayTransform.acescgToOutputLinearSrgb(input,settings),0f)
        assertArrayEquals(output, AgxDisplayTransform.acescgToOutputLinearSrgb(input,
            settings.copy(agxContrast=1.5f,agxSaturation=0f,highlightShoulder=1f)),0f)
    }
}
