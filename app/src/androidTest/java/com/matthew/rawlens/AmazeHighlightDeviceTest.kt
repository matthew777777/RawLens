// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AmazeHighlightDeviceTest {
    @Test fun clippedGreenIsNeutralWithReconstructionOffInBothFinalShaders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = Gles31AmazeProcessor(context)
        val jpeg = Gles31JpegOutputProcessor(context)
        val settings = JpegOutputSettings(ultraHdr = false, displayP3 = false)
        // All balanced channels are below 0.70, but green is at sensor white.
        val sensor = floatArrayOf(0.6f, 1f, 0.84f)
        val white = floatArrayOf(0.5f, 1f, 0.7f)
        val matrix = floatArrayOf(2f,0f,0f, 0f,1f,0f, 0f,0f,1f/0.7f)
        try {
            for (pattern in BayerPattern.entries) for (fused in listOf(false, true)) {
                val w=64; val h=64
                val cfa=UnpackedRawCfa(w,h,pattern,FloatArray(w*h) {
                    sensor[pattern.colorOrdinalAt(it%w,it/w)]
                },RawCrop(0,0,w,h))
                processor.process(cfa, cameraWhiteNormalized=white,
                    cameraToAcescgColumnMajor=matrix,
                    fusedOutputSettings=if(fused) settings else null) { output ->
                    if(fused) {
                        val bitmap=jpeg.processEncoded(output,settings).bitmap
                        try {
                            val pixel=bitmap.getPixel(32,32)
                            val r=android.graphics.Color.red(pixel)
                            val g=android.graphics.Color.green(pixel)
                            val b=android.graphics.Color.blue(pixel)
                            assertTrue("$pattern fused RGB=$r,$g,$b", maxOf(r,g,b)-minOf(r,g,b)<=3)
                        } finally { bitmap.recycle() }
                    } else {
                        val bytes=processor.readbackHalfFloat(output)
                        val rgb=FloatArray(3) { VfGpuImport.halfBitsToFloat(bytes.getShort((32*w+32)*8+it*2)) }
                        assertEquals("$pattern R/G",rgb[0],rgb[1],0.005f)
                        assertEquals("$pattern B/G",rgb[2],rgb[1],0.005f)
                    }
                }
                // Disabling the fallback (opposed ON) must retain reconstructed color.
                processor.process(cfa,cameraWhiteNormalized=white,cameraToAcescgColumnMajor=matrix,
                    whiteWindow=SceneLinearColorProcessor.WHITE_WINDOW_OFF) { output ->
                    val bytes=processor.readbackHalfFloat(output)
                    val r=VfGpuImport.halfBitsToFloat(bytes.getShort((32*w+32)*8))
                    val g=VfGpuImport.halfBitsToFloat(bytes.getShort((32*w+32)*8+2))
                    assertTrue("reconstructed color retained",r-g>0.1f)
                }
            }
        } finally { processor.close() }
    }
}
