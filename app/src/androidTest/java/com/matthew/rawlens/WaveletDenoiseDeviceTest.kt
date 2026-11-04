// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Half
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On-device checks of the production wavelet host: buffer-output backtransform,
 * both algorithms, and the fused capture-sharpening + denoise submit.
 */
class WaveletDenoiseDeviceTest {
    private val wb = floatArrayOf(1.9f, 1f, 1.45f)
    private val noiseA = 5e-4f
    private val noiseB = 1e-6f

    /** Smooth colour gradients + edges + Poisson-Gaussian noise, RGBA16F. */
    private fun frame(w: Int, h: Int, seed: Long, gain: Float = 1f): ByteArray {
        val rnd = Random(seed)
        val out = ByteBuffer.allocate(w * h * 8).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val edge = if ((x / 97 + y / 61) % 2 == 0) 1f else 0.45f
            val base = floatArrayOf(0.05f + 0.25f * x / w, 0.12f * edge, 0.04f + 0.2f * y / h)
            for (c in 0 until 3) {
                val clean = base[c] * (if (c == 1) 1f else edge)
                val v = clean + rnd.nextGaussian().toFloat() * sqrt(gain * noiseA * clean + gain * gain * noiseB)
                out.putShort(Half.toHalf(v))
            }
            out.putShort(Half.toHalf(1f))
        }
        return out.array()
    }

    private fun direct(bytes: ByteArray) =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }

    private fun bytes(buffer: ByteBuffer) = ByteArray(buffer.capacity()).also { buffer.duplicate().apply { rewind(); get(it) } }

    @Test fun bothAlgorithmsDenoiseAndFusedSharpeningMatchesSequential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stage = WaveletDenoiser(context)
        try {
            val w = 1024; val h = 768
            val input = frame(w, h, 7)
            for (algorithm in WaveletAlgorithm.entries) {
                val settings = WaveletSettings(enabled = true, algorithm = algorithm)
                val buf = direct(input)
                assertTrue(stage.process(buf, buf, w, h, settings, noiseA, noiseB, wb))
                val out = ByteBuffer.wrap(bytes(buf)).order(ByteOrder.nativeOrder())
                val src = ByteBuffer.wrap(input).order(ByteOrder.nativeOrder())
                var changed = 0
                var sumAbs = 0.0
                for (i in 0 until w * h * 4) {
                    val v = Half.toFloat(out.getShort(i * 2))
                    assertTrue("finite $algorithm at $i", v.isFinite())
                    if (i % 4 == 3) assertTrue("alpha $algorithm", v == 1f)
                    else { val d = abs(v - Half.toFloat(src.getShort(i * 2))); sumAbs += d; if (d > 0f) changed++ }
                }
                val mean = sumAbs / (w * h * 3)
                Log.i(TAG, "$algorithm changed=$changed meanAbsDelta=$mean gpu=${stage.lastGpuMs}ms")
                assertTrue("$algorithm denoised", changed > w * h && mean > 1e-4 && mean < 0.05)
            }
            // Fused (one submit) must equal sharpen-then-denoise, byte for byte.
            val sharpen = CaptureSharpeningSettings(true, CaptureSharpeningLevel.entries[1])
            val settings = WaveletSettings(enabled = true)
            val sequential = direct(input)
            stage.captureSharpen(sequential, w, h, wb, BayerPattern.RGGB, sharpen)
            stage.process(sequential, sequential, w, h, settings, noiseA, noiseB, wb)
            val fused = direct(input)
            stage.process(fused, fused, w, h, settings, noiseA, noiseB, wb,
                WaveletDenoiser.SharpenRequest(sharpen, BayerPattern.RGGB, null))
            assertArrayEquals(bytes(sequential), bytes(fused))
        } finally {
            stage.release()
        }
    }

    @Test fun fullFrameTiming() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stage = WaveletDenoiser(context)
        try {
            val w = 4080; val h = 3060
            val input = frame(w, h, 11)
            val buf = direct(input)
            for (algorithm in WaveletAlgorithm.entries) {
                val settings = WaveletSettings(enabled = true, algorithm = algorithm)
                val times = (0 until 3).map {
                    buf.rewind(); buf.put(input); buf.rewind()
                    val t0 = System.nanoTime()
                    stage.process(buf, buf, w, h, settings, noiseA, noiseB, wb)
                    ((System.nanoTime() - t0) / 1e6) to stage.lastGpuMs
                }
                Log.i(TAG, "TIMING $algorithm ${w}x$h wall/gpu ms = $times")
            }
        } finally {
            stage.release()
        }
    }

    /** Learned noise model: samples accumulate per key, then the model drives the shot. */
    @Test fun learnedNoiseModelStoresSamplesAndApplies() {
        checkLearnedNoiseModel(WaveletAlgorithm.RAWLENS)
    }

    @Test fun rawrLearnsNoiseWithoutSensorProfile() {
        checkLearnedNoiseModel(WaveletAlgorithm.RAWR)
    }

    private fun checkLearnedNoiseModel(algorithm: WaveletAlgorithm) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stage = WaveletDenoiser(context)
        val dir = java.io.File(context.filesDir, "wavelet-noise")
        val source = { iso: Int -> WaveletDenoiser.NoiseSource(iso, "device-test-${System.nanoTime() / 1000000000}") }
        val key = source(0).rawModeKey
        try {
            val w = 1024; val h = 768
            val settings = WaveletSettings(enabled = true, algorithm = algorithm)
            val before = dir.listFiles()?.toSet() ?: emptySet()
            for ((i, iso) in listOf(100, 200, 400, 800, 1600, 3200, 1000).withIndex()) {
                val buf = direct(frame(w, h, 20L + i, iso / 400f))
                assertTrue(stage.process(buf, buf, w, h, settings, 0f, 0f, wb,
                    noiseSource = WaveletDenoiser.NoiseSource(iso, key)))
            }
            val created = (dir.listFiles()?.toSet() ?: emptySet()) - before
            assertTrue("one store file per key", created.size == 1)
            val lines = created.single().readLines()
            Log.i(TAG, "noise store ${lines.size} lines: ${lines.take(3)}")
            assertTrue("header + samples", lines.first() == "rawlens-noise v2" && lines.size >= 7)
            created.forEach { it.delete() }
        } finally {
            stage.release()
        }
    }

    @Test fun rawrUsesStoredPriorAndDoesNotTrainTwiceOnReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stage = WaveletDenoiser(context)
        val dir = WaveletDenoiser.noiseDir(context)
        val key = "noise-prior-test-${System.nanoTime()}"
        val low = WaveletDenoiser.NoiseSource(400, "$key-low", 800, 991L)
        val high = low.copy(rawModeKey = "$key-high")
        val paths = listOf(low, high).map { java.io.File(WaveletDenoiser.storePath(dir, it)!!) }
        try {
            for ((index, path) in paths.withIndex()) {
                val factor = if (index == 0) 1f else 4f
                path.writeText("rawlens-noise v2\n" + (1..3).joinToString("") {
                    "400 ${noiseA * factor} ${noiseB * factor} 8 800 $it\n"
                })
            }
            val settings = WaveletSettings(enabled = true, algorithm = WaveletAlgorithm.RAWR)
            val input = frame(1024, 768, 71L)
            val a = direct(input)
            val b = direct(input)
            assertTrue(stage.process(a, a, 1024, 768, settings, 0f, 0f, wb, noiseSource = low))
            assertTrue(stage.process(b, b, 1024, 768, settings, 0f, 0f, wb, noiseSource = high))
            assertTrue("stored noise prior changes RAWR output", !bytes(a).contentEquals(bytes(b)))
            val count = paths[0].readLines().size
            val replay = direct(input)
            assertTrue(stage.process(replay, replay, 1024, 768, settings, 0f, 0f, wb, noiseSource = low))
            assertTrue("same capture trains once", paths[0].readLines().size == count)
            val prediction = WaveletVulkan.predictNoise(paths[1].absolutePath, 400f)
            assertTrue("shared read-only prediction available", prediction != null)
        } finally {
            paths.forEach { it.delete() }
            stage.release()
        }
    }

    companion object { private const val TAG = "WaveletDeviceTest" }
}
