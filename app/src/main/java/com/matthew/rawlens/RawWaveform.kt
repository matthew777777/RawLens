// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.BlackLevelPattern
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Quick-tile scope selection. Tap toggles visibility of the selected graph;
 * long-press switches graphs (turning the scope on when it was off so the
 * switch is visible).
 */
data class ScopeUiState(val enabled: Boolean, val mode: ScopeMode) {
    fun onTap(): ScopeUiState = copy(enabled = !enabled)
    fun onLongPress(): ScopeUiState = copy(enabled = true, mode = mode.next())
}

/** Bottom scope display: classic histogram or RGB waveform parade. Tap the scope to switch. */
enum class ScopeMode {
    HISTOGRAM,
    WAVEFORM;

    fun next(): ScopeMode = if (this == HISTOGRAM) WAVEFORM else HISTOGRAM

    companion object {
        fun fromPreference(value: String?): ScopeMode =
            entries.firstOrNull { it.name == value } ?: HISTOGRAM
    }
}

/**
 * RGB waveform parade: per-column level densities. Index `column * levels + level`,
 * level 0 is black at the bottom. Values are linear RAW or AgX display-encoded
 * (JPEG preview) depending on [agxApplied].
 */
data class RgbWaveform(
    val columns: Int,
    val levels: Int,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val agxApplied: Boolean
)

/**
 * Samples the sensor mosaic into a column-preserving RGB parade without demosaicing;
 * green combines both green CFA sites like [RawHistogramSampler].
 *
 * Same live-sampling budget discipline as the histogram: runs on the camera callback
 * thread at ~4 Hz, bulk row reads, LUT-indexed pixels, no per-pixel divisions.
 */
object RawWaveformSampler {
    const val COLUMNS = 96
    const val LEVELS = 48

    /** Frame-column bin for a sensor pixel; out-of-range x clamps to the edges. */
    internal fun columnOf(x: Int, width: Int, columns: Int = COLUMNS): Int =
        ((x.toLong() * columns / width.coerceAtLeast(1)).toInt()).coerceIn(0, columns - 1)

    /**
     * Precomputed [columnOf] per sensor x: one division per column instead of
     * one per sampled pixel (the 64-bit division was the sampler's hottest
     * per-pixel cost on the camera thread).
     */
    internal fun columnLut(width: Int, columns: Int = COLUMNS): IntArray =
        IntArray(width.coerceAtLeast(1)) { x -> columnOf(x, width, columns) }

    /** Level bin for a display/linear value 0..1; out-of-range clamps to the ends. */
    internal fun levelOf(value: Double, levels: Int = LEVELS): Int =
        (value * (levels - 1)).toInt().coerceIn(0, levels - 1)

    fun sample(
        image: Image,
        characteristics: CameraCharacteristics,
        scopeLut: FloatArray? = null,
        /**
         * Scene-referred adaptive exposure (EV) applied as a linear gain
         * BEFORE the AgX curve, mirroring develop's pre-tonemap exposureEv so
         * the JPEG scope predicts the saved file. Honored only with a LUT;
         * the linear-RAW (DNG) path ignores it, staying bit-exact.
         */
        exposureEv: Double = 0.0,
        dynamicBlack: BlackLevelPattern? = null,
        dynamicWhite: Int? = null
    ): RgbWaveform? {
        val plane = image.planes.singleOrNull() ?: return null
        if (image.width < 2 || image.height < 2) return null
        if (plane.pixelStride < 2 && PackedRawFormat.ofInt(image.format) == null) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: return null
        if (cfa !in 0..3) return null
        val black = dynamicBlack ?: characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = (dynamicWhite?.takeIf { it > 0 }
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
            ?.coerceAtLeast(1) ?: return null
        return sample(image, plane, cfa, black, white, scopeLut, exposureEv)
    }

    /**
     * Characteristics-free core: the static [CameraCharacteristics] keys are
     * null under plain unit tests (android.jar stubs), so every
     * framework-touching read stays in the outer overload and the pixel loop
     * — including the scene-referred EV gain — stays unit-covered.
     */
    internal fun sample(
        image: Image,
        plane: Image.Plane,
        cfa: Int,
        black: BlackLevelPattern?,
        white: Int,
        scopeLut: FloatArray?,
        exposureEv: Double
    ): RgbWaveform? {
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val limit = buffer.limit()
        // Packed sessions sample native codes through a row-cached reader;
        // the black/white LUTs below are already native-scale, so the math
        // is identical. Bad geometry refuses the frame like before.
        val packedSampler = if (plane.pixelStride < 2) {
            val packed = PackedRawFormat.ofInt(image.format) ?: return null
            try {
                RawPackedUnpacker.PackedSampleReader(
                    buffer, image.width, image.height, plane.rowStride, 0, packed
                )
            } catch (_: IllegalArgumentException) {
                return null
            }
        } else null
        val shortView = buffer.asShortBuffer()
        val shortCapacity = shortView.capacity()
        val bulkRows = plane.pixelStride == 2 && plane.rowStride % 2 == 0
        val rowEven = if (bulkRows) ShortArray(image.width) else null
        val rowOdd = if (bulkRows) ShortArray(image.width) else null
        val blackLut = IntArray(4) { i -> black?.getOffsetForIndex(i % 2, i / 2) ?: 0 }
        val invLut = DoubleArray(4) { i -> 1.0 / (white - blackLut[i]).coerceAtLeast(1) }
        val colorLut = IntArray(4) { i -> colorAt(cfa, i % 2, i / 2) }
        // Hoisted out of the pixel loop: 2^EV is the scene-referred gain
        // develop applies pre-tonemap. Non-finite EV degrades to unity.
        val exposureGain = if (scopeLut != null && exposureEv.isFinite() && exposureEv != 0.0)
            2.0.pow(exposureEv) else 1.0
        val frameColumns = columnLut(image.width)
        val red = IntArray(COLUMNS * LEVELS)
        val green = IntArray(COLUMNS * LEVELS)
        val blue = IntArray(COLUMNS * LEVELS)
        val blocksWide = image.width / 2
        val blocksHigh = image.height / 2
        val blockStep = meterGridStep(blocksWide.toLong() * blocksHigh, SCOPE_TARGET_BLOCKS)

        var blockY = 0
        while (blockY < blocksHigh) {
            var bulkEven: ShortArray? = null
            var bulkOdd: ShortArray? = null
            if (bulkRows && rowEven != null && rowOdd != null) {
                val y0 = blockY * 2
                val start0 = y0 * (plane.rowStride / 2)
                val start1 = start0 + plane.rowStride / 2
                if (start0 >= 0 && start1 + image.width <= shortCapacity) {
                    // Indexed bulk get needs ShortBufferCompat: the absolute
                    // overload is missing below newer runtimes (API 30 crash).
                    ShortBufferCompat.getBulk(shortView, start0, rowEven, 0, image.width)
                    ShortBufferCompat.getBulk(shortView, start1, rowOdd, 0, image.width)
                    bulkEven = rowEven
                    bulkOdd = rowOdd
                }
            }
            var blockX = 0
            while (blockX < blocksWide) {
                for (dy in 0..1) for (dx in 0..1) {
                    val x = blockX * 2 + dx
                    val y = blockY * 2 + dy
                    val phase = ((y and 1) shl 1) or (x and 1)
                    val row = if (dy == 0) bulkEven else bulkOdd
                    val value: Int = if (row != null) {
                        row[x].toInt() and 0xffff
                    } else if (packedSampler != null) {
                        packedSampler.codeAt(x, y)
                    } else {
                        val offset = y * plane.rowStride + x * plane.pixelStride
                        if (offset + 1 >= limit) continue
                        buffer.getShort(offset).toInt() and 0xffff
                    }
                    val normalized = ((value - blackLut[phase]).coerceAtLeast(0) * invLut[phase])
                        .coerceIn(0.0, 1.0)
                    val curved = AgxDisplayTransform.scopeEncode(normalized, scopeLut, exposureGain)
                    val cell = frameColumns[x] * LEVELS + levelOf(curved)
                    when (colorLut[phase]) {
                        0 -> red[cell]++
                        1 -> green[cell]++
                        else -> blue[cell]++
                    }
                }
                blockX += blockStep
            }
            blockY += blockStep
        }
        return RgbWaveform(COLUMNS, LEVELS, red, green, blue, agxApplied = scopeLut != null)
    }

    private fun colorAt(cfa: Int, x: Int, y: Int): Int = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
            if (y == 0) if (x == 0) 0 else 1 else if (x == 0) 1 else 2
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
            if (y == 0) if (x == 0) 1 else 0 else if (x == 0) 2 else 1
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
            if (y == 0) if (x == 0) 1 else 2 else if (x == 0) 0 else 1
        else -> if (y == 0) if (x == 0) 2 else 1 else if (x == 0) 1 else 0
    }
}
