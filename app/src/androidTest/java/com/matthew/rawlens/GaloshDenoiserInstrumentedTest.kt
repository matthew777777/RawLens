package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GaloshDenoiserInstrumentedTest {
    private fun mosaic(w: Int, h: Int, pattern: BayerPattern) = UnpackedRawCfa(
        w, h, pattern,
        FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val base = 0.12f + 0.45f * (x.toFloat() / w) + 0.18f * (y.toFloat() / h)
            val gain = floatArrayOf(1.00f, 0.85f, 0.85f, 0.70f)[(y and 1) * 2 + (x and 1)]
            (base * gain + (((i * 1103515245 + 12345) ushr 16) % 2000 - 1000) * 1e-6f)
                .coerceIn(0f, 1f)
        },
        RawCrop(0, 0, w, h)
    )

    @Test fun guardsFallBackWithoutNative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denoiser = GaloshDenoiser(context)
        val cfa = mosaic(32, 32, BayerPattern.RGGB)
        assertSame(cfa, denoiser.denoise(cfa, GaloshSettings(enabled = false)))
        assertSame(cfa, denoiser.denoise(cfa, GaloshSettings(enabled = true, strength = 0f)))
    }

    @Test(timeout = 180_000) fun denoiseRemapsNonRgbb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = mosaic(64, 64, BayerPattern.BGGR)
        val output = GaloshDenoiser(context).denoise(source, GaloshSettings(enabled = true))
        assertNotNull("BGGR must remap and denoise, not fall back", output)
        output!!
        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        assertEquals(BayerPattern.BGGR, output.pattern)
        assertTrue(output.values.all { it.isFinite() })
    }

    @Test(timeout = 180_000) fun denoiseProducesFiniteCfa() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = mosaic(128, 96, BayerPattern.RGGB)
        val t = System.nanoTime()
        val output = GaloshDenoiser(context).denoise(source, GaloshSettings(enabled = true))
        assertNotNull("Galosh bridge must produce a CFA on device", output)
        output!!
        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        assertEquals(source.pattern, output.pattern)
        assertEquals(source.sensorCropLeft, output.sensorCropLeft)
        assertEquals(source.sensorCropTop, output.sensorCropTop)
        assertTrue(output.values.all { it.isFinite() })
        var diff = 0.0
        for (i in output.values.indices) {
            val d = (output.values[i] - source.values[i]).toDouble()
            diff += d * d
        }
        diff = kotlin.math.sqrt(diff / output.values.size)
        assertTrue("denoiser must change the input (rms diff=$diff)", diff > 1e-4)
        android.util.Log.i("GALOSHPHASE3",
            "bridge denoise 128x96 wall=${(System.nanoTime() - t) / 1_000_000}ms rmsDiff=$diff")
    }

    @Test(timeout = 180_000) fun fastModeStaysCloseToFull() {
        // Fast mode (4 of 16 WHT phases) must trade only modest quality:
        // its output must stay within 40 dB PSNR of the full run and the
        // full run must denoise (its input-output change exceeds the
        // fast-vs-full difference).
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denoiser = GaloshDenoiser(context)
        val source = mosaic(128, 96, BayerPattern.RGGB)
        val full = denoiser.denoise(source, GaloshSettings(enabled = true))
        assertNotNull("full run must produce a CFA", full)
        val fast = denoiser.denoise(source, GaloshSettings(enabled = true, fastMode = true))
        assertNotNull("fast run must produce a CFA", fast)
        requireNotNull(full) { "full run must produce a CFA" }
        requireNotNull(fast) { "fast run must produce a CFA" }
        fun mse(a: FloatArray, b: FloatArray): Double {
            var acc = 0.0
            for (i in a.indices) {
                val d = (a[i] - b[i]).toDouble()
                acc += d * d
            }
            return acc / a.size
        }
        val fastVsFull = mse(fast.values, full.values)
        val psnr = 10 * kotlin.math.log10(1.0 / fastVsFull)
        val denoiseEffect = mse(full.values, source.values)
        android.util.Log.i("GALOSHPHASE3",
            "fast-vs-full psnr=${"%.2f".format(psnr)}dB denoiseMse=$denoiseEffect")
        assertTrue("fast mode diverged (psnr=$psnr dB)", psnr > 40.0)
        assertTrue("full run must change more than fast differs", denoiseEffect > fastVsFull)
    }
}
