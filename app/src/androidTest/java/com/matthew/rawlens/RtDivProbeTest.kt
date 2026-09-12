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
class RtDivProbeTest {
    @Test
    fun probeRtDiv() {
        val regime = InstrumentationRegistry.getArguments().getString("regime", "tiny")
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
                float x = a[i]; float y = b[i];
                precise float d = y; precise float n = x;
                precise float q0 = n / d;
                precise float rr = fma(-q0, d, n) / d;
                precise float qq = q0 + rr;
                q[i] = qq;
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
            val rnd = java.util.Random(1234)
            val fa = FloatArray(n)
            val fb = FloatArray(n)
            for (i in 0 until n) {
                when (regime) {
                    "tiny" -> { fa[i] = rnd.nextFloat() * 0.01f; fb[i] = 1e-5f + rnd.nextFloat() * 0.02f }
                    "neg" -> { fa[i] = (rnd.nextFloat() - 0.6f) * 2f; fb[i] = (rnd.nextFloat() - 0.5f) * 2f }
                    "norm" -> { fa[i] = (rnd.nextFloat() - 0.5f) * 2f; fb[i] = 0.001f + rnd.nextFloat() * 3f }
                    "denorm" -> { fa[i] = rnd.nextFloat() * 1e-30f; fb[i] = rnd.nextFloat() * 1e-30f + 1e-37f }
                    else -> { fa[i] = rnd.nextFloat(); fb[i] = rnd.nextFloat() + 0.001f }
                }
                if (fb[i] == 0f) fb[i] = 1e-6f
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
                val cpu = fa[i] / fb[i]
                if (i < 2) Log.i("RtDivProbe", "$regime i=$i a=${fa[i]} b=${fb[i]} gpu=$gpu cpu=$cpu")
                if (gpu.toRawBits() == cpu.toRawBits()) exact++
                else {
                    val d = Math.abs(gpu - cpu)
                    val ulp = Math.ulp(cpu)
                    if (d <= ulp && ulp > 0f) off1++ else { offBig++; if (d.isFinite() && ulp > 0f) worst = maxOf(worst, d / ulp) }
                }
            }
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            Log.i("RtDivProbe", "$regime exact=$exact off1=$off1 offBig=$offBig worstUlps=$worst of $n")
        }
    }
}
