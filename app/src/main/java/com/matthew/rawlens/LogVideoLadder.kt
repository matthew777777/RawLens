// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Size

/**
 * Direct-Log-only resolution ladder: fewer sensor pixels for less power.
 * The encoder output stays 4K (the blit stretches); what changes is the
 * camera stream + Vulkan compute load, which is where the power goes.
 *
 * Rungs are fractions of the largest advertised RAW size, so the ladder
 * adapts to any sensor (including single-size sensors, where every rung
 * resolves to max). E.g. on a 4080x3060 sensor HIGH lands near the 0.7x
 * class (~8MP, roughly 40% fewer pixels for about the same log image).
 */
object LogVideoLadder {
    enum class Rung(val label: String, val fraction: Double) {
        /** Full sensor (open gate). */
        FULL("open-gate", 1.0),

        /** ~2/3 pixels (the 0.7x class on this sensor). */
        HIGH("high", 0.65),

        /** ~1/3 pixels. */
        BALANCED("balanced", 0.35),

        /** ~1/5 pixels, preview-grade gate. */
        EFFICIENT("efficient", 0.2),
    }

    /** Plain pixel dims: `android.util.Size` getters return 0 on JVM unit tests. */
    data class RawSize(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
    }

    /**
     * Largest advertised size at or under `max * fraction`. Falls back to
     * the largest size when nothing smaller exists. Pure and unit-tested.
     */
    fun resolveSizes(sizes: List<RawSize>, rung: Rung): RawSize {
        val sorted = sizes.sortedByDescending { it.pixels }
        if (sorted.isEmpty()) error("no sizes")
        val cap = (sorted.first().pixels * rung.fraction).toLong()
        return sorted.firstOrNull { it.pixels <= cap } ?: sorted.first()
    }

    /** Android-facing wrapper returning the original [Size] object. */
    fun resolve(sizes: List<Size>, rung: Rung): Size {
        val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
        if (sorted.isEmpty()) error("no sizes")
        val target = resolveSizes(sorted.map { RawSize(it.width, it.height) }, rung)
        return sorted.first { it.width == target.width && it.height == target.height }
    }

    /** Distinct ladder decisions for the given sensor (dedupes single-size sensors). */
    fun distinctRungs(sizes: List<Size>): List<Pair<Rung, Size>> {
        if (sizes.isEmpty()) return emptyList()
        val seen = LinkedHashMap<String, Pair<Rung, Size>>()
        for (rung in Rung.entries) {
            val size = resolve(sizes, rung)
            seen.getOrPut("${size.width}x${size.height}") { rung to size }
        }
        return seen.values.toList()
    }

    /** One heat-degrade step: heat changes quality, never survival. */
    data class HeatDecision(val rung: Rung, val step: Int, val preferMhc: Boolean)

    /**
     * Single degrade step for a hot device: drop one rung when that
     * yields strictly fewer pixels, else fall to the cheap superpixel
     * path (computeStep only affects that path, so fused + a
     * single-size sensor degrades by switching path, not rung). Pure
     * and unit-tested; the caller decides the thermal trigger.
     */
    fun heatDegrade(sizes: List<RawSize>, rung: Rung, step: Int, preferMhc: Boolean): HeatDecision {
        val order = Rung.entries
        val idx = order.indexOf(rung)
        if (idx in 0 until order.lastIndex) {
            val down = order[idx + 1]
            if (resolveSizes(sizes, down).pixels < resolveSizes(sizes, rung).pixels) {
                return HeatDecision(down, step, preferMhc)
            }
        }
        return HeatDecision(rung, maxOf(step, 4), false)
    }

    /** Megapixels for logs and file naming. */
    fun megapixels(width: Int, height: Int): Double = width.toLong() * height / 1e6

    /** Android-facing wrapper. */
    fun megapixels(size: Size): Double = megapixels(size.width, size.height)

    /**
     * Compute geometry for a superpixel step: same full-frame FOV, fewer
     * invocations (step 4 = 1/4 the work of step 2). Mirrors the
     * viewfinder's long-edge budget trick when the sensor exposes no
     * binned RAW sizes (this MTK exposes exactly one: 4080x3060).
     */
    fun exportDims(raw: RawSize, step: Int): RawSize {
        require(step >= 2 && step % 2 == 0) { "step must be even and >= 2" }
        return RawSize(raw.width / step, raw.height / step)
    }

    /** Center-crop rectangle for aspect-correct output (all even: Bayer phase). */
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * Center 16:9 region of the sensor at full width: the blit stretches
     * texture->surface, so unstretched output needs a 16:9 compute region,
     * not a 4:3 one. Even origin dims preserve CFA phase; even height keeps
     * every row-pair whole. Pure and unit-tested.
     */
    fun centerCrop16x9(sensorW: Int, sensorH: Int): Crop {
        require(sensorW % 2 == 0 && sensorH % 2 == 0) { "odd sensor" }
        val h = (sensorW * 9 / 16 / 2) * 2
        val top = ((sensorH - h) / 2 / 2) * 2
        return Crop(0, top, sensorW, h)
    }

    /**
     * Center 16:9 window capped at 4K (even origin/dims, Bayer phase
     * preserved): on sensors wider than 3840 the record path takes a
     * true 3840x2160 window — 1:1 with the encode, so the fused stage
     * runs no fractional resample and no duplicate MHC evals — instead
     * of a full-width crop (~6% linear crop-in, same center). Smaller
     * sensors keep the full-width fit. Pure and unit-tested.
     */
    fun centerCropCapped16x9(sensorW: Int, sensorH: Int, maxW: Int = 3840, maxH: Int = 2160): Crop {
        require(sensorW % 2 == 0 && sensorH % 2 == 0) { "odd sensor" }
        val full = centerCrop16x9(sensorW, sensorH)
        if (full.width <= maxW && full.height <= maxH) return full
        var w = (minOf(full.width, maxW) / 2) * 2
        var h = (w * 9 / 16 / 2) * 2
        if (h > maxH) {
            h = (maxH / 2) * 2
            w = (h * 16 / 9 / 2) * 2
        }
        val left = ((sensorW - w) / 2 / 2) * 2
        val top = ((sensorH - h) / 2 / 2) * 2
        return Crop(left, top, w, h)
    }
}
