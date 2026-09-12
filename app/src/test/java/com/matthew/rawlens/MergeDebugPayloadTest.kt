// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream

/** Merge-debug payload helpers: naming, manifest text, frame serialization. */
class MergeDebugPayloadTest {
    private fun metadata(): RawFrameMetadata {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.imageWidth).thenReturn(4)
        `when`(metadata.imageHeight).thenReturn(4)
        `when`(metadata.timestampNanos).thenReturn(111L)
        `when`(metadata.frameNumber).thenReturn(7L)
        `when`(metadata.sensitivityIso).thenReturn(100)
        `when`(metadata.exposureTimeNanos).thenReturn(10000000L)
        `when`(metadata.exifOrientation).thenReturn(1)
        return metadata
    }

    private fun frame(index: Int, value: Float): MergeDebugPayload.DebugFrame {
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB,
            FloatArray(16) { value }, RawCrop(0, 0, 4, 4))
        return MergeDebugPayload.DebugFrame(index, cfa, metadata())
    }

    @Test fun frameFilesAreZeroPaddedInBurstOrder() {
        assertEquals("frame-00.dng", MergeDebugPayload.frameFileName(0))
        assertEquals("frame-07.dng", MergeDebugPayload.frameFileName(7))
        assertEquals("frame-29.dng", MergeDebugPayload.frameFileName(29))
    }

    @Test fun payloadTextCarriesInfoAndOneStanzaPerFrame() {
        val text = MergeDebugPayload.payloadText(
            mapOf("mode" to "mosaic", "selected" to "2"),
            listOf(frame(1, 0.5f), frame(0, 0.25f)))
        assertTrue(text.contains("mode=mosaic\n"))
        assertTrue(text.contains("selected=2\n"))
        // Sorted by file index regardless of input order.
        assertTrue(text.indexOf("frame.0.file=frame-00.dng") < text.indexOf("frame.1.file=frame-01.dng"))
        assertTrue(text.contains("frame.0.timestampNs=111\n"))
        assertTrue(text.contains("frame.1.iso=100\n"))
        assertTrue(text.contains("frame.1.exposureNs=10000000\n"))
    }

    @Test fun writtenFramesAreValidTiffDngs() {
        for ((index, value) in listOf(0 to 0.25f, 1 to 0.5f)) {
            val bytes = ByteArrayOutputStream().also {
                MergeDebugPayload.writeFrame(it, frame(index, value))
            }.toByteArray()
            assertTrue("frame $index must be a little-endian TIFF", bytes.size > 8 &&
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 42.toByte() && bytes[3] == 0.toByte())
        }
    }

    @Test fun payloadDirCreatesNestedTag() {
        val root = java.nio.file.Files.createTempDirectory("merge-debug-test").toFile()
        try {
            val dir = MergeDebugPayload.payloadDir(root, "mosaic-123")
            assertTrue(dir.isDirectory)
            assertEquals("mosaic-123", dir.name)
        } finally {
            root.deleteRecursively()
        }
    }
}
