// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shared fullscreen-texture blit for encoder-surface probes (Phase A).
 * Extracted verbatim from the AHB bridge so the Vulkan bridge reuses the
 * identical draw (passthrough RGBA -> window surface, no color work).
 */
internal object GlBlit {
    fun quadBuffer(): FloatBuffer {
        // Fullscreen triangle strip: x,y,u,v.
        val v = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        return ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(v); position(0) }
    }

    fun drawQuad(program: Int, quad: FloatBuffer) {
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUV")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
        checkGl("drawQuad")
    }

    fun buildProgram(): Int {
        val vs = "attribute vec4 aPos;attribute vec2 aUV;varying vec2 vUV;" +
            "void main(){gl_Position=aPos;vUV=aUV;}"
        val fs = "precision mediump float;varying vec2 vUV;uniform sampler2D uTex;" +
            "void main(){gl_FragColor=texture2D(uTex,vUV);}"
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        check(p != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        check(link[0] == GLES20.GL_TRUE) { "blit link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    /**
     * Dithered blit: FP16 texture -> 8-bit surface with one LSB of
     * temporally-decorrelated white-noise dither. This is what makes the
     * 8-bit encoder surface visually band-free: quantization error becomes
     * high-frequency noise instead of contour steps. highp: mediump could
     * itself quantize the ramp being protected.
     */
    fun buildDitherProgram(): Int {
        val vs = "attribute vec4 aPos;attribute vec2 aUV;varying vec2 vUV;" +
            "void main(){gl_Position=aPos;vUV=aUV;}"
        val fs = "precision highp float;varying vec2 vUV;" +
            "uniform sampler2D uTex;uniform vec2 uRes;uniform float uSeed;" +
            "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7))+uSeed*17.0)*43758.5453);}" +
            "void main(){vec3 c=texture2D(uTex,vUV).rgb;" +
            "float d=(hash(floor(vUV*uRes))-0.5)/255.0;" +
            "gl_FragColor=vec4(c+d,1.0);}"
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        check(p != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        check(link[0] == GLES20.GL_TRUE) { "dither link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    fun drawDithered(program: Int, quad: FloatBuffer, resX: Int, resY: Int, seed: Float) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uRes"), resX.toFloat(), resY.toFloat()
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSeed"), seed)
        drawQuad(program, quad)
    }

    fun genTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texture = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texture
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        check(s != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun checkGl(op: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw IllegalStateException("$op GL error 0x${e.toString(16)}")
    }
}
