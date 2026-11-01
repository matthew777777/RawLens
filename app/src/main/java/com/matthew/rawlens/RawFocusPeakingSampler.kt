// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import java.nio.ByteOrder

/** RAW-only local detail detector; clipping/noise gates run before marks are generated. */
object RawFocusPeakingSampler {
    const val COLS = 128
    const val PATCH = FocusPeakingDetail.SIDE * 2
    fun rowsFor(width: Int, height: Int, cols: Int = COLS): Int =
        (cols.toLong() * height / width.coerceAtLeast(1)).toInt().coerceIn(24, 96)

    fun sample(image: Image, characteristics: CameraCharacteristics,
               result: CaptureResult? = null, levels: DngMetadataOverrides = DngMetadataOverrides(),
               preferNative: Boolean = true): FocusPeakingFrame? {
        val started = System.nanoTime()
        val plane = image.planes.singleOrNull() ?: return null
        if (image.width < PATCH || image.height < PATCH) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return null
        if (cfa !in 0..3) return null
        // Never apply a neighboring frame's noise/level metadata to these pixels.
        val matched = result?.takeIf { it.get(CaptureResult.SENSOR_TIMESTAMP) == image.timestamp }
        val staticBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val dynamicBlack = matched?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = FloatArray(4) { phase -> levels.blackLevels?.getOrNull(phase)?.toFloat()
            ?: dynamicBlack?.getOrNull(phase)
            ?: staticBlack?.getOffsetForIndex(phase and 1, phase shr 1)?.toFloat() ?: 0f }
        val white = levels.whiteLevel?.toFloat()
            ?: matched?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: return null
        if (!white.isFinite() || black.any { !it.isFinite() || white <= it }) return null
        val inverse = FloatArray(4) { 1f / (white - black[it]) }
        val profile = levels.noiseProfile?.takeIf { it.size == 8 }?.toDoubleArray()
            ?: if (levels.whiteLevel == null) matched?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { pairs ->
                DoubleArray(pairs.size * 2) { if (it % 2 == 0) pairs[it / 2].first else pairs[it / 2].second }
            } else null
        val buffer = plane.buffer.duplicate().slice().order(ByteOrder.nativeOrder())
        val rows = rowsFor(image.width, image.height)
        val packedBits = if (plane.pixelStride >= 2) 0 else
            PackedRawFormat.ofInt(image.format)?.bitsPerPixel ?: return null
        if (preferNative) NativeFocusPeaking.sample(buffer, image.width, image.height,
            plane.rowStride, plane.pixelStride, packedBits, cfa, black, white, profile, COLS, rows)?.let { edges ->
            val mask = BooleanArray(edges.size) { edges[it] >= 0 }
            val points = FloatArray(edges.size * 2)
            for (i in edges.indices) if (mask[i]) {
                points[2 * i] = (edges[i] % image.width + 0.5f) / image.width
                points[2 * i + 1] = (edges[i] / image.width + 0.5f) / image.height
            }
            return frame(image, characteristics, mask, points, rows, started, "native")
        }
        val packed = if (plane.pixelStride < 2) {
            val format = PackedRawFormat.ofInt(image.format) ?: return null
            try { RawPackedUnpacker.PackedSampleReader(buffer, image.width, image.height,
                plane.rowStride, 0, format) } catch (_: IllegalArgumentException) { return null }
        } else null
        val bulk = plane.pixelStride == 2 && plane.rowStride % 2 == 0
        val shorts = buffer.asShortBuffer()
        val band = Array(PATCH) { ShortArray(image.width) }
        val mask = BooleanArray(COLS * rows)
        val points = FloatArray(mask.size * 2)
        val green = FloatArray(FocusPeakingDetail.SIDE * FocusPeakingDetail.SIDE)
        val clipped = BooleanArray(green.size)
        val scratch = FocusPeakingDetail.Scratch()
        val sameParity = cfa == 1 || cfa == 2
        val g0 = if (sameParity) 0 else 1
        val g1 = if (sameParity) 3 else 2
        val quad = FloatArray(4)
        for (row in 0 until rows) {
            val centerY = ((row + 0.5f) * image.height / rows).toInt()
            val qy = ((centerY - PATCH / 2) / 2 * 2).coerceIn(0, (image.height - PATCH) / 2 * 2)
            for (dy in 0 until PATCH) {
                val y = qy + dy
                if (bulk) {
                    val start = y * (plane.rowStride / 2)
                    if (start < 0 || start + image.width > shorts.capacity()) return null
                    ShortBufferCompat.getBulk(shorts, start, band[dy], 0, image.width)
                } else for (x in 0 until image.width) {
                    // Decode packed rows in row order, once, instead of thrashing the row cache.
                    val value = if (packed != null) packed.codeAt(x, y) ?: return null else {
                        val offset = y.toLong() * plane.rowStride + x.toLong() * plane.pixelStride
                        if (offset < 0 || offset + 2 > buffer.limit()) return null
                        buffer.getShort(offset.toInt()).toInt() and 0xffff
                    }
                    band[dy][x] = value.toShort()
                }
            }
            for (col in 0 until COLS) {
                val centerX = ((col + 0.5f) * image.width / COLS).toInt()
                val qx = ((centerX - PATCH / 2) / 2 * 2).coerceIn(0, (image.width - PATCH) / 2 * 2)
                var mean = 0f
                for (gy in 0 until FocusPeakingDetail.SIDE) for (gx in 0 until FocusPeakingDetail.SIDE) {
                    var saturated = false
                    for (phase in 0..3) {
                        val dy = gy * 2 + (phase shr 1)
                        val x = qx + gx * 2 + (phase and 1)
                        val value = band[dy][x].toInt() and 0xffff
                        val normalized = (value - black[phase]) * inverse[phase]
                        if (normalized >= FocusPeakingDetail.CLIP_LEVEL) saturated = true
                        quad[phase] = normalized.coerceIn(0f, 1f)
                    }
                    val i = gy * FocusPeakingDetail.SIDE + gx
                    green[i] = (quad[g0] + quad[g1]) * 0.5f
                    clipped[i] = saturated
                    mean += green[i]
                }
                mean /= green.size
                var variance = 0f
                if (profile != null && profile.size == 8) for (phase in 0..3) {
                    val s = profile[phase * 2]; val o = profile[phase * 2 + 1]
                    if (s.isFinite() && o.isFinite() && s >= 0 && o >= 0)
                        variance = maxOf(variance, (s * mean + o).toFloat())
                }
                val edge = FocusPeakingDetail.detect(green, clipped, variance, scratch)
                if (edge >= 0) {
                    val cell = row * COLS + col
                    mask[cell] = true
                    points[cell * 2] = (qx + (edge % FocusPeakingDetail.SIDE) * 2 + 0.5f) / image.width
                    points[cell * 2 + 1] = (qy + (edge / FocusPeakingDetail.SIDE) * 2 + 0.5f) / image.height
                }
            }
        }
        return frame(image, characteristics, mask, points, rows, started)
    }

    private fun frame(image: Image, characteristics: CameraCharacteristics, mask: BooleanArray,
                      points: FloatArray, rows: Int, started: Long, backend: String = "kotlin"): FocusPeakingFrame {
        val elapsedMs = (System.nanoTime() - started) / 1e6
        if (elapsedMs > 200) Log.w("RawLensCamera", "Peaking detail sample ${"%.1f".format(elapsedMs)}ms; worker drops backlog")
        return FocusPeakingFrame(COLS, rows, mask,
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT,
            image.width, image.height, sensorPoints = points, timestampNanos = image.timestamp,
            samplingNanos = System.nanoTime() - started, backend = backend)
    }
}
