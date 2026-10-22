// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * RAW-based ETTR single-exposure state. The repeating preview keeps running
 * under hardware AE; every sampled RAW frame refreshes this pair, which the
 * next still capture (and HDR bracket base) uses instead of the Camera2
 * meter. Camera thread only, except [settings].
 *
 * The correction is Magic Lantern's auto ETTR ([MagicLanternEttr]); the
 * shutter-first / ISO-second split and the gyro handheld ceiling are
 * RawLens's ([RawEttrMeter.solve]). The controller keeps the mode/capture
 * gates, the camera ranges, the gyro ceiling and the noise profile.
 */
internal class EttrLoop(initialSettings: EttrSettings) {
    @Volatile var settings: EttrSettings = initialSettings
    var iso: Int? = null
    var shutterNanos: Long? = null
    var converged = false
    var hottest = Float.NaN
    /** Measured clipped fractions (hottest/second) of the latest sampled frame. */
    var clipHot = Float.NaN
    var clipSecond = Float.NaN
    var lastUpdateMs = Long.MIN_VALUE
    /** Magic Lantern's per-percentile memory from the last non-clipped frame. */
    private val memory = MagicLanternEttr.Memory()

    /** Drops the converged ETTR pair; the enabled preference is preserved. */
    fun clearLive() {
        iso = null
        shutterNanos = null
        converged = false
        hottest = Float.NaN
        clipHot = Float.NaN
        clipSecond = Float.NaN
        memory.diffFromLower.fill(0.0)
    }

    /** One sample against the metered baseline pair and the camera's ranges. */
    fun update(
        sample: EttrRawSample,
        baselineIso: Int,
        baselineShutter: Long,
        isoLower: Int,
        isoUpper: Int,
        shutterLower: Long,
        shutterUpper: Long,
        safeShutterNanos: Long,
        dynamicRangeEv: Double = Double.NaN,
    ) {
        val levels = sample.levels
        val quads = sample.quads ?: return
        updateClipState(sample)
        val shift = MagicLanternEttr.correction(
            quads, memory, -settings.headroomEv.toDouble(), dynamicRangeEv
        ).correctionEv
        val result = RawEttrMeter.solve(
            baselineIso,
            baselineShutter,
            shift,
            EttrLimits(
                isoMin = isoLower,
                isoMax = settings.isoLimit.takeIf { it > 0 }
                    ?.coerceIn(isoLower, isoUpper) ?: isoUpper,
                shutterMinNanos = shutterLower,
                shutterMaxNanos = shutterUpper,
                safeShutterNanos = safeShutterNanos
            ),
            hottestChannel = levels.hottest
        )
        iso = result.iso
        shutterNanos = result.shutterNanos
        converged = result.converged
        hottest = levels.hottest
    }

    /**
     * Hottest/second measured clipped fractions for the ETTR debug readout, judged
     * over R, pooled green, B — both greens are one color for reconstruction.
     */
    private fun updateClipState(sample: EttrRawSample) {
        fun fraction(saturated: Int, total: Int): Float =
            if (total > 0) saturated.toFloat() / total else 0f
        val greenTotal = sample.totals.getOrElse(1) { 0 } + sample.totals.getOrElse(2) { 0 }
        val greenSat = sample.saturated.getOrElse(1) { 0 } + sample.saturated.getOrElse(2) { 0 }
        val colors = floatArrayOf(
            fraction(sample.saturated.getOrElse(0) { 0 }, sample.totals.getOrElse(0) { 0 }),
            fraction(greenSat, greenTotal),
            fraction(sample.saturated.getOrElse(3) { 0 }, sample.totals.getOrElse(3) { 0 })
        )
        var hottest = 0f
        var second = 0f
        for (value in colors) {
            if (value > hottest) {
                second = hottest
                hottest = value
            } else if (value > second) {
                second = value
            }
        }
        clipHot = hottest
        clipSecond = second
    }
}
