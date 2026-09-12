// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log

@RunWith(AndroidJUnit4::class)
class RawTherapeeAmazeInstrumentedTest {
    @Test
    fun orderedGlesAgainstPinnedHostUpstream() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val data=ByteBuffer.wrap(instrumentation.context.assets.open("amaze_reference.bin").use {
            it.readBytes()
        }).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x414d415a,data.int)
        val count=data.int
        var totalMismatches=0L
        Gles31AmazeProcessor.EglComputeContext().use {
            Log.i("AmazeParity", "GPU ${GLES31.glGetString(GLES31.GL_RENDERER)}")
            Gles31AmazeProcessor.ProgramCache(instrumentation.targetContext).use { programs ->
                val program=programs.get("amaze/ordered_reference.glsl")
                val buffers=IntArray(4)
                GLES31.glGenBuffers(4,buffers,0)
                fun buffer(binding:Int,size:Int,values:ByteBuffer?=null) {
                    GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,buffers[binding])
                    GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,size,values,GLES31.GL_DYNAMIC_COPY)
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,binding,buffers[binding])
                }
                try {
                    // Zero-filled to match the oracle's calloc semantics: cells
                    // the algorithm never writes (tile fringes) must read 0,
                    // and the first fixture must not see uninitialized memory.
                    buffer(2,486400*4*4,ByteBuffer.allocate(486400*4*4))
                    buffer(3,25600*4*4,ByteBuffer.allocate(25600*4*4))
                    repeat(count) {
                        val w=data.int; val h=data.int; val phase=data.int; val scene=data.int; val gain=data.float
                        val raw=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder())
                        repeat(w*h) { raw.putFloat(data.float) }; raw.flip()
                        val expected=FloatArray(w*h*3) { data.float }
                        buffer(0,w*h*4,raw); buffer(1,w*h*12)
                        val phases=arrayOf(intArrayOf(0,1,1,2),intArrayOf(1,0,2,1),
                            intArrayOf(1,2,0,1),intArrayOf(2,1,1,0))
                        GLES31.glUniform2i(GLES31.glGetUniformLocation(program,"u_size"),w,h)
                        GLES31.glUniform4iv(GLES31.glGetUniformLocation(program,"u_fc"),1,phases[phase],0)
                        GLES31.glUniform1f(GLES31.glGetUniformLocation(program,"u_initial_gain"),gain)
                        val tiles=((w+127)/128)*((h+127)/128)
                        val started=SystemClock.elapsedRealtimeNanos()
                        // Serial, one workgroup per dispatch, shared region 0:
                        // edge tiles read the previous tile's leftovers past
                        // their fresh range (serial staleness), which batched
                        // per-region dispatch cannot replicate bit-exactly.
                        for(tile in 0 until tiles) {
                            GLES31.glUniform1i(GLES31.glGetUniformLocation(program,"u_tile_start"),tile)
                            GLES31.glDispatchCompute(1,1,1)
                            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                        }
                        GLES31.glFinish()
                        val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1e6
                        assertEquals("dispatch",GLES31.GL_NO_ERROR,GLES31.glGetError())
                        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,buffers[1])
                        val mapped=(GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,0,w*h*12,
                            GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        var mismatches=0; var maxError=0f
                        for(i in expected.indices) {
                            val actual=mapped.get()
                            if(actual.toRawBits()!=expected[i].toRawBits()) mismatches++
                            if(!actual.isFinite()) maxError=Float.POSITIVE_INFINITY
                            else maxError=maxOf(maxError,kotlin.math.abs(actual-expected[i]))
                        }
                        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                        totalMismatches+=mismatches
                        Log.i("AmazeParity","phase=$phase scene=$scene ${w}x$h mismatches=$mismatches maxError=$maxError ms=$elapsed")
                    }
                } finally { GLES31.glDeleteBuffers(4,buffers,0) }
            }
        }
        assertEquals("GLES differing float samples",0L,totalMismatches)
    }

    @Test
    fun nativeDemosaicIsBitIdenticalToPinnedHostUpstream() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val bytes=context.assets.open("amaze_reference.bin").use { it.readBytes() }
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x414d415a,data.int)
        val count=data.int
        val patterns=arrayOf(BayerPattern.RGGB,BayerPattern.GRBG,BayerPattern.GBRG,BayerPattern.BGGR)
        repeat(count) {
            val w=data.int; val h=data.int; val phase=data.int; val scene=data.int; val gain=data.float
            val raw=FloatArray(w*h) { data.float }
            val expected=FloatArray(w*h*3) { data.float }
            val actual=RawTherapeeAmaze.demosaic(raw,w,h,patterns[phase],gain)
            assertEquals(expected.size,actual.size)
            var mismatches=0
            var maxError=0f
            for(i in actual.indices) {
                assertTrue("nonfinite phase=$phase scene=$scene index=$i",actual[i].isFinite())
                if(expected[i].toRawBits()!=actual[i].toRawBits()) mismatches++
                maxError=maxOf(maxError,kotlin.math.abs(expected[i]-actual[i]))
            }
            assertEquals("phase=$phase scene=$scene ${w}x$h maxError=$maxError",0,mismatches)
        }
        assertEquals(24,count)
        assertEquals(0,data.remaining())
    }
}
