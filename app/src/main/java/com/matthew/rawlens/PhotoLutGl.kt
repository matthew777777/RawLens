// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30 as GL

/** One hardware-trilinear fetch in the existing output pass. Owns a texture in the caller's EGL context. */
internal class PhotoLutGl {
    private class Uniforms(program: Int) {
        val sampler = GL.glGetUniformLocation(program, "u_photoCube")
        val enabled = GL.glGetUniformLocation(program, "u_photoCubeEnabled")
        val minimum = GL.glGetUniformLocation(program, "u_photoCubeMin")
        val span = GL.glGetUniformLocation(program, "u_photoCubeSpan")
        val texel = GL.glGetUniformLocation(program, "u_photoCubeTexel")
        val color = GL.glGetUniformLocation(program, "u_photoCubeColor")
        val tone = GL.glGetUniformLocation(program, "u_photoCubeTone")
        val adjust = GL.glGetUniformLocation(program, "u_photoCubeAdjust")
        var initialized = false
        var lut: CubeLut? = null
        var adjustments: PhotoLutAdjustments? = null
    }

    private val uniforms = HashMap<Int, Uniforms>()
    private var uploaded: CubeLut? = null
    private var texture = 0

    fun bind(program: Int, look: PhotoLutLook?) {
        val lut = look?.cube
        val u = uniforms.getOrPut(program) { Uniforms(program) }
        if (u.sampler < 0) return
        if (!u.initialized) {
            GL.glUniform1i(u.sampler, TEXTURE_UNIT)
            u.initialized = true
        }
        if (u.lut !== lut) {
            GL.glUniform1i(u.enabled, if (lut == null) 0 else 1)
            if (lut != null) {
                val min = lut.domainMin()
                val max = lut.domainMax()
                GL.glUniform3fv(u.minimum, 1, min, 0)
                GL.glUniform3f(u.span, max[0] - min[0], max[1] - min[1], max[2] - min[2])
                val texel = 1f / lut.size
                GL.glUniform2f(u.texel, 1f - texel, 0.5f * texel)
            }
            u.lut = lut
        }
        if (look == null || lut == null) return
        val a = look.adjustments
        if (u.adjustments != a) {
            GL.glUniform1i(u.adjust, if (a == neutral) 0 else 1)
            GL.glUniform4f(u.color, a.saturation / 100f, a.vibrance / 100f, a.warmth / 100f, a.tint / 100f)
            GL.glUniform3f(u.tone, a.contrast / 100f, a.shadows / 100f, a.highlights / 100f)
            u.adjustments = a
        }

        GL.glActiveTexture(GL.GL_TEXTURE0 + TEXTURE_UNIT)
        try {
            if (texture == 0) {
                val ids = IntArray(1)
                GL.glGenTextures(1, ids, 0)
                texture = ids[0]
                check(texture != 0) { "Could not allocate photo LUT texture" }
                GL.glBindTexture(GL.GL_TEXTURE_3D, texture)
                for (p in intArrayOf(GL.GL_TEXTURE_MIN_FILTER, GL.GL_TEXTURE_MAG_FILTER)) {
                    GL.glTexParameteri(GL.GL_TEXTURE_3D, p, GL.GL_LINEAR)
                }
                for (p in intArrayOf(GL.GL_TEXTURE_WRAP_S, GL.GL_TEXTURE_WRAP_T, GL.GL_TEXTURE_WRAP_R)) {
                    GL.glTexParameteri(GL.GL_TEXTURE_3D, p, GL.GL_CLAMP_TO_EDGE)
                }
                GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_MAX_LEVEL, 0)
            } else {
                GL.glBindTexture(GL.GL_TEXTURE_3D, texture)
            }
            if (uploaded !== lut) {
                val size = lut.size
                GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGBA16F, size, size, size, 0,
                    GL.GL_RGBA, GL.GL_HALF_FLOAT, lut.rgbaHalfBuffer())
                uploaded = lut
            }
        } finally {
            GL.glActiveTexture(GL.GL_TEXTURE0)
        }
    }

    fun close() {
        if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        uploaded = null
        uniforms.clear()
    }

    companion object {
        private const val TEXTURE_UNIT = 8
        private val neutral = PhotoLutAdjustments()
        // This source is shared by the runtime-generated viewfinder and output shaders.
        val glsl = """
            uniform highp sampler3D u_photoCube;
            uniform bool u_photoCubeEnabled;
            uniform highp vec3 u_photoCubeMin;
            uniform highp vec3 u_photoCubeSpan;
            uniform highp vec2 u_photoCubeTexel;
            uniform bool u_photoCubeAdjust;
            uniform highp vec4 u_photoCubeColor;
            uniform highp vec3 u_photoCubeTone;
            highp vec3 photoCube(highp vec3 encoded) {
                if (!u_photoCubeEnabled) return encoded;
                highp vec3 uv = clamp((encoded - u_photoCubeMin) / u_photoCubeSpan, 0.0, 1.0);
                highp vec3 rgb = clamp(textureLod(u_photoCube, uv * u_photoCubeTexel.x + u_photoCubeTexel.y, 0.0).rgb, 0.0, 1.0);
                if (!u_photoCubeAdjust) return rgb;

                const highp vec3 luma = vec3(0.2126, 0.7152, 0.0722);
                highp float y = dot(rgb, luma);
                highp float shadows = 1.0 - smoothstep(0.0, 0.55, y);
                highp float highlights = smoothstep(0.45, 1.0, y);
                highp float contrast = (y - 0.5) * (4.0 * y * (1.0 - y));
                highp float target = clamp(y + 0.65 * u_photoCubeTone.x * contrast
                    + 0.18 * dot(u_photoCubeTone.yz, vec2(shadows, highlights)), 0.0, 1.0);
                highp float chroma = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);
                highp float saturation = (1.0 + u_photoCubeColor.x)
                    * (1.0 + u_photoCubeColor.y * (1.0 - chroma));
                rgb = vec3(target) + (rgb - vec3(y)) * saturation;
                highp vec3 balance = u_photoCubeColor.z * vec3(0.16, 0.0, -0.16)
                    + u_photoCubeColor.w * vec3(0.08, -0.08, 0.08);
                return clamp(rgb * (1.0 + balance), 0.0, 1.0);
            }
        """.trimIndent()
    }
}
