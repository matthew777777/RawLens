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
 * Each grid cell measures HIGH-frequency energy at native pixel resolution: a
 * 4×4 green lattice (stride-2 inside one 8×8 Bayer patch at the cell center)
 * scored with mean Tenengrad energy (gx²+gy² from central differences).
 * Gradients between downsampled cells cannot work — at ~42 px spacing any
 * texture passes whether in focus or not — so the metric never leaves native
 * resolution. Green-only matches what the eye focuses on.
 *
 * Noise discipline: a black-mean floor gates pure-black cells, the threshold
 * sits ~2× above bright-area shot-noise energy, a one-step wash guard doubles
 * the threshold past [WASH_FRACTION], and [despeckle] drops isolated singles
 * (sensor noise spikes; real edges are contiguous).
 */
object RawFocusPeakingSampler {
    const val COLS = 96
    const val MIN_ROWS = 24
    const val MAX_ROWS = 96

    /** Bayer-pixels square sampled per cell; greens on a stride-2 lattice (4×4). */
    const val PATCH = 8

    /** Mean Tenengrad energy above which a cell counts as sharp. */
    const val EDGE_THRESHOLD = 0.008f

    /** Cells darker than this green mean are never sharp (pure-black noise gate). */
    const val BLACK_MEAN_FLOOR = 0.03f

    /** Pass fraction above which the threshold doubles once (wash guard). */
    const val WASH_FRACTION = 0.45f

    /** Grid rows holding cells ~square on any sensor aspect. */
    fun rowsFor(width: Int, height: Int, cols: Int = COLS): Int =
        (cols.toLong() * height / width.coerceAtLeast(1)).toInt().coerceIn(MIN_ROWS, MAX_ROWS)

    /**
     * Green-column start within an even-aligned patch row [y] for [cfa]:
     * GRBG/GBRG carry green where x-parity == y-parity, RGGB/BGGR where it
     * differs. The 4 patch greens sit at start, +2, +4, +6.
     */
    internal fun greenColStart(cfa: Int, y: Int): Int {
        val sameParity = cfa == 1 || cfa == 2
        return (y and 1) xor (if (sameParity) 0 else 1)
    }

    /**
     * Mean Tenengrad energy over the interior 2×2 of a 4×4 normalized green
     * grid (row-major): average gx²+gy² from central differences. Pure so the
     * focus metric stays unit-tested.
     */
    internal fun tenengrad(green4: FloatArray): Float {
        require(green4.size == 16) { "Tenengrad needs a 4×4 grid, got ${green4.size}" }
        var sum = 0f
        for (y in 1..2) for (x in 1..2) {
            val gx = green4[y * 4 + x + 1] - green4[y * 4 + x - 1]
            val gy = green4[(y + 1) * 4 + x] - green4[(y - 1) * 4 + x]
            sum += gx * gx + gy * gy
        }
        return sum / 4f
    }

    /**
     * Drops isolated single cells: a passing cell survives only with a passing
     * 8-neighbor. Photon-noise spikes are isolated; focused edges span cells.
     */
    internal fun despeckle(mask: BooleanArray, cols: Int, rows: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until rows) for (x in 0 until cols) {
            if (!mask[y * cols + x]) continue
            var keep = false
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until cols && ny in 0 until rows && mask[ny * cols + nx]) {
                    keep = true
                    break
                }
            }
            out[y * cols + x] = keep
        }
        return out
    }

    internal fun maskOf(
        energy: FloatArray,
        mean: FloatArray,
        cols: Int,
        rows: Int,
        threshold: Float = EDGE_THRESHOLD
    ): BooleanArray {
        require(energy.size == cols * rows) { "energy grid ${energy.size} != $cols×$rows" }
        require(mean.size == cols * rows) { "mean grid ${mean.size} != $cols×$rows" }
        var mask = thresholdMask(energy, mean, threshold)
        val total = cols * rows
        if (total > 0 && mask.count { it }.toFloat() / total > WASH_FRACTION) {
            mask = thresholdMask(energy, mean, threshold * 2f)
        }
        return despeckle(mask, cols, rows)
    }

    private fun thresholdMask(energy: FloatArray, mean: FloatArray, threshold: Float): BooleanArray =
        BooleanArray(energy.size) { i -> mean[i] >= BLACK_MEAN_FLOOR && energy[i] >= threshold }

    @Volatile private var diagLogged = false

    fun sample(image: Image, characteristics: CameraCharacteristics): FocusPeakingFrame? {
        val plane = image.planes.singleOrNull() ?: return null
        if (plane.pixelStride < 2 || image.width < PATCH || image.height < PATCH) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: return null
        if (cfa !in 0..3) return null
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.coerceAtLeast(1) ?: return null
        // One bulk copy per sampled row (same discipline as the histogram):
        // each band needs 4 green rows, then every cell indexes the arrays.
        // ~300 bulk copies + ~110k indexed reads per sample at 4 Hz.
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val limit = buffer.limit()
        val shortView = buffer.asShortBuffer()
        val shortCapacity = shortView.capacity()
        val bulkRows = plane.pixelStride == 2 && plane.rowStride % 2 == 0
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val blackLut = IntArray(4) { i -> black?.getOffsetForIndex(i % 2, i / 2) ?: 0 }
        val invLut = DoubleArray(4) { i -> 1.0 / (white - blackLut[i]).coerceAtLeast(1) }
        val cols = COLS
        val rows = rowsFor(image.width, image.height, cols)
        val energy = FloatArray(cols * rows)
        val mean = FloatArray(cols * rows)
        val bandRows = if (bulkRows) Array(4) { ShortArray(image.width) } else null
        val patch = FloatArray(16)
        for (row in 0 until rows) {
            val cy = ((row + 0.5f) * image.height / rows).toInt()
            val qy = (cy / 2 * 2).coerceIn(0, image.height - PATCH)
            var bandOk = bandRows != null
            if (bandRows != null) {
                for (k in 0..3) {
                    val start = (qy + k * 2) * (rowStride / 2)
                    if (start < 0 || start + image.width > shortCapacity) {
                        bandOk = false
                        break
                    }
                    // Indexed bulk get needs ShortBufferCompat: the absolute
                    // overload is missing below newer runtimes (API 30 crash).
                    ShortBufferCompat.getBulk(shortView, start, bandRows[k], 0, image.width)
                }
            }
            for (col in 0 until cols) {
                val cx = ((col + 0.5f) * image.width / cols).toInt()
                val qx = (cx / 2 * 2).coerceIn(0, image.width - PATCH)
                var patchMean = 0f
                for (ky in 0..3) {
                    val y = qy + ky * 2
                    val x0 = qx + greenColStart(cfa, y)
                    for (kx in 0..3) {
                        val x = x0 + kx * 2
                        val phase = ((y and 1) shl 1) or (x and 1)
                        val value: Int? = if (bandOk && bandRows != null) {
                            bandRows[ky][x].toInt() and 0xffff
                        } else {
                            val offset = y * rowStride + x * pixelStride
                            if (offset < 0 || offset + 1 >= limit) null
                            else buffer.getShort(offset).toInt() and 0xffff
                        }
                        val normalized = if (value == null) 0f
                        else (((value - blackLut[phase]).coerceAtLeast(0) * invLut[phase])
                            .coerceIn(0.0, 1.0)).toFloat()
                        patch[ky * 4 + kx] = normalized
                        patchMean += normalized
                    }
                }
                energy[row * cols + col] = tenengrad(patch)
                mean[row * cols + col] = patchMean / 16f
            }
        }
        val mask = maskOf(energy, mean, cols, rows)
        if (!diagLogged) {
            diagLogged = true
            var peak = 0f
            var sum = 0.0
            for (e in energy) {
                if (e > peak) peak = e
                sum += e
            }
            android.util.Log.i(
                "RawLensCamera",
                "Focus peaking first sample: grid=${cols}x$rows passes=${mask.count { it }} " +
                    "meanEnergy=${"%.5f".format(sum / energy.size)} peakEnergy=${"%.4f".format(peak)} " +
                    "threshold=$EDGE_THRESHOLD cfa=$cfa"
            )
        }
        val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return FocusPeakingFrame(cols, rows, mask, rotation, mirrored)
    }
}
