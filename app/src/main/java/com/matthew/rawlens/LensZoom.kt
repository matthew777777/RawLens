// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Zoom-factor labels ("0.6×", "1×", "3×") from the optical metric, focal length divided
 * by sensor width. Shared by the viewfinder switcher and the lens picker so both name a
 * lens the same way.
 */
internal object LensZoom {
    /** A conventional phone main camera: about 24 mm full-frame equivalent. */
    private const val MAIN_METRIC = 24f / 36f
    /** Equivalent focal lengths (mm) that can pass for the main camera. */
    private val MAIN_RANGE_MM = 19f..35f

    fun isKnown(metric: Float): Boolean = metric.isFinite() && metric > 0f && metric < 1_000f

    /**
     * Metric that counts as 1×: the rear lens nearest 24 mm equivalent, but only when it
     * really is a main-camera focal length. With only ultra-wide or only tele lenses in
     * [rearMetrics] the nominal 24 mm is used, so an ultra-wide is never promoted to 1×.
     */
    fun baseline(rearMetrics: Collection<Float>): Float {
        val nearest = rearMetrics.filter(::isKnown).minByOrNull { abs(it - MAIN_METRIC) }
        return if (nearest != null && nearest * 36f in MAIN_RANGE_MM) nearest else MAIN_METRIC
    }

    /** "?" marks a lens whose optics the camera does not report, unless it is the only one. */
    fun label(metric: Float, rearMetrics: Collection<Float>): String {
        if (!isKnown(metric)) return if (rearMetrics.size <= 1) "1×" else "?"
        val zoom = metric / baseline(rearMetrics)
        return if (abs(zoom - round(zoom)) < 0.08f && round(zoom) >= 1f) "${round(zoom).toInt()}×"
        else String.format(Locale.US, "%.1f×", zoom)
    }
}
