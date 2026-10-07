// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import java.nio.ByteOrder

/**
 * Focus-peaking overlay color, persisted by [preferenceValue]. Green matches the
 * native monitor overlay default (FocusPeakingParams.color); the rest are the
 * usual camera-assist primaries.
 */
enum class FocusPeakingColor(val preferenceValue: String, val label: String, val argb: Int) {
    GREEN("green", "GREEN", 0xFF4DFF1A.toInt()),
    RED("red", "RED", 0xFFFF2E2E.toInt()),
    YELLOW("yellow", "YELLOW", 0xFFFFF01A.toInt()),
    CYAN("cyan", "CYAN", 0xFF1AE8FF.toInt()),
    MAGENTA("magenta", "MAGENTA", 0xFFFF1AE8.toInt()),
    WHITE("white", "WHITE", 0xFFF2F2F2.toInt());

    fun next(): FocusPeakingColor = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): FocusPeakingColor =
            entries.firstOrNull { it.preferenceValue == value || it.name == value } ?: GREEN
    }
}

/**
 * Auto-show policy: peaking is a focusing assist, not a permanent overlay. It
 * appears only while the user is focusing — a manual focus distance is set, or
 * a tap-to-focus scan/lock is in flight — and only when the master switch is on.
 */
object FocusPeakingPolicy {
    fun shouldShow(enabled: Boolean, manualFocus: Boolean, touchFocusActive: Boolean): Boolean =
        enabled && (manualFocus || touchFocusActive)
}

/**
 * One peaking verdict in sensor coordinates: [mask] is row-major over a
 * [cols]×[rows] grid covering the full frame (same full-frame sampling as the
 * live histogram; the app never sets a scaler crop). [rotation]/[mirrored]
 * match the RAW viewfinder's UV transform (SENSOR_ORIENTATION / front-facing),
 * so the overlay view can map cells onto the displayed frame.
 */
data class FocusPeakingFrame(
    val cols: Int,
    val rows: Int,
    val mask: BooleanArray,
    val rotation: Int = 0,
    val mirrored: Boolean = false
)

/**
 * Sensor→view mapping, the inverse of the RAW viewfinder's UV transform
 * ([RawPreviewGeometry.sensorPoint]): where sensor UV (su,sv) appears on screen.
 * Rotations are multiples of 90°, so cells stay axis-aligned; mapping two
 * diagonal corners and taking min/max yields the view rect.
 */
object FocusPeakingGeometry {
    fun viewPoint(su: Float, sv: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> =
        when (rotation) {
            90 -> (if (mirrored) sv else 1f - sv) to su
            180 -> (if (mirrored) su else 1f - su) to 1f - sv
            270 -> (if (mirrored) 1f - sv else sv) to 1f - su
            else -> (if (mirrored) 1f - su else su) to sv
        }

    /** Normalized view rect [l,t,r,b] for sensor cell ([col],[row]). */
    fun viewCell(
        col: Int,
        row: Int,
        cols: Int,
        rows: Int,
        rotation: Int,
        mirrored: Boolean
    ): FloatArray {
        val (x0, y0) = viewPoint(
            col / cols.toFloat(), row / rows.toFloat(), rotation, mirrored
        )
        val (x1, y1) = viewPoint(
            (col + 1) / cols.toFloat(), (row + 1) / rows.toFloat(), rotation, mirrored
        )
        return floatArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }
}

/**
 * Samples sharpness from the sensor mosaic without demosaicing, on the same
 * repeating RAW stream (and ~4 Hz throttle) as the live histogram.
 *
 * Each grid cell averages the two green sites of its center Bayer quad, then a
 * central-difference gradient (|gx|+|gy|) over the green grid marks sharp cells.
 * Green-only keeps the read budget at ~14k pixels per sample and matches what
 * the eye focuses on; a black-mean floor suppresses pure-black noise and a
 * one-step wash guard doubles the threshold when over [WASH_FRACTION] of the
 * frame passes, so high-ISO grain never paints the whole screen.
 */
object RawFocusPeakingSampler {
    const val COLS = 96
    const val MIN_ROWS = 24
    const val MAX_ROWS = 96

    /** Normalized green-gradient sum (|gx|+|gy|) above which a cell counts as sharp. */
    const val EDGE_THRESHOLD = 0.15f

    /** Cells darker than this green mean are never sharp (pure-black noise gate). */
    const val BLACK_MEAN_FLOOR = 0.03f

    /** Interior pass fraction above which the threshold doubles once (wash guard). */
    const val WASH_FRACTION = 0.45f

    /** Grid rows holding cells ~square on any sensor aspect. */
    fun rowsFor(width: Int, height: Int, cols: Int = COLS): Int =
        (cols.toLong() * height / width.coerceAtLeast(1)).toInt().coerceIn(MIN_ROWS, MAX_ROWS)

    /** Green-site offsets within one 2×2 Bayer quad for [cfa]: dx0,dy0,dx1,dy1. */
    internal fun greenOffsets(cfa: Int): IntArray = when (cfa) {
        // GRBG / GBRG carry green on the diagonal, RGGB / BGGR off it.
        1, 2 -> intArrayOf(0, 0, 1, 1)
        else -> intArrayOf(1, 0, 0, 1)
    }

    /**
     * Gradient scores over a row-major [cols]×[rows] green grid: |gx|+|gy|
     * from central differences, 0 on the border. Pure so thresholds stay
     * unit-tested.
     */
    internal fun scoresOf(green: FloatArray, cols: Int, rows: Int): FloatArray {
        val scores = FloatArray(cols * rows)
        for (y in 1 until rows - 1) {
            val row = y * cols
            for (x in 1 until cols - 1) {
                val gx = green[row + x + 1] - green[row + x - 1]
                val gy = green[row + cols + x] - green[row - cols + x]
                scores[row + x] = kotlin.math.abs(gx) + kotlin.math.abs(gy)
            }
        }
        return scores
    }

    internal fun maskOf(
        green: FloatArray,
        cols: Int,
        rows: Int,
        threshold: Float = EDGE_THRESHOLD
    ): BooleanArray {
        require(green.size == cols * rows) { "green grid ${green.size} != $cols×$rows" }
        val scores = scoresOf(green, cols, rows)
        var mask = thresholdMask(scores, green, threshold)
        val interior = (cols - 2).coerceAtLeast(0) * (rows - 2).coerceAtLeast(0)
        if (interior > 0 && mask.count { it }.toFloat() / interior > WASH_FRACTION) {
            mask = thresholdMask(scores, green, threshold * 2f)
        }
        return mask
    }

    private fun thresholdMask(scores: FloatArray, green: FloatArray, threshold: Float): BooleanArray =
        BooleanArray(scores.size) { i -> green[i] >= BLACK_MEAN_FLOOR && scores[i] >= threshold }

    fun sample(image: Image, characteristics: CameraCharacteristics): FocusPeakingFrame? {
        val plane = image.planes.singleOrNull() ?: return null
        if (plane.pixelStride < 2 || image.width < 2 || image.height < 2) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: return null
        if (cfa !in 0..3) return null
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.coerceAtLeast(1) ?: return null
        // Cells are sparse across rows, so per-cell direct reads beat bulk row
        // copies here (the histogram's dense scan is the opposite trade). Same
        // LUT discipline otherwise: no per-pixel divisions or framework calls.
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val limit = buffer.limit()
        val blackLut = IntArray(4) { i -> black?.getOffsetForIndex(i % 2, i / 2) ?: 0 }
        val invLut = DoubleArray(4) { i -> 1.0 / (white - blackLut[i]).coerceAtLeast(1) }
        val greens = greenOffsets(cfa)
        val cols = COLS
        val rows = rowsFor(image.width, image.height, cols)
        val grid = FloatArray(cols * rows)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until rows) {
            val cy = ((row + 0.5f) * image.height / rows).toInt().coerceIn(0, image.height - 2)
            val qy = cy / 2 * 2
            for (col in 0 until cols) {
                val cx = ((col + 0.5f) * image.width / cols).toInt().coerceIn(0, image.width - 2)
                val qx = cx / 2 * 2
                var sum = 0.0
                var count = 0
                for (k in 0..1) {
                    val x = qx + greens[k * 2]
                    val y = qy + greens[k * 2 + 1]
                    val offset = y * rowStride + x * pixelStride
                    if (offset < 0 || offset + 1 >= limit) continue
                    val phase = ((y and 1) shl 1) or (x and 1)
                    val value = buffer.getShort(offset).toInt() and 0xffff
                    sum += ((value - blackLut[phase]).coerceAtLeast(0) * invLut[phase])
                        .coerceIn(0.0, 1.0)
                    count++
                }
                grid[row * cols + col] = if (count > 0) (sum / count).toFloat() else 0f
            }
        }
        val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return FocusPeakingFrame(cols, rows, maskOf(grid, cols, rows), rotation, mirrored)
    }
}
