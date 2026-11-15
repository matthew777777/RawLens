package com.rawlens.srpipeline.cli

import org.junit.Assert.*
import org.junit.Test

class ColourDiffCliTest {
    private fun plan(vararg flags: String) = parseArgs(arrayOf("--in", "/tmp/burst", "--out", "/tmp/out",
        "--backend", "vulkan", "--spv-dir", "/tmp/spv", "--kernel", "kernelnet", *flags))

    @Test fun `actual parser consumes colour gains before the next argument`() {
        val p=plan("--colour-diff","2.2,1.6","--scale","2")
        assertArrayEquals(floatArrayOf(2.2f,1.6f),p.colourDiff,0f)
        assertEquals(2f,p.scale,0f)
    }
    @Test fun `automatic mode uses relative reference neutral and supports explicit opt out`() {
        val neutral=doubleArrayOf(.5,1.0,.625)
        assertArrayEquals(floatArrayOf(2f,1.6f),colourDiffGains(plan(),neutral),0f)
        assertArrayEquals(floatArrayOf(2f,1.6f),colourDiffGains(plan("--colour-diff","auto"),neutral),0f)
        assertNull(colourDiffGains(plan("--colour-diff","off"),neutral))
        assertArrayEquals(floatArrayOf(1.8f,1.4f),colourDiffGains(plan("--colour-diff","1.8,1.4"),neutral),0f)
    }
    @Test fun `reference backend defaults and invalid neutral are unchanged`() {
        assertNull(colourDiffGains(plan().copy(kernel="wronski"),doubleArrayOf(.5,1.0,.625)))
        assertNull(colourDiffGains(plan().copy(backend="cpu"),doubleArrayOf(.5,1.0,.625)))
        for(n in listOf(null,doubleArrayOf(0.0,1.0,1.0),doubleArrayOf(Double.NaN,1.0,1.0),doubleArrayOf(1.0)))
            assertNull(colourDiffGains(plan(),n))
    }
}
