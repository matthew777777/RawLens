package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrFlowNetInstrumentedTest {
    @Test fun nativeModelLoadsAndProducesFiniteFlow() {
        val width = 512
        val height = 384
        val random = java.util.Random(93741L)
        val texture = FloatArray(64 * 48) { random.nextFloat() }
        val cfa = UnpackedRawCfa(width, height, BayerPattern.RGGB,
            FloatArray(width * height) { i ->
                val x = i % width; val y = i / width
                0.1f + texture[(y / 8) * 64 + x / 8] * 0.7f
            }, RawCrop(0, 0, width, height))
        val frame = HdrMergeFrame(cfa, 10_000_000, 100)
        val aligner = HdrFlowNetAligner(InstrumentationRegistry.getInstrumentation().targetContext)
        val flow = aligner.align(frame, frame)
        assertNotNull("FlowNet model must execute, without identity fallback", flow)
        for (y in 32 until height - 32 step 32) for (x in 32 until width - 32 step 32) {
            val (dx, dy) = requireNotNull(flow).displacement(x, y)
            assertTrue(dx.isFinite() && dy.isFinite())
            assertTrue("Identical frames should have near-zero flow: $dx,$dy",
                kotlin.math.abs(dx) < 4f && kotlin.math.abs(dy) < 4f)
        }
    }
}
