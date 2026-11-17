// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import android.util.Half
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Device checks of the RapidRAW v1.7 develop port: dispatch slicing, fused output parity
 * and the ON/OFF switch. All medians are GPU radix selects; nothing crosses to the CPU.
 */
@RunWith(AndroidJUnit4::class)
class RapidRawDevelopInstrumentedTest {
    @Test fun slicedWorkgroupsMatchMonolithicAtOddEdges() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val mono=RapidRawDevelop(context,1_000_000)
            val sliced=RapidRawDevelop(context,128)
            try {
                withTexture(field(65,49){x,y ->
                    floatArrayOf(.03f+.4f*x/65,.05f+.2f*y/49,.04f+.1f*(x+y)/114)
                },65,49){scene ->
                    val expected=mono.withDeveloped(scene,AMOUNTS){read(it)}
                    val actual=sliced.withDeveloped(scene,AMOUNTS){read(it)}
                    assertArrayEquals("Dispatch offsets must preserve every workgroup halo",expected,actual,0f)
                }
            }finally{mono.close();sliced.close()}
        }
    }

    @Test fun fusedJpegMatchesSeparatePassesIncludingOddGainmapEdges() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val cleanup=RapidRawDevelop(context)
            val processor=Gles31JpegOutputProcessor(context)
            try {
                withTexture(field(65,49){x,y ->
                    val l=.02f+2f*x/65;val n=if((x+y)%2==0).013f else -.013f
                    floatArrayOf(l+n,l*.8f,l*.7f-n)
                },65,49){scene ->
                    for(tone in PhotoToneMapper.entries)for(p3 in listOf(false,true))for(hdr in listOf(false,true)){
                        val on=JpegOutputSettings(chromaCleanup=ChromaCleanup(rapidRawPreprocessing=true),toneMapper=tone,displayP3=p3,ultraHdr=hdr)
                        val off=on.copy(chromaCleanup=on.chromaCleanup.copy(rapidRawPreprocessing=false))
                        val separate=cleanup.withDeveloped(scene,AMOUNTS){processor.process(it,off)}
                        val fused=processor.process(scene,on)
                        fun compare(a:android.graphics.Bitmap,b:android.graphics.Bitmap){
                            assertEquals(a.width,b.width);assertEquals(a.height,b.height)
                            // Compare stored JPEG codes; getPixels converts P3 to sRGB and can
                            // amplify a one-code input rounding difference during conversion.
                            val aa=ByteBuffer.allocateDirect(a.byteCount);val bb=ByteBuffer.allocateDirect(b.byteCount)
                            a.copyPixelsToBuffer(aa);b.copyPixelsToBuffer(bb)
                            var worst=0
                            for(i in 0 until a.byteCount)
                                worst=maxOf(worst,kotlin.math.abs((aa.get(i).toInt() and 255)-(bb.get(i).toInt() and 255)))
                            report("fused/separate $tone P3=$p3 HDR=$hdr ${a.width}x${a.height} maxCode=$worst")
                            assertTrue("$tone P3=$p3 HDR=$hdr code difference $worst",worst<=1)
                        }
                        try {
                            compare(separate.bitmap,fused.bitmap)
                            if(hdr&&fused.settings.ultraHdr)compare(checkNotNull(separate.gainmap),checkNotNull(fused.gainmap))
                        }finally{
                            separate.bitmap.recycle();separate.gainmap?.recycle()
                            fused.bitmap.recycle();fused.gainmap?.recycle()
                        }
                    }
                }
            }finally{cleanup.close();processor.close()}
        }
    }

    @Test fun jpegSwitchBypassesCleanupAndCanBeReenabled() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            withTexture(field(65,49){x,y ->
                val n=if((x+y)%2==0).02f else -.02f
                floatArrayOf(.1f+n,.1f,.1f-n)
            },65,49){scene ->
                val processor=Gles31JpegOutputProcessor(context)
                val on=JpegOutputSettings(chromaCleanup=ChromaCleanup(rapidRawPreprocessing=true))
                val off=on.copy(chromaCleanup=on.chromaCleanup.copy(rapidRawPreprocessing=false))
                fun pixels(settings:JpegOutputSettings):IntArray {
                    val jpeg=processor.process(scene,settings)
                    return try{IntArray(65*49).also{jpeg.bitmap.getPixels(it,0,65,0,0,65,49)}}
                    finally{jpeg.bitmap.recycle();jpeg.gainmap?.recycle()}
                }
                try {
                    val bypass=pixels(off)
                    val cleaned=pixels(on)
                    assertFalse("Switch did not change noisy output",bypass.contentEquals(cleaned))
                    assertArrayEquals("OFF must return to the same bypass output",bypass,pixels(off))
                    assertArrayEquals("ON must be repeatable",cleaned,pixels(on))
                }finally{processor.close()}
            }
        }
    }

    private fun report(text:String) {
        android.util.Log.i("RapidRawDevelopTest",text)
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null),"rapidraw-mobile-results.txt").appendText("${System.currentTimeMillis()} $text\n")
    }

    private fun field(w:Int,h:Int,pixel:(Int,Int)->FloatArray):ByteBuffer {
        val b=ByteBuffer.allocateDirect(w*h*8).order(ByteOrder.nativeOrder())
        for(y in 0 until h)for(x in 0 until w){
            for(c in pixel(x,y))b.putShort(Half.toHalf(c))
            b.putShort(Half.toHalf(1f))
        }
        return b.apply { rewind() }
    }
    private fun withTexture(src:ByteBuffer,w:Int,h:Int,block:(AmazeGpuOutput)->Unit) {
        val ids=IntArray(1);GLES30.glGenTextures(1,ids,0)
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA16F,w,h)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,w,h,GLES30.GL_RGBA,GLES30.GL_HALF_FLOAT,src)
            assertEquals(GLES30.GL_NO_ERROR,GLES30.glGetError())
            block(AmazeGpuOutput(ids[0],w,h))
        }finally{GLES30.glDeleteTextures(1,ids,0)}
    }
    private fun read(scene:AmazeGpuOutput):FloatArray {
        val ids=IntArray(1);GLES30.glGenFramebuffers(1,ids,0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,ids[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,scene.textureId,0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE,GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val data=ByteBuffer.allocateDirect(scene.width*scene.height*8).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0,0,scene.width,scene.height,GLES30.GL_RGBA,GLES30.GL_HALF_FLOAT,data)
            assertEquals(GLES30.GL_NO_ERROR,GLES30.glGetError())
            return FloatArray(scene.width*scene.height*4){Half.toFloat(data.getShort(it*2))}
        }finally{
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
            GLES30.glDeleteFramebuffers(1,ids,0)
        }
    }
    private companion object {
        val AMOUNTS=RapidRawDevelop.Amounts(ChromaCleanup.DEFAULT_RAPIDRAW_COLOR_NR,ChromaCleanup.DEFAULT_RAPIDRAW_SHARPENING)
    }
}
