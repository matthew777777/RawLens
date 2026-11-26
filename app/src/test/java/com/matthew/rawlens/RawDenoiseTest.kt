// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDenoiseTest {
    @Test fun defaultsAreOffWithAutomaticTexture() {
        val settings = DenoiseSettings()
        assertFalse(settings.rawDenoise.enabled)
        assertEquals(1f, settings.rawDenoise.texture, 0f)
        assertEquals(RawDenoiseSettings.MAX_TEXTURE, RawDenoiseSettings(texture = 9f).bounded().texture, 0f)
    }

    @Test fun lumaAndChromaAreIndependent() {
        assertTrue(RawDenoiseSettings(true, luma = 0f).active)
        assertTrue(RawDenoiseSettings(true, chroma = 0f).active)
        assertFalse(RawDenoiseSettings(true, luma = 0f, chroma = 0f).active)
        val lumaOnly = RawDenoiseSettings(true, luma = 0f, chroma = .5f).withMode(RawDenoiseSettings.Mode.LUMA)
        assertEquals(RawDenoiseSettings(true, luma = 1f, chroma = 0f), lumaOnly)
        assertEquals(RawDenoiseSettings.Mode.LUMA, lumaOnly.mode)
        RawDenoiseSettings.Mode.entries.forEach { assertEquals(it, lumaOnly.withMode(it).mode) }
        assertFalse(RawDenoiseSettings(false).active)
        val b = RawDenoiseSettings(true, luma = 3f, chroma = -1f).bounded()
        assertEquals(1f, b.luma, 0f)
        assertEquals(0f, b.chroma, 0f)
    }

    @Test fun whiteBalanceKeepsScaleAndAveragesGreens() {
        assertArrayEquals(floatArrayOf(4f, 2f, 3f),
            CaptureSharpeningSettings.collapseWhiteBalance(floatArrayOf(4f, 1.5f, 2.5f, 3f)), 0f)
        assertNull(CaptureSharpeningSettings.collapseWhiteBalance(null))
        assertNull(CaptureSharpeningSettings.collapseWhiteBalance(floatArrayOf(1f, 1f, 1f)))
        assertNull(CaptureSharpeningSettings.collapseWhiteBalance(floatArrayOf(1f, Float.NaN, 1f, 1f)))
        assertNull(CaptureSharpeningSettings.collapseWhiteBalance(floatArrayOf(1f, 0f, 1f, 1f)))
    }

    @Test fun outputLevelsDecodeBlackAndWhite() {
        val n = RawNormalization(BayerPattern.RGGB, List(4) { RawDenoiser.PEDESTAL }, RawDenoiser.OUTPUT_WHITE)
        fun linear(code: Float) = (code - n.blackAt(0, 0)) / (n.whiteLevel - n.blackAt(0, 0))
        assertEquals(1f, linear(65535f), 0f)
        for (x in listOf(0f, 0.18f, 0.9f)) assertEquals(x, linear(65535f * (x * 15f / 16f + 1f / 16f)), 1e-6f)
    }

    @Test fun hotPixelsBecomeTheSameColourMedianAndPaddingIsDropped() {
        val w = 8
        val h = 6
        val stride = w * 2 + 6
        val source = ByteBuffer.allocate(stride * h).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) source.putShort(y * stride + x * 2, (100 * ((y and 1) * 2 + (x and 1)) + x).toShort())
        source.putShort(2 * stride + 4 * 2, 60000.toShort())
        val target = ByteBuffer.allocate(w * h * 2).order(ByteOrder.nativeOrder())
        RawDenoiser.copyWithoutHotPixels(source, RawPlaneLayout(w, h, stride, 2, 10, 20), target,
            listOf(IntPointSnapshot(14, 22), IntPointSnapshot(0, 0)))
        assertEquals(4, target.getShort((2 * w + 4) * 2).toInt())
        assertEquals(305, target.getShort((3 * w + 5) * 2).toInt())
        assertEquals(100 * 3 + 7, target.getShort((5 * w + 7) * 2).toInt())
    }
}
