// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LapisHexaDngTest {
    @Test fun remainingShadingMapIsExportedOnceAtReducedBayerBounds() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.cameraId).thenReturn("0")
        val map = LensShadingSnapshot(2, 2, ImmutableFloatValues(FloatArray(16) { 1f + it * .02f }))
        `when`(metadata.lensShadingMap).thenReturn(map)
        val fields = LapisHexaDng.fields(metadata, 8160, 6120, 64f, "shading", 1700000000123)
        val tags = fields.descriptors.filterIndexed { i, _ -> i % 3 == 0 }
        assertEquals(1, tags.count { it == 51009 })
        assertArrayEquals(TinyDngMetadata.gainMap(map, BayerPattern.GBRG,
            RawCrop(0, 0, 8160, 6120), 0), fields.payloads[tags.indexOf(51009)])
        `when`(metadata.lensShadingAlreadyApplied).thenReturn(true)
        val baked = LapisHexaDng.fields(metadata, 8160, 6120, 64f, "baked", 1700000000123)
        assertFalse(baked.descriptors.filterIndexed { i, _ -> i % 3 == 0 }.contains(51009))
    }

    @Test fun serializedReducedDngPassesCfaValidation() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.cameraId).thenReturn("0")
        val pixels = ByteBuffer.allocate(64 * 48 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(64 * 48) { pixels.putShort((256 + it % 3800).toShort()) }
        val file = java.io.File.createTempFile("rawr-reduced-", ".dng")
        try {
            LapisHexaDng.write(file, pixels, 64, 48, metadata, 1234, 64f, false)
            LapisDngValidation.validate(file, 64, 48, reducedHexadeca = true)
        } finally { file.delete() }
    }

    @Test fun reducedDngDescribesBayerAndSummedCodesExactlyOnce() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.exifOrientation).thenReturn(6)
        `when`(metadata.cameraId).thenReturn("0")
        val fields = LapisHexaDng.fields(metadata, 8160, 6120, 64.25f, "rawr CFA provenance", 1700000000123)
        val tags = fields.descriptors.filterIndexed { i, _ -> i % 3 == 0 }
        assertEquals(tags.size, tags.distinct().size)
        fun bytes(tag: Int) = fields.payloads[tags.indexOf(tag)]
        fun ints(tag: Int): List<Int> {
            val b = ByteBuffer.wrap(bytes(tag)).order(ByteOrder.LITTLE_ENDIAN)
            return List(b.remaining() / 4) { b.int }
        }
        assertArrayEquals(byteArrayOf(1, 2, 0, 1), bytes(33422))
        assertEquals(listOf(4092), ints(50717))
        assertEquals(listOf(257, 1, 257, 1, 257, 1, 257, 1), ints(50714))
        assertEquals(listOf(0, 0, 6120, 8160), ints(50829))
        assertTrue(bytes(270).toString(Charsets.US_ASCII).contains("rawr CFA provenance"))
        assertFalse(tags.contains(51009)) // no fabricated shading opcode for the pure readout
    }
}
