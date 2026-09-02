// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorUnpackerTest {
    @Test
    fun unpacksContiguousLittleEndianWithoutClamping() {
        val source = rawBuffer(0, 64, 100, 150, 200, 250, 300, 400)
        val output = RawSensorUnpacker.unpackNormalized(
            source,
            RawPlaneLayout(4, 2, 8, 2),
            RawNormalization(BayerPattern.RGGB, listOf(100f, 100f, 100f, 100f), 300f),
            byteOrder = ByteOrder.LITTLE_ENDIAN
        )

        assertEquals(-0.5f, output.values[0], EPSILON)
        assertEquals(0f, output.values[2], EPSILON)
        assertEquals(1f, output.values[6], EPSILON)
        assertEquals(1.5f, output.values[7], EPSILON)
    }

    @Test
    fun honorsRowPixelStrideAndDoesNotRequireFinalRowPadding() {
        val source = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN)
        putU16(source, 0, 10)
        putU16(source, 4, 20)
        putU16(source, 8, 30)
        putU16(source, 11, 40)
        putU16(source, 15, 50)
        putU16(source, 19, 60)
        source.position(0)
        val output = RawSensorUnpacker.unpackNormalized(
            source,
            RawPlaneLayout(3, 2, 11, 4),
            RawNormalization(BayerPattern.RGGB, List(4) { 0f }, 100f),
            byteOrder = ByteOrder.LITTLE_ENDIAN
        )

        assertEquals(listOf(.1f, .2f, .3f, .4f, .5f, .6f), output.values.toList())
    }

    @Test
    fun bufferPositionIsThePlaneDataOrigin() {
        val source = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        putU16(source, 4, 25)
        putU16(source, 6, 50)
        putU16(source, 8, 75)
        putU16(source, 10, 100)
        source.position(4)
        val output = RawSensorUnpacker.unpackNormalized(
            source,
            RawPlaneLayout(2, 2, 4, 2),
            RawNormalization(BayerPattern.RGGB, List(4) { 0f }, 100f),
            byteOrder = ByteOrder.LITTLE_ENDIAN
        )
        assertEquals(listOf(.25f, .5f, .75f, 1f), output.values.toList())
    }

    @Test
    fun appliesBlackLevelsInFullSensorParityAfterOddCrop() {
        val source = rawBuffer(110, 220, 330, 440, 110, 220, 330, 440)
        val output = RawSensorUnpacker.unpackNormalized(
            source,
            RawPlaneLayout(4, 2, 8, 2, sensorOriginX = 1, sensorOriginY = 1),
            RawNormalization(BayerPattern.RGGB, listOf(110f, 220f, 330f, 440f), 1000f),
            crop = RawCrop(1, 0, 2, 2),
            byteOrder = ByteOrder.LITTLE_ENDIAN
        )

        assertEquals(BayerPattern.GBRG, output.pattern)
        assertEquals((220f - 330f) / (1000f - 330f), output.values[0], EPSILON)
        assertEquals((330f - 440f) / (1000f - 440f), output.values[1], EPSILON)
        assertEquals((220f - 110f) / (1000f - 110f), output.values[2], EPSILON)
        assertEquals((330f - 220f) / (1000f - 220f), output.values[3], EPSILON)
    }

    @Test
    fun rejectsUnknownCfaInvalidStrideAndTruncatedBuffer() {
        assertThrows(UnsupportedOperationException::class.java) { BayerPattern.fromCamera2(5) }
        assertThrows(IllegalArgumentException::class.java) { RawPlaneLayout(4, 2, 7, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            RawSensorUnpacker.unpackNormalized(
                ByteBuffer.allocate(7),
                RawPlaneLayout(2, 2, 4, 2),
                RawNormalization(BayerPattern.RGGB, List(4) { 0f }, 1023f)
            )
        }
    }

    @Test
    fun amazeContractRejectsOddOrTinyCrops() {
        val odd = UnpackedRawCfa(3, 4, BayerPattern.RGGB, FloatArray(12), RawCrop(0, 0, 3, 4))
        assertThrows(IllegalArgumentException::class.java) { odd.requireAmazeCompatible() }
    }

    @Test
    fun geometryUsesOnlyProvableCoordinateMappings() {
        val cropped = RawBufferGeometry.resolve(
            4000, 3000, IntRectSnapshot(8, 10, 4008, 3010), null, null, null
        ) as RawBufferGeometry.Supported
        assertEquals(8, cropped.sensorOriginX)
        assertEquals(10, cropped.sensorOriginY)

        val fullPixelArray = RawBufferGeometry.resolve(
            4032,
            3024,
            null,
            IntRectSnapshot(16, 12, 4016, 3012),
            4032,
            3024
        ) as RawBufferGeometry.Supported
        assertEquals(RawCrop(16, 12, 4000, 3000), fullPixelArray.processingCrop)

        val imageCropped = RawBufferGeometry.resolve(
            4000,
            3000,
            IntRectSnapshot(8, 10, 4008, 3010),
            null,
            null,
            null,
            IntRectSnapshot(2, 4, 3998, 2996)
        ) as RawBufferGeometry.Supported
        assertEquals(RawCrop(2, 4, 3996, 2992), imageCropped.processingCrop)

        assertTrue(
            RawBufferGeometry.resolve(3999, 2999, null, null, 4032, 3024) is
                RawBufferGeometry.Unsupported
        )
    }

    private fun rawBuffer(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
            values.forEach { buffer.putShortU16(it) }
            buffer.flip()
        }

    private fun ByteBuffer.putShortU16(value: Int) {
        putShort(value.toShort())
    }

    private fun putU16(buffer: ByteBuffer, index: Int, value: Int) {
        buffer.putShort(index, value.toShort())
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
