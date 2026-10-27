// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Slice program + textures + quad. All calls run on the render thread. */
internal class BguGlSlice {
    private val program: Int
    private val aPosition: Int
    private val aUv: Int
    private val uGuide: Int
    private val uGrid0: Int
    private val uGrid1: Int
    private val uGrid2: Int
    private val uScale: Int
    private val uGridDim: Int
    private val vboPos: Int
    private val vboUv: Int
    private val texGuide: Int
    // Separate texture for CPU-fallback guides: a texture with an EGL
    // image bound must never see glTexImage2D, so the byte-upload path
    // gets its own name with identical sampling parameters.
    private val texCpuGuide: Int
    private var cpuGuideW = 0
    private var cpuGuideH = 0
    private val texGrid = IntArray(3)
    private var gridW = 0
    private var gridH = 0
    private var gridZ = 0
    private var uvKey = ""
    private var fittedLowW = 0
    private var fittedLowH = 0

    init {
        program = link(BguSlice.vertexShader(), BguSlice.fragmentShader())
        aPosition = GLES30.glGetAttribLocation(program, "position")
        aUv = GLES30.glGetAttribLocation(program, "uv")
        uGuide = GLES30.glGetUniformLocation(program, "uGuide")
        uGrid0 = GLES30.glGetUniformLocation(program, "uGrid0")
        uGrid1 = GLES30.glGetUniformLocation(program, "uGrid1")
        uGrid2 = GLES30.glGetUniformLocation(program, "uGrid2")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uGridDim = GLES30.glGetUniformLocation(program, "uGridDim")
        val vbos = IntArray(2)
        GLES30.glGenBuffers(2, vbos, 0)
        vboPos = vbos[0]
        vboUv = vbos[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, floatBufferOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        ), GLES30.GL_STATIC_DRAW)
        val tex = IntArray(5)
        GLES30.glGenTextures(5, tex, 0)
        texGuide = tex[0]
        texGrid[0] = tex[1]
        texGrid[1] = tex[2]
        texGrid[2] = tex[3]
        texCpuGuide = tex[4]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texGuide)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCpuGuide)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        for (i in 0..2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texGrid[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun uploadGrid(fitted: BguFittedGrid) {
        val grid = fitted.grid
        val packed = BguSlice.packGrid(grid)
        for (i in 0..2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texGrid[i])
            val buf = ByteBuffer.allocateDirect(packed[i].size * 2)
                .order(ByteOrder.nativeOrder())
            for (s in packed[i]) buf.putShort(s)
            buf.flip()
            if (grid.gw != gridW || grid.gh != gridH || grid.gz != gridZ) {
                GLES30.glTexImage3D(
                    GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F,
                    grid.gw, grid.gh, grid.gz, 0,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf
                )
            } else {
                GLES30.glTexSubImage3D(
                    GLES30.GL_TEXTURE_3D, 0, 0, 0, 0,
                    grid.gw, grid.gh, grid.gz,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf
                )
            }
        }
        fittedLowW = fitted.lowW
        fittedLowH = fitted.lowH
        gridW = grid.gw
        gridH = grid.gh
        gridZ = grid.gz
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
    }

    /**
     * CPU-fallback guide upload: RGBA8 bytes into the dedicated texture
     * (reallocates only on size change). Same sampling contract as the
     * EGL-imported Vulkan export, so the slice shader is untouched.
     */
    fun uploadCpuGuide(buf: ByteBuffer, w: Int, h: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCpuGuide)
        if (w != cpuGuideW || h != cpuGuideH || w <= 0 || h <= 0) {
            if (w <= 0 || h <= 0) return
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
            )
            cpuGuideW = w
            cpuGuideH = h
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
            )
        }
    }

    fun draw(
        eglImage: Long, cpuGuide: ByteBuffer?, geo: BguGuideGeo,
        rotation: Int, mirrored: Boolean, viewW: Int, viewH: Int, fit: IntArray
    ): Boolean {
        if (viewW <= 0 || viewH <= 0) return true
        val guideTex = if (cpuGuide != null) {
            uploadCpuGuide(cpuGuide, geo.width, geo.height)
            texCpuGuide
        } else {
            if (eglImage == 0L) return true
            if (VfEglImport.bindEGLImageToTexture2D(eglImage, texGuide) != 0) return false
            texGuide
        }
        val key = "$rotation,$mirrored"
        if (key != uvKey) {
            uvKey = key
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUv)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, 8 * 4,
                floatBufferOf(*BguGeometry.displayUv(rotation, mirrored)),
                GLES30.GL_DYNAMIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
        // WYSIWYG aspect-fit: the frame keeps its display aspect centered
        // in the surface; the full surface clears first so the bars stay
        // black (glClear is viewport-scissored).
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(fit[0], fit[1], fit[2], fit[3])
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, guideTex)
        GLES30.glUniform1i(uGuide, 0)
        val units = intArrayOf(uGrid0, uGrid1, uGrid2)
        for (i in 0..2) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texGrid[i])
            GLES30.glUniform1i(units[i], 1 + i)
        }
        val s = BguFit.DEFAULT_S.toFloat()
        val r = BguFit.DEFAULT_R
        // Geometry belongs to the uploaded fit, which may lag guide resizing.
        GLES30.glUniform3f(uScale, fittedLowW / s, fittedLowH / s, 1f / r)
        GLES30.glUniform3f(uGridDim, gridW.toFloat(), gridH.toFloat(), gridZ.toFloat())
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUv)
        GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return true
    }

    fun destroy() {
        try {
            GLES30.glDeleteProgram(program)
        } catch (_: Exception) {
        }
        try {
            GLES30.glDeleteBuffers(2, intArrayOf(vboPos, vboUv), 0)
        } catch (_: Exception) {
        }
        try {
            GLES30.glDeleteTextures(5, intArrayOf(texGuide, texGrid[0], texGrid[1], texGrid[2], texCpuGuide), 0)
        } catch (_: Exception) {
        }
    }

    companion object {
        private fun floatBufferOf(vararg values: Float): java.nio.FloatBuffer {
            val buf = ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(values)
            buf.flip()
            return buf
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw IllegalStateException("createShader failed")
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("compile failed: $log")
            }
            return shader
        }

        private fun link(vs: String, fs: String): Int {
            val program = GLES30.glCreateProgram()
            if (program == 0) throw IllegalStateException("createProgram failed")
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            GLES30.glAttachShader(program, v)
            GLES30.glAttachShader(program, f)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw IllegalStateException("link failed: $log")
            }
            return program
        }
    }
}

