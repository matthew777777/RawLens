package com.matthew.rawlens

/** Camera2 raster-order S/O pairs to DNG RGB planes, following Android DngCreator. */
internal object DngNoiseProfile {
    fun toRgb(values: DoubleArray?, sensorPattern: BayerPattern): DoubleArray? {
        if (values == null || values.size !in setOf(6, 8)) return null
        if (values.any { !it.isFinite() } || values.indices.any {
                if (it % 2 == 0) values[it] <= 0.0 else values[it] < 0.0
            }) return null
        // Six-value overrides already describe DNG R/G/B planes.
        if (values.size == 6) return values.copyOf()
        val rgb = DoubleArray(6)
        for (cell in 0..3) {
            val plane = sensorPattern.colorAt(cell and 1, cell shr 1).ordinal
            // Keep the complete pair with the larger slope, including its intercept.
            if (values[cell * 2] > rgb[plane * 2]) {
                rgb[plane * 2] = values[cell * 2]
                rgb[plane * 2 + 1] = values[cell * 2 + 1]
            }
        }
        return rgb
    }
}
