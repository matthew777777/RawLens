// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.EGL14 as EGL
import android.opengl.GLES30 as GL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual optional display shader and half-float 3D texture against CPU trilinear. */
@RunWith(AndroidJUnit4::class)
class DirectLogLutDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun technicalAssistThroughDisplayShader() = exercise(null, "technical")

    @Test fun userSuppliedSonyLutThroughDisplayShader() {
        val path = InstrumentationRegistry.getArguments().getString("sonyLutPath")
        assumeTrue("Supply external Sony LUT with -e sonyLutPath", path != null)
        val lut = File(requireNotNull(path)).reader().use { CubeLut.parse(it) }
        exercise(lut,"sony")
    }

    @Test fun userSuppliedLutLibraryLifecycle() {
        val path = InstrumentationRegistry.getArguments().getString("sonyLutPath")
        assumeTrue("Supply external LUT with -e sonyLutPath", path != null)
        val library = DirectLogLutLibrary(context)
        val originalSelection = library.selected
        var imported: String? = null
        try {
            imported = library.import(android.net.Uri.fromFile(File(requireNotNull(path))))
            assertEquals(imported, library.selected)
            assertTrue(library.entries().any { it.name == imported })
            assertEquals(33, requireNotNull(library.load()).size)
            library.intensity = .5f
            assertEquals(.5f,DirectLogLutLibrary(context).intensity,0f)
            library.selected = DirectLogLutLibrary.TECHNICAL
            library.selected = imported
            assertEquals(.5f,library.intensity,0f)
            library.remove(imported)
            imported = null
            assertEquals(DirectLogLutLibrary.OFF,library.selected)
        } finally {
            imported?.let { library.remove(it) }
            library.selected = originalSelection
        }
    }

    private fun exercise(lut: CubeLut?, name: String) {
        val width = 256
        // First half is a grey ramp, second half deterministic coloured patches.
        val input = ByteArray(width*3)
        repeat(width) { x -> repeat(3) { c ->
            input[3*x+c] = (if (x < 128) x*2 else (x*(intArrayOf(37,73,113)[c])+c*41) % 256).toByte()
        } }
        val rgba = ByteBuffer.allocateDirect(width*4)
        repeat(width) { x -> repeat(3) { rgba.put(input[3*x+it]) }; rgba.put(255.toByte()) }; rgba.position(0)
        val display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
        check(EGL.eglInitialize(display, IntArray(2),0,IntArray(2),0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL.eglChooseConfig(display,intArrayOf(EGL.EGL_RENDERABLE_TYPE,0x40,
            EGL.EGL_SURFACE_TYPE,EGL.EGL_PBUFFER_BIT,EGL.EGL_RED_SIZE,8,EGL.EGL_GREEN_SIZE,8,
            EGL.EGL_BLUE_SIZE,8,EGL.EGL_ALPHA_SIZE,8,EGL.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val ctx = EGL.eglCreateContext(display,configs[0],EGL.EGL_NO_CONTEXT,
            intArrayOf(EGL.EGL_CONTEXT_CLIENT_VERSION,3,EGL.EGL_NONE),0)
        check(ctx != EGL.EGL_NO_CONTEXT)
        val surface = EGL.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL.EGL_WIDTH,width,
            EGL.EGL_HEIGHT,1,EGL.EGL_NONE),0)
        check(EGL.eglMakeCurrent(display,surface,surface,ctx))
        val dir = File(context.getExternalFilesDir(null),"sony-direct-log").apply { mkdirs() }
        val report = StringBuilder()
        try {
            GL.glDisable(GL.GL_DITHER)
            GL.glViewport(0,0,width,1)
            val texture = GlBlit.genTexture()
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,width,1,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,rgba)
            for (intensity in listOf(0f,.5f,1f)) {
                val monitor = DirectLogMonitorGl(lut,intensity)
                try {
                    val program = monitor.program()
                    GL.glUseProgram(program)
                    monitor.bind()
                    GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
                    GlBlit.drawQuad(program,GlBlit.quadBuffer())
                    val result = ByteBuffer.allocateDirect(width*4)
                    GL.glReadPixels(0,0,width,1,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,result)
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                    val out = ByteArray(width*3)
                    var maxError = 0
                    var worst = ""
                    repeat(width) { x ->
                        val rgb = FloatArray(3) { (input[3*x+it].toInt() and 255)/255f }
                        val expected = (lut?.apply(rgb,intensity) ?: SonyDisplayAssist.apply(rgb,intensity))
                        repeat(3) { c ->
                            val value = result.get(4*x+c).toInt() and 255
                            out[3*x+c] = value.toByte()
                            val error = abs(value-(expected[c].coerceIn(0f,1f)*255).roundToInt())
                            if (error > maxError) {
                                maxError = error
                                worst = "pixel=$x channel=$c input=${rgb.toList()} expected=${expected[c]} actual=$value"
                            }
                        }
                    }
                    assertTrue("$name/$intensity display error $maxError > 2 codes: $worst",maxError <= 2)
                    report.append("$name intensity=$intensity samples=${width*3} max8bitError=$maxError\n")
                    if (intensity == 1f) {
                        File(dir,"$name-input.rgb").writeBytes(input)
                        File(dir,"$name-gpu.rgb").writeBytes(out)
                        File(dir,"$name-cpu.rgb").writeBytes(ByteArray(width*3) { i ->
                            val rgb = FloatArray(3) { (input[3*(i/3)+it].toInt() and 255)/255f }
                            ((lut?.apply(rgb) ?: SonyDisplayAssist.apply(rgb))[i%3].coerceIn(0f,1f)*255).roundToInt().toByte()
                        })
                    }
                } finally { monitor.release() }
            }
            File(dir,"$name-monitor-report.txt").writeText(report.toString())
        } finally {
            EGL.eglMakeCurrent(display,EGL.EGL_NO_SURFACE,EGL.EGL_NO_SURFACE,EGL.EGL_NO_CONTEXT)
            EGL.eglDestroySurface(display,surface)
            EGL.eglDestroyContext(display,ctx)
            EGL.eglTerminate(display)
        }
    }
}
