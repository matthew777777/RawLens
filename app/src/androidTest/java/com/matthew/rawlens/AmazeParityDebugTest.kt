// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.GLES31
import android.util.Log

@RunWith(AndroidJUnit4::class)
class AmazeParityDebugTest {
    @Test
    fun dumpMismatchTopology() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val data = ByteBuffer.wrap(instrumentation.context.assets.open("amaze_reference.bin").use {
            it.readBytes()
        }).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x414d415a, data.int)
        val count = data.int
        Gles31AmazeProcessor.EglComputeContext().use {
            Gles31AmazeProcessor.ProgramCache(instrumentation.targetContext).use { programs ->
                val program = programs.get("amaze/ordered_reference.glsl")
                val buffers = IntArray(4)
                GLES31.glGenBuffers(4, buffers, 0)
                fun buffer(binding: Int, size: Int, values: ByteBuffer? = null) {
                    GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[binding])
                    GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, size, values, GLES31.GL_DYNAMIC_COPY)
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffers[binding])
                }
                try {
                    // Same zero-filled serial setup as the parity test.
                    buffer(2, 486400 * 4 * 4, ByteBuffer.allocate(486400 * 4 * 4))
                    buffer(3, 25600 * 4 * 4, ByteBuffer.allocate(25600 * 4 * 4))
                    repeat(count) {
                        val w = data.int; val h = data.int; val phase = data.int; val scene = data.int; val gain = data.float
                        val raw = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                        repeat(w * h) { raw.putFloat(data.float) }; raw.flip()
                        val expected = FloatArray(w * h * 3) { data.float }
                        buffer(0, w * h * 4, raw); buffer(1, w * h * 12)
                        val phases = arrayOf(intArrayOf(0, 1, 1, 2), intArrayOf(1, 0, 2, 1),
                            intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0))
                        GLES31.glUniform2i(GLES31.glGetUniformLocation(program, "u_size"), w, h)
                        GLES31.glUniform4iv(GLES31.glGetUniformLocation(program, "u_fc"), 1, phases[phase], 0)
                        GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "u_initial_gain"), gain)
                        val tiles = ((w + 127) / 128) * ((h + 127) / 128)
                        for (tile in 0 until tiles) {
                            GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "u_tile_start"), tile)
                            GLES31.glDispatchCompute(1, 1, 1)
                            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                        }
                        GLES31.glFinish()
                        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
                        val mapped = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, w * h * 12,
                            GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        val actual = FloatArray(w * h * 3) { mapped.get() }
                        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                        var frame = 0; var inner = 0; var innerBig = 0
                        var logged = 0
                        for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
                            val i = (y * w + x) * 3 + c
                            if (actual[i].toRawBits() != expected[i].toRawBits()) {
                                val border = x < 4 || y < 4 || x >= w - 4 || y >= h - 4
                                if (border) frame++ else {
                                    inner++
                                    val err = kotlin.math.abs(actual[i] - expected[i])
                                    if (err > 1f) innerBig++
                                    if (logged < 10 && (scene == 1 || scene == 3 || scene == 5) && phase == 0) {
                                        Log.i("AmazeDbg", "ph=$phase sc=$scene x=$x y=$y c=$c exp=${expected[i]} act=${actual[i]}")
                                        logged++
                                    }
                                }
                            }
                        }
                        Log.i("AmazeDbg", "ph=$phase sc=$scene ${w}x$h frame4=$frame inner=$inner innerBig=$innerBig")
                    }
                } finally { GLES31.glDeleteBuffers(4, buffers, 0) }
            }
        }
    }
}
