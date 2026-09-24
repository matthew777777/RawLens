package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawNindBayerInstrumentedTest {
    @Test(timeout = 120_000) fun bundledBayerLoadsAndProducesGainMatchedCfa() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = RawNindNcnnProcessor.start(context)
        assertTrue("Bundled Bayer model must load and warm up", processor.waitBayerReady(60_000))
        val source = UnpackedRawCfa(32, 32, BayerPattern.RGGB,
            FloatArray(32 * 32) { 0.2f + ((it * 17 % 31) - 15) * 0.001f }, RawCrop(0, 0, 32, 32))
        val output = RawNindDenoiser(context).denoise(source, CfaNoiseModel.from(null))
        assertNotNull("AI capture path must reach a working model", output)
        output!!
        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        assertEquals(source.pattern, output.pattern)
        assertTrue(output.values.all { it.isFinite() && it >= 0f })
        assertTrue("Arbitrary learned gain was not corrected: ${output.values.average()}",
            output.values.average() in 0.10..0.35)
    }
}
