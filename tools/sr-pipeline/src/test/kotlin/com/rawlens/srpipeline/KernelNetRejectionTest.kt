package com.rawlens.srpipeline

import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class KernelNetRejectionTest {
    @Test fun `rejection evaluates the continuous field at guide centres with LUT correction off`() {
        val lib=System.getProperty("srpipeline.lib", "")
        val spv=System.getProperty("srpipeline.spv_robust", "")
        assumeTrue(lib.isNotEmpty() && spv.isNotEmpty())
        SrpBridge.loadPath(lib)
        val handle=SrpBridge.nativeCreate();assertTrue(handle!=0L)
        try {
            val w=96;val h=64;val gw=w/2;val gh=h/2;val n=gw*gh;val nx=w/16;val ny=h/16
            fun pack(a: FloatArray)=SrpBridge.directFloats(a.size).apply { asFloatBuffer().put(a) }
            fun resident(a: FloatArray)=SrpBridge.createResident(handle,a.size.toLong()).also {
                assertEquals(0,SrpBridge.uploadResident(handle,it,pack(a),a.size.toLong()))
            }
            // Analytic expectation for a linear tile field with constant border extension.
            fun meanIndex(g: Double,last: Double): Double {
                fun bump(x: Double)=maxOf(0.0,1.0-abs(x)).let { it*it*it/6 }
                return g.coerceIn(0.0,last)+bump(g)-bump(g-last)
            }
            fun field(x: Int,y: Int)=meanIndex((x+.5)/8-.5,(nx-1).toDouble()) to
                meanIndex((y+.5)/8-.5,(ny-1).toDouble())
            val raw=FloatArray(w*h) { i ->
                val v=.1f+.01f*(i%w/2)+.007f*(i/w/2);v*v
            }
            val means=FloatArray(n*3) { i ->
                val x=i%n%gw;val y=i%n/gw;val (tx,ty)=field(x,y)
                // Compared guide is a linear ramp. A uniform .01 reference
                // residual gives exactly exp(-(.01^2)/.0004), independently
                // of position, flow and channel. This also catches an accidental
                // LUT enable when only the continuous-field bit is set.
                (.1+.01*(x+(.1+.2*tx)*.5)+.007*(y+(-.1+.15*ty)*.5)+.01).toFloat()
            }
            val flow=FloatArray(nx*ny*2) { i ->
                if(i%2==0) .1f+.2f*(i/2%nx) else -.1f+.15f*(i/2/nx)
            }
            val strengths=FloatArray(nx*ny) { .15f+.1f*(it%nx)+.05f*(it/nx) }
            // Independent cubic basis for membership of the first tile.
            fun basis(x: Double): Double {
                val d=abs(x)
                return when { d<1 -> 2.0/3-d*d+d*d*d/2; d<2 -> (2-d).let { it*it*it/6 }; else -> 0.0 }
            }
            fun firstMembership(x: Int,y: Int): Double {
                fun axis(g: Double)=(-2..0).sumOf { basis(g-it) }
                return axis((x+.5)/8-.5)*axis((y+.5)/8-.5)
            }
            for (s3 in listOf(false,true)) {
                val output=SrpBridge.directFloats(n)
                assertEquals(0,SrpBridge.nativeRobustComputeFrameRes(handle,resident(raw),resident(means),
                    resident(FloatArray(n*3) { .0004f }),pack(flow),pack(floatArrayOf(.1f,.1f,.1f,.1f)),
                    resident(strengths),output,File(spv).readBytes(),w,h,16,2,2,
                    0f.toRawBits(),nx,ny,if(s3) resident(FloatArray(n) { 1f }) else 0L,.15f.toRawBits()))
                val r=output.asFloatBuffer()
                for(y in 6 until gh-6) for(x in 6 until gw-6) {
                    var expected=1.0
                    for(dy in -2..2) for(dx in -2..2) {
                        val (tx,ty)=field(x+dx,y+dy)
                        val value=(.15+.1*tx+.05*ty)*exp(-.25) *
                            if(s3) 1.0-firstMembership(x+dx,y+dy) else 1.0
                        expected=minOf(expected,value)
                    }
                    assertEquals("rejection S3=$s3 ($x,$y)",expected,r.get(y*gw+x).toDouble(),1e-4)
                }
            }
            // Full motion membership must retain full S.3 rejection.
            val rejected=SrpBridge.directFloats(n)
            assertEquals(0,SrpBridge.nativeRobustComputeFrameRes(handle,resident(raw),resident(means),
                resident(FloatArray(n*3) { .0004f }),pack(flow),pack(floatArrayOf(.1f,.1f,.1f,.1f)),
                resident(FloatArray(nx*ny) { .15f }),rejected,File(spv).readBytes(),w,h,16,2,2,
                0f.toRawBits(),nx,ny,resident(FloatArray(n) { 1f }),.15f.toRawBits()))
            val rejectValues=rejected.asFloatBuffer()
            for(i in 0 until n) assertEquals("full S3 rejection",0f,rejectValues.get(i),1e-6f)
        } finally { SrpBridge.nativeDestroy(handle) }
    }
}
