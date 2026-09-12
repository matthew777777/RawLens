// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.GLES31
import android.util.Log

@RunWith(AndroidJUnit4::class)
class DivPrecisionProbeTest {
    @Test
    fun probeDivisionRounding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val src = """
            #version 310 es
            precision highp float;
            precision highp int;
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) readonly buffer InA { float a[]; };
            layout(std430, binding = 1) readonly buffer InB { float b[]; };
            layout(std430, binding = 2) buffer OutQ { float q[]; };
            void main() {
                uint i = gl_GlobalInvocationID.x;
                q[i] = a[i] / b[i];
            }
        """.trimIndent()
        val egl = Gles31AmazeProcessor.EglComputeContext()
        egl.use {
            egl.makeCurrent()
            val sh = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(sh, src)
            GLES31.glCompileShader(sh)
            val st = IntArray(1)
            GLES31.glGetShaderiv(sh, GLES31.GL_COMPILE_STATUS, st, 0)
            assert(st[0] != 0)
            val pr = GLES31.glCreateProgram()
            GLES31.glAttachShader(pr, sh)
            GLES31.glLinkProgram(pr)
            GLES31.glUseProgram(pr)
            val n = 4096
            val rnd = java.util.Random(42)
            val fa = FloatArray(n) { 0.01f + rnd.nextFloat() * 2f }
            val fb = FloatArray(n) { 0.001f + rnd.nextFloat() * 3f }
            // Include exact AMaZE-style values: c*(w+w)/(w*(eps+c)+w*(eps+c)) pieces
            fa[0] = 0.18f; fb[0] = 1e-5f + 0.18f
            fa[1] = 1.4f; fb[1] = 0.03f
            fun buf(data: FloatArray?): Int {
                val id = IntArray(1)
                GLES31.glGenBuffers(1, id, 0)
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id[0])
                val bb = if (data != null) {
                    ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).also {
                        it.asFloatBuffer().put(data); it.position(0)
                    }
                } else null
                GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, n * 4, bb, GLES31.GL_DYNAMIC_COPY)
                return id[0]
            }
            val b0 = buf(fa); val b1 = buf(fb); val b2 = buf(null)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, b0)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, b1)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, b2)
            GLES31.glDispatchCompute(n / 64, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES31.glFinish()
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, b2)
            val mapped = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, n * 4,
                GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var exact = 0; var off1 = 0; var off2 = 0; var offBig = 0; var worst = 0f
            for (i in 0 until n) {
                val gpu = mapped.get()
                val cpu = fa[i] / fb[i]
                if (i < 3) Log.i("DivProbe", "sample i=$i a=${fa[i]} b=${fb[i]} gpu=$gpu cpu=$cpu")
                val d = Math.abs(gpu - cpu)
                val ulp = Math.ulp(cpu)
                if (gpu.toRawBits() == cpu.toRawBits()) exact++
                else if (d <= ulp) off1++
                else if (d <= 2 * ulp) off2++
                else { offBig++; worst = maxOf(worst, d / ulp) }
            }
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            Log.i("DivProbe", "exact=$exact off1=$off1 off2=$off2 offBig=$offBig worstUlps=$worst of $n")
        }
    }
}
