// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a foreign DNG (RAWR_20261101_161329_650, X300 Ultra, 4080x3064 RGGB,
 * black 1024, white 8712) through the real on-device JPEG develop with app
 * defaults (HL reconstruction on, AUTO adaptive exposure), cleanup on and off.
 *
 * The DNG is lossless-JPEG compressed, so the host decodes it first:
 * `rawpy raw_image -> little-endian u16 plane`, then
 * `adb push rawr_plane.u16 /data/local/tmp/rawlens-e2e/`.
 * Outputs land in the app's external files dir (`rawr-render/`) for adb pull.
 */
@RunWith(AndroidJUnit4::class)
class RawrDngRenderDeviceTest {
    @Test fun renderRawrDngCleanupOnAndOff() {
        val plane = File(DIR, PLANE)
        assertTrue("Missing $plane; push the decoded plane first", plane.isFile)
        val raw = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN)
        raw.put(plane.readBytes()).flip()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(context.getExternalFilesDir(null), "rawr-render").apply { mkdirs() }
        val coordinator = RawDevelopmentCoordinator(context)
        try {
            for ((label, cleanup) in listOf(
                "cleanup_on" to ChromaCleanup(),
                "cleanup_off" to ChromaCleanup(falseColorSuppression = false, defringeStrength = 0f)
            )) {
                val output = JpegOutputSettings(chromaCleanup = cleanup)
                val settings = RawDevelopmentSettings(
                    adaptiveExposureStrength = adaptivePreviewStrength(CaptureExposureMode.AUTO, output),
                    adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(output)
                )
                val start = SystemClock.elapsedRealtime()
                val developed = coordinator.developJpeg(raw.duplicate().order(ByteOrder.LITTLE_ENDIAN),
                    metadata(), settings = settings, outputSettings = output)
                val ms = SystemClock.elapsedRealtime() - start
                try {
                    File(outDir, "RAWR_rawlens_$label.jpg").outputStream().use {
                        developed.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    Log.i(TAG, "$label developMs=$ms -> $outDir")
                } finally {
                    developed.bitmap.recycle()
                    developed.gainmap?.recycle()
                }
            }
        } finally {
            coordinator.close()
        }
    }

    private fun d(vararg v: Double) = ImmutableDoubleValues(v)

    /** DNG tags are row-major; RawFrameMetadata uses Camera2's column-major ordering. */
    private fun dngMatrix(vararg rowMajor: Double): ImmutableDoubleValues {
        require(rowMajor.size == 9)
        return ImmutableDoubleValues(DoubleArray(9) { index ->
            rowMajor[(index % 3) * 3 + index / 3]
        })
    }

    private fun metadata(): RawFrameMetadata {
        val full = IntRectSnapshot(0, 0, W, H)
        return RawFrameMetadata(
            cameraId = "rawr-dng", timestampNanos = 1L, frameNumber = 1L,
            imageWidth = W, imageHeight = H, imageCrop = full,
            rawPlaneCount = 1, rawPlaneRowStride = W * 2, rawPlanePixelStride = 2,
            exifOrientation = 1, sensorOrientationDegrees = 0,
            sensitivityIso = 406, exposureTimeNanos = 861_326L, frameDurationNanos = 33_333_333L,
            rollingShutterSkewNanos = null,
            cfaPattern = BayerPattern.RGGB, rawDevelopmentUnsupportedReason = null,
            blackLevels = ImmutableFloatValues(floatArrayOf(1024f, 1024f, 1024f, 1024f)),
            blackLevelSource = BlackLevelSource.STATIC,
            whiteLevel = 8712f, whiteLevelSource = WhiteLevelSource.STATIC,
            pixelArraySize = W to H, activeArray = full, preCorrectionActiveArray = full,
            rawCropRegion = null,
            bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, W, H), "rawr-dng"),
            lensShadingAlreadyApplied = false, lensShadingMap = null, hotPixels = emptyList(),
            wbGains = null,
            neutralColorPoint = d(0.4443359375, 1.0, 0.578125),
            colorCorrectionTransform = null,
            colorMatrix1 = dngMatrix(0.921875, -0.34375, -0.109375, -0.421875, 1.2578125, 0.1328125, -0.1328125, 0.28125, 0.3984375),
            colorMatrix2 = dngMatrix(1.3515625, -0.703125, -0.2421875, -0.6640625, 1.7578125, -0.0703125, -0.1171875, 0.2421875, 0.578125),
            cameraCalibration1 = dngMatrix(1.0390625, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.9765625),
            cameraCalibration2 = dngMatrix(1.0234375, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.9921875),
            forwardMatrix1 = dngMatrix(0.5390625, 0.3515625, 0.078125, 0.1796875, 0.9609375, -0.140625, 0.0390625, -0.421875, 1.2109375),
            forwardMatrix2 = dngMatrix(0.5546875, 0.15625, 0.2578125, 0.203125, 0.6875, 0.1171875, 0.078125, -0.578125, 1.3203125),
            referenceIlluminant1 = 21, // D65
            referenceIlluminant2 = 17, // Standard light A
            noiseProfile = null, sensorPixelMode = null, rawBinningFactorUsed = false,
            activePhysicalCameraId = null
        )
    }

    private companion object {
        const val TAG = "RawrDngRender"
        const val DIR = "/data/local/tmp/rawlens-e2e"
        const val PLANE = "rawr_plane.u16"
        const val W = 4080
        const val H = 3064
    }
}
