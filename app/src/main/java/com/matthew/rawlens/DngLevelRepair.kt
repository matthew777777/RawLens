// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * Sampled black/white levels over a coarse grid: per-Bayer-phase minima in
 * Camera2 order (TL, TR, BL, BR) plus the global peak. Codes are raw u16.
 */
internal data class SampledLevels(val phaseMin: IntArray, val max: Int) {
    override fun equals(other: Any?): Boolean =
        other is SampledLevels && max == other.max && phaseMin.contentEquals(other.phaseMin)

    override fun hashCode(): Int = 31 * max + phaseMin.contentHashCode()
}

/**
 * Display/save levels when the HAL's reported black/white are missing or
 * insane (Vivo X300 Ultra default sensor mode: white=0 placeholders or
 * white <= black). Sampling the frame's own bytes keeps the tags
 * self-consistent with the payload: editors normalize file bytes by these
 * tags, so a sampled pair renders correctly while a garbage pair writes a
 * black DNG (or fails the save outright).
 *
 * Pure JVM (ByteBuffer only): host-tested, no Android framework types.
 */
internal object DngLevelRepair {
    /** Standard sensor saturation ceilings, ascending. */
    private val WHITE_CEILINGS = intArrayOf(1023, 4095, 16383, 65535)

    /** True when [black]/[white] cannot normalize and must be repaired. */
    fun needsRepair(black: FloatArray?, white: Float?): Boolean {
        if (black == null || white == null) return true
        return !VfLevels.isSane(black, white)
    }

    /**
     * Coarse min/max over ~3k buffer codes with per-phase minima; absolute
     * reads only. Null when the plane layout is unusable (hostile strides,
     * empty buffer) or nothing was readable. Never throws.
     */
    fun sample(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int
    ): SampledLevels? {
        if (pixelStride <= 0 || rowStride < 0 || width <= 0 || height <= 0) return null
        // Absolute gets are limit-bounded; every sample stays at or below
        // the last readable u16 even when stride claims exceed the store.
        val lastReadable = buffer.limit() - 2
        if (lastReadable < 0) return null
        val phaseMin = IntArray(4) { Int.MAX_VALUE }
        var max = Int.MIN_VALUE
        var n = 0L
        val stepX = maxOf(1, width / 64)
        val stepY = maxOf(1, height / 48)
        var y2 = 0
        while (y2 < height) {
            var x2 = 0
            while (x2 < width) {
                // Both parities per stride cell: a stride >= 2 from the
                // origin would otherwise never visit odd columns/rows and
                // two Bayer phases would stay unsampled on every real frame.
                for (dy in 0..1) {
                    val y = y2 + dy
                    if (y >= height) break
                    val rowBase = y.toLong() * rowStride.toLong()
                    if (rowBase > lastReadable) break
                    for (dx in 0..1) {
                        val x = x2 + dx
                        if (x >= width) break
                        val offset = rowBase + x.toLong() * pixelStride.toLong()
                        if (offset > lastReadable) break
                        val code = buffer.getShort(offset.toInt()).toInt() and 0xffff
                        val phase = ((y and 1) shl 1) or (x and 1)
                        if (code < phaseMin[phase]) phaseMin[phase] = code
                        if (code > max) max = code
                        n++
                    }
                }
                x2 += stepX
            }
            y2 += stepY
        }
        if (n == 0L || phaseMin.any { it == Int.MAX_VALUE }) return null
        return SampledLevels(phaseMin, max)
    }

    /**
     * Smallest standard saturation ceiling covering [maxObserved]. The true
     * ceiling is unknowable when the HAL lies; rounding up preserves
     * headroom semantics (no clip) and keeps burst frames on one rung.
     * Non-standard truths (Vivo DCG 8712) land on the next rung up,
     * unclipped and consistently across the burst.
     */
    fun ceilingWhite(maxObserved: Int): Float {
        for (ceiling in WHITE_CEILINGS) {
            if (maxObserved <= ceiling) return ceiling.toFloat()
        }
        return 65535f
    }

    /**
     * Full repair from a frame sample: per-phase black floors plus the
     * ceiling white. The output always satisfies [VfLevels.isSane] (a
     * perfectly uniform frame at exactly a ceiling rung still clears by
     * one code), so downstream Q6 conversion and tag writes never throw.
     */
    fun repair(sample: SampledLevels): Pair<FloatArray, Float> {
        val black = FloatArray(4) { sample.phaseMin[it].coerceIn(0, 65535).toFloat() }
        var blackMax = black[0]
        for (b in black) if (b > blackMax) blackMax = b
        val white = ceilingWhite(sample.max).coerceAtLeast(blackMax + 1f)
        return black to white
    }
}
