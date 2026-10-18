package com.rawlens.srpipeline.stages

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Generalized Anscombe Transform (noise GAT) stage.
 *
 * Mirrors `gat` / `cpu_GAT` in `handheld_super_resolution/utils_image.py`:
 * input 2D mosaiced raw `[H, W]` float image plus `alpha`/`beta` noise-model
 * parameters in EXIF plane order `[R, G1, B, G2]`, output `[H, W]` float
 * variance-stabilized image.
 *
 * Per-pixel plane mapping (spatial RGGB mosaic to EXIF planes):
 * `x` even / `y` even -> R (`alpha[0]`), `x` odd / `y` even -> G1
 * (`alpha[1]`), `x` even / `y` odd -> G2 (`alpha[3]`), `x` odd / `y` odd ->
 * B (`alpha[2]`), i.e. `i = px + py * (3 - 2 * px)`.
 *
 * Transform: `VST = max(0, a * I + 3/8 * a^2 + b)`, `out = 2 / a *
 * sqrt(VST)`. Computed in double precision and rounded once to float,
 * mirroring the numba kernel (Python-float `alpha`/`beta` arithmetic).
 * Float-tolerant: the recorded envelope is ~9.8e-4 maxabs.
 */
object Gat {

    fun gat(image: Array<FloatArray>, alpha: FloatArray, beta: FloatArray): Array<FloatArray> {
        requireRectImage(image, "image")
        require(alpha.size == 4) { "alpha should be of length 4, got ${alpha.size}" }
        require(beta.size == 4) { "beta should be of length 4, got ${beta.size}" }
        require(alpha.all { it > 0 }) {
            "alpha should be positive, got ${alpha.toList()} (VST is ill defined and kernels would be wrong)"
        }
        val h = image.size
        val w = image[0].size
        return Array(h) { y ->
            FloatArray(w) { x ->
                val px = x and 1
                val py = y and 1
                val i = px + py * (3 - 2 * px)
                val a = alpha[i].toDouble()
                val b = beta[i].toDouble()
                val vst = max(0.0, a * image[y][x].toDouble() + 3.0 / 8.0 * a * a + b)
                (2.0 / a * sqrt(vst)).toFloat()
            }
        }
    }
}
