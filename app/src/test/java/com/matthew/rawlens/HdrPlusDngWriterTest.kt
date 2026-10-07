// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HdrPlusDngWriterTest {
    @Test fun writesUint16CfaDngTagsAndQuantizedSamples() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.referenceIlluminant1).thenReturn(21)
        val values = FloatArray(16) { it / 16f }
        values[14] = 0.5f
        values[15] = 3.5f
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, values, RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also { HdrPlusDngWriter.write(it, cfa, metadata) }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, buffer.getShort(0).toInt() and 0xffff)
        assertEquals(42, buffer.getShort(2).toInt())
        val count = buffer.getShort(8).toInt() and 0xffff
        val tags = HashMap<Int, Pair<Int, Int>>()
        var stripOffset = 0
        val inlineValues = HashMap<Int, Int>()
        for (i in 0 until count) {
            val p = 10 + i * 12
            val tag = buffer.getShort(p).toInt() and 0xffff
            tags[tag] = (buffer.getShort(p + 2).toInt() and 0xffff) to buffer.getInt(p + 4)
            inlineValues[tag] = buffer.getInt(p + 8)
            if (tag == 273) stripOffset = buffer.getInt(p + 8)
        }
        assertEquals(3, tags.getValue(339).first) // SHORT
        assertEquals(1, inlineValues.getValue(339)) // unsigned int, not IEEE float
        assertEquals(16, inlineValues.getValue(258))
        assertEquals(32803, inlineValues.getValue(262))
        assertEquals(65535, inlineValues.getValue(50717))
        assertEquals(32, inlineValues.getValue(279))
        assertEquals(stripOffset + 32, bytes.size)
        assertEquals(1, tags.getValue(339).second)
        assertEquals(1, tags.getValue(50717).second)
        // BlackLevel covers the 2x2 repeat grid (rawspeed rejects count 1).
        assertEquals(3, tags.getValue(50714).first)
        assertEquals(4, tags.getValue(50714).second)
        assertTrue(tags.containsKey(33422))
        // Rawspeed wants Make/Model for camera identification.
        assertTrue(tags.containsKey(271))
        assertTrue(tags.containsKey(272))
        fun sample(index: Int) = buffer.getShort(stripOffset + index * 2).toInt() and 0xffff
        assertEquals(0, sample(0))
        assertEquals(32768, sample(14)) // 0.5 * 65535 rounds half up
        assertEquals(65535, sample(15)) // clamped, not wrapped
    }

    @Test fun writesExifBlockExposureIsoAndProvenance() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.referenceIlluminant1).thenReturn(21)
        `when`(metadata.exposureTimeNanos).thenReturn(10_000_000L)
        `when`(metadata.sensitivityIso).thenReturn(100)
        `when`(metadata.aperture).thenReturn(2.8f)
        `when`(metadata.focalLengthMm).thenReturn(5.4f)
        `when`(metadata.focusDistanceM).thenReturn(4.55f)
        `when`(metadata.flashFired).thenReturn(false)
        val provenance = HdrPlusProvenance(
            mergedFrames = 8, selectedFrames = 8, rejectedFrames = 0,
            highQuality = true, strength = 13f,
            tileSize = 32, searchDistance = 64, referenceIndex = 4,
            referenceTimestampNs = 123456789L, referenceFrameNumber = 42,
            sourceWidth = 4032, sourceHeight = 3024, sourceCameraId = "0",
            mergeMs = 1234, packMs = 50, gpuMs = 900.5, unpackMs = 12,
            frames = List(8) { HdrPlusFrameInfo(10_000_000L, 100) }
        )
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also {
            HdrPlusDngWriter.write(it, cfa, metadata,
                captureTimeMillis = 1_700_000_000_123L, provenance = provenance)
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ifdEntries(at: Int): Map<Int, Triple<Int, Int, Int>> {
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
        val ifd0 = ifdEntries(8)
        // IFD0 exposure + ISO.
        val (expType, expCount, expOff) = ifd0.getValue(33434)
        assertEquals(5, expType)
        assertEquals(1, expCount)
        assertEquals(0.01,
            (buffer.getInt(expOff).toLong() and 0xffffffffL).toDouble() /
                (buffer.getInt(expOff + 4).toLong() and 0xffffffffL), 1e-9)
        assertEquals(100, ifd0.getValue(34855).third)
        // ImageDescription provenance record.
        val (_, descCount, descOff) = ifd0.getValue(270)
        val descBytes = ByteArray(descCount) { buffer.get(descOff + it) }
        val desc = String(descBytes, Charsets.US_ASCII).trimEnd('\u0000')
        assertTrue(desc, desc.startsWith("Captured with RawLens\nHDR+ GPU merge\n"))
        assertTrue(desc, desc.contains("\nPARAMETERS\n"))
        assertTrue(desc, desc.contains("- Frames: 8\n"))
        assertTrue(desc, desc.contains("- Rejected frames: 0\n"))
        assertTrue(desc, desc.contains("- Output: 4032x3024 (1.00x, ~12.2 MP)\n"))
        assertTrue(desc, desc.contains("- Merge: HDR+ frequency (tile alignment + per-frequency Wiener merge)\n"))
        assertTrue(desc, desc.contains("- HDR+ strength: 13.000\n"))
        assertTrue(desc, desc.contains("- HDR+ tile size: 32\n"))
        assertTrue(desc, desc.contains("\nBase Frame Selection:\n"))
        assertTrue(desc, desc.contains("- Reference: auto-picked (frame 5 of 8)\n"))
        assertTrue(desc, desc.contains("\nFrames:\n"))
        assertTrue(desc, desc.contains("- F1: 10.0 ms, ISO 100\n"))
        assertTrue(desc, desc.contains("- F5: 10.0 ms, ISO 100 (reference)\n"))
        assertTrue(desc, desc.contains("\nTIMINGS\n"))
        assertTrue(desc, desc.contains("- Merge: 1234 ms (pack 50 ms, GPU 900.5 ms, unpack 12 ms)\n"))
        assertTrue(desc, desc.contains("\nSource:\n"))
        assertTrue(desc, desc.contains("- Camera: 0\n"))
        assertTrue(desc, desc.contains("- Reference timestamp: 123456789 ns\n"))
        assertTrue(desc, desc.contains("- Reference frame: 42\n"))
        // EXIF sub-IFD: f-number, focal length, flash (37385, not 37388),
        // subject distance, and a well-formed date tag.
        assertTrue(ifd0.containsKey(34665))
        val exif = ifdEntries(ifd0.getValue(34665).third)
        val (_, _, fOff) = exif.getValue(33437)
        assertEquals(2.8,
            (buffer.getInt(fOff).toLong() and 0xffffffffL).toDouble() /
                (buffer.getInt(fOff + 4).toLong() and 0xffffffffL), 1e-6)
        assertTrue(exif.containsKey(37386))
        assertEquals(3, exif.getValue(37385).first)
        assertEquals(0, exif.getValue(37385).third)
        assertTrue(exif.containsKey(37382))
        val (_, dateCount, dateOff) = exif.getValue(36867)
        val stamp = String(ByteArray(dateCount) { buffer.get(dateOff + it) }, Charsets.US_ASCII)
            .trimEnd('\u0000')
        assertTrue(stamp, stamp.matches(Regex("\\d{4}:\\d\\d:\\d\\d \\d\\d:\\d\\d:\\d\\d")))
    }

    @Test fun provenanceBlockRendersPerFrameStrengths() {
        val provenance = HdrPlusProvenance(
            mergedFrames = 4, selectedFrames = 4, rejectedFrames = 0,
            highQuality = false, strength = 3f,
            tileSize = 32, searchDistance = 64, referenceIndex = 1,
            referenceTimestampNs = 1L, referenceFrameNumber = 7,
            sourceWidth = 4032, sourceHeight = 3024, sourceCameraId = "0",
            mergeMs = 10, packMs = 1, gpuMs = 5.0, unpackMs = 1,
            frames = listOf(
                HdrPlusFrameInfo(10_000_000L, 100, mergeStrength = 3.0f),
                HdrPlusFrameInfo(10_000_000L, 100, mergeStrength = 13.0f),
                HdrPlusFrameInfo(10_000_000L, 100, mergeStrength = 11.8f),
                HdrPlusFrameInfo(10_000_000L, 100, mergeStrength = 3.0f)
            )
        )
        val desc = HdrPlusDngWriter.provenanceBlock(provenance)
        assertTrue(desc, desc.contains("- HDR+ strength: 3.000 (per-frame)\n"))
        assertTrue(desc, desc.contains("- F1: 10.0 ms, ISO 100, s=3.0\n"))
        assertTrue(desc, desc.contains("- F2: 10.0 ms, ISO 100, s=13.0 (reference)\n"))
        assertTrue(desc, desc.contains("- F3: 10.0 ms, ISO 100, s=11.8\n"))
    }

    @Test fun provenanceBlockMarksActiveStrengthMaps() {
        val provenance = HdrPlusProvenance(
            mergedFrames = 2, selectedFrames = 2, rejectedFrames = 0,
            highQuality = false, strength = 3f,
            tileSize = 32, searchDistance = 64, referenceIndex = 0,
            referenceTimestampNs = 1L, referenceFrameNumber = 7,
            sourceWidth = 64, sourceHeight = 64, sourceCameraId = "0",
            mergeMs = 10, packMs = 1, gpuMs = 5.0, unpackMs = 1,
            mapsActive = true,
            frames = listOf(
                HdrPlusFrameInfo(10_000_000L, 100),
                HdrPlusFrameInfo(10_000_000L, 100)
            )
        )
        val desc = HdrPlusDngWriter.provenanceBlock(provenance)
        assertTrue(desc, desc.contains("- HDR+ strength: 3.000 (strength maps)\n"))
        assertTrue(desc, !desc.contains(", s="))
    }
}
