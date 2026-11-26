// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Network denoiser on the NPU: the graph matches the Mac simulation (tools/raw-denoise/pmrid/export_nnapi.py --ref,
 * pushed to /data/local/tmp/rawdn/pmrid_ref.bin), and a whole develop keeps colour and removes noise.
 */
class NetworkDenoiseDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val black = 64f
    private val white = 1023f
    private val rgb = floatArrayOf(0.09f, 0.18f, 0.12f)

    private fun net(): NetworkDenoiser {
        val d = NetworkDenoiser.get(context)
        assertNotNull("no NPU runs the network on this phone", d)
        return d!!
    }

    @Test fun graphMatchesSimulation() {
        val ref = File("/data/local/tmp/rawdn/pmrid_ref.bin")
        assumeTrue("push the export test vector to $ref", ref.canRead())
        val b = ByteBuffer.wrap(ref.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val h = b.int
        val w = b.int
        val sigma = b.float
        val n = w * h * 4
        val u = ByteArray(n).also { b.get(it) }
        val expected = ByteArray(n).also { b.get(it) }
        val got = ByteArray(n)
        val d = net()
        val t = System.nanoTime()
        assertTrue("tile failed on ${d.device}", d.runTile(u, sigma, got))
        val ms = (System.nanoTime() - t) / 1e6
        var max = 0
        var sum = 0.0
        var exact = 0
        for (i in 0 until n) {
            val e = abs((got[i].toInt() and 255) - (expected[i].toInt() and 255))
            max = maxOf(max, e); sum += e; if (e == 0) exact++
        }
        Log.i("NetworkDenoiseDevice", "tile ${w}x$h on ${d.device}: ${"%.1f".format(ms)} ms, vs simulation max $max LSB, " +
            "mean ${"%.3f".format(sum / n)} LSB, exact ${"%.1f".format(100.0 * exact / n)}%")
        assertTrue("graph differs from the simulation: mean ${sum / n} LSB", sum / n < 1.5)
    }

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

    @Test fun developKeepsColourAndRemovesNoise() {
        net()
        val coordinator = RawDevelopmentCoordinator(context)
        try {
            for (pattern in BayerPattern.entries) {
                val w = 1280
                val h = 1026
                val stride = w * 2 + 64
                val crop = RawCrop(4, 2, 1200, 1000)
                val m = metadata(w, h, stride, pattern, crop)
                val plane = raw(w, h, pattern, stride)
                fun develop(denoise: RawDenoiseSettings, source: ByteBuffer = plane): DoubleArray {
                    val jpeg = coordinator.developJpeg(source, m, RawDevelopmentSettings(denoise = DenoiseSettings(rawDenoise = denoise)),
                        JpegOutputSettings(hlReconstruction = false, ultraHdr = false, displayP3 = false))
                    assertEquals(crop.width, jpeg.bitmap.width)
                    return stats(jpeg.bitmap).also { jpeg.bitmap.recycle() }
                }
                val noisy = develop(RawDenoiseSettings(false))
                val network = develop(RawDenoiseSettings(true, engine = RawDenoiseSettings.Engine.NETWORK))
                val half = develop(RawDenoiseSettings(true, engine = RawDenoiseSettings.Engine.NETWORK, networkStrength = 0.5f))
                val truth = develop(RawDenoiseSettings(false), raw(w, h, pattern, stride, noisy = false))
                val name = "$pattern"
                Log.i("NetworkDenoiseDevice", "$name noisy=${noisy.contentToString()} network=${network.contentToString()} " +
                    "half=${half.contentToString()} truth=${truth.contentToString()}")
                for (i in 0..2) assertTrue("$name mean $i ${network[i]} vs ${truth[i]}", abs(network[i] - truth[i]) < 1.5)
                assertTrue("$name chroma ${network[3]} vs ${noisy[3]}", network[3] < noisy[3] * 0.6)
                assertTrue("$name luma ${network[4]} vs ${noisy[4]}", network[4] < noisy[4] * 0.8)
                assertTrue("$name half strength in between", half[4] > network[4] && half[4] < noisy[4])
            }
        } finally { coordinator.close() }
    }

    @Test fun fullFrameTiming() {
        net()
        val w = 4080
        val h = 3060
        val m = metadata(w, h, w * 2, BayerPattern.RGGB, RawCrop(0, 0, w, h))
        val plane = raw(w, h, BayerPattern.RGGB, w * 2)
        val coordinator = RawDevelopmentCoordinator(context)
        try {
            for (i in 0 until 3) for (engine in RawDenoiseSettings.Engine.entries) {
                val t = System.nanoTime()
                val jpeg = coordinator.developJpeg(plane, m, RawDevelopmentSettings(
                    denoise = DenoiseSettings(rawDenoise = RawDenoiseSettings(true, engine = engine))),
                    JpegOutputSettings(hlReconstruction = false, ultraHdr = false, displayP3 = false))
                jpeg.bitmap.recycle()
                Log.i("NetworkDenoiseDevice", "full develop engine=$engine ms=${(System.nanoTime() - t) / 1_000_000}")
            }
        } finally { coordinator.close() }
    }

    private fun metadata(w: Int, h: Int, stride: Int, pattern: BayerPattern, crop: RawCrop) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = w, imageHeight = h,
        imageCrop = IntRectSnapshot(0, 0, w, h), rawPlaneCount = 1, rawPlaneRowStride = stride,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
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
