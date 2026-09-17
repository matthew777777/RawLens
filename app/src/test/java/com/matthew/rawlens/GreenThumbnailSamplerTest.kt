// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class GreenThumbnailSamplerTest {
    @Test fun `green proxy selects green sites for every bayer pattern`() {
        for (pattern in BayerPattern.entries) {
            val frame = rawFrame(8, 8, pattern, originX = 0, originY = 0,
                greenCode = 1023, otherCode = 64)
            val thumb = GreenThumbnailSampler.sample(
                frame.image, 8, 8, 0, 0, 8, 8, frame.rowStride, 2,
                pattern, 0, 0, blacks(), 1023f
            )!!

            assertEquals(4, thumb.quadsW)
            assertEquals(4, thumb.quadsH)
            thumb.luminance.forEach { assertEquals(1.0f, it, 1e-6f) }
        }
    }

    @Test fun `red and blue energy never leaks into the proxy`() {
        val frame = rawFrame(8, 8, BayerPattern.RGGB, originX = 0, originY = 0,
            greenCode = 64, otherCode = 1023)
        val thumb = GreenThumbnailSampler.sample(
            frame.image, 8, 8, 0, 0, 8, 8, frame.rowStride, 2,
            BayerPattern.RGGB, 0, 0, blacks(), 1023f
        )!!

        thumb.luminance.forEach { assertEquals(0.0f, it, 1e-6f) }
    }

    @Test fun `sensor origin shift rephases the pattern`() {
        // RGGB content read at origin (1,0) exposes GRBG phase.
        val frame = rawFrame(8, 8, BayerPattern.GRBG, originX = 0, originY = 0,
            greenCode = 1023, otherCode = 64)
        val thumb = GreenThumbnailSampler.sample(
            frame.image, 8, 8, 0, 0, 8, 8, frame.rowStride, 2,
            BayerPattern.RGGB, 1, 0, blacks(), 1023f
        )!!

        thumb.luminance.forEach { assertEquals(1.0f, it, 1e-6f) }
    }

    @Test fun `strides and padding are honored`() {
        // pixelStride 4 with row padding.
        val width = 8
        val height = 8
        val pixelStride = 4
        val rowStride = (width - 1) * pixelStride + 2 + 6
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val green = BayerPattern.RGGB.colorAt(x, y) == CfaColor.GREEN
            buf.putShort(y * rowStride + x * pixelStride, (if (green) 1023 else 64).toShort())
        }
        val image = Mockito.mock(Image::class.java)
        val plane = Mockito.mock(Image.Plane::class.java)
        Mockito.`when`(plane.buffer).thenReturn(buf)
        Mockito.`when`(plane.rowStride).thenReturn(rowStride)
        Mockito.`when`(plane.pixelStride).thenReturn(pixelStride)
        Mockito.`when`(image.planes).thenReturn(arrayOf(plane))

        val thumb = GreenThumbnailSampler.sample(
            image, width, height, 0, 0, width, height, rowStride, pixelStride,
            BayerPattern.RGGB, 0, 0, blacks(), 1023f
        )!!

        thumb.luminance.forEach { assertEquals(1.0f, it, 1e-6f) }
    }

    @Test fun `working set is bounded on full-resolution frames`() {
        val frame = rawFrame(512, 512, BayerPattern.BGGR, originX = 0, originY = 0,
            greenCode = 900, otherCode = 64)
        val thumb = GreenThumbnailSampler.sample(
            frame.image, 512, 512, 0, 0, 512, 512, frame.rowStride, 2,
            BayerPattern.BGGR, 0, 0, blacks(), 1023f
        )!!

        assertTrue(thumb.quadsW <= BurstReferenceSelection.MAX_THUMB_QUADS)
        assertTrue(thumb.quadsH <= BurstReferenceSelection.MAX_THUMB_QUADS)
        assertTrue(thumb.stepQuads > 1)
        assertEquals(thumb.luminance.size, thumb.quadsW * thumb.quadsH)
    }

    @Test fun `unsupported geometry returns null instead of throwing`() {
        val frame = rawFrame(8, 8, BayerPattern.RGGB, originX = 0, originY = 0,
            greenCode = 1023, otherCode = 64)
        fun sampleWith(
            pattern: BayerPattern? = BayerPattern.RGGB,
            white: Float = 1023f,
            cropW: Int = 8,
            cropH: Int = 8,
            rowStride: Int = frame.rowStride
        ) = GreenThumbnailSampler.sample(
            frame.image, 8, 8, 0, 0, cropW, cropH, rowStride, 2,
            pattern, 0, 0, blacks(), white
        )

        assertNull(sampleWith(pattern = null))
        assertNull(sampleWith(white = 64f))
        assertNull(sampleWith(cropW = 7))
        assertNull(sampleWith(cropH = 4))
        assertNull(sampleWith(rowStride = 4))
    }

    @Test fun `buffer position is preserved and fromCfa agrees`() {
        val pattern = BayerPattern.GBRG
        val width = 16
        val height = 16
        val buf = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val green = pattern.colorAt(x, y) == CfaColor.GREEN
            val code = if (green) 800 else 100
            buf.putShort((y * width + x) * 2, code.toShort())
            values[y * width + x] = (code - 64f) / (1023f - 64f)
        }
        // Buffer position/limit are the plane origin contract; sampling
        // must leave them undisturbed (absolute reads only).
        buf.position(0)
        val limit = buf.limit()
        val image = Mockito.mock(Image::class.java)
        val plane = Mockito.mock(Image.Plane::class.java)
        Mockito.`when`(plane.buffer).thenReturn(buf)
        Mockito.`when`(plane.rowStride).thenReturn(width * 2)
        Mockito.`when`(plane.pixelStride).thenReturn(2)
        Mockito.`when`(image.planes).thenReturn(arrayOf(plane))

        val fromBuffer = GreenThumbnailSampler.sample(
            image, width, height, 0, 0, width, height, width * 2, 2,
            pattern, 0, 0, blacks(), 1023f
        )!!
        assertEquals(0, buf.position())
        assertEquals(limit, buf.limit())

        val cfa = UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height), 0, 0)
        val fromArray = GreenThumbnailSampler.fromCfa(cfa)!!

        assertEquals(fromBuffer.quadsW, fromArray.quadsW)
        assertEquals(fromBuffer.quadsH, fromArray.quadsH)
        assertEquals(fromBuffer.thumbnailHash, fromArray.thumbnailHash)
        fromBuffer.luminance.forEachIndexed { i, v ->
            assertEquals(v, fromArray.luminance[i], 1e-6f)
        }
        assertNull(GreenThumbnailSampler.fromCfa(cfa.copy(width = 6, height = 6)))
    }

    private fun blacks() = listOf(64f, 64f, 64f, 64f)

    private data class RawFrame(val image: Image, val rowStride: Int)

    private fun rawFrame(
        width: Int,
        height: Int,
        pattern: BayerPattern,
        originX: Int,
        originY: Int,
        greenCode: Int,
        otherCode: Int
    ): RawFrame {
        val rowStride = width * 2
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val green = pattern.colorAt(originX + x, originY + y) == CfaColor.GREEN
            buf.putShort((y * width + x) * 2, (if (green) greenCode else otherCode).toShort())
        }
        val image = Mockito.mock(Image::class.java)
        val plane = Mockito.mock(Image.Plane::class.java)
        Mockito.`when`(plane.buffer).thenReturn(buf)
        Mockito.`when`(plane.rowStride).thenReturn(rowStride)
        Mockito.`when`(plane.pixelStride).thenReturn(2)
        Mockito.`when`(image.planes).thenReturn(arrayOf(plane))
        return RawFrame(image, rowStride)
    }
}
