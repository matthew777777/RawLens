// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Fresh BGU slice stage (no shared code with the legacy tonemap): the ES 3.00
 * slice shaders, f32->f16 grid packing for filterable RGBA16F 3D textures,
 * and the cell-geometry mapping shared with the fit generator. Pure and
 * unit-tested; GL calls live in [BguViewfinder].
 */
internal object BguSlice {
    fun vertexShader(): String =
        "#version 300 es\n" +
            "in vec2 position;\n" +
            "in vec2 uv;\n" +
            "out highp vec2 tex;\n" +
            "void main() { tex = uv; gl_Position = vec4(position, 0.0, 1.0); }\n"

    /**
     * Slice + present. Samples the RGBA8 guide quad, merges greens, looks up
     * the fitted 3x4 affine model by trilinear (x, y, luma), applies it, and
     * adds one-LSB interleaved-gradient-noise dither in display space.
     *
     * uScale = (lowW / s, lowH / s, 1 / r): guide-normalized coords to grid
     * cell units. uGridDim = (gw, gh, gz): cell units to normalized 3D
     * texture coords ((cell + 0.5) / dim hits texel centers).
     */
    fun fragmentShader(): String =
        "#version 300 es\n" +
            "precision mediump float;\n" +
            "precision highp int;\n" +
            "uniform lowp sampler2D uGuide;\n" +
            "uniform lowp sampler3D uGrid0;\n" +
            "uniform lowp sampler3D uGrid1;\n" +
            "uniform lowp sampler3D uGrid2;\n" +
            "uniform vec3 uScale;\n" +
            "uniform vec3 uGridDim;\n" +
            "in highp vec2 tex;\n" +
            "out vec4 fragColor;\n" +
            "highp float ign(highp vec2 p, highp float ch) {\n" +
            "  return fract(52.9829189 * fract(dot(p + ch * 19.19, vec2(0.06711056, 0.00583715))));\n" +
            "}\n" +
            "void main() {\n" +
            "  vec4 q = texture(uGuide, tex);\n" +
            "  vec3 g = vec3(q.r, (q.g + q.b) * 0.5, q.a);\n" +
            "  float luma = clamp(dot(g, vec3(0.25, 0.5, 0.25)), 0.0, 1.0);\n" +
            "  vec3 cell = vec3(tex.x * uScale.x, tex.y * uScale.y, luma * uScale.z);\n" +
            "  vec3 n = (cell + vec3(0.5)) / uGridDim;\n" +
            "  vec4 r0 = texture(uGrid0, n);\n" +
            "  vec4 r1 = texture(uGrid1, n);\n" +
            "  vec4 r2 = texture(uGrid2, n);\n" +
            "  vec4 gh = vec4(g, 1.0);\n" +
            "  vec3 developed = vec3(dot(gh, r0), dot(gh, r1), dot(gh, r2));\n" +
            "  vec3 enc = clamp(developed, 0.0, 1.0);\n" +
            "  highp vec2 p = gl_FragCoord.xy;\n" +
            "  enc += vec3(ign(p, 0.0) - 0.5, ign(p, 1.0) - 0.5, ign(p, 2.0) - 0.5) / 255.0;\n" +
            "  fragColor = vec4(enc, 1.0);\n" +
            "}\n"

    /**
     * Packs [grid] into 3 RGBA16F 3D textures (one per matrix row). Texel
     * order is x-fastest (x + gw * (y + gh * z)), channels RGBA = the row's
     * 4 coefficients. Returns 3 ShortArrays of gw*gh*gz*4 half bits.
     */
    fun packGrid(grid: BguGrid): Array<ShortArray> {
        val texels = grid.gw * grid.gh * grid.gz
        return Array(3) { row ->
            ShortArray(texels * 4) { i ->
                val texel = i / 4
                val k = i % 4
                val x = texel % grid.gw
                val y = (texel / grid.gw) % grid.gh
                val z = texel / (grid.gw * grid.gh)
                floatToHalf(grid.at(x, y, z, row, k))
            }
        }
    }

    /**
     * Round-to-nearest f32 -> IEEE-754 half bits. NaN maps to +0 (a broken
     * fit coefficient must not poison the texture); infinities and overflow
     * saturate to +/-max half; subnormals flush to signed zero.
     */
    fun floatToHalf(value: Float): Short {
        if (value.isNaN()) return 0
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        if (!value.isFinite()) return (sign or 0x7bff).toShort()
        val exp = ((bits ushr 23) and 0xff) - 112
        if (exp >= 31) return (sign or 0x7bff).toShort()
        if (exp <= 0) return sign.toShort()
        val mantissa = bits and 0x7fffff
        val rounded = mantissa + 0x1000
        if (rounded and 0x00800000 != 0) {
            // Mantissa rounded up to 1.0: carry into the exponent.
            val carried = exp + 1
            if (carried >= 31) return (sign or 0x7bff).toShort()
            return (sign or (carried shl 10)).toShort()
        }
        return (sign or (exp shl 10) or (rounded ushr 13)).toShort()
    }
}
