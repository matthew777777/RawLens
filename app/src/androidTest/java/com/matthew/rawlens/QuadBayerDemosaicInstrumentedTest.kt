// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class QuadBayerDemosaicInstrumentedTest {
    @Test fun fullResolutionAllPatternsCropPhasesAndBorders() {
        val processor = Gles31AmazeProcessor(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            for (pattern in BayerPattern.entries) for (oy in 0..3) for (ox in 0..3) {
                val w = 14; val h = 10
                val rgb = floatArrayOf(.2f,.3f,.4f)
                val values = FloatArray(w*h) { i ->
                    rgb[when(pattern.colorAt((i%w+ox)/2,(i/w+oy)/2)) {
                        CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
                    }]
                }
                val frame = QuadBayerFrame(UnpackedRawCfa(w,h,pattern,values,RawCrop(0,0,w,h),ox,oy),pattern)
                processor.processQuadBayer(frame) { output ->
                    assertEquals(w,output.width); assertEquals(h,output.height)
                    val pixels = read(output)
                    for (i in 0 until w*h) for (c in 0..2) {
                        assertEquals("$pattern origin=$ox,$oy pixel=$i channel=$c",rgb[c],pixels[4*i+c],.002f)
                    }
                }
            }
        } finally { processor.close() }
    }

    @Test fun measuredPhotositesArePreservedWithoutBinning() {
        val processor = Gles31AmazeProcessor(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val w=16; val h=12
            // Variation within each 2x2 same-color group would disappear if binned.
            val values=FloatArray(w*h) { i -> .15f + (i%7)*.025f }
            val frame=QuadBayerFrame(UnpackedRawCfa(w,h,BayerPattern.RGGB,values,RawCrop(0,0,w,h)),BayerPattern.RGGB)
            processor.processQuadBayer(frame) { out ->
                val rgb=read(out)
                for (y in 0 until h) for (x in 0 until w) {
                    val channel=when(BayerPattern.RGGB.colorAt(x/2,y/2)) {
                        CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
                    }
                    assertEquals(values[y*w+x],rgb[(y*w+x)*4+channel],.002f)
                }
            }
        } finally { processor.close() }
    }

    private fun read(out: AmazeGpuOutput): FloatArray {
        val fbo=IntArray(1)
        GLES30.glGenFramebuffers(1,fbo,0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,out.textureId,0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE,GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val buffer=ByteBuffer.allocateDirect(out.width*out.height*16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            GLES30.glReadPixels(0,0,out.width,out.height,GLES30.GL_RGBA,GLES30.GL_FLOAT,buffer)
            assertEquals(GLES30.GL_NO_ERROR,GLES30.glGetError())
            return FloatArray(out.width*out.height*4).also { buffer.position(0);buffer.get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
            GLES30.glDeleteFramebuffers(1,fbo,0)
        }
    }
}
