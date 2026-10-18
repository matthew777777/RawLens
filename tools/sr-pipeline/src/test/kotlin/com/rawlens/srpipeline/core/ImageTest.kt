package com.rawlens.srpipeline.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTest {

    @Test
    fun `zeros have expected shape and contents`() {
        val img = Image.zeros(3, 4, 2)
        assertEquals(3, img.height)
        assertEquals(4, img.width)
        assertEquals(2, img.channels)
        assertEquals(24, img.data.size)
        for (v in img.data) {
            assertEquals(0f, v, 0f)
        }
    }

    @Test
    fun `get set and channel roundtrip`() {
        val img = Image.zeros(2, 3, 2)
        img[1, 2, 0] = 1.5f
        img[0, 1, 1] = -2.25f
        assertEquals(1.5f, img[1, 2, 0], 0f)
        assertEquals(-2.25f, img[0, 1, 1], 0f)
        val c0 = img.channel(0)
        assertEquals(1.5f, c0[1 * 3 + 2], 0f)
    }

    @Test
    fun `complex spectrum stores real and imag`() {
        val spec = ComplexSpectrum(2, 2, floatArrayOf(1f, 2f, 3f, 4f), FloatArray(4))
        assertEquals(1f to 0f, spec[0, 0])
        spec.set(1, 1, 9f, -9f)
        assertEquals(9f to -9f, spec[1, 1])
    }
}
