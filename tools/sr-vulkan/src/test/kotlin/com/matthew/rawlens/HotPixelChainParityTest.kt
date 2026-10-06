// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Jamy-L parity: the reference has no hot-pixel stage, so the chain must
 * pass merge samples through unaltered. The stuck-low gate misfires on thin
 * scene structure (corners/curves shorter than the 5x5 same-phase ring) and
 * inpaints dark line pixels with the bright ring mean (~2x), which the merge
 * then renders as isolated green dots (root cause on
 * IMG_20260927_134544_522: 375 replica flags within 10px of the 20 post-
 * processed spikes, zero of them sensor-locked, inpaint closure exact to
 * 5 decimals at the autopsied pixel).
 *
 * Fixture: 64x64 RGGB, flat bright field with a dark 3-tap corner. The
 * corner tip is darker than all eight same-phase ring taps by ~7x the 6σ
 * gate, so the pre-fix chain flags and inpaints it; the parity chain must
 * preserve the unpacked value exactly.
 */
class HotPixelChainParityTest {
    private val side = 64
    private val white = 1023f
    private val blacks = listOf(64f, 64f, 64f, 64f)
    private val bg = 350
    private val dark = 140
    // Burst-measured DNG NoiseProfile (R S,O, G S,O, B S,O).
    private val profile = ImmutableDoubleValues(
        doubleArrayOf(
            0.00033967603153224466, 3.809788260380617e-07,
            0.0001718619281381254, 3.903343045313949e-07,
            0.00033983540672202875, 3.7863041190895577e-07
        )
    )

    private fun cornerCodes(): ShortArray {
        val codes = ShortArray(side * side) { bg.toShort() }
        // Dark corner tip at (32,32) plus two dark neighbours: scene
        // structure, not a defect — every same-phase ring tap stays bright.
        codes[32 * side + 32] = dark.toShort()
        codes[32 * side + 33] = dark.toShort()
        codes[33 * side + 32] = dark.toShort()
        return codes
    }

    private fun packedFrame(codes: ShortArray): RawSrPackedFrame {
        val plane = ByteBuffer.allocateDirect(codes.size * 2).order(ByteOrder.nativeOrder())
        for (c in codes) plane.putShort(c)
        plane.flip()
        return RawSrPackedFrame(
            plane, RawPlaneLayout(side, side, side * 2, 2, 0, 0),
            RawCrop(0, 0, side, side),
            RawNormalization(BayerPattern.RGGB, blacks, white),
            null, profile
        )
    }

    private fun metadata(): RawFrameMetadata = RawFrameMetadata(
        cameraId = "test", timestampNanos = 0L, frameNumber = 0L,
        imageWidth = side, imageHeight = side,
        imageCrop = IntRectSnapshot(0, 0, side, side),
        rawPlaneCount = 1, rawPlaneRowStride = side * 2, rawPlanePixelStride = 2,
        exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000L,
        frameDurationNanos = null, rollingShutterSkewNanos = null,
        cfaPattern = BayerPattern.RGGB, rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(64f, 64f, 64f, 64f)),
        blackLevelSource = BlackLevelSource.STATIC,
        whiteLevel = white, whiteLevelSource = WhiteLevelSource.STATIC,
        pixelArraySize = null, activeArray = null, preCorrectionActiveArray = null,
        rawCropRegion = null,
        bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, side, side), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null,
        hotPixels = emptyList(), wbGains = null, neutralColorPoint = null,
        colorCorrectionTransform = null, colorMatrix1 = null, colorMatrix2 = null,
        cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = null, forwardMatrix2 = null,
        referenceIlluminant1 = null, referenceIlluminant2 = null,
        noiseProfile = profile, sensorPixelMode = null, rawBinningFactorUsed = null,
        activePhysicalCameraId = null
    )

    @Test fun referenceFramePreservesThinSceneStructure() {
        val codes = cornerCodes()
        val input = RawSrMergeJob.MosaicInput(packedFrame(codes), metadata())
        val frame = RawSrMergeJob.buildReferenceFrame(input, tuning = RawSrTuning.forSnr(18.0))
        // Parity: samples are the plain unpack, bit-exact, even where the
        // stuck-low gate would fire (corner tip darker than its full ring).
        for (i in codes.indices) {
            val expected = (codes[i].toInt() and 0xffff).toFloat()
            val want = (expected - 64f) / (1023f - 64f)
            assertEquals("sample $i", want, frame.samples[i], 0.0f)
        }
    }
}
