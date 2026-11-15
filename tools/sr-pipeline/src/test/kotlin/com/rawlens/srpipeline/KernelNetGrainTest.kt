package com.rawlens.srpipeline

import org.junit.Assert.*
import com.rawlens.srpipeline.stages.LumaFrameWeights
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.*

/** Preserve measured sensor noise, without adding generated grain to clean data. */
class KernelNetGrainTest {
    @Test fun `flat sensor grain survives at both scales without mean or colour changes`() {
        val lib=System.getProperty("srpipeline.lib", "")
        val spv=System.getProperty("srpipeline.spv_merge_splat", "")
        assumeTrue(lib.isNotEmpty() && spv.isNotEmpty())
        SrpBridge.loadPath(lib)
        val h=SrpBridge.nativeCreate()
        assertTrue("Vulkan device required",h!=0L)
        try {
            fun pack(a: FloatArray)=SrpBridge.directFloats(a.size).apply { asFloatBuffer().put(a) }
            fun resident(a: FloatArray)=SrpBridge.createResident(h,a.size.toLong()).also {
                assertEquals(0,SrpBridge.uploadResident(h,it,pack(a),a.size.toLong()))
            }
            val w=64; val height=64; val nx=4; val ny=4
            val covariance=FloatArray(w/2*(height/2)*4) {
                if(it%4==0 || it%4==2) .5f else 0f
            }
            val kernels=resident(covariance)
            val robustness=pack(FloatArray(w/2*(height/2)) { 1f })
            fun reconstruct(scale: Int,retention: Float,noisy: Boolean): FloatArray {
                val n=w*height*scale*scale*3
                val num=resident(FloatArray(n)); val den=resident(FloatArray(n))
                val random=Random(4921)
                val weights=LumaFrameWeights.forBurst(16,retention)
                try {
                    for(frame in 0..15) {
                        val fx=if(frame==0) 0f else (.3*sin(frame*.7)).toFloat()
                        val fy=if(frame==0) 0f else .37f*frame
                        val raw=FloatArray(w*height) { i ->
                            val x=i%w+.5-fx; val y=i/w+.5-fy
                            val scene=.2 + if(x<32) 0.0 else .03*sin(.4*x)*cos(.3*y)
                            (scene+if(noisy) .01*random.nextGaussian() else 0.0).toFloat()
                        }
                        val input=resident(raw)
                        try {
                            val flow=FloatArray(nx*ny*2) {
                                if(it%2==0) fx else fy
                            }
                            assertEquals(0,SrpBridge.nativeMergeSplatFrameRes(h,input,pack(flow),kernels,
                                robustness.apply { rewind() },num,den,File(spv).readBytes(),w,height,
                                w*scale,height*scale,covariance.size,w/2*(height/2),16,scale.toFloat(),
                                if(frame==0) 14 else 10,nx,ny,1f,weights[frame]))
                        } finally { SrpBridge.freeResident(h,input) }
                    }
                    val nb=SrpBridge.directFloats(n); val db=SrpBridge.directFloats(n)
                    assertEquals(0,SrpBridge.downloadResident(h,num,nb,n.toLong()))
                    assertEquals(0,SrpBridge.downloadResident(h,den,db,n.toLong()))
                    val nf=nb.asFloatBuffer(); val df=db.asFloatBuffer()
                    return FloatArray(n) { nf.get(it)/df.get(it) }
                } finally { SrpBridge.freeResident(h,num); SrpBridge.freeResident(h,den) }
            }
            for(scale in listOf(1,2)) {
                val baseline=reconstruct(scale,0f,true)
                val retained=reconstruct(scale,1f,true)
                val cleanBaseline=reconstruct(scale,0f,false)
                val cleanRetained=reconstruct(scale,1f,false)
                val width=w*scale
                fun green(a: FloatArray,x: Int,y: Int)=a[(y*width+x)*3+1].toDouble()
                for((region,range) in listOf("flat" to 10..22,"textured" to 40..50)) {
                    val samples=ArrayList<Pair<Double,Double>>()
                    var mean=0.0
                    for(y in 10*scale until 50*scale) for(x in range.first*scale until range.last*scale) {
                        val i=(y*width+x)*3
                        assertEquals("red differences unchanged",baseline[i],retained[i],0f)
                        assertEquals("blue differences unchanged",baseline[i+2],retained[i+2],0f)
                        if(region=="flat") assertEquals("no invented grain",.2,green(cleanRetained,x,y),1e-6)
                        fun noise(a: FloatArray,clean: FloatArray,xx: Int,yy: Int)=green(a,xx,yy)-green(clean,xx,yy)
                        fun hp(a: FloatArray,clean: FloatArray)=noise(a,clean,x,y)-(noise(a,clean,x-1,y)+
                            noise(a,clean,x+1,y)+noise(a,clean,x,y-1)+noise(a,clean,x,y+1))/4
                        samples+=hp(baseline,cleanBaseline) to hp(retained,cleanRetained)
                        mean+=noise(retained,cleanRetained,x,y)
                    }
                    fun rms(second: Boolean)=sqrt(samples.map { val v=if(second) it.second else it.first; v*v }.average())
                    val ratio=rms(true)/rms(false)
                    println("green grain $scale x $region: high-pass RMS ratio=$ratio")
                    assertTrue("source grain should survive in $region at $scale x: $ratio",ratio>1.3)
                    assertEquals("retained noise stays unbiased",0.0,mean/samples.size,.001)
                }
            }
            SrpBridge.freeResident(h,kernels)
        } finally { SrpBridge.nativeDestroy(h) }
    }
}
