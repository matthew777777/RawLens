package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawNindBayerInstrumentedTest {
    @Test(timeout = 120_000) fun bayerTilesCoverPartialEdges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = RawNindNcnnProcessor.start(context)
        assertTrue(processor.waitBayerReady(60_000))
        // 272 packed pixels cross the 256px tile boundary; height and the
        // final tile need clamp padding.
        val width = 272
        val height = 16
        val packed = java.nio.ByteBuffer.allocateDirect(width * height * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until packed.capacity()) packed.put(i, 0.2f)
        val output = processor.runInferenceBayer(packed, width, height)
        assertNotNull(output)
        val rgb = output!!.asFloatBuffer()
        assertEquals(width * height * 12, rgb.capacity())
        for (i in 0 until rgb.capacity()) {
            assertTrue("Invalid output at $i", rgb.get(i).isFinite())
        }
    }

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
