package com.rawlens.srpipeline

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Ground-truth geometry, rather than an oracle that repeats the shader formula. */
class KernelNetGreenEdgeTest {
    @Test fun `subpixel burst preserves a straight green edge at both output scales`() {
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
            val w=40; val height=40; val nx=3; val ny=3
            val covariance=FloatArray(w/2*(height/2)*4) {
                if(it%4==0 || it%4==2) .25f else 0f
            }
            val kernels=resident(covariance)
            val robustness=pack(FloatArray(w/2*(height/2)) { 1f })
            // Integrate the known diagonal edge over each raw sensor pixel.
            fun scene(x: Double,y: Double): Float {
                var sum=0.0
                for(a in 0..7) for(b in 0..7) {
                    val d=x+(a+.5)/8-.5-.47*(y+(b+.5)/8-.5)-9.1
                    sum+=.1+.8/(1+exp(-(d/.2).coerceIn(-50.0,50.0)))
                }
                return (sum/64).toFloat()
            }
            for(scale in listOf(1,2)) {
                val ow=w*scale; val n=ow*height*scale*3
                val num=SrpBridge.createResident(h,n.toLong())
                val den=SrpBridge.createResident(h,n.toLong())
                try {
                    for(frame in 0..11) {
                        val fx=if(frame==0) 0f else (.08*sin(frame*.7)).toFloat()
                        val fy=if(frame==0) 0f else (.31*frame).toFloat()
                        val raw=FloatArray(w*height) { i -> scene(i%w+.5-fx,i/w+.5-fy) }
                        val input=resident(raw)
                        try {
                            val flow=FloatArray(nx*ny*2) { if(it%2==0) fx else fy }
                            assertEquals(0,SrpBridge.nativeMergeSplatFrameRes(h,input,pack(flow),kernels,
                                robustness.apply { rewind() },num,den,File(spv).readBytes(),w,height,
                                ow,height*scale,covariance.size,w/2*(height/2),16,scale.toFloat(),
                                if(frame==0) 14 else 10,nx,ny,1f))
                        } finally { SrpBridge.freeResident(h,input) }
                    }
                    val nb=SrpBridge.directFloats(n); val db=SrpBridge.directFloats(n)
                    assertEquals(0,SrpBridge.downloadResident(h,num,nb,n.toLong()))
                    assertEquals(0,SrpBridge.downloadResident(h,den,db,n.toLong()))
                    val nf=nb.asFloatBuffer(); val df=db.asFloatBuffer()
                    fun green(x: Int,y: Int): Double {
                        val i=(y*ow+x)*3+1
                        return nf.get(i).toDouble()/df.get(i)
                    }
                    var squaredError=0.0; var rows=0
                    for(y in 8*scale until 28*scale) {
                        fun crossing(level: Double): Double {
                            val x=(8*scale until 28*scale-1).first {
                                green(it,y)<=level && green(it+1,y)>level
                            }
                            return (x+.5+(level-green(x,y))/(green(x+1,y)-green(x,y)))/scale
                        }
                        val error=crossing(.5)-(.47*(y+.5)/scale+9.1)
                        squaredError+=error*error; rows++
                        // A broad blur could also straighten an edge; reject that escape.
                        assertTrue("edge remains resolved at $scale x",crossing(.82)-crossing(.18)<2.0)
                    }
                    assertTrue("CFA phase must not corrugate a straight edge at $scale x: ${sqrt(squaredError/rows)}",
                        sqrt(squaredError/rows)<if(scale==1) .05 else .04)
                } finally {
                    SrpBridge.freeResident(h,num); SrpBridge.freeResident(h,den)
                }
            }
            SrpBridge.freeResident(h,kernels)
        } finally { SrpBridge.nativeDestroy(h) }
    }
}
