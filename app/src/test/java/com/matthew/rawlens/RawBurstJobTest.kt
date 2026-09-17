// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

class RawBurstJobTest {
    @Test fun `success snapshots metadata and gyro without live result lookup`() {
        val frames = listOf(buffered(3_000L), buffered(1_000L), buffered(2_000L))
        var seen: List<BurstFrameSnapshot>? = null
        val job = RawBurstJob.create(
            frames,
            metadataFor = { metadataFor(it.timestampNanos) },
            gyroFor = { timestamp, _, _ -> listOf(GyroSample(timestamp, 0.1f, 0.2f, 0.3f)) },
            work = { seen = it }
        )
        frames.forEach { Mockito.clearInvocations(it.result) }

        job.run()

        val snapshots = requireNotNull(seen)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), snapshots.map { it.timestampNanos })
        snapshots.forEach { snapshot ->
            assertEquals("0", snapshot.metadata.cameraId)
            assertEquals(100, snapshot.sensitivityIso)
            assertEquals(10_000_000L, snapshot.exposureNanos)
            assertEquals(listOf(GyroSample(snapshot.timestampNanos, 0.1f, 0.2f, 0.3f)), snapshot.gyroSamples)
        }
        frames.forEach {
            Mockito.verify(it.image).close()
            Mockito.verifyNoMoreInteractions(it.result)
        }
        job.run()
        frames.forEach { Mockito.verify(it.image).close() }
    }

    @Test fun `duplicate timestamps fail construction and close every image once`() {
        val frames = listOf(buffered(1_000L), buffered(1_000L))

        assertThrows(IllegalArgumentException::class.java) {
            RawBurstJob.create(frames, { metadataFor(it.timestampNanos) }, { _, _, _ -> emptyList() }) {}
        }

        frames.forEach { Mockito.verify(it.image).close() }
    }

    @Test fun `selection failure closes every image once`() {
        val single = listOf(buffered(1_000L))

        assertThrows(IllegalArgumentException::class.java) {
            RawBurstJob.create(single, { metadataFor(it.timestampNanos) }, { _, _, _ -> emptyList() }) {}
        }
        Mockito.verify(single.single().image).close()

        assertThrows(IllegalArgumentException::class.java) {
            RawBurstJob.create(emptyList(), { metadataFor(it.timestampNanos) }, { _, _, _ -> emptyList() }) {}
        }
    }

    @Test fun `geometry mismatch fails construction and closes every image once`() {
        val frames = listOf(buffered(1_000L), buffered(2_000L))
        val mismatched = mapOf(frames[1].timestampNanos to metadataFor(2_000L, width = 16))

        assertThrows(IllegalArgumentException::class.java) {
            RawBurstJob.create(
                frames,
                { mismatched[it.timestampNanos] ?: metadataFor(it.timestampNanos) },
                { _, _, _ -> emptyList() }
            ) {}
        }

        frames.forEach { Mockito.verify(it.image).close() }
    }

    @Test fun `incomplete exposure metadata fails construction and closes every image once`() {
        val frames = listOf(buffered(1_000L), buffered(2_000L))
        val broken = mapOf(frames[0].timestampNanos to metadataFor(1_000L, exposureNanos = null))

        assertThrows(IllegalArgumentException::class.java) {
            RawBurstJob.create(
                frames,
                { broken[it.timestampNanos] ?: metadataFor(it.timestampNanos) },
                { _, _, _ -> emptyList() }
            ) {}
        }

        frames.forEach { Mockito.verify(it.image).close() }
    }

    @Test fun `cancellation before run skips work and closes every image once`() {
        val frames = listOf(buffered(1_000L), buffered(2_000L))
        var calls = 0
        val job = RawBurstJob.create(
            frames, { metadataFor(it.timestampNanos) }, { _, _, _ -> emptyList() }
        ) { calls++ }

        assertTrue(job.cancelBeforeRun())
        assertFalse(job.cancelBeforeRun())
        job.run()

        assertEquals(0, calls)
        frames.forEach { Mockito.verify(it.image).close() }
    }

    @Test fun `processing exception closes every image once`() {
        val frames = listOf(buffered(1_000L), buffered(2_000L))
        val job = RawBurstJob.create(
            frames, { metadataFor(it.timestampNanos) }, { _, _, _ -> emptyList() }
        ) { error("reconstruction failure") }

        assertThrows(IllegalStateException::class.java) { job.run() }
        job.cancelBeforeRun()

        frames.forEach { Mockito.verify(it.image).close() }
    }

    private fun buffered(timestamp: Long): BufferedRawFrame = BufferedRawFrame(
        image = Mockito.mock(Image::class.java),
        result = Mockito.mock(TotalCaptureResult::class.java),
        timestampNanos = timestamp,
        exposureNanos = 10_000_000L,
        rollingShutterSkewNanos = 5_000_000L,
        motionRadiansPerSecond = 0.01f
    )

    private fun metadataFor(
        timestamp: Long,
        width: Int = 32,
        exposureNanos: Long? = 10_000_000L
    ): RawFrameMetadata = RawFrameMetadata(
        cameraId = "0",
        timestampNanos = timestamp,
        frameNumber = timestamp,
        imageWidth = width,
        imageHeight = 24,
        imageCrop = IntRectSnapshot(0, 0, width, 24),
        rawPlaneCount = 1,
        rawPlaneRowStride = width * 2,
        rawPlanePixelStride = 2,
        exifOrientation = 1,
        sensorOrientationDegrees = 90,
        sensitivityIso = 100,
        exposureTimeNanos = exposureNanos,
        frameDurationNanos = 33_333_333L,
        rollingShutterSkewNanos = 5_000_000L,
        cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(64f, 64f, 64f, 64f)),
        blackLevelSource = BlackLevelSource.STATIC,
        whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC,
        pixelArraySize = null,
        activeArray = null,
        preCorrectionActiveArray = null,
        rawCropRegion = null,
        bufferGeometry = RawBufferGeometry.Supported(
            sensorOriginX = 0,
            sensorOriginY = 0,
            processingCrop = RawCrop(0, 0, width, 24),
            provenance = "test"
        ),
        lensShadingAlreadyApplied = false,
        lensShadingMap = null,
        hotPixels = emptyList(),
        wbGains = null,
        neutralColorPoint = null,
        colorCorrectionTransform = null,
        colorMatrix1 = null,
        colorMatrix2 = null,
        cameraCalibration1 = null,
        cameraCalibration2 = null,
        forwardMatrix1 = null,
        forwardMatrix2 = null,
        referenceIlluminant1 = null,
        referenceIlluminant2 = null,
        noiseProfile = null,
        sensorPixelMode = null,
        rawBinningFactorUsed = null,
        activePhysicalCameraId = null
    )
}
