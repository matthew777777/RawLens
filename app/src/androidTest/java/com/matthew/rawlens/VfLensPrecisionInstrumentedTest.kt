// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.EGL14.*
import android.opengl.GLES30.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production GL lens function on the actual GPU, without a camera. */
@RunWith(AndroidJUnit4::class)
class VfLensPrecisionInstrumentedTest {
    @Test fun oddGreenRowsKeepTheirGainAcrossTheSensor() {
        val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        assertTrue(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(eglChooseConfig(display, intArrayOf(EGL_RENDERABLE_TYPE, 0x40,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_NONE), 0, configs, 0, 1, count, 0))
        assertTrue(count[0] > 0)
        val context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        val surface = eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
        assertTrue(eglMakeCurrent(display, surface, surface, context))
        try {
            // Compile both complete production programs, including varying linkage.
            val cpuVertex = "attribute vec2 position; attribute vec2 uv; varying highp vec2 tex;" +
                "void main(){ tex=uv; gl_Position=vec4(position,0.,1.); }"
            glDeleteProgram(program(cpuVertex, VfGpuImport.cpuFragmentShader()))
            glDeleteProgram(program(VfGpuImport.gpuVertexShader(), VfGpuImport.gpuFragmentShader()))
            val fragment = VfGpuImport.cpuFragmentShader()
            val lensFunction = fragment.substringAfter("vec4 vfLensGain(")
                .substringBefore("void main()")
            // Use the real lens function in isolation so tonemap quantization cannot
            // hide a channel swap. The two green gains are deliberately distinct.
            val testFragment = "precision mediump float;\n#define VFHP highp\n" +
                "uniform sampler2D u_lens; uniform highp ivec2 u_lensSize;" +
                "uniform highp ivec4 u_lensActive; uniform int u_applyLens;" +
                "uniform highp ivec2 u_quadBase; uniform highp ivec2 u_frameSize;" +
                "uniform int u_step; uniform int u_lensGreenRow;" +
                "vec4 vfLensGain(" + lensFunction +
                "void main(){ gl_FragColor=vfLensGain(vec2(0.5))*0.125; }"
            val prog = program(cpuVertex, testFragment)
            glUseProgram(prog)
            fun loc(n: String) = glGetUniformLocation(prog, n)
            val textures = IntArray(1)
            glGenTextures(1, textures, 0)
            glBindTexture(GL_TEXTURE_2D, textures[0])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            val pixels = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            pixels.asShortBuffer().put(VfGpuImport.packLensHalf(floatArrayOf(1f, 2f, 4f, 1f)))
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1, 1, 0, GL_RGBA, GL_HALF_FLOAT, pixels)
            glUniform1i(loc("u_lens"), 0)
            glUniform1i(loc("u_applyLens"), 1)
            glUniform2i(loc("u_lensSize"), 1, 1)
            glUniform4i(loc("u_lensActive"), 0, 0, 8192, 8192)
            glUniform2i(loc("u_frameSize"), 1, 1)
            glUniform1i(loc("u_step"), 2)
            val vertices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).position(0)
            val attr = glGetAttribLocation(prog, "position")
            glEnableVertexAttribArray(attr)
            glVertexAttribPointer(attr, 2, GL_FLOAT, false, 0, vertices)
            glViewport(0, 0, 1, 1)
            for (greenRow in 0..1) {
                glUniform1i(loc("u_lensGreenRow"), greenRow)
                for (row in intArrayOf(0, 1, 2047, 2048, 2049, 4095, 4096, 6143, 8190)) {
                    glUniform2i(loc("u_quadBase"), 0, row)
                    glDrawArrays(GL_TRIANGLES, 0, 3)
                    val out = ByteBuffer.allocateDirect(4)
                    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, out)
                    val odd = (row + greenRow) and 1 != 0
                    assertEquals("row=$row Gr=$greenRow", if (odd) 128 else 64, out.get(1).toInt() and 255)
                    assertEquals("row=$row Gb=$greenRow", if (odd) 64 else 128, out.get(2).toInt() and 255)
                }
            }
            assertEquals(GL_NO_ERROR, glGetError())
            glDeleteTextures(1, textures, 0)
            glDeleteProgram(prog)
        } finally {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            eglDestroySurface(display, surface)
            eglDestroyContext(display, context)
            eglTerminate(display)
        }
    }

    private fun program(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, source)
            glCompileShader(id)
            val ok = IntArray(1)
            glGetShaderiv(id, GL_COMPILE_STATUS, ok, 0)
            assertEquals(glGetShaderInfoLog(id), GL_TRUE, ok[0])
            return id
        }
        val vs = compile(GL_VERTEX_SHADER, vertex)
        val fs = compile(GL_FRAGMENT_SHADER, fragment)
        val id = glCreateProgram()
        glAttachShader(id, vs)
        glAttachShader(id, fs)
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        assertEquals(glGetProgramInfoLog(id), GL_TRUE, ok[0])
        glDeleteShader(vs)
        glDeleteShader(fs)
        return id
    }
}
