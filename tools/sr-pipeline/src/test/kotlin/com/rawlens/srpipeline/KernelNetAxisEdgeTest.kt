package com.rawlens.srpipeline

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Near-axis edges expose diagonal CFA interpolation bias missed by a 25-degree edge. */
class KernelNetAxisEdgeTest {
    @Test fun `horizontal and vertical edges do not acquire diagonal Bayer phase teeth`() {
        val lib=System.getProperty("srpipeline.lib","")
        val spv=System.getProperty("srpipeline.spv_merge_splat","")
        assumeTrue(lib.isNotEmpty() && spv.isNotEmpty())
        SrpBridge.loadPath(lib)
        val h=SrpBridge.nativeCreate();assertTrue(h!=0L)
        try {
            fun pack(a: FloatArray)=SrpBridge.directFloats(a.size).apply { asFloatBuffer().put(a) }
            fun resident(a: FloatArray)=SrpBridge.createResident(h,a.size.toLong()).also {
                assertEquals(0,SrpBridge.uploadResident(h,it,pack(a),a.size.toLong()))
            }
            val w=40;val height=40
            val covariance=FloatArray(w/2*(height/2)*4) { if(it%4==0 || it%4==2) .5f else 0f }
            val kernels=resident(covariance)
            try {
                for(horizontal in listOf(false,true)) for(scale in listOf(1,2)) {
                    fun distance(x: Double,y: Double)=if(horizontal) y-18-.12*(x-18) else x-18-.12*(y-18)
                    fun scene(x: Double,y: Double): Float {
                        var sum=0.0
                        for(a in 0..7) for(b in 0..7) {
                            val d=distance(x+(a+.5)/8-.5,y+(b+.5)/8-.5)
                            sum+=.1+.8/(1+exp(-(d/.25).coerceIn(-50.0,50.0)))
                        }
                        return (sum/64).toFloat()
                    }
                    val ow=w*scale;val n=w*height*scale*scale*3
                    val num=resident(FloatArray(n));val den=resident(FloatArray(n))
                    try {
                        for(frame in 0..5) {
                            val fx=.19f*frame;val fy=.27f*frame
                            val raw=FloatArray(w*height) { scene(it%w+.5-fx,it/w+.5-fy) }
                            val input=resident(raw)
                            try {
                                val flow=FloatArray(3*3*2) { if(it%2==0) fx else fy }
                                assertEquals(0,SrpBridge.nativeMergeSplatFrameRes(h,input,pack(flow),kernels,
                                    pack(FloatArray(w/2*(height/2)) { 1f }),num,den,File(spv).readBytes(),
                                    w,height,ow,height*scale,covariance.size,w/2*(height/2),16,
                                    scale.toFloat(),10,3,3,1f))
                            } finally { SrpBridge.freeResident(h,input) }
                        }
                        val nb=SrpBridge.directFloats(n);val db=SrpBridge.directFloats(n)
                        assertEquals(0,SrpBridge.downloadResident(h,num,nb,n.toLong()))
                        assertEquals(0,SrpBridge.downloadResident(h,den,db,n.toLong()))
                        val nf=nb.asFloatBuffer();val df=db.asFloatBuffer()
                        var energy=0.0;var count=0
                        for(y in 8*scale until 28*scale) for(x in 8*scale until 28*scale) {
                            val xx=(x+.5)/scale;val yy=(y+.5)/scale
                            if(abs(distance(xx,yy))>=1.2) continue
                            val i=(y*ow+x)*3+1
                            val error=nf.get(i)/df.get(i)-scene(xx,yy)
                            energy+=error*error;count++
                        }
                        val rms=sqrt(energy/count)
                        println("axis-edge horizontal=$horizontal scale=$scale RMS=$rms")
                        assertTrue("edge fidelity horizontal=$horizontal scale=$scale: $rms",rms<.03)
                    } finally { SrpBridge.freeResident(h,num);SrpBridge.freeResident(h,den) }
                }
            } finally { SrpBridge.freeResident(h,kernels) }
        } finally { SrpBridge.nativeDestroy(h) }
    }
}
