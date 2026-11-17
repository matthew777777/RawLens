// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class SigmoidDisplayTransformTest {
    @Test fun defaultBlackGreyAndWhiteAnchors() {
        assertEquals(.000152f,SigmoidDisplayTransform.curve(0f),1e-7f)
        assertEquals(.1845f,SigmoidDisplayTransform.curve(.1845f),1e-6f)
        assertTrue(SigmoidDisplayTransform.curve(10000f)>.99999f)
        var last=0f
        for(i in 0..1000) {
            val x=SigmoidDisplayTransform.curve(i/100f)
            assertTrue(x>=last);assertTrue(x<=1f);last=x
        }
    }
    @Test fun hueOrderNeutralityAndNegativePrimaries() {
        for(rgb in listOf(floatArrayOf(.1f,.4f,2f),floatArrayOf(2f,.1f,.4f),floatArrayOf(.4f,2f,.1f))) {
            val out=SigmoidDisplayTransform.map(rgb)
            val order=(0..2).sortedBy { rgb[it] };val lo=order[0];val mid=order[1];val hi=order[2]
            assertEquals((rgb[mid]-rgb[lo])/(rgb[hi]-rgb[lo]),(out[mid]-out[lo])/(out[hi]-out[lo]),1e-5f)
        }
        for(v in listOf(0f,.1845f,1f,100f)) {
            val out=SigmoidDisplayTransform.map(floatArrayOf(v,v,v))
            out.forEach { assertEquals(SigmoidDisplayTransform.curve(v),it,1e-6f) }
        }
        SigmoidDisplayTransform.map(floatArrayOf(-.1f,.3f,1f)).forEach { assertTrue(it.isFinite() && it>=0f) }
    }
}
