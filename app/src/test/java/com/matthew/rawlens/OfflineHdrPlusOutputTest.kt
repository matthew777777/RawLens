// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OfflineHdrPlusOutputTest {
    private fun reference(cameraId: String = "unknown"): RawFrameMetadata =
        mock(RawFrameMetadata::class.java).also {
            `when`(it.cameraId).thenReturn(cameraId)
            `when`(it.exifOrientation).thenReturn(1)
            `when`(it.imageWidth).thenReturn(4)
            `when`(it.imageHeight).thenReturn(4)
            `when`(it.timestampNanos).thenReturn(100L)
            `when`(it.frameNumber).thenReturn(7L)
        }

    private fun provenance(metadata: List<RawFrameMetadata>, keep: List<Int> = metadata.indices.toList(), ref: Int = 0) =
        BurstMergeRunner.offlineHdrPlusProvenance(metadata,
            HdrPlusAutoDecision(3f, true, keep, ref,
                metadata.indices.filterNot { it in keep }, false, false),
            HdrPlusMerge.Timings(1, 2.0, 1), null, 4L)

    @Test fun offlineUnknownCameraPassesBothDngTagPaths() {
        // OfflineBurstMetadata uses "unknown" when the take has no Camera2 ID.
        val metadata = reference()
        val provenance = provenance(listOf(metadata, metadata))
        assertEquals("unknown", provenance.sourceCameraId)
        for (compression in DngCompression.entries) {
            val fields = HdrPlusDngWriter.buildFields(BayerPattern.RGGB, metadata, provenance, compression)
            assertTrue(fields.descriptors.isNotEmpty())
        }
    }

    @Test fun provenanceUsesTheKeptReferenceCamera() {
        val frames = listOf(reference("0"), reference("1"), reference("2"))
        val provenance = provenance(frames, keep = listOf(0, 2), ref = 1)
        assertEquals("2", provenance.sourceCameraId)
        assertEquals(2, provenance.mergedFrames)
        assertEquals(1, provenance.rejectedFrames)
        HdrPlusDngWriter.buildFields(BayerPattern.RGGB, frames[2], provenance, DngCompression.UNCOMPRESSED)
    }

    @Test fun phoneModelAsCameraIdReproducesTheOutputFailure() {
        val metadata = reference()
        val wrong = provenance(listOf(metadata, metadata)).copy(sourceCameraId = "Xiaomi 2407FPN8EG")
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HdrPlusDngWriter.buildFields(BayerPattern.RGGB, metadata, wrong, DngCompression.UNCOMPRESSED)
        }
        assertEquals("Provenance source camera must be the reference metadata camera", failure.message)
    }

    @Test fun offlineProvenanceSerializesToAnActualDng() {
        HostDngLib.assumeAvailable()
        val metadata = reference()
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16) { .5f }, RawCrop(0, 0, 4, 4))
        val output = ByteArrayOutputStream()
        HdrPlusDngWriter.write(output, cfa, metadata,
            provenance = provenance(listOf(metadata, metadata)))
        assertTrue(output.size() > 32)
    }
}
