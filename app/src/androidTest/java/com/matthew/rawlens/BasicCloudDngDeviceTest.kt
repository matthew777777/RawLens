// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional real-scene fixture: decoded IMG_20261115_085914_296.dng.
 * Push its unmodified little-endian sensor plane as clouds.u16 in the target app external files directory.
 * Personal source pixels are deliberately not bundled in the test APK/repository. */
class BasicCloudDngDeviceTest {
    @Test fun cloudShoulderRecoversGradationWithoutChangingExposure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plane = File(context.getExternalFilesDir(null), "clouds.u16")
        assumeTrue("external cloud DNG fixture is not installed", plane.isFile)
        assertEquals((W * H * 2).toLong(), plane.length())
        val raw = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN)
            .put(plane.readBytes()).apply { flip() }
        val dir = File(context.getExternalFilesDir(null), "clouds-render").apply { mkdirs() }
        val coordinator = RawDevelopmentCoordinator(context)
        val images = mutableListOf<Bitmap>()
        try {
            for (strength in listOf(0f, 1f)) {
                val look = JpegOutputSettings(toneMapper = PhotoToneMapper.BASIC, highlightShoulder = strength)
                val tuning = AdaptiveExposureTuning.fromOutputSettings(look)
                val settings = RawDevelopmentSettings(adaptiveExposureStrength = 1f, adaptiveExposureTuning = tuning)
                val developed = coordinator.developJpeg(raw.duplicate().order(ByteOrder.LITTLE_ENDIAN),
                    metadata(), settings, look)
                images += developed.bitmap
                File(dir, "basic-shoulder-${strength.toInt()}.jpg").outputStream().use {
                    developed.bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }
                developed.gainmap?.recycle()
            }
            var whiteBefore = 0; var whiteAfter = 0; var darker = 0; var checked = 0
            var maxMidError = 0
            for (y in 0 until H step 2) for (x in 0 until W step 2) {
                val a = images[0].getPixel(x,y); val b = images[1].getPixel(x,y)
                val ca = intArrayOf(Color.red(a), Color.green(a), Color.blue(a))
                val cb = intArrayOf(Color.red(b), Color.green(b), Color.blue(b))
                // Neutral v2 preserves scene <= 0.6 (sRGB code <= 203).
                if (ca.max() <= 200) for (c in 0..2) maxMidError = maxOf(maxMidError, kotlin.math.abs(ca[c]-cb[c]))
                if (x in (W*.17).toInt() until (W*.37).toInt() && y >= (H*.66).toInt()) {
                    checked++
                    if (ca.min() >= 250) whiteBefore++
                    if (cb.min() >= 250) whiteAfter++
                    if (cb.sum() < ca.sum()-6) darker++
                }
            }
            File(dir,"metrics.txt").writeText("cloudSamples=$checked whiteBefore=$whiteBefore whiteAfter=$whiteAfter darker=$darker maxMidError=$maxMidError")
            assertTrue("cloud shoulder did not lower highlight codes", darker > checked/10)
            assertTrue("near-white cloud area did not shrink: $whiteBefore -> $whiteAfter", whiteAfter <= whiteBefore)
            assertTrue("midtone codes changed by $maxMidError", maxMidError <= 2)
        } finally {
            images.forEach(Bitmap::recycle); coordinator.close()
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
            cameraId = "cloud-dng", timestampNanos = 1L, frameNumber = 1L,
            imageWidth = W, imageHeight = H, imageCrop = full,
            rawPlaneCount = 1, rawPlaneRowStride = W * 2, rawPlanePixelStride = 2,
            exifOrientation = 1, sensorOrientationDegrees = 0,
            sensitivityIso = 50, exposureTimeNanos = 2_068_000L, frameDurationNanos = 33_333_333L,
            rollingShutterSkewNanos = null,
            cfaPattern = BayerPattern.GBRG, rawDevelopmentUnsupportedReason = null,
            blackLevels = ImmutableFloatValues(floatArrayOf(1024f, 1024f, 1024f, 1024f)),
            blackLevelSource = BlackLevelSource.STATIC,
            whiteLevel = 15424f, whiteLevelSource = WhiteLevelSource.STATIC,
            pixelArraySize = W to H, activeArray = full, preCorrectionActiveArray = full,
            rawCropRegion = null,
            bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, W, H), "cloud-dng"),
            lensShadingAlreadyApplied = false, lensShadingMap = null, hotPixels = emptyList(),
            wbGains = null,
            neutralColorPoint = d(0.495164, 1.0, 0.710125),
            colorCorrectionTransform = null,
            colorMatrix1 = dngMatrix(0.666794, -0.15889, -0.085739, -0.573944, 1.389786, 0.143021, -0.137878, 0.265152, 0.603622),
            colorMatrix2 = dngMatrix(1.531464, -0.469604, -0.215057, -0.476227, 1.445328, 0.006699, -0.071747, 0.238724, 0.232956),
            cameraCalibration1 = dngMatrix(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            cameraCalibration2 = dngMatrix(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            forwardMatrix1 = dngMatrix(0.673141, 0.195038, 0.096024, 0.276184, 0.818207, -0.094406, 0.021652, -0.232452, 1.036011),
            forwardMatrix2 = dngMatrix(0.574493, 0.184006, 0.205704, 0.193817, 0.745377, 0.060791, -0.014496, -0.528687, 1.368408),
            referenceIlluminant1 = 21, // D65
            referenceIlluminant2 = 17, // Standard light A
            noiseProfile = null, sensorPixelMode = null, rawBinningFactorUsed = false,
            activePhysicalCameraId = null
        )
    }

    private companion object { const val W = 4080; const val H = 3060 }
}
