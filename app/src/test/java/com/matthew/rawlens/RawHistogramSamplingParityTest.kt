// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.mockito.Mockito

class RawHistogramSamplingParityTest {
    @Test fun allBinsMatchScalarAcrossCfaStridesCurvesAndTruncatedQuads() {
        val w = 80
        val h = 66
        val random = java.util.Random(51037)
        for (pixelStride in intArrayOf(2, 4)) for (truncated in listOf(false, true)) {
            val rowStride = w * pixelStride + 16
            val bytes = ByteBuffer.allocate(rowStride * h).order(ByteOrder.nativeOrder())
            for (y in 0 until h) for (x in 0 until w)
                bytes.putShort(y * rowStride + x * pixelStride, random.nextInt(1400).toShort())
            if (truncated) bytes.limit((h - 1) * rowStride + (w - 3) * pixelStride)
            val image = Mockito.mock(Image::class.java)
            val plane = Mockito.mock(Image.Plane::class.java)
            Mockito.`when`(image.width).thenReturn(w)
            Mockito.`when`(image.height).thenReturn(h)
            Mockito.`when`(plane.buffer).thenReturn(bytes)
            Mockito.`when`(plane.rowStride).thenReturn(rowStride)
            Mockito.`when`(plane.pixelStride).thenReturn(pixelStride)
            for (cfa in 0..3) for (curved in listOf(false, true)) {
                val lut = if (curved) FloatArray(1024) { kotlin.math.sqrt(it / 1023.0).toFloat() } else null
                val actual = RawHistogramSampler.sample(image, plane, cfa, null, 1023, lut, 0.0)!!
                val expected = Array(4) { IntArray(64) }
                val sites = RawPreviewGeometry.channels(cfa)
                for (y in 0 until h step 2) for (x in 0 until w step 2) {
                    val rgb = DoubleArray(3)
                    var complete = true
                    // Canonical channel order, deliberately independent of the
                    // production loop's phase/channel tables and bulk-row reads.
                    for (ch in 0..3) {
                        val site = sites[ch]
                        val offset = (y + site / 2) * rowStride + (x + site % 2) * pixelStride
                        if (offset + 1 >= bytes.limit()) { complete = false; continue }
                        val code = bytes.getShort(offset).toInt() and 65535
                        val normalized = (code * (1.0 / 1023)).coerceIn(0.0, 1.0)
                        val value = AgxDisplayTransform.scopeEncode(normalized, lut)
                        val color = when (ch) { 0 -> 0; 3 -> 2; else -> 1 }
                        expected[color][(value * 63).toInt().coerceIn(0, 63)]++
                        if (color == 1) rgb[color] += value else rgb[color] = value
                    }
                    if (complete) {
                        val luma = 0.2126 * rgb[0] + 0.7152 * (rgb[1] / 2) + 0.0722 * rgb[2]
                        expected[3][(luma * 63).toInt().coerceIn(0, 63)]++
                    }
                }
                assertArrayEquals(expected[0], actual.red)
                assertArrayEquals(expected[1], actual.green)
                assertArrayEquals(expected[2], actual.blue)
                assertArrayEquals(expected[3], actual.luminance)
            }
        }
    }
}
