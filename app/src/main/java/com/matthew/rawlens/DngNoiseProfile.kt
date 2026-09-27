package com.matthew.rawlens

/**
 * Camera2 raster-order S/O pairs to normalized DNG RGB planes, following Android
 * DngCreator's per-plane loudest-pair selection. Eight-coefficient inputs are
 * code-domain (`S*code + O`) and normalize per winning phase (`S/(W-b)`,
 * `(S*b + O)/(W-b)^2`) so the tag carries the normalized-domain model DNG
 * readers expect; six-coefficient inputs are already normalized and pass
 * through. Returns null — the tag is omitted, never fabricated — for null,
 * missized, non-finite, or non-positive-slope profiles, and for
 * eight-coefficient inputs without usable black/white levels.
 */
internal object DngNoiseProfile {
    fun toRgb(
        values: DoubleArray?,
        sensorPattern: BayerPattern,
        blackLevels: FloatArray? = null,
        whiteLevel: Float? = null
    ): DoubleArray? {
        if (values == null || values.size !in setOf(6, 8)) return null
        if (values.any { !it.isFinite() } || values.indices.any {
                if (it % 2 == 0) values[it] <= 0.0 else values[it] < 0.0
            }) return null
        // Six-value overrides already describe normalized DNG R/G/B planes.
        if (values.size == 6) return values.copyOf()
        val white = whiteLevel?.toDouble()
        if (blackLevels == null || blackLevels.size != 4 ||
            blackLevels.any { !it.isFinite() } || white == null || !white.isFinite() ||
            blackLevels.any { white <= it }) return null
        val rgb = DoubleArray(6)
        // Winning raster cell per plane: normalization needs its black level.
        val cellOf = IntArray(3) { -1 }
        for (cell in 0..3) {
            val plane = sensorPattern.colorAt(cell and 1, cell shr 1).ordinal
            // Keep the complete pair with the larger slope, including its intercept.
            if (values[cell * 2] > rgb[plane * 2]) {
                rgb[plane * 2] = values[cell * 2]
                rgb[plane * 2 + 1] = values[cell * 2 + 1]
                cellOf[plane] = cell
            }
        }
        for (plane in 0..2) {
            val cell = cellOf[plane]
            if (cell < 0) return null
            val black = blackLevels[cell].toDouble()
            val range = white - black
            val slope = rgb[plane * 2]
            rgb[plane * 2] = slope / range
            rgb[plane * 2 + 1] = (slope * black + rgb[plane * 2 + 1]) / (range * range)
        }
        return rgb
    }
}
