package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TinyDngMetadataTest {
    @Test fun gainMapKeepsChannelValuesForEveryCfaAndCropParity() {
        val map = LensShadingSnapshot(2, 3,
            ImmutableFloatValues(FloatArray(24) { 1f + it / 4 + (it % 4) / 10f }))
        for (sensor in BayerPattern.entries) for (originY in 0..1) for (originX in 0..1) {
            val pattern = sensor.shifted(originX, originY)
            val active = RawCrop(3, 1, 10, 6)
            val bytes = TinyDngMetadata.gainMap(map, pattern, active, originY)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            assertEquals(4, b.int)
            repeat(4) { cell ->
                assertEquals(9, b.int); assertEquals(0x01030000, b.int)
                assertEquals(1, b.int); assertEquals(100, b.int)
                val y = cell / 2; val x = cell % 2
                assertEquals(y, b.int); assertEquals(x, b.int)
                assertEquals(6, b.int); assertEquals(10, b.int)
                assertEquals(0, b.int); assertEquals(1, b.int)
                assertEquals(2, b.int); assertEquals(2, b.int)
                assertEquals(2, b.int); assertEquals(3, b.int)
                assertEquals(1.0, b.double, 0.0); assertEquals(0.5, b.double, 0.0)
                assertEquals(0.0, b.double, 0.0); assertEquals(0.0, b.double, 0.0)
                assertEquals(1, b.int)
                val sy = y + 1 + originY; val sx = x + 3 + originX
                val channel = when (sensor.colorAt(sx, sy)) {
                    CfaColor.RED -> 0; CfaColor.BLUE -> 3
                    CfaColor.GREEN -> if (sy % 2 == 0) 1 else 2
                }
                repeat(6) { point -> assertEquals(1f + point + channel / 10f, b.float, 0f) }
            }
            assertFalse(b.hasRemaining())
        }
    }

    @Test fun capturesNoiseFractionalBlackCalibrationAndOmitsAppliedGainMap() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("test")
        `when`(metadata.cfaPattern).thenReturn(BayerPattern.BGGR)
        `when`(metadata.exifOrientation).thenReturn(6)
        `when`(metadata.noiseProfile).thenReturn(ImmutableDoubleValues(
            doubleArrayOf(4.0, 0.4, 2.0, 0.9, 3.0, 0.2, 1.0, 0.1)))
        `when`(metadata.lensShadingMap).thenReturn(LensShadingSnapshot(1, 1,
            ImmutableFloatValues(floatArrayOf(1f, 2f, 3f, 4f))))
        `when`(metadata.lensShadingAlreadyApplied).thenReturn(true)
        `when`(metadata.forwardMatrix1).thenReturn(ImmutableDoubleValues(
            doubleArrayOf(1.0, 4.0, 7.0, 2.0, 5.0, 8.0, 3.0, 6.0, 9.0)))
        val matrix = List(9) { it / 10.0 }
        val tags = TinyDngMetadata.create(metadata, DngMetadataOverrides(colorMatrix1 = matrix),
            BayerPattern.BGGR, doubleArrayOf(64.5, 65.25, 66.0, 67.0), 4095, RawCrop(2, 4, 10, 6))
        fun payload(tag: Int): ByteBuffer {
            val index = tags.descriptors.chunked(3).indexOfFirst { it[0] == tag }
            assertTrue(index >= 0)
            return ByteBuffer.wrap(tags.payloads[index]).order(ByteOrder.LITTLE_ENDIAN)
        }
        val noise = payload(51041)
        assertArrayEquals(doubleArrayOf(1.0, 0.1, 3.0, 0.2, 4.0, 0.4), DoubleArray(6) { noise.double }, 0.0)
        val black = payload(50714)
        assertEquals(64.5, black.int.toDouble() / black.int, 0.0)
        val colors = payload(50721)
        matrix.forEach { assertEquals(it, colors.int.toDouble() / colors.int, 1e-6) }
        val forward = payload(50964)
        for (value in 1..9) assertEquals(value.toDouble(), forward.int.toDouble() / forward.int, 0.0)
        assertFalse(tags.descriptors.chunked(3).any { it[0] == 51009 })
    }
}
