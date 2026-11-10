// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class HdrPlusSceneNoiseTest {
    private fun measure(signal: Double, sigma: Double, scale: Int = 1): Double {
        val w = 256
        val random = Random(42)
        val black = floatArrayOf(64f, 80f, 96f, 112f).map { it * scale }.toFloatArray()
        // Nonzero buffer position exercises the packed-buffer view contract.
        val bytes = ByteBuffer.allocate(w * w * 2 + 16).order(ByteOrder.nativeOrder())
        bytes.position(16)
        for (y in 0 until w) for (x in 0 until w) {
            val phase = (y % 2) * 2 + x % 2
            val v = black[phase] + scale * (signal + sigma * random.nextGaussian())
            bytes.putShort(v.toInt().coerceIn(0, 65535).toShort())
        }
        bytes.position(16)
        return HdrPlusSceneNoise.measure(bytes, w, w, black, 4095f * scale)
    }

    @Test fun estimatesKnownGaussianRelativeNoise() {
        assertEquals(0.04, measure(1000.0, 40.0), 0.003)
        assertEquals(0.10, measure(200.0, 20.0), 0.008)
    }

    @Test fun bitDepthAndPerChannelBlackOffsetsDoNotChangeNoiseDemand() {
        assertEquals(measure(500.0, 20.0), measure(500.0, 20.0, 4), 0.001)
    }

    @Test fun sameNoiseInDarkerSignalNeedsMoreDenoising() {
        val bright = measure(1000.0, 20.0)
        val dark = measure(200.0, 20.0)
        assertTrue(dark > bright)
        assertTrue(HdrPlusSceneNoise.strength(listOf(dark)) > HdrPlusSceneNoise.strength(listOf(bright)))
    }

    @Test fun clippingAndTruncationReturnUnknown() {
        val bytes = ByteBuffer.allocate(128 * 128 * 2)
        assertTrue(HdrPlusSceneNoise.measure(bytes, 128, 128, FloatArray(4), 4095f).isNaN())
        assertTrue(HdrPlusSceneNoise.measure(bytes, 256, 256, FloatArray(4), 4095f).isNaN())
        assertTrue(HdrPlusSceneNoise.measure(bytes, 128, 128, FloatArray(4), Float.NaN).isNaN())
    }

    @Test fun policyIsContinuousMonotonicBoundedAndHandlesMissingData() {
        var previous = 0f
        for (i in 0..1000) {
            val strength = HdrPlusSceneNoise.strength(listOf(i / 1000.0))
            assertTrue(strength >= previous)
            assertTrue(strength in HdrPlusSettings.MIN_STRENGTH..HdrPlusAutoTuning.AUTO_STRENGTH_MAX)
            if (i > 0) assertTrue(strength - previous < 0.1f)
            previous = strength
        }
        for (values in listOf(emptyList(), listOf(Double.NaN), listOf(-0.1), listOf(Double.POSITIVE_INFINITY)))
            assertEquals(HdrPlusSettings.DEFAULT_STRENGTH, HdrPlusSceneNoise.strength(values), 0f)
    }
}
