// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor
import org.junit.Assert.*
import org.junit.Test

class RawSrPackedKernelInputTest {
    private fun direct(n: Int) = ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    @Test fun `packed luma is identical to unpacked oracle for padded crops and every phase`() {
        for (pattern in BayerPattern.entries) for (oy in 0..1) for (ox in 0..1) {
            val bytes=ByteBuffer.allocateDirect(40*12+6).order(ByteOrder.nativeOrder())
            bytes.position(6)
            for (y in 0 until 12) for (x in 0 until 16) {
                bytes.putShort(6+y*40+x*2,((x*913+y*719)%65536).toShort())
            }
            val frame=RawSrPackedFrame(bytes,RawPlaneLayout(16,12,40,2,3,5),RawCrop(ox,oy,12,10),
                RawNormalization(pattern,listOf(64f,68f,72f,76f),1023f),null)
            val expected=RawSrKernelNetAniso.lumaPlane(RawSrMergeJob.unpack(frame))
            val scratch=direct(30)
            val actual=RawSrPackedKernelInput.luma(frame,scratch)
            assertSame(scratch,actual)
            assertEquals(6,bytes.position())
            for (i in 0 until 30) assertEquals(expected[i],actual[i],0f)
        }
    }

    @Test fun `bulk model conversion matches clamped per-triple math and reuses storage`() {
        val pw=3; val ph=2; val w=6; val h=4
        val floats=FloatArray(pw*ph*3) { i -> if(i<12) .1f+(i%6)*.28f else -.7f+(i%6)*.23f }
        floats[1]=Float.NaN
        val buf=direct(floats.size).apply { put(floats);rewind() }
        val constructor=KernelNetNcnnProcessor.Result::class.java.getDeclaredConstructor(
            FloatBuffer::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        constructor.isAccessible=true
        val result=constructor.newInstance(buf,pw,ph)
        val scratch=FloatArray(w*h*4) { -999f }
        val planes=FloatArray(floats.size)
        repeat(2) {
            val field=RawSrKernelNetAniso.precisionFromModel(result,w,h,scratch,planes)!!
            assertSame(scratch,field.values)
            for (y in 0 until h) for(x in 0 until w) {
                val triple=FloatArray(3) { c -> RawSrKernelNetAniso.samplePlane(buf,c,pw,ph,x,y,w,h) }
                val expected=FloatArray(4)
                if(!RawSrKernelNetAniso.precisionOf(triple[0],triple[1],triple[2],expected,0)) {
                    expected[0]=.5f;expected[3]=.5f
                }
                // The produced field floors every kernel's narrow axis at
                // 0.5 quads (zipper regression): mirror that clamp on the
                // single-texel expectation. Per-texel independence keeps the
                // comparison bitwise-exact.
                val clamped=MosaicSrReconstructor.clampMinorAxis(
                    RawSrKernelCovariance.MatrixField(1,1,expected)).values
                for(c in 0..3) assertEquals(clamped[c],field.values[(y*w+x)*4+c],0f)
            }
            assertEquals(0,buf.position())
            // A new inference overwrites the same scratch; no stale precision survives.
            for (i in 0 until floats.size) buf.put(i,if(i<12) .4f else .1f)
        }
    }
}
