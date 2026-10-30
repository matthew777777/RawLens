// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.EGL14.*
import android.opengl.GLES30.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewTonemapEfficiencyDeviceTest {
    @Test fun previewQualityAndGpuCostAgainstPinnedReference() {
        val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        assertTrue(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        assertTrue(eglChooseConfig(display, intArrayOf(EGL_RENDERABLE_TYPE, 0x40,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        val surface = eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL_WIDTH, 1080, EGL_HEIGHT, 1613, EGL_NONE), 0)
        val cappedSize = VfPresentSize.forLayout(1080, 1613, 1080)
        val cappedSurface = eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL_WIDTH, cappedSize.width, EGL_HEIGHT, cappedSize.height, EGL_NONE), 0)
        assertTrue(eglMakeCurrent(display, surface, surface, context))
        val before = InstrumentationRegistry.getInstrumentation().context.assets
            .open("preview-efficiency/tonemap-before.frag").bufferedReader().use { it.readText() }
        val vertex = "attribute vec2 position; varying highp vec2 tex;" +
            "void main(){ tex=position*0.5+vec2(0.5); gl_Position=vec4(position,0.,1.); }"
        val programs = intArrayOf(program(vertex, before), program(vertex, VfGpuImport.cpuFragmentShader()))
        val texture = IntArray(1)
        glGenTextures(1, texture, 0)
        val vertices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }
        try {
            glBindTexture(GL_TEXTURE_2D, texture[0])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            val input = ByteBuffer.allocateDirect(256 * 128 * 4)
            val random = java.util.Random(65537)
            repeat(input.capacity()) { input.put(random.nextInt(256).toByte()) }
            input.flip()
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 128, 0, GL_RGBA, GL_UNSIGNED_BYTE, input)
            val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            fun setup(p: Int, hue: Float, gamut: Float, ev: Float, p3: Int, punchy: Float) {
                glUseProgram(p)
                fun i(n: String, v: Int) = glUniform1i(glGetUniformLocation(p, n), v)
                fun f(n: String, v: Float) = glUniform1f(glGetUniformLocation(p, n), v)
                i("raw", 0); i("u_applyLens", 0); i("u_jpeg", 1); i("u_displayP3", p3)
                glUniform4f(glGetUniformLocation(p, "gains"), 2f, 1f, 1f, 1.5f)
                glUniformMatrix3fv(glGetUniformLocation(p, "color"), 1, false, identity, 0)
                glUniformMatrix3fv(glGetUniformLocation(p, "u_camToAces"), 1, false, identity, 0)
                glUniform3f(glGetUniformLocation(p, "u_cameraWhite"), 0.5f, 1f, 0.7f)
                f("u_agxPunchy", punchy); f("u_agxContrast", 1.1f); f("u_agxSaturation", 1.2f)
                f("u_agxPurity", 1f); f("u_agxHue", hue); f("u_agxGamut", gamut)
                f("u_agxShadowEv", 8f); f("u_agxHighlightEv", 6.5f)
                f("u_exposureEv", ev); f("u_highlightShoulder", 0.5f)
                val attr = glGetAttribLocation(p, "position")
                glEnableVertexAttribArray(attr)
                vertices.position(0)
                glVertexAttribPointer(attr, 2, GL_FLOAT, false, 0, vertices)
            }
            val pixels = Array(2) { ByteBuffer.allocateDirect(256 * 128 * 4) }
            var maxError = 0
            var totalError = 0L
            var channelsChecked = 0L
            val errors = IntArray(256)
            var cases = 0
            for (controls in arrayOf(floatArrayOf(0f, 0f), floatArrayOf(.5f, 0f),
                floatArrayOf(0f, .5f), floatArrayOf(.5f, .5f), floatArrayOf(1f, 1f))) {
                for (ev in floatArrayOf(-3f, 0f, 3f)) for (p3 in 0..1) for (punchy in 0..1) {
                    glViewport(0, 0, 256, 128)
                    for (idx in 0..1) {
                        setup(programs[idx], controls[0], controls[1], ev, p3, punchy.toFloat())
                        glDrawArrays(GL_TRIANGLES, 0, 3)
                        pixels[idx].clear()
                        glReadPixels(0, 0, 256, 128, GL_RGBA, GL_UNSIGNED_BYTE, pixels[idx])
                    }
                    for (i in 0 until pixels[0].capacity()) if (i % 4 != 3) {
                        val error = kotlin.math.abs((pixels[0].get(i).toInt() and 255) - (pixels[1].get(i).toInt() and 255))
                        maxError = maxOf(maxError, error)
                        totalError += error
                        channelsChecked++
                        errors[error]++
                    }
                    cases++
                }
            }
            var sum = 0L
            var p99 = 0
            for (i in errors.indices) { sum += errors[i]; if (sum >= channelsChecked * .99) { p99 = i; break } }
            val meanError = totalError.toDouble() / channelsChecked
            Log.i("PreviewEfficiency", "tonemap cases=$cases maxCodeError=$maxError meanCodeError=$meanError p99=$p99 renderer=${glGetString(GL_RENDERER)}")
            assertTrue("preview maximum error $maxError", maxError <= 12)
            assertTrue("preview mean error $meanError", meanError <= .5)
            assertTrue("preview p99 error $p99", p99 <= 2)
            val timer = glGetString(GL_EXTENSIONS)?.contains("GL_EXT_disjoint_timer_query") == true
            val query = IntArray(1)
            if (timer) glGenQueries(1, query, 0)
            val times = Array(3) { ArrayList<Double>() }
            for (round in 0 until 9) for (offset in 0..2) {
                val idx = (round + offset) % 3
                val target = if (idx == 2) cappedSurface else surface
                assertTrue(eglMakeCurrent(display, target, target, context))
                glViewport(0, 0, if (idx == 2) cappedSize.width else 1080,
                    if (idx == 2) cappedSize.height else 1613)
                setup(programs[if (idx == 0) 0 else 1], 0f, 0f, 0f, 0, 0f)
                glClearColor(0f, 0f, 0f, 1f)
                glClear(GL_COLOR_BUFFER_BIT); glDrawArrays(GL_TRIANGLES, 0, 3); glFinish()
                repeat(4) {
                    val start = System.nanoTime()
                    if (timer) glBeginQuery(0x88BF, query[0]) // GL_TIME_ELAPSED_EXT
                    // Keep the GPU fed instead of measuring eight scheduler
                    // wakeups. Fences/flushes seal each frame's work, preventing
                    // a tiled driver from discarding earlier overwritten draws.
                    val fences = LongArray(8)
                    for (frame in fences.indices) {
                        glClear(GL_COLOR_BUFFER_BIT); glDrawArrays(GL_TRIANGLES, 0, 3)
                        fences[frame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                        glFlush()
                    }
                    if (timer) glEndQuery(0x88BF)
                    glFinish()
                    val elapsed = if (timer) {
                        val nanos = IntArray(1)
                        glGetQueryObjectuiv(query[0], GL_QUERY_RESULT, nanos, 0)
                        (nanos[0].toLong() and 0xffffffffL) / 8e6
                    } else (System.nanoTime() - start) / 8e6
                    fences.forEach(::glDeleteSync)
                    if (round >= 2) times[idx].add(elapsed)
                }
            }
            if (timer) {
                val disjoint = BooleanArray(1)
                glGetBooleanv(0x8FBB, disjoint, 0) // GL_GPU_DISJOINT_EXT
                assertFalse("GPU timer disjoint", disjoint[0])
                glDeleteQueries(1, query, 0)
            }
            val medians = times.map { it.sorted()[it.size / 2] }
            Log.i("PreviewEfficiency", "batch-draw timing=${if (timer) "gpu-query" else "cpu-inclusive"} baselineFullMs=${medians[0]} currentFullMs=${medians[1]} cappedMs=${medians[2]} capped=${cappedSize.width}x${cappedSize.height} shaderRatio=${medians[0] / medians[1]} totalRatio=${medians[0] / medians[2]}")
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            programs.forEach(::glDeleteProgram)
            glDeleteTextures(1, texture, 0)
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            eglDestroySurface(display, surface); eglDestroySurface(display, cappedSurface)
            eglDestroyContext(display, context); eglTerminate(display)
        }
    }

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, source); glCompileShader(id)
            val status = IntArray(1)
            glGetShaderiv(id, GL_COMPILE_STATUS, status, 0)
            assertEquals(glGetShaderInfoLog(id), GL_TRUE, status[0])
            return id
        }
        val vs = shader(GL_VERTEX_SHADER, vertex)
        val fs = shader(GL_FRAGMENT_SHADER, fragment)
        val p = glCreateProgram()
        glAttachShader(p, vs); glAttachShader(p, fs); glLinkProgram(p)
        val status = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, status, 0)
        assertEquals(glGetProgramInfoLog(p), GL_TRUE, status[0])
        glDeleteShader(vs); glDeleteShader(fs)
        return p
    }
}
