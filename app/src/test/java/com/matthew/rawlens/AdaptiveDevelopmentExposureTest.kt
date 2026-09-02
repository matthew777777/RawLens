// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDevelopmentExposureTest {
    @Test
    fun placesOrdinaryMidtonesNearTargetWithinBoundedCorrection() {
        val under = AdaptiveDevelopmentExposure.analyze(cfa(FloatArray(4096) { 0.09f }))
        assertEquals(1.0, under.correctionEv, 0.02)
        val bright = AdaptiveDevelopmentExposure.analyze(cfa(FloatArray(4096) { 0.5f }))
        assertEquals(-1.474, bright.correctionEv, 0.02)
        assertTrue(under.correctionEv in -1.5..1.5 && bright.correctionEv in -1.5..1.5)
    }

    @Test
    fun sparseSpecularsDoNotDominateLogAverage() {
        val values = FloatArray(10_240) { if (it < 51) 1f else 0.09f }
        val result = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertTrue("specular tail suppressed useful lift: $result", result.correctionEv > 0.8)
    }

    @Test
    fun lowKeySceneReceivesOnlyConservativePositiveLift() {
        val result = AdaptiveDevelopmentExposure.analyze(cfa(FloatArray(4096) { 0.005f }))
        assertTrue(result.lowKey)
        assertTrue(result.correctionEv in 0.0..1.3)
    }

    @Test
    fun sharedStateComputesOnlyTheFirstFrameCorrection() {
        val state = SharedAdaptiveExposure()
        var analyses = 0
        val first = state.resolve {
            analyses++
            AdaptiveExposureResult(0.75, -3.0, 1.0, false)
        }
        val second = state.resolve {
            analyses++
            AdaptiveExposureResult(-1.0, -1.0, 1.0, false)
        }
        assertEquals(1, analyses)
        assertEquals(first.correctionEv, second.correctionEv, 0.0)
    }

    @Test
    fun directRawSamplingMatchesCpuNormalizationAndLensShading() {
        val width = 64
        val height = 64
        val rowStride = width * 2 + 8
        val source = ByteBuffer.allocate(rowStride * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until width) {
            source.putShort(y * rowStride + x * 2, (96 + (x * 7 + y * 11) % 700).toShort())
        }
        source.position(0)
        val layout = RawPlaneLayout(width, height, rowStride, 2, sensorOriginX = 3, sensorOriginY = 5)
        val normalization = RawNormalization(
            BayerPattern.GBRG, listOf(64f, 65f, 66f, 67f), 1023f
        )
        val crop = RawCrop(2, 4, 60, 56)
        val lens = LensShadingModel(
            2, 2,
            floatArrayOf(
                1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f,
                1.8f, 1.9f, 2f, 2.1f, 2.2f, 2.3f, 2.4f, 2.5f
            ),
            IntRectSnapshot(3, 5, 67, 69)
        )
        val cpu = LensShadingCorrector.applyOwnedInPlace(
            RawSensorUnpacker.unpackNormalized(source, layout, normalization, crop), lens
        ).cfa
        val reference = AdaptiveDevelopmentExposure.analyze(cpu)
        val direct = AdaptiveDevelopmentExposure.analyzeRaw(source, layout, normalization, crop, lens)

        assertEquals(reference.correctionEv, direct.correctionEv, 1e-12)
        assertEquals(reference.logAverage, direct.logAverage, 1e-12)
        assertEquals(reference.highlight, direct.highlight, 1e-12)
    }

    private fun cfa(values: FloatArray): UnpackedRawCfa {
        val width = 64
        val height = values.size / width
        return UnpackedRawCfa(
            width, height, BayerPattern.RGGB, values,
            RawCrop(0, 0, width, height)
        )
    }
}
