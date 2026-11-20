package com.rawlens.srpipeline.cli

import org.junit.Assert.*
import org.junit.Test

class ColourDiffCliTest {
    private fun plan(vararg flags: String) = parseArgs(arrayOf("--in", "/tmp/burst", "--out", "/tmp/out",
        "--backend", "vulkan", "--spv-dir", "/tmp/spv", "--kernel", "wronski", *flags))

    @Test fun `grain retention supports default and explicit opt out without consuming scale`() {
        assertEquals(0.5f,plan().grainRetention,0f)
        for(value in listOf(0f,.5f,1f)) {
            val p=plan("--grain-retention",value.toString(),"--scale","2")
            assertEquals(value,p.grainRetention,0f)
            assertEquals(2f,p.scale,0f)
        }
    }

    @Test fun `repeated tune options compose and later duplicate keys override`() {
        val p=plan("--tune","kStretch=2.5","--tune","kShrink=1.8","--tune","kStretch=2.8")
        val t=parseTune(p.tune!!)
        assertEquals(2.8f,t.kStretch,0f)
        assertEquals(1.8f,t.kShrink,0f)
        assertEquals(3f,parseTune(plan("--tune","kStretch=2.5","--tune","app").tune!!).kStretch,0f)
    }

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
    @Test fun `organic Wronski opts into white balanced colour differences`() {
        val p=plan("--kernel","wronski","--reconstruction","organic","--scale","2")
        assertTrue(p.organicReconstruction)
        assertEquals(2f,p.scale,0f)
        assertArrayEquals(floatArrayOf(2f,1.6f),colourDiffGains(p,doubleArrayOf(.5,1.0,.625)),0f)
        assertFalse(plan("--reconstruction","organic","--reconstruction","legacy").organicReconstruction)
    }

    @Test fun `reference backend defaults and invalid neutral are unchanged`() {
        // Wronski follows the app (--tune app is the default); --tune ipol is the IPOL reference.
        assertNotNull(colourDiffGains(plan().copy(kernel="wronski"),doubleArrayOf(.5,1.0,.625)))
        assertNull(colourDiffGains(plan("--tune","ipol").copy(kernel="wronski"),doubleArrayOf(.5,1.0,.625)))
        assertNull(colourDiffGains(plan().copy(backend="cpu"),doubleArrayOf(.5,1.0,.625)))
        for(n in listOf(null,doubleArrayOf(0.0,1.0,1.0),doubleArrayOf(Double.NaN,1.0,1.0),doubleArrayOf(1.0)))
            assertNull(colourDiffGains(plan(),n))
    }
}
