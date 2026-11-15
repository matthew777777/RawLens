package com.rawlens.srpipeline

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.*

class KernelNetTextureGainTest {
    @Test fun `fine diagonal texture is interpolated without inverse-filter amplification`() {
        val lib=System.getProperty("srpipeline.lib","")
        val spv=System.getProperty("srpipeline.spv_merge_splat","")
        assumeTrue(lib.isNotEmpty() && spv.isNotEmpty())
        SrpBridge.loadPath(lib)
        val h=SrpBridge.nativeCreate(); assertTrue(h!=0L)
        try {
            fun pack(a: FloatArray)=SrpBridge.directFloats(a.size).apply { asFloatBuffer().put(a) }
            fun resident(a: FloatArray)=SrpBridge.createResident(h,a.size.toLong()).also {
                assertEquals(0,SrpBridge.uploadResident(h,it,pack(a),a.size.toLong()))
            }
            val w=48; val height=48
            // A pair of diagonal waves, inside the green lattice's Nyquist
            // limit. The old 2x separable detail boost multiplies their gain.
            val raw=FloatArray(w*height) { i ->
                val x=i%w; val y=i/w
                (.5+.1*cos(.65*Math.PI*(x+y-1)/2)*cos(.65*Math.PI*(x-y-1)/2)).toFloat()
            }
            val input=resident(raw)
            val cov=FloatArray(w/2*(height/2)*4) { if(it%4==0 || it%4==2) .5f else 0f }
            val kernels=resident(cov)
            try {
                for(scale in listOf(1,2)) {
                    val ow=w*scale; val n=ow*height*scale*3
                    val num=resident(FloatArray(n)); val den=resident(FloatArray(n))
                    try {
                        assertEquals(0,SrpBridge.nativeMergeSplatFrameRes(h,input,
                            pack(FloatArray(3*3*2)),kernels,pack(FloatArray(w/2*(height/2)) { 1f }),
                            num,den,File(spv).readBytes(),w,height,ow,height*scale,cov.size,
                            w/2*(height/2),16,scale.toFloat(),10,3,3,1f))
                        val nb=SrpBridge.directFloats(n); val db=SrpBridge.directFloats(n)
                        assertEquals(0,SrpBridge.downloadResident(h,num,nb,n.toLong()))
                        assertEquals(0,SrpBridge.downloadResident(h,den,db,n.toLong()))
                        val nf=nb.asFloatBuffer(); val df=db.asFloatBuffer()
                        var energy=0.0; var count=0
                        for(y in 8*scale until 40*scale) for(x in 8*scale until 40*scale) {
                            val i=(y*ow+x)*3+1
                            val v=nf.get(i)/df.get(i)-.5
                            energy+=v*v; count++
                        }
                        val rms=sqrt(energy/count)
                        assertTrue("no artificial diagonal texture boost at $scale x: $rms",rms<.055)
                        assertTrue("texture is not erased at $scale x: $rms",rms>.025)
                    } finally { SrpBridge.freeResident(h,num); SrpBridge.freeResident(h,den) }
                }
            } finally { SrpBridge.freeResident(h,input); SrpBridge.freeResident(h,kernels) }
        } finally { SrpBridge.nativeDestroy(h) }
    }
}
