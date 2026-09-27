// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

internal object RawBayerLayout {
    const val QUAD_REQUIRES_DEMOSAIC = "Quad-Bayer requires its dedicated demosaic path"
    /** CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT, inlined so this stays pure Kotlin and JVM-testable. */
    const val PIXEL_MODE_DEFAULT = 0
    /** CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, inlined likewise. */
    const val PIXEL_MODE_MAXIMUM_RESOLUTION = 1

    /**
     * True only for unbinned full-resolution grouped-mosaic buffers, which need remosaic (or the
     * dedicated Quad demosaic) instead of the regular Bayer path.
     *
     * Binned output is regular Bayer — the HAL already remosaiced and binned it — so any binned
     * evidence (`rawBinning == true` or default pixel mode) vetoes. Grouped-mosaic buffers only
     * arrive as unbinned full-resolution UHR output, which needs positive per-capture evidence
     * (`rawBinning == false` and/or maximum-resolution pixel mode) plus the grouped static CFA.
     * SENSOR_INFO_BINNING_FACTOR alone proves nothing: it is static (2x2 on every Quad-Bayer
     * sensor) and also describes already-binned regular-Bayer streams.
     */
    fun requiresRemosaic(
        rawBinning: Boolean?,
        ultraHighResolution: Boolean,
        sensorPixelMode: Int?,
        groupWidth: Int? = null,
        groupHeight: Int? = null
    ): Boolean {
        // Binned output is regular Bayer, never grouped mosaic.
        if (rawBinning == true || sensorPixelMode == PIXEL_MODE_DEFAULT) return false
        // Route to remosaic only with positive unbinned evidence; missing keys fail safe to Bayer.
        if (rawBinning != false && sensorPixelMode != PIXEL_MODE_MAXIMUM_RESOLUTION) return false
        if (!ultraHighResolution) return false
        return (groupWidth ?: 1) > 1 || (groupHeight ?: 1) > 1
    }
}
