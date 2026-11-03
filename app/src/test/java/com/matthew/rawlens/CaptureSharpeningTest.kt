// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureSharpeningTest {
    @Test fun defaultIsOffAndEnabledPresetsMatchRawr() {
        assertFalse(CaptureSharpeningSettings().enabled)
        assertEquals(CaptureSharpeningLevel.Low, CaptureSharpeningSettings().level)
        assertEquals(listOf(2, 3, 4, 6), CaptureSharpeningLevel.entries.map { it.iterations })
    }

    @Test fun whiteBalanceMatchesRawrCaptureMetadataDefaultsAndGreenAverage() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), CaptureSharpeningSettings.whiteBalance(null), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), CaptureSharpeningSettings.whiteBalance(floatArrayOf(2f)), 0f)
        assertArrayEquals(floatArrayOf(4f, 2f, 3f),
            CaptureSharpeningSettings.whiteBalance(floatArrayOf(4f, 1.5f, 2.5f, 3f)), 0f)
    }

    @Test fun sensorClipBitsMatchPhysicalChannelsForAllBayerPatterns() {
        val expected = arrayOf(intArrayOf(1, 2, 4, 8), intArrayOf(2, 1, 8, 4),
            intArrayOf(4, 8, 1, 2), intArrayOf(8, 4, 2, 1))
        for ((p, pattern) in BayerPattern.entries.withIndex()) for (phase in 0..3) {
            val values = FloatArray(4) { 0.9949f }.apply { this[phase] = 0.995f }
            val cfa = UnpackedRawCfa(2, 2, pattern, values, RawCrop(0, 0, 2, 2))
            val clip = CaptureSharpeningClipState.fromSensorCfa(cfa)
            assertEquals(expected[p][phase], clip.bitsAt(0, 0))
            // Lens shading, denoise or reconstruction cannot change retained sensor evidence.
            values.fill(2f)
            assertEquals(expected[p][phase], clip.bitsAt(0, 0))
            assertEquals(expected[p][phase].toShort(), clip.directBuffer().getShort(0))
        }
    }

    @Test fun rawClipPackingHonorsBufferOriginStrideCropAndBayerPhase() {
        val raw = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder()).apply {
            position(8)
            putShort(8 + 12 + 2, 1023.toShort()) // blue at sensor (1,1)
            putShort(8 + 24 + 4, 990.toShort()) // red, bright but below sensor clip threshold
        }
        val clip = CaptureSharpeningClipState.fromRawSensor(raw, RawPlaneLayout(4, 4, 12, 2),
            RawNormalization(BayerPattern.RGGB, listOf(64f, 80f, 100f, 128f), 1023f), RawCrop(1, 1, 2, 2))
        assertEquals(BayerPattern.BGGR, clip.pattern)
        assertEquals(8, clip.bitsAt(0, 0))
        assertEquals(8, raw.position())
    }

    @Test fun lensShadingRetainsSensorClippingInsteadOfClassifyingBoostedValues() {
        val source = UnpackedRawCfa(2, 2, BayerPattern.RGGB,
            floatArrayOf(0.995f, 0.7f, 0.8f, 0.9f), RawCrop(0, 0, 2, 2))
        val retained = source.copy(captureSharpeningClipState = CaptureSharpeningClipState.fromSensorCfa(source))
        val corrected = LensShadingCorrector.apply(retained, LensShadingModel(1, 1,
            floatArrayOf(2f, 2f, 2f, 2f), IntRectSnapshot(0, 0, 2, 2))).cfa
        assertTrue(corrected.values.all { it > 1f })
        assertEquals(1, requireNotNull(corrected.captureSharpeningClipState).bitsAt(0, 0))
    }

    @Test fun clippingIsSpatialAndUsesMergeProvenanceInsteadOfHdrRadiance() {
        val values = FloatArray(16) { 4f }
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, values, RawCrop(0, 0, 4, 4),
            highlightClipping = CfaHighlightClipping.from(4, 4) { it == 15 })
        val clip = CaptureSharpeningClipState.fromSensorCfa(cfa)
        assertEquals(0, clip.bitsAt(0, 0))
        assertEquals(0, clip.bitsAt(1, 0))
        assertEquals(0, clip.bitsAt(0, 1))
        assertEquals(8, clip.bitsAt(1, 1))
    }
}
