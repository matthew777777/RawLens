// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.EGL14.*
import android.opengl.GLES30.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production GL texture packing, sampling and affine application vs CPU oracle. */
@RunWith(AndroidJUnit4::class)
class BguGlSliceInstrumentedTest {
    @Test fun productionSliceMatchesScalarAcrossGridAndIntensityBoundaries() {
        val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        assertTrue(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        assertTrue(eglChooseConfig(display, intArrayOf(EGL_RENDERABLE_TYPE, 0x40,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        val w = 34
        val h = 26
        val surface = eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE), 0)
        assertTrue(eglMakeCurrent(display, surface, surface, context))
        var slice: BguGlSlice? = null
        try {
            slice = BguGlSlice()
            val guide = ByteArray(w*h*4) { i ->
                val x = (i/4)%w
                val y = (i/4)/w
                when(i%4) {
                    0 -> (x*255/(w-1)).toByte()
                    1, 2 -> (y*255/(h-1)).toByte()
                    else -> ((x+y)*255/(w+h-2)).toByte()
                }
            }
            val direct = ByteBuffer.allocateDirect(guide.size).put(guide)
            direct.rewind()
            // Change grid shape between draws, as occurs during guide boost,
            // target changes and a lens switch. Coefficients are exact halves.
            for (signed in listOf(false, true)) for (low in arrayOf(intArrayOf(17, 13), intArrayOf(49, 33))) {
                val dims = BguFit.gridDims(low[0], low[1])
                val plane = dims[0]*dims[1]*dims[2]
                val grid = BguGrid(dims[0], dims[1], dims[2], FloatArray(plane*12))
                for (z in 0 until grid.gz) for (y in 0 until grid.gh) for (x in 0 until grid.gw) {
                    val cell = x+grid.gw*(y+grid.gh*z)
                    for(row in 0..2) {
                        grid.coeffs[cell+plane*(row*4+row)] = if(signed) 3.5f else 0.5f+z/16f
                        if(signed) grid.coeffs[cell+plane*(row*4+(row+1)%3)] = -2.5f
                        grid.coeffs[cell+plane*(row*4+3)] = (x+y)/32f
                    }
                }
                slice.uploadGrid(BguFittedGrid(1, low[0], low[1], grid))
                assertTrue(slice.draw(0, direct, BguGuideGeo(0,0,w,h,2),
                    0, false, w, h, intArrayOf(0,0,w,h)))
                val actual = ByteBuffer.allocateDirect(w*h*4)
                glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,actual)
                val expected = BguSliceCpu.sliceScalar(guide,w,h,grid,
                    low[0]/16f,low[1]/16f,8f,false)
                var max = 0
                for(y in 0 until h) for(x in 0 until w) for(ch in 0..2) {
                    // GL framebuffer origin is bottom-left; sensor rows are top-down.
                    val src = ((h-1-y)*w+x)*4
                    val ci = if(ch==2) 3 else ch
                    val want = expected[src+ci].toInt() and 255
                    val got = actual.get((y*w+x)*4+ch).toInt() and 255
                    max = maxOf(max,kotlin.math.abs(want-got))
                }
                android.util.Log.i("RawLensBguA5", "GL slice low=${low.toList()} signed=$signed maxCodeError=$max")
                assertTrue("GL slice differs by $max codes",max<=2) // final dither + mediump
                assertEquals(GL_NO_ERROR,glGetError())
            }
        } finally {
            slice?.destroy()
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
            eglDestroySurface(display,surface)
            eglDestroyContext(display,context)
            eglTerminate(display)
        }
    }
}
