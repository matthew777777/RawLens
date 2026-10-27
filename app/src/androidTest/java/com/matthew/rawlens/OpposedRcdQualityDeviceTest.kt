// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class OpposedRcdQualityDeviceTest {
    @Test fun floatCfaAndWhiteWindowPreserveReconstructedAboveWhiteValues() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val processor=Gles31AmazeProcessor(context)
        try {
            for(pattern in BayerPattern.entries) for(cameraLinear in listOf(false,true)) {
                val expected=floatArrayOf(2f,0.5f,0.25f)
                val w=64; val h=64
                val cfa=UnpackedRawCfa(w,h,pattern,FloatArray(w*h) { i -> expected[pattern.colorOrdinalAt(i%w,i/w)] },RawCrop(0,0,w,h))
                processor.processRcdCfa(cfa,cameraLinear=cameraLinear,
                    whiteWindow=SceneLinearColorProcessor.WHITE_WINDOW_OFF) { output ->
                    val bytes=processor.readbackHalfFloat(output)
                    val offset=(32*w+32)*8
                    for(c in 0..2) {
                        val value=VfGpuImport.halfBitsToFloat(bytes.getShort(offset+2*c))
                        assertEquals("$pattern cameraLinear=$cameraLinear c=$c",expected[c],value,0.004f)
                    }
                }
            }
        } finally { processor.close() }
    }
}
