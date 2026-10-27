// Copyright 2019 Google LLC.
// SPDX-License-Identifier: Apache-2.0
package com.matthew.rawlens

/**
 * Kotlin port of TurboColormap GLSL, Anton Mikhailov / Ruofei Du.
 * https://gist.github.com/mikhailov-work/0d177465a8151eb6ede1768d51d476c7
 * revision f0b77bf03aa5fce5a3ebe25988fef36ec43f5096.
 * Changes: precompute Android ARGB8 entries and clamp polynomial overshoot on quantization.
 */
internal object TurboColormap {
    private val table = IntArray(256) { i ->
        val x = i / 255.0
        val x2 = x * x
        val x3 = x2 * x
        val x4 = x2 * x2
        val x5 = x3 * x2
        fun code(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        val r = code(0.13572138 + 4.61539260*x - 42.66032258*x2 + 132.13108234*x3 - 152.94239396*x4 + 59.28637943*x5)
        val g = code(0.09140261 + 2.19418839*x + 4.84296658*x2 - 14.18503333*x3 + 4.27729857*x4 + 2.82956604*x5)
        val b = code(0.10667330 + 12.64194608*x - 60.58204836*x2 + 110.36276771*x3 - 89.90310912*x4 + 27.34824973*x5)
        (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
    fun argb(level: Double): Int = table[(level.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()]
}
