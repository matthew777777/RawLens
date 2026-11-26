// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** Whole develop with and without the RAW denoiser: colour mean kept, chroma noise removed, fine luma kept in place. */
class RawDenoiseDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val black = 64f
    private val white = 1023f
    private val rgb = floatArrayOf(0.09f, 0.18f, 0.12f)

    /** Noisy grey frame on a smooth horizontal ramp; a=1.3e-3 b=7e-6 is a night frame. */
    private fun raw(w: Int, h: Int, pattern: BayerPattern, strideBytes: Int, noisy: Boolean = true): ByteBuffer {
        val random = Random(7)
        val out = ByteBuffer.allocateDirect(strideBytes * h).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val v = rgb[pattern.colorOrdinalAt(x, y)] * (0.4f + 1.6f * x / w)
            val n = if (noisy) sqrt(1.3e-3f * v + 7e-6f) * random.nextGaussian().toFloat() else 0f
            out.putShort(y * strideBytes + x * 2, ((v + n) * (white - black) + black + 0.5f).toInt().coerceIn(0, 1023).toShort())
        }
        return out
    }

    /** {meanR, meanG, meanB, chroma sigma, luma sigma} from horizontal neighbour differences. */
    private fun stats(b: Bitmap): DoubleArray {
        val w = b.width
        val px = IntArray(w * b.height)
        b.getPixels(px, 0, w, 0, 0, w, b.height)
        val mean = DoubleArray(3)
        var chroma = 0.0
        var luma = 0.0
        var n = 0
        for (y in 16 until b.height - 16) for (x in 16 until w - 24) {
            val p = px[y * w + x]
            val q = px[y * w + x + 8]
            fun c(v: Int, i: Int) = (v shr (16 - 8 * i)) and 255
            for (i in 0..2) mean[i] += c(p, i).toDouble()
            val dr = c(p, 0) - c(q, 0)
            val dg = c(p, 1) - c(q, 1)
            val db = c(p, 2) - c(q, 2)
            chroma += (dr - dg).toDouble() * (dr - dg) + (db - dg).toDouble() * (db - dg)
            luma += ((dr + dg + db) / 3.0).let { it * it }
            n++
        }
        return doubleArrayOf(mean[0] / n, mean[1] / n, mean[2] / n, sqrt(chroma / n), sqrt(luma / n))
    }

    @Test fun developKeepsColourAndRemovesChromaNoise() {
        val coordinator = RawDevelopmentCoordinator(context)
        try {
            for (demosaic in listOf(JpegDemosaic.AMAZE, JpegDemosaic.RCD)) for (pattern in BayerPattern.entries) for (hl in listOf(false, true)) {
                val w = 1280
                val h = 1026
                val stride = w * 2 + 64
                val crop = RawCrop(4, 2, 1200, 1000)
                val m = metadata(w, h, stride, pattern, crop)
                val plane = raw(w, h, pattern, stride)
                fun develop(denoise: RawDenoiseSettings, source: ByteBuffer = plane): DoubleArray {
                    val jpeg = coordinator.developJpeg(source, m, RawDevelopmentSettings(
                        denoise = DenoiseSettings(rawDenoise = denoise)),
                        JpegOutputSettings(hlReconstruction = hl, ultraHdr = false, displayP3 = false, demosaic = demosaic))
                    assertEquals(crop.width, jpeg.bitmap.width)
                    return stats(jpeg.bitmap).also { jpeg.bitmap.recycle() }
                }
                val noisy = develop(RawDenoiseSettings(false))
                val clean = develop(RawDenoiseSettings(true, 0f))
                val auto = develop(RawDenoiseSettings(true, engine = RawDenoiseSettings.Engine.CLASSIC))
                val chromaOnly = develop(RawDenoiseSettings(true, luma = 0f))
                val lumaOnly = develop(RawDenoiseSettings(true, chroma = 0f))
                val v1 = develop(RawDenoiseSettings(true, engine = RawDenoiseSettings.Engine.CLASSIC_V1))
                assertTrue("chroma only keeps luma noise ${chromaOnly[4]} vs ${noisy[4]}", chromaOnly[4] > auto[4])
                assertTrue("chroma only removes color ${chromaOnly[3]} vs ${noisy[3]}", chromaOnly[3] < noisy[3] * 0.45)
                assertTrue("luma only keeps color ${lumaOnly[3]} vs ${auto[3]}", lumaOnly[3] > auto[3] * 1.5)
                val truth = develop(RawDenoiseSettings(false), raw(w, h, pattern, stride, noisy = false))
                val name = "$demosaic $pattern hl=$hl"
                Log.i("RawDenoiseDevice", "$name noisy=${noisy.contentToString()} clean=${clean.contentToString()} " +
                    "auto=${auto.contentToString()} truth=${truth.contentToString()}")
                for (i in 0..2) assertTrue("$name mean $i ${auto[i]} vs ${truth[i]}", abs(auto[i] - truth[i]) < 1.5)
                for (i in 0..2) assertTrue("$name v1 mean $i ${v1[i]} vs ${truth[i]}", abs(v1[i] - truth[i]) < 1.5)
                // night frame: v1 keeps the coarse grain the default thins, colour noise removed the same way
                assertTrue("$name v1 keeps more grain ${v1[4]} vs ${auto[4]}", v1[4] >= auto[4])
                assertTrue("$name v1 chroma ${v1[3]} vs ${noisy[3]}", v1[3] < noisy[3] * 0.45)
                assertTrue("$name chroma ${auto[3]} vs ${noisy[3]}", auto[3] < noisy[3] * 0.45)
                assertTrue("$name luma ${auto[4]} vs ${noisy[4]}", auto[4] < noisy[4] && auto[4] > clean[4] * 1.2)
            }
        } finally { coordinator.close() }
    }

    @Test fun fullFrameTiming() {
        val w = 4080
        val h = 3060
        val crop = RawCrop(0, 0, w, h)
        val m = metadata(w, h, w * 2, BayerPattern.RGGB, crop)
        val plane = raw(w, h, BayerPattern.RGGB, w * 2)
        val coordinator = RawDevelopmentCoordinator(context)
        try {
            for (i in 0 until 4) for (enabled in listOf(false, true)) {
                val t = System.nanoTime()
                val jpeg = coordinator.developJpeg(plane, m, RawDevelopmentSettings(
                    denoise = DenoiseSettings(rawDenoise = RawDenoiseSettings(enabled))),
                    JpegOutputSettings(hlReconstruction = false, ultraHdr = false, displayP3 = false))
                jpeg.bitmap.recycle()
                Log.i("RawDenoiseDevice", "full develop denoise=$enabled ms=${(System.nanoTime() - t) / 1_000_000}")
            }
        } finally { coordinator.close() }
    }

    private fun metadata(w: Int, h: Int, stride: Int, pattern: BayerPattern, crop: RawCrop) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = w, imageHeight = h,
        imageCrop = IntRectSnapshot(0, 0, w, h), rawPlaneCount = 1, rawPlaneRowStride = stride,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        // No ISO: the synthetic noise must not enter the learned model.
        sensitivityIso = null, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = pattern,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(FloatArray(4) { black }),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = white,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = w to h,
        activeArray = IntRectSnapshot(0, 0, w, h), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, crop, "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(),
        wbGains = ImmutableFloatValues(floatArrayOf(2f, 1f, 1f, 1.5f)),
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(0.5, 1.0, 2.0 / 3.0)), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = ImmutableDoubleValues(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)), forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )
}
