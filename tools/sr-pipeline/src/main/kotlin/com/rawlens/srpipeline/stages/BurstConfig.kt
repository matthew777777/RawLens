package com.rawlens.srpipeline.stages

/**
 * Burst-run configuration finalisation: SNR estimation, SNR-based
 * parameter updates, and shape validation.
 *
 * Mirrors `estimate_image_snr`, `update_snr_config`, `sanitize_config`,
 * and `lerp` in `handheld_super_resolution/{utils_image,params}.py`.
 * All arithmetic is double precision, evaluated in the same element
 * order as the numpy originals (plane order R,G1,G2,B; concatenated
 * head-to-tail). One deliberate caveat: numpy's `sum` uses pairwise
 * summation while this port accumulates head-to-tail, so the SNR can
 * differ in the last ulp — only an exact hit on a tile-size decision
 * boundary (14/22 dB) or a lerp endpoint could change the outcome, and
 * the CLI prints the SNR so such a hit is visible. Anything that would
 * `raise`/`warn` in Python throws/warns here the same way.
 */
object BurstConfig {

    /** SNR-based tile-size decision, mirroring `update_snr_config`. */
    fun tileSizeForSnr(snr: Double): Int = when {
        snr <= 14 -> 64
        snr <= 22 -> 32
        else -> 16
    }

    /** `lerp(x, [6,30] -> [y0,y1])`, clamped, mirroring `params.lerp`. */
    fun lerp(snr: Double, y0: Double, y1: Double): Double {
        val t = ((snr - 6.0) / 24.0).coerceIn(0.0, 1.0)
        return y0 + (y1 - y0) * t
    }

    /**
     * `estimate_image_snr`: per-plane `(x in (0,1))` mask, `sqrt(sum
     * top^2 / sum var)`, `20 * log10`. `image` is the normalised RGGB
     * raster; alpha/beta are the R,G1,B,G2 profile (doubles).
     */
    fun estimateSnr(image: Array<FloatArray>, alpha: DoubleArray, beta: DoubleArray): Double {
        require(alpha.size == 4 && beta.size == 4) { "BurstConfig: need R,G1,B,G2 alpha/beta" }
        val h = image.size
        val w = image[0].size
        var sumTop = 0.0
        var sumBot = 0.0
        // Plane order R, G1, G2, B, mirroring the concatenate order.
        for (py in 0 until h / 2) for (px in 0 until w / 2) {
            val r = image[2 * py][2 * px].toDouble()
            if (r > 0 && r < 1) {
                sumTop += r * r
                sumBot += alpha[0] * r + beta[0]
            }
        }
        for (py in 0 until h / 2) for (px in 0 until w / 2) {
            val g = image[2 * py][2 * px + 1].toDouble()
            if (g > 0 && g < 1) {
                sumTop += g * g
                sumBot += alpha[1] * g + beta[1]
            }
        }
        for (py in 0 until h / 2) for (px in 0 until w / 2) {
            val g = image[2 * py + 1][2 * px].toDouble()
            if (g > 0 && g < 1) {
                sumTop += g * g
                sumBot += alpha[3] * g + beta[3]
            }
        }
        for (py in 0 until h / 2) for (px in 0 until w / 2) {
            val b = image[2 * py + 1][2 * px + 1].toDouble()
            if (b > 0 && b < 1) {
                sumTop += b * b
                sumBot += alpha[2] * b + beta[2]
            }
        }
        return 20.0 * kotlin.math.log10(kotlin.math.sqrt(sumTop / sumBot))
    }

    /** Finalised per-burst parameters (tile size + kernel shaping). */
    data class Final(
        val snr: Double,
        val tileSize: Int,
        val tileSizes: List<Int>,
        val kDetail: Double,
        val kDenoise: Double,
        val dTh: Double,
        val dTr: Double,
    )

    /**
     * `update_snr_config` for SNR-based fields: tile size (+ the
     * `[1,1,1,0.5]` tile-size factors) and the four lerped kernel
     * params. `snr` is clipped to [6,30] like the Python (which warns).
     */
    fun finalize(snr: Double): Final {
        val clipped = snr.coerceIn(6.0, 30.0)
        val tileSize = tileSizeForSnr(clipped)
        return Final(
            snr = clipped,
            tileSize = tileSize,
            tileSizes = listOf(tileSize, tileSize, tileSize, (tileSize * 0.5).toInt()),
            kDetail = lerp(clipped, 0.33, 0.25),
            kDenoise = lerp(clipped, 5.0, 3.0),
            dTh = lerp(clipped, 0.81, 0.71),
            dTr = lerp(clipped, 1.24, 1.0),
        )
    }

    /**
     * `sanitize_config` shape leg: scale >= 1, then the padded image
     * (`ts*ceil(im/ts)` with the base tile size) must tile every
     * pyramid level (floored dims, `n_tiles >= 1` per level).
     */
    fun sanitize(scale: Float, baseTileSize: Int, factors: List<Int>, tileSizes: List<Int>, h: Int, w: Int) {
        require(scale >= 1f) { "BurstConfig: scale must be >= 1" }
        var ly = baseTileSize * ((h + baseTileSize - 1) / baseTileSize)
        var lx = baseTileSize * ((w + baseTileSize - 1) / baseTileSize)
        for ((lvl, f) in factors.withIndex()) {
            ly = kotlin.math.floor(ly / f.toDouble()).toInt()
            lx = kotlin.math.floor(lx / f.toDouble()).toInt()
            val ts = tileSizes[lvl]
            require(ly / ts >= 1 && lx / ts >= 1) {
                "BurstConfig: image ${h}x$w incompatible with tile size $ts at level $lvl"
            }
        }
    }
}
