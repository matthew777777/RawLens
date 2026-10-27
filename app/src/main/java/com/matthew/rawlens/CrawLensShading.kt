// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.floor

/**
 * Per-frame `lensShadingMap` for RAW video takes.
 *
 * On-disk schema (CRS): each frame JSON may carry `lensShadingMapWidth` /
 * `lensShadingMapHeight` plus `lensShadingMap` as four nested color planes
 * (R, Gr, Gb, B), each `Width x Height` row-major gains. Readers bake the
 * correction into DNG output and ignore a missing or malformed map, so takes without the keys keep working everywhere.
 *
 * Planes are color order, NOT CFA tile order: pixels map to planes through
 * `sensorArrangment`, with green in an R row reading Gr and green in a B row
 * reading Gb (see upstream `lensShadingChannelIndex()`). The HAL supplies
 * the same correction as a Camera2 lens-shading map in R, G-even, G-odd, B
 * order, so R/B copy directly and each green plane takes whichever HAL
 * green matches the sensor-row parity of its R/B rows.
 *
 * The grid covers the encoded (post-crop) frame with grid points at cell
 * centers; sampling follows the same corner-anchored bilinear convention as
 * [LensShadingModel.gainAt] on the HAL side and cell centers on the encoder
 * side.
 *
 * Pure Kotlin (no Camera2 types): the caller snapshots the HAL gains into a
 * [LensShadingSnapshot] and geometry into primitives. Every failure returns
 * null so the recorder skips embedding instead of writing a wrong map.
 */
internal object CrsLensShading {
    /** Grid backstop shared with `LensShading.h` (`kMaxGridExtent`). */
    const val MAX_EXTENT = 512

    /** Gain grid with four color-ordered gains (R, Gr, Gb, B) per point. */
    data class Grid(val columns: Int, val rows: Int, val gains: FloatArray)

    /**
     * Resample a HAL gain grid to the encoder schema.
     *
     * @param hal HAL map in Camera2 order (R, G-even, G-odd, B per point).
     * @param cfa Camera2 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` (0..3 Bayer).
     * @param active sensor rect the HAL grid spans (active array).
     * @param frameWidth/frameHeight full video frame; must equal the active
     *   size (encoded pixel `(x, y)` is sensor `(active.left + x, active.top + y)`).
     * @param cropTop/cropHeight encoded row window inside the frame
     *   ([VideoCrop.resolve]); the map covers this window.
     * @return color-ordered grid over the encoded window, or null when the
     *   map must not be embedded (bad content, geometry mismatch, oversize
     *   grid).
     */
    fun resample(
        hal: LensShadingSnapshot,
        cfa: Int,
        active: IntRectSnapshot,
        frameWidth: Int,
        frameHeight: Int,
        cropTop: Int,
        cropHeight: Int
    ): Grid? {
        val cols = hal.columns
        val rows = hal.rows
        if (cols < 1 || cols > MAX_EXTENT || rows < 1 || rows > MAX_EXTENT) return null
        val src = hal.gains.toFloatArray()
        if (src.size != rows * cols * 4) return null
        if (src.any { !it.isFinite() || it <= 0f }) return null
        val pattern = try {
            BayerPattern.fromCamera2(cfa)
        } catch (_: Exception) {
            return null
        }
        if (active.width <= 0 || active.height <= 0) return null
        if (frameWidth != active.width || frameHeight != active.height) return null
        if (cropTop < 0 || cropHeight <= 0 || cropTop + cropHeight > frameHeight) return null

        // Encoded pixel (x, y) sits at sensor (active.left + x, originY + y).
        val originX = active.left
        val originY = active.top + cropTop
        // Gr/Gb source channels. The decoder maps frame rows to R/B rows
        // through `sensorArrangment` (frame parity, no origin shift), so the
        // tile rows come from the bare arrangement: green in an R row reads
        // Gr, in a B row Gb. Each plane then takes whichever HAL green
        // (even/odd) matches the sensor-row parity of those frame rows.
        // Fixed for the whole grid.
        var redRow = -1
        var blueRow = -1
        for (ty in 0..1) for (tx in 0..1) {
            when (pattern.colorAt(tx, ty)) {
                CfaColor.RED -> redRow = ty
                CfaColor.BLUE -> blueRow = ty
                CfaColor.GREEN -> {}
            }
        }
        if (redRow < 0 || blueRow < 0) return null
        val grHal = if (((originY + redRow) and 1) == 0) 1 else 2
        val gbHal = if (((originY + blueRow) and 1) == 0) 1 else 2

        val out = FloatArray(rows * cols * 4)
        for (r in 0 until rows) {
            val ey = (r + 0.5) * cropHeight / rows
            for (c in 0 until cols) {
                val ex = (c + 0.5) * frameWidth / cols
                val sx = originX + ex
                val sy = originY + ey
                val base = (r * cols + c) * 4
                out[base] = sampleHal(src, cols, rows, 0, sx, sy, active)
                out[base + 1] = sampleHal(src, cols, rows, grHal, sx, sy, active)
                out[base + 2] = sampleHal(src, cols, rows, gbHal, sx, sy, active)
                out[base + 3] = sampleHal(src, cols, rows, 3, sx, sy, active)
            }
        }
        if (out.any { !it.isFinite() || it <= 0f }) return null
        return Grid(cols, rows, out)
    }

    /**
     * Serialize a grid to the `"lensShadingMapWidth": ..., ...` triplet for
     * splicing into the frame JSON, mirroring `buildLensShadingJson()`.
     * Kotlin `Float.toString` is shortest-round-trip and locale-independent,
     * so gains survive the JSON round-trip exactly (same values as the C++
     * `%.9g`, different but equivalent text).
     */
    fun toFrameJson(grid: Grid): String {
        require(grid.columns in 1..MAX_EXTENT && grid.rows in 1..MAX_EXTENT) {
            "Lens-shading grid extents out of range"
        }
        require(grid.gains.size == grid.rows * grid.columns * 4) {
            "Lens-shading gain count mismatch"
        }
        require(grid.gains.all { it.isFinite() && it > 0f }) {
            "Lens-shading gains must be finite and positive"
        }
        val planes = StringBuilder("\"lensShadingMapWidth\":${grid.columns}," +
            "\"lensShadingMapHeight\":${grid.rows},\"lensShadingMap\":[")
        val points = grid.rows * grid.columns
        for (ch in 0 until 4) {
            if (ch != 0) planes.append(',')
            planes.append('[')
            for (i in 0 until points) {
                if (i != 0) planes.append(',')
                planes.append(grid.gains[i * 4 + ch].toString())
            }
            planes.append(']')
        }
        planes.append(']')
        return planes.toString()
    }

    /** Corner-anchored bilinear sample of the HAL grid (same convention as [LensShadingModel.gainAt]). */
    private fun sampleHal(
        src: FloatArray,
        cols: Int,
        rows: Int,
        channel: Int,
        sx: Double,
        sy: Double,
        active: IntRectSnapshot
    ): Float {
        val nx = if (active.width == 1) 0.0 else (sx - active.left) / (active.width - 1).toDouble()
        val ny = if (active.height == 1) 0.0 else (sy - active.top) / (active.height - 1).toDouble()
        val mapX = nx.coerceIn(0.0, 1.0) * (cols - 1)
        val mapY = ny.coerceIn(0.0, 1.0) * (rows - 1)
        val x0 = floor(mapX).toInt()
        val y0 = floor(mapY).toInt()
        val x1 = minOf(x0 + 1, cols - 1)
        val y1 = minOf(y0 + 1, rows - 1)
        val tx = (mapX - x0).toFloat()
        val ty = (mapY - y0).toFloat()
        fun gain(row: Int, col: Int): Float = src[(row * cols + col) * 4 + channel]
        val top = gain(y0, x0) * (1f - tx) + gain(y0, x1) * tx
        val bottom = gain(y1, x0) * (1f - tx) + gain(y1, x1) * tx
        return top * (1f - ty) + bottom * ty
    }
}
