// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BguGuideEncodingTest {
    @Test fun standardSrgbAnchorsAndMonotonicity() {
        assertEquals(0f,BguGuideEncoding.encode(0f),0f)
        assertEquals(0.040449936f,BguGuideEncoding.encode(0.0031308f),1e-7f)
        assertEquals(0.46135613f,BguGuideEncoding.encode(0.18f),1e-6f)
        assertEquals(1f,BguGuideEncoding.encode(1f),1e-6f)
        var previous=0f
        for(i in 0..510) {
            val value=BguGuideEncoding.encode(i/510f)
            assertTrue(value>=previous)
            previous=value
        }
    }
}
