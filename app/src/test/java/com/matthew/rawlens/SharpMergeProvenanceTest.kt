// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.util.Locale
import java.io.File
import android.util.Pair
import androidx.exifinterface.media.ExifInterface

class SharpMergeProvenanceTest {
    private fun frame(timestamp: Long): RawFrameMetadata = mock(RawFrameMetadata::class.java).also {
        `when`(it.cameraId).thenReturn("0")
        `when`(it.timestampNanos).thenReturn(timestamp)
        `when`(it.imageWidth).thenReturn(4080)
        `when`(it.imageHeight).thenReturn(3060)
        `when`(it.exposureTimeNanos).thenReturn(1_600_000L)
        `when`(it.sensitivityIso).thenReturn(50)
        `when`(it.frameNumber).thenReturn(1682L)
    }

    @Test fun bothEnginesCarryDetailedFactsIntoJpegWithoutMislabeling() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val frames = listOf(frame(11), frame(22))
            val merged = MfsrMerge.MfsrResult(ByteBuffer.allocate(0), 4080, 3060,
                2, 1234, frames[1], null)
            for (engine in MfsrEngine.entries) {
                val settings = MfsrSettings(engine = engine, lkrZoom = LkrZoom.X2)
                val provenance = sharpMergeProvenance(merged, settings, 3, frames, "test selection")
                val block = LinearRgbDngWriter.provenanceBlock(provenance)
                assertTrue(block.contains("${settings.label} merge"))
                assertTrue(block.contains("Rejected frames: 1"))
                assertTrue(block.contains("Output: 4080x3060"))
                assertTrue(block.contains("Reference: frame 2 of 2"))
                assertTrue(block.contains("F2: 1.600 ms, ISO 50 (reference)"))
                assertTrue(block.contains("Merge: 1234 ms"))
                val jpeg = jpegImageDescription(JpegOutputSettings(), settings.mergeFileSuffix(), block)
                assertTrue(jpeg.startsWith(block))
                assertJpegRoundTrip(jpeg)
                assertTrue(jpeg.contains("Merged linear camera RGB"))
                assertFalse(jpeg.contains("AMaZE"))
                if (engine == MfsrEngine.LKR) {
                    assertFalse(jpeg.contains("MFSR", ignoreCase = true))
                    assertEquals("LK-SR", settings.mergeFileSuffix())
                    assertTrue(block.contains("Reconstruction scale: 2x"))
                }
            }
        } finally { Locale.setDefault(oldLocale) }
    }

    @Test fun jpegPreservesHdrPlusRecordAndSingleFrameDefault() {
        val description = "Captured with RawLens\nHDR+ GPU merge\nPARAMETERS\nFrames: 16\nTIMINGS\nMerge: 21789 ms"
        assertJpegRoundTrip(jpegImageDescription(JpegOutputSettings(), CaptureFileNames.TYPE_HDRPLUS, description))
        assertTrue(jpegImageDescription(JpegOutputSettings(), CaptureFileNames.TYPE_HDRPLUS,
            description).startsWith(description))
        assertTrue(jpegImageDescription(JpegOutputSettings(), null, null).startsWith("RAW_SENSOR -> AMaZE"))
    }
    private fun assertJpegRoundTrip(description: String) {
        mockConstruction(Pair::class.java) { pair, context ->
            Pair::class.java.getField("first").set(pair, context.arguments()[0])
            Pair::class.java.getField("second").set(pair, context.arguments()[1])
        }.use {
            val file = File.createTempFile("merge-description", ".jpg")
            try {
                file.writeBytes(byteArrayOf(-1, -40, -1, -64, 0, 11, 8, 0, 1, 0, 1, 1, 1, 17, 0, -1, -39))
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                    saveAttributes()
                }
                // AndroidX getAttribute replaces ASCII control characters (including newlines)
                // for display. Inspect stored bytes to verify the actual multi-line EXIF record.
                val bytes = requireNotNull(ExifInterface(file).getAttributeBytes(ExifInterface.TAG_IMAGE_DESCRIPTION))
                assertEquals(description, bytes.toString(Charsets.US_ASCII).trimEnd('\u0000'))
            } finally { file.delete() }
        }
    }

}
