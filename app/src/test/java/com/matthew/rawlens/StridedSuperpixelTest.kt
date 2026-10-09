// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito

class StridedSuperpixelTest {
    @Test fun `stride 2 matches the single-quad VF superpixel mean`() {
        // One quad per output pixel: R, (G1+G2)/2, B normalized.
        val frame = rawFrame(4, 4, BayerPattern.RGGB, 0, 0, rCode = 1000, bCode = 400,
            g1Code = 800, g2Code = 600)
        val img = StridedSuperpixel.sample(
            frame.image, 4, 4, 0, 0, 4, 4, frame.rowStride, 2,
            BayerPattern.RGGB, 0, 0, blacks(), 1023f, stride = 2
        )!!

        assertEquals(2, img.width)
        assertEquals(2, img.height)
        val range = 1023f - 64f
        val r = (1000f - 64f) / range
        val g = ((800f - 64f) + (600f - 64f)) / 2f / range
        val b = (400f - 64f) / range
        for (o in 0 until 4) {
            assertEquals(r, img.rgb[o * 3], 1e-6f)
            assertEquals(g, img.rgb[o * 3 + 1], 1e-6f)
            assertEquals(b, img.rgb[o * 3 + 2], 1e-6f)
        }
    }

    @Test fun `stride 4 averages every site in the block`() {
        // 8x8 RGGB, stride 4 -> 2x2. Only one site per color is hot inside
        // block (0,0); the means must dilute over the full 4x4 block (4 R,
        // 8 G, 4 B sites), proving no point sampling.
        val width = 8
        val height = 8
        val buf = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val code = when {
                x == 0 && y == 0 -> 1023 // the block's R site in quad (0,0)
                x == 1 && y == 0 -> 1023 // the block's G1 site in quad (0,0)
                x == 3 && y == 3 -> 1023 // the block's B site in quad (1,1)
                else -> 64
            }
            buf.putShort((y * width + x) * 2, code.toShort())
        }
        val img = StridedSuperpixel.sample(
            mockImage(buf, width * 2, 2), width, height, 0, 0, width, height,
            width * 2, 2, BayerPattern.RGGB, 0, 0, blacks(), 1023f, stride = 4
        )!!

        assertEquals(2, img.width)
        assertEquals(2, img.height)
        // Block (0,0): 1 hot R of 4, 1 hot G of 8, 1 hot B of 4.
        assertEquals(0.25f, img.rgb[0], 1e-6f)
        assertEquals(0.125f, img.rgb[1], 1e-6f)
        assertEquals(0.25f, img.rgb[2], 1e-6f)
        // Remaining blocks are all black.
        for (o in 1 until 4) {
            assertEquals(0f, img.rgb[o * 3], 1e-6f)
            assertEquals(0f, img.rgb[o * 3 + 1], 1e-6f)
            assertEquals(0f, img.rgb[o * 3 + 2], 1e-6f)
        }
    }

    @Test fun `every bayer pattern yields the same means on uniform colors`() {
        val range = 1023f - 64f
        val r = (900f - 64f) / range
        val g = (700f - 64f) / range
        val b = (500f - 64f) / range
        for (pattern in BayerPattern.entries) {
            val frame = rawFrame(8, 8, pattern, 0, 0, rCode = 900, gCode = 700, bCode = 500)
            val img = StridedSuperpixel.sample(
                frame.image, 8, 8, 0, 0, 8, 8, frame.rowStride, 2,
                pattern, 0, 0, blacks(), 1023f, stride = 4
            )!!
            assertEquals(2, img.width)
            assertEquals(2, img.height)
            for (o in 0 until 4) {
                assertEquals("pattern $pattern R", r, img.rgb[o * 3], 1e-6f)
                assertEquals("pattern $pattern G", g, img.rgb[o * 3 + 1], 1e-6f)
                assertEquals("pattern $pattern B", b, img.rgb[o * 3 + 2], 1e-6f)
            }
        }
    }

    @Test fun `strides and padding are honored`() {
        // pixelStride 4 with row padding, stride 4 over 8x8.
        val width = 8
        val height = 8
        val pixelStride = 4
        val rowStride = (width - 1) * pixelStride + 2 + 6
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val code = when (BayerPattern.RGGB.colorAt(x, y)) {
                CfaColor.RED -> 900
                CfaColor.GREEN -> 700
                CfaColor.BLUE -> 500
            }
            buf.putShort(y * rowStride + x * pixelStride, code.toShort())
        }
        val img = StridedSuperpixel.sample(
            mockImage(buf, rowStride, pixelStride), width, height, 0, 0, width, height,
            rowStride, pixelStride, BayerPattern.RGGB, 0, 0, blacks(), 1023f, stride = 4
        )!!

        val range = 1023f - 64f
        for (o in 0 until 4) {
            assertEquals((900f - 64f) / range, img.rgb[o * 3], 1e-6f)
            assertEquals((700f - 64f) / range, img.rgb[o * 3 + 1], 1e-6f)
            assertEquals((500f - 64f) / range, img.rgb[o * 3 + 2], 1e-6f)
        }
    }

    @Test fun `sensor origin shift rephases the pattern`() {
        // RGGB content read at origin (1,0) exposes GRBG phase.
        val frame = rawFrame(8, 8, BayerPattern.GRBG, 0, 0, rCode = 900, gCode = 700, bCode = 500)
        val img = StridedSuperpixel.sample(
            frame.image, 8, 8, 0, 0, 8, 8, frame.rowStride, 2,
            BayerPattern.RGGB, 1, 0, blacks(), 1023f, stride = 4
        )!!

        val range = 1023f - 64f
        for (o in 0 until 4) {
            assertEquals((900f - 64f) / range, img.rgb[o * 3], 1e-6f)
            assertEquals((700f - 64f) / range, img.rgb[o * 3 + 1], 1e-6f)
            assertEquals((500f - 64f) / range, img.rgb[o * 3 + 2], 1e-6f)
        }
    }

    @Test fun `full-sensor geometry yields 1020x765 at stride 4`() {
        assertEquals(1020 to 765, StridedSuperpixel.outputDims(4080, 3060, 4))
        assertEquals(2040 to 1530, StridedSuperpixel.outputDims(4080, 3060, 2))
        try {
            StridedSuperpixel.outputDims(4080, 3060, 3)
            fail("odd stride must throw")
        } catch (_: IllegalArgumentException) { }
        try {
            StridedSuperpixel.outputDims(2, 2, 4)
            fail("crop smaller than stride must throw")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `codes below black floor at zero`() {
        val frame = rawFrame(4, 4, BayerPattern.RGGB, 0, 0, rCode = 10, gCode = 10, bCode = 10)
        val img = StridedSuperpixel.sample(
            frame.image, 4, 4, 0, 0, 4, 4, frame.rowStride, 2,
            BayerPattern.RGGB, 0, 0, blacks(), 1023f, stride = 2
        )!!
        img.rgb.forEach { assertEquals(0f, it, 0f) }
    }

    @Test fun `unsupported geometry returns null instead of throwing`() {
        val frame = rawFrame(8, 8, BayerPattern.RGGB, 0, 0, rCode = 900, gCode = 700, bCode = 500)
        fun sampleWith(
            pattern: BayerPattern? = BayerPattern.RGGB,
            white: Float = 1023f,
            cropW: Int = 8,
            cropH: Int = 8,
            rowStride: Int = frame.rowStride,
            stride: Int = 4
        ) = StridedSuperpixel.sample(
            frame.image, 8, 8, 0, 0, cropW, cropH, rowStride, 2,
            pattern, 0, 0, blacks(), white, stride
        )

        assertNull(sampleWith(pattern = null))
        assertNull(sampleWith(white = 64f))
        assertNull(sampleWith(cropW = 7))
        assertNull(sampleWith(cropH = 4, stride = 8))
        assertNull(sampleWith(rowStride = 4))
        assertNull(sampleWith(stride = 3))
        assertNull(sampleWith(stride = 1))
        assertNull(StridedSuperpixel.fromCfa(
            UnpackedRawCfa(6, 6, BayerPattern.RGGB, FloatArray(36), RawCrop(0, 0, 6, 6), 0, 0), 8
        ))
    }

    @Test fun `buffer position is preserved and fromCfa agrees`() {
        val pattern = BayerPattern.GBRG
        val width = 16
        val height = 16
        val buf = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val code = when (pattern.colorAt(x, y)) {
                CfaColor.RED -> 900
                CfaColor.GREEN -> 700
                CfaColor.BLUE -> 500
            }
            buf.putShort((y * width + x) * 2, code.toShort())
            values[y * width + x] = (code - 64f) / (1023f - 64f)
        }
        buf.position(0)
        val limit = buf.limit()

        val fromBuffer = StridedSuperpixel.sample(
            mockImage(buf, width * 2, 2), width, height, 0, 0, width, height,
            width * 2, 2, pattern, 0, 0, blacks(), 1023f, stride = 4
        )!!
        assertEquals(0, buf.position())
        assertEquals(limit, buf.limit())

        val cfa = UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height), 0, 0)
        val fromArray = StridedSuperpixel.fromCfa(cfa, 4)!!

        assertEquals(fromBuffer.width, fromArray.width)
        assertEquals(fromBuffer.height, fromArray.height)
        assertTrue(fromBuffer.rgb.size == fromArray.rgb.size)
        fromBuffer.rgb.forEachIndexed { i, v ->
            assertEquals(v, fromArray.rgb[i], 1e-6f)
        }
    }

    private fun blacks() = listOf(64f, 64f, 64f, 64f)

    private data class RawFrame(val image: Image, val rowStride: Int)

    private fun mockImage(buf: ByteBuffer, rowStride: Int, pixelStride: Int): Image {
        val image = Mockito.mock(Image::class.java)
        val plane = Mockito.mock(Image.Plane::class.java)
        Mockito.`when`(plane.buffer).thenReturn(buf)
        Mockito.`when`(plane.rowStride).thenReturn(rowStride)
        Mockito.`when`(plane.pixelStride).thenReturn(pixelStride)
        Mockito.`when`(image.planes).thenReturn(arrayOf(plane))
        return image
    }

    private fun rawFrame(
        width: Int,
        height: Int,
        pattern: BayerPattern,
        originX: Int,
        originY: Int,
        rCode: Int,
        bCode: Int,
        gCode: Int = 0,
        g1Code: Int = gCode,
        g2Code: Int = gCode
    ): RawFrame {
        val rowStride = width * 2
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = originX + x
            val sy = originY + y
            val code = when (pattern.colorAt(sx, sy)) {
                CfaColor.RED -> rCode
                CfaColor.BLUE -> bCode
                CfaColor.GREEN -> if (sy and 1 == 0) g1Code else g2Code
            }
            buf.putShort((y * width + x) * 2, code.toShort())
        }
        return RawFrame(mockImage(buf, rowStride, 2), rowStride)
    }
}
