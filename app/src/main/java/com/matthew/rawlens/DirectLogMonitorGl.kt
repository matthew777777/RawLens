// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30 as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional GLES3 monitor transform. Runs after the recorded P010 is sampled. */
internal class DirectLogMonitorGl(private val lut: CubeLut?, private val intensity: Float) {
    private var program = 0
    private var texture = 0

    fun program(): Int {
        if (program != 0) return program
        val vertex = compile(GL.GL_VERTEX_SHADER, """#version 300 es
            in vec4 aPos; in vec2 aUV; out vec2 vUV;
            void main() { gl_Position = aPos; vUV = aUV; }
        """.trimIndent())
        val fragment = compile(GL.GL_FRAGMENT_SHADER, """#version 300 es
            precision highp float;
            precision highp sampler3D;
            in vec2 vUV; out vec4 frag;
            uniform highp sampler2D uTex;
            uniform sampler3D uLut;
            uniform vec3 uMin, uMax;
            uniform float uSize, uIntensity;
            uniform vec3 uInverseR, uInverseG, uInverseB;
            float inverseSony(float x) {
                return x >= 171.2102946929 / 1023.0
                    ? pow(10.0, (x * 1023.0 - 420.0) / 261.5) * 0.19 - 0.01
                    : (x * 1023.0 - 95.0) * 0.01125 / (171.2102946929 - 95.0);
            }
            float bt709(float x) {
                x = clamp(x, 0.0, 1.0);
                return x < 0.018 ? 4.5 * x : 1.099 * pow(x, 0.45) - 0.099;
            }
            void main() {
                vec3 source = texture(uTex, vUV).rgb;
                vec3 mapped;
                if (uSize < 1.0) {
                    vec3 cine = vec3(inverseSony(source.r), inverseSony(source.g), inverseSony(source.b));
                    vec3 linear = vec3(dot(cine, uInverseR), dot(cine, uInverseG), dot(cine, uInverseB));
                    mapped = vec3(bt709(linear.r), bt709(linear.g), bt709(linear.b));
                } else {
                    vec3 grid = clamp((source - uMin) / (uMax - uMin), 0.0, 1.0);
                    // Texel centers: 0 -> first center, 1 -> last center.
                    vec3 coord = (grid * (uSize - 1.0) + 0.5) / uSize;
                    mapped = texture(uLut, coord).rgb;
                }
                frag = vec4(mix(source, mapped, uIntensity), 1.0);
            }
        """.trimIndent())
        try {
            program = GL.glCreateProgram()
            check(program != 0)
            GL.glAttachShader(program, vertex)
            GL.glAttachShader(program, fragment)
            GL.glLinkProgram(program)
            val status = IntArray(1)
            GL.glGetProgramiv(program, GL.GL_LINK_STATUS, status, 0)
            check(status[0] == GL.GL_TRUE) { GL.glGetProgramInfoLog(program) }
            val names = IntArray(1)
            GL.glGenTextures(1, names, 0)
            texture = names[0]
            check(texture != 0)
            GL.glActiveTexture(GL.GL_TEXTURE1)
            GL.glBindTexture(GL.GL_TEXTURE_3D, texture)
            for (parameter in listOf(GL.GL_TEXTURE_MIN_FILTER, GL.GL_TEXTURE_MAG_FILTER))
                GL.glTexParameteri(GL.GL_TEXTURE_3D, parameter, GL.GL_LINEAR)
            for (parameter in listOf(GL.GL_TEXTURE_WRAP_S, GL.GL_TEXTURE_WRAP_T, GL.GL_TEXTURE_WRAP_R))
                GL.glTexParameteri(GL.GL_TEXTURE_3D, parameter, GL.GL_CLAMP_TO_EDGE)
            val data = lut?.data() ?: floatArrayOf(0f,0f,0f)
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(data).position(0)
            GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
            val size = lut?.size ?: 1
            GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGB16F, size, size, size,
                0, GL.GL_RGB, GL.GL_FLOAT, buffer)
            check(GL.glGetError() == GL.GL_NO_ERROR) { "Monitor LUT texture upload failed" }
            GL.glUseProgram(program)
            GL.glUniform1i(GL.glGetUniformLocation(program, "uTex"), 0)
            GL.glUniform1i(GL.glGetUniformLocation(program, "uLut"), 1)
            GL.glUniform3fv(GL.glGetUniformLocation(program, "uMin"), 1, lut?.domainMin() ?: floatArrayOf(0f,0f,0f), 0)
            GL.glUniform3fv(GL.glGetUniformLocation(program, "uMax"), 1, lut?.domainMax() ?: floatArrayOf(1f,1f,1f), 0)
            GL.glUniform1f(GL.glGetUniformLocation(program, "uSize"), lut?.size?.toFloat() ?: 0f)
            for ((row, name) in listOf("uInverseR", "uInverseG", "uInverseB").withIndex())
                GL.glUniform3fv(GL.glGetUniformLocation(program, name), 1, SonyGamut.inverseRow(row), 0)
            GL.glUniform1f(GL.glGetUniformLocation(program, "uIntensity"), intensity)
            return program
        } catch (failure: Exception) {
            release()
            throw failure
        } finally {
            GL.glDeleteShader(vertex)
            GL.glDeleteShader(fragment)
            GL.glActiveTexture(GL.GL_TEXTURE0)
        }
    }

    fun bind() {
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_3D, texture)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    /** Caller owns the current EGL context; context loss also releases these names. */
    fun release() {
        if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GL.glDeleteProgram(program)
        texture = 0
        program = 0
    }

    companion object {
        private fun compile(type: Int, source: String): Int {
            val shader = GL.glCreateShader(type)
            check(shader != 0)
            GL.glShaderSource(shader, source)
            GL.glCompileShader(shader)
            val status = IntArray(1)
            GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GL.GL_TRUE) {
                val reason = GL.glGetShaderInfoLog(shader)
                GL.glDeleteShader(shader)
                error(reason)
            }
            return shader
        }
    }
}
