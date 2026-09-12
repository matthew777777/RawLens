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
class AluProbeTest {
    @Test
    fun probeMulAddSub() {
        val which = InstrumentationRegistry.getArguments().getString("op", "mul")
        val src = """
            #version 310 es
            #extension GL_EXT_gpu_shader5 : require
            precision highp float;
            precision highp int;
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) readonly buffer InA { float a[]; };
            layout(std430, binding = 1) readonly buffer InB { float b[]; };
            layout(std430, binding = 2) buffer OutQ { float q[]; };
            void main() {
                uint i = gl_GlobalInvocationID.x;
                q[i] = ${if (which == "add") "a[i] + b[i]" else if (which == "sub") "a[i] - b[i]" else "a[i] * b[i]"};
            }
        """.trimIndent()
        val egl = Gles31AmazeProcessor.EglComputeContext()
        egl.use {
            val sh = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(sh, src)
            GLES31.glCompileShader(sh)
            val st = IntArray(1)
            GLES31.glGetShaderiv(sh, GLES31.GL_COMPILE_STATUS, st, 0)
            assert(st[0] != 0) { GLES31.glGetShaderInfoLog(sh) }
            val pr = GLES31.glCreateProgram()
            GLES31.glAttachShader(pr, sh)
            GLES31.glLinkProgram(pr)
            GLES31.glUseProgram(pr)
            val n = 8192
            val rnd = java.util.Random(99)
            // Mix scales: tiny (scene3-like), normal, large, plus cancellation-prone pairs
            val fa = FloatArray(n) {
                when (it % 4) {
                    0 -> rnd.nextFloat() * 0.01f
                    1 -> (rnd.nextFloat() - 0.5f) * 2f
                    2 -> (rnd.nextFloat() - 0.5f) * 130000f
                    else -> floatBits(rnd.nextInt())
                }
            }
            val fb = FloatArray(n) {
                when (it % 4) {
                    0 -> rnd.nextFloat() * 0.01f
                    1 -> (rnd.nextFloat() - 0.5f) * 2f
                    2 -> (rnd.nextFloat() - 0.5f) * 130000f
                    else -> floatBits(rnd.nextInt())
                }
            }
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
            val ids = intArrayOf(buf(fa), buf(fb), buf(null))
            for (b in 0..2) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, b, ids[b])
            GLES31.glDispatchCompute(n / 64, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES31.glFinish()
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ids[2])
            val mapped = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, n * 4,
                GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var exact = 0; var off1 = 0; var offBig = 0; var worst = 0f
            for (i in 0 until n) {
                val gpu = mapped.get()
                val cpu = when (which) { "add" -> fa[i] + fb[i]; "sub" -> fa[i] - fb[i]; else -> fa[i] * fb[i] }
                if (gpu.toRawBits() == cpu.toRawBits()) exact++
                else {
                    val d = Math.abs(gpu - cpu)
                    val ulp = Math.ulp(cpu)
                    if (d <= ulp && ulp > 0f) off1++ else { offBig++; if (d.isFinite() && ulp > 0f) worst = maxOf(worst, d / ulp) }
                }
            }
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            Log.i("AluProbe", "$which exact=$exact off1=$off1 offBig=$offBig worstUlps=$worst of $n")
        }
    }

    private fun floatBits(bits: Int): Float {
        var b = bits and 0x7fffffff
        if (b >= 0x7f800000) b = b and 0x007fffff or 0x3f800000
        return Float.fromBits(b)
    }
}
