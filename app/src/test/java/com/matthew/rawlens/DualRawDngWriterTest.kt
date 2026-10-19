// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DualRawDngWriterTest {
    companion object {
        @BeforeClass @JvmStatic
        fun assumeHostDngLib() = HostDngLib.assumeAvailable()

        private fun metadata(): RawFrameMetadata {
            val metadata = mock(RawFrameMetadata::class.java)
            `when`(metadata.cameraId).thenReturn("0")
            `when`(metadata.cfaPattern).thenReturn(BayerPattern.RGGB)
            `when`(metadata.exposureTimeNanos).thenReturn(10_000_000L)
            `when`(metadata.sensitivityIso).thenReturn(100)
            `when`(metadata.referenceIlluminant1).thenReturn(21)
            return metadata
        }

        private fun ifdEntries(buffer: ByteBuffer, at: Int): Map<Int, Triple<Int, Int, Int>> {
            val out = HashMap<Int, Triple<Int, Int, Int>>()
            val n = buffer.getShort(at).toInt() and 0xffff
            for (i in 0 until n) {
                val p = at + 2 + i * 12
                out[buffer.getShort(p).toInt() and 0xffff] =
                    Triple(buffer.getShort(p + 2).toInt() and 0xffff,
                        buffer.getInt(p + 4), buffer.getInt(p + 8))
            }
            return out
        }

        private fun rational(buffer: ByteBuffer, offset: Int, signed: Boolean): Double {
            val num = buffer.getInt(offset)
            val den = buffer.getInt(offset + 4)
            return num.toDouble() / den.toDouble()
        }
    }

    @Test fun writesDualRawTagsLevelsAndSamples() {
        val samples = IntArray(16) { it * 1000 }
        val mosaic = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { mosaic.putShort(it.toShort()) }
        mosaic.position(0)
        val bytes = ByteArrayOutputStream().also {
            DualRawDngWriter.write(it, mosaic, 4, 4, metadata(),
                headroom = 4.0, orientation = 2, gps = null,
                captureTimeMillis = 1_700_000_000_123L)
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, buffer.getShort(0).toInt() and 0xffff)
        assertEquals(42, buffer.getShort(2).toInt())
        val ifd0 = ifdEntries(buffer, 8)
        assertEquals(16, ifd0.getValue(258).third)
        assertEquals(32803, ifd0.getValue(262).third)
        // Orientation is an explicit writer input, not metadata echo.
        assertEquals(2, ifd0.getValue(274).third)
        // Uncompressed legacy DNG 1.4 version tags, inline BYTE[4].
        assertEquals(0x00000401, ifd0.getValue(50706).third)
        assertEquals(0x00000401, ifd0.getValue(50707).third)
        // RGGB pattern bytes inline: R,G / G,B -> 0,1,1,2.
        assertEquals(0x02010100, ifd0.getValue(33422).third)
        // Personal description flavor preserved verbatim.
        val (descType, descCount, descOffset) = ifd0.getValue(270)
        assertEquals(2, descType)
        val descBytes = ByteArray(descCount) { buffer.get(descOffset + it) }
        assertEquals(DualRawDngWriter.DESCRIPTION,
            String(descBytes, 0, descCount - 1, Charsets.US_ASCII))
        // Forced post-merge levels: RATIONAL 1024 x4 over the 2x2 grid.
        val (blackType, blackCount, blackOffset) = ifd0.getValue(50714)
        assertEquals(5, blackType)
        assertEquals(4, blackCount)
        repeat(4) { assertEquals(1024.0, rational(buffer, blackOffset + it * 8, false), 0.0) }
        assertEquals(65535, ifd0.getValue(50717).third)
        // BaselineExposure = log2(headroom) = log2(4) = 2.
        val (evType, evCount, evOffset) = ifd0.getValue(50730)
        assertEquals(10, evType)
        assertEquals(1, evCount)
        assertEquals(2.0, rational(buffer, evOffset, true), 1e-9)
        // Low-frame exposure/ISO on IFD0.
        val (_, _, isoInline) = ifd0.getValue(34855)
        assertEquals(100, isoInline)
        val (_, expCount, expOffset) = ifd0.getValue(33434)
        assertEquals(1, expCount)
        assertEquals(0.01, rational(buffer, expOffset, false), 1e-9)
        assertTrue(ifd0.containsKey(271))
        assertTrue(ifd0.containsKey(272))
        // Mosaic samples stream through verbatim in one strip.
        val stripOffset = ifd0.getValue(273).third
        assertEquals(32, ifd0.getValue(279).third)
        assertEquals(stripOffset + 32, bytes.size)
        repeat(16) {
            assertEquals(samples[it], buffer.getShort(stripOffset + it * 2).toInt() and 0xffff)
        }
    }

    @Test fun rejectsNonPositiveHeadroomAndBadOrientation() {
        val mosaic = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        try {
            DualRawDngWriter.write(ByteArrayOutputStream(), mosaic, 2, 2, metadata(),
                headroom = 0.0, orientation = 1, gps = null, captureTimeMillis = 0L)
            throw AssertionError("headroom 0 must fail")
        } catch (_: IllegalArgumentException) { }
        try {
            DualRawDngWriter.write(ByteArrayOutputStream(), mosaic, 2, 2, metadata(),
                headroom = 2.0, orientation = 0, gps = null, captureTimeMillis = 0L)
            throw AssertionError("orientation 0 must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsNonBayerCfa() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.cfaPattern).thenReturn(null)
        val mosaic = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        try {
            DualRawDngWriter.write(ByteArrayOutputStream(), mosaic, 2, 2, metadata,
                headroom = 2.0, orientation = 1, gps = null, captureTimeMillis = 0L)
            throw AssertionError("null CFA must fail")
        } catch (_: IllegalStateException) { }
    }
}
