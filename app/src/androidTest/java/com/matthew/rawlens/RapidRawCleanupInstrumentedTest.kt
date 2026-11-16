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
import kotlin.random.Random

/** Exercises the actual EGL readback -> JNI -> texture upload on the device.
 * The separate host oracle proves numerical parity with upstream Rust.
 */
@RunWith(AndroidJUnit4::class)
class RapidRawCleanupInstrumentedTest {
    @Test fun slicedWorkgroupsMatchMonolithicAtOddEdges() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val mono=RapidRawCleanup(context,1_000_000)
            val sliced=RapidRawCleanup(context,128)
            try {
                withTexture(field(65,49){x,y ->
                    floatArrayOf(.03f+.4f*x/65,.05f+.2f*y/49,.04f+.1f*(x+y)/114)
                },65,49){scene ->
                    val expected=mono.withCleaned(scene){read(it)}
                    val actual=sliced.withCleaned(scene){read(it)}
                    assertArrayEquals("Dispatch offsets must preserve every workgroup halo",expected,actual,0f)
                }
            }finally{mono.close();sliced.close()}
        }
    }

    @Test fun fusedJpegMatchesSeparatePassesIncludingOddGainmapEdges() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val cleanup=RapidRawCleanup(context)
            val processor=Gles31JpegOutputProcessor(context)
            try {
                withTexture(field(65,49){x,y ->
                    val l=.02f+2f*x/65;val n=if((x+y)%2==0).013f else -.013f
                    floatArrayOf(l+n,l*.8f,l*.7f-n)
                },65,49){scene ->
                    for(tone in PhotoToneMapper.entries)for(p3 in listOf(false,true))for(hdr in listOf(false,true)){
                        val on=JpegOutputSettings(chromaCleanup=ChromaCleanup(rapidRawPreprocessing=true),toneMapper=tone,displayP3=p3,ultraHdr=hdr)
                        val off=on.copy(chromaCleanup=on.chromaCleanup.copy(rapidRawPreprocessing=false))
                        val separate=cleanup.withCleaned(scene){processor.process(it,off)}
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

    @Test fun fullResolutionCompletedGpuAndCpuTimings() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val cleanup=RapidRawCleanup(context)
            try {
                withTexture(field(32,32){_,_->floatArrayOf(.1f,.1f,.1f)},32,32){scene ->
                    cleanup.withCleaned(scene){GLES30.glFinish()}
                }
                val w=4080;val h=3060
                withTexture(field(w,h){x,y ->
                    val l=.02f+.5f*x/w;val n=if((x+y)%2==0).007f else -.007f
                    floatArrayOf(l+n,l,l-n)
                },w,h){scene ->
                    fun timed(block:()->Unit):Long{
                        GLES30.glFinish();val start=android.os.SystemClock.elapsedRealtime()
                        block();GLES30.glFinish();return android.os.SystemClock.elapsedRealtime()-start
                    }
                    val cpu=timed{RapidRawCleanupReference.withCleaned(scene){GLES30.glFinish()}}
                    val gpu=List(3){timed{cleanup.withCleaned(scene){GLES30.glFinish()}}}
                    report("4080x3060 completed CPU=${cpu}ms GPU=${gpu}ms speedup=${cpu.toDouble()/gpu.sorted()[1]}")
                    val processor=Gles31JpegOutputProcessor(context)
                    val on=JpegOutputSettings(chromaCleanup=ChromaCleanup(rapidRawPreprocessing=true),ultraHdr=true)
                    val off=on.copy(chromaCleanup=on.chromaCleanup.copy(rapidRawPreprocessing=false))
                    fun dispose(jpeg:DevelopedJpeg){jpeg.bitmap.recycle();jpeg.gainmap?.recycle()}
                    try {
                        // Compile both output variants before timing, then compare completed JPEG bitmap work.
                        dispose(cleanup.withCleaned(scene){processor.process(it,off)})
                        dispose(processor.process(scene,on))
                        val separate=List(3){timed{dispose(cleanup.withCleaned(scene){processor.process(it,off)})}}
                        val fused=List(3){timed{dispose(processor.process(scene,on))}}
                        report("4080x3060 UltraHDR bitmap separate=${separate}ms fused=${fused}ms")
                    }finally{processor.close()}
                }
            }finally{cleanup.close()}
        }
    }

    @Test fun gpuMatchesCpuReferenceOnNoisyEdges() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val rng=Random(274)
            val src=field(129,97){x,y ->
                val l=if(x<64).025f else .7f
                floatArrayOf(l+(rng.nextFloat()-.5f)*.025f,l+(rng.nextFloat()-.5f)*.01f,l+(rng.nextFloat()-.5f)*.025f)
            }
            withTexture(src,129,97){scene ->
                val expected=RapidRawCleanupReference.withCleaned(scene){read(it)}
                val cleanup=RapidRawCleanup(context)
                try {
                    cleanup.withCleaned(scene){actual ->
                        val pixels=read(actual)
                        var worst=0f;var sum=0.0
                        for(i in pixels.indices){val d=kotlin.math.abs(pixels[i]-expected[i]);worst=maxOf(worst,d);sum+=d}
                        report("GPU/reference max=$worst mean=${sum/pixels.size}")
                        assertTrue("GPU/reference difference $worst",worst<.004f)
                        assertTrue("GPU/reference mean ${sum/pixels.size}",sum/pixels.size<.0004)
                    }
                }finally{cleanup.close()}
            }
        }
    }

    @Test fun fullResolutionJpegOutputCompletesWithAndWithoutGainmap() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val w=4080;val h=3060
            val src=field(w,h){x,y ->
                val level=.03f+.5f*x/w
                val noise=if((x+y)%2==0).01f else -.01f
                floatArrayOf(level+noise,level,level-noise)
            }
            withTexture(src,w,h){scene ->
                val processor=Gles31JpegOutputProcessor(context)
                try {
                    for(hdr in listOf(false,true)) {
                        val jpeg=processor.process(scene,JpegOutputSettings(ultraHdr=hdr))
                        try {
                            assertEquals(w,jpeg.bitmap.width);assertEquals(h,jpeg.bitmap.height)
                            if(jpeg.settings.ultraHdr)assertNotNull(jpeg.gainmap)
                            val center=jpeg.bitmap.getPixel(w/2,h/2)
                            assertTrue(android.graphics.Color.green(center)>0)
                        }finally{jpeg.bitmap.recycle();jpeg.gainmap?.recycle()}
                    }
                }finally{processor.close()}
            }
        }
    }

    @Test fun neutralAndBlackSurviveAndSourceIsNotModified() {
        Gles31AmazeProcessor.EglComputeContext().use {
            for (level in listOf(0f, 0.18f, 2f)) {
                val src = field(17, 13) { _, _ -> floatArrayOf(level, level, level) }
                withTexture(src, 17, 13) { scene ->
                    val original = read(scene)
                    withCleaned(scene) { cleaned ->
                        assertNotEquals(scene.textureId, cleaned.textureId)
                        val actual = read(cleaned)
                        // Matrix roundtrip, fp16 storage, and sqrt/square reconstruction.
                        for (i in actual.indices) assertEquals("level=$level i=$i", original[i], actual[i], 0.002f)
                    }
                    assertArrayEquals(original, read(scene), 0f)
                }
            }
        }
    }

    @Test fun chromaSpecklesFallWithoutLosingTheBrightnessEdge() {
        Gles31AmazeProcessor.EglComputeContext().use {
            val rng = Random(8173)
            val w=129; val h=97
            val src = field(w,h) { x, _ ->
                val base = if (x < w/2) 0.035f else 0.35f
                floatArrayOf(base + (rng.nextFloat()-.5f)*.025f, base,
                    base + (rng.nextFloat()-.5f)*.025f)
            }
            withTexture(src,w,h) { scene ->
                val before = read(scene)
                withCleaned(scene) { cleaned ->
                    val after = read(cleaned)
                    fun chroma(a: FloatArray): Double = (0 until w*h).sumOf { i ->
                        val r=a[i*4]-a[i*4+1];val b=a[i*4+2]-a[i*4+1]
                        (r*r+b*b).toDouble()
                    }
                    assertTrue("Chroma residual was not reduced", chroma(after) < chroma(before)*.5)
                    fun mean(a:FloatArray,from:Int,to:Int):Double {
                        var sum=0.0;var n=0
                        for(y in 0 until h)for(x in from until to){sum+=a[(y*w+x)*4+1];n++}
                        return sum/n
                    }
                    assertEquals(mean(before,4,w/2-4),mean(after,4,w/2-4),.004)
                    assertEquals(mean(before,w/2+4,w-4),mean(after,w/2+4,w-4),.008)
                    assertArrayEquals(before,read(scene),0f)
                    assertTrue(after.all(Float::isFinite))
                }
            }
        }
    }

    private fun <T> withCleaned(scene:AmazeGpuOutput,consume:(AmazeGpuOutput)->T):T {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val cleanup=RapidRawCleanup(context)
        return try{cleanup.withCleaned(scene,consume)}finally{cleanup.close()}
    }

    private fun report(text:String) {
        android.util.Log.i("RapidRawCleanupTest",text)
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
}
