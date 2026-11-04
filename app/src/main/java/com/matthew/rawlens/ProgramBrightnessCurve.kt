// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.ln

/**
 * Exposure-normalized scene brightness, not calibrated lux. Unlike raw pixel brightness,
 * this estimate remains constant as AE changes exposure in an unchanged linear scene.
 * Adaptive tuning is experimental and has not been calibrated per device.
 */
internal object ProgramBrightnessCurve {
    data class Tuning(val target: Double, val constraintScale: Double)
    private val scenePoints = doubleArrayOf(0.1, 1.0, 16.0, 256.0)

    fun evaluate(mode: ProgramBrightnessTuning, mean: Double, iso: Int, shutterNanos: Long,
                 profile: ProgramAeProfile = ProgramAeProfile()): Tuning {
        val targets = doubleArrayOf(profile.darkTarget, profile.dimTarget, profile.normalTarget, profile.brightTarget)
        if (mode == ProgramBrightnessTuning.FIXED || !mean.isFinite() || mean < 0 || iso <= 0 || shutterNanos <= 0)
            return Tuning(ProgramAgc.Y_TARGET, 1.0)
        val scene = mean / ((iso / 100.0) * (shutterNanos / 1e9))
        val target = when {
            scene <= scenePoints.first() -> targets.first()
            scene >= scenePoints.last() -> targets.last()
            else -> {
                val hi = scenePoints.indexOfFirst { it >= scene }
                val lo = hi - 1
                val fraction = ln(scene / scenePoints[lo]) / ln(scenePoints[hi] / scenePoints[lo])
                targets[lo] + fraction * (targets[hi] - targets[lo])
            }
        }
        // Scale the lower histogram constraint too, otherwise it can undo the dark-scene target.
        return Tuning(target, target / ProgramAgc.Y_TARGET)
    }
}
