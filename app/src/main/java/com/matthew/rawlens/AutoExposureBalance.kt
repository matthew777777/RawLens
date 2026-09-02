// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.roundToInt
import kotlin.math.ln
import kotlin.math.pow

internal data class ExposureBalanceLimits(
    val isoMin: Int,
    val isoMax: Int,
    val shutterMinNanos: Long,
    val shutterMaxNanos: Long,
    /** PhotonCamera Photo mode begins its low-light extension at 1/30 s, then ramps to the cap. */
    val shutterStartNanos: Long = shutterMaxNanos
)

internal data class ExposureBalanceResult(
    val iso: Int,
    val shutterNanos: Long,
    val isoLimited: Boolean,
    val shutterLimited: Boolean
)

/** Shutter-first, dual-axis exposure balance ported from PhotonCamera's dev exposure curve. */
internal object AutoExposureBalance {
    fun apply(
        meteredIso: Int,
        meteredShutterNanos: Long,
        multiplier: Float,
        limits: ExposureBalanceLimits
    ): ExposureBalanceResult {
        val energy = meteredIso.toDouble() * meteredShutterNanos
        val balance = multiplier.coerceIn(0.25f, 4f).toDouble()
        // Port of PhotonCamera Photo's dynamic low-light curve: as the scene gets darker, the
        // usable per-frame cap moves geometrically from its start point (normally 1/30 s) to the
        // configured ceiling (normally 1/15 s), over four stops of extra exposure demand.
        val capStart = limits.shutterStartNanos.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        val energyAtStart = capStart.toDouble() * limits.isoMin
        val dynamicCap = if (energy <= energyAtStart || limits.shutterMaxNanos == capStart) {
            capStart.toDouble()
        } else {
            val stopsPastStart = ln(energy / energyAtStart) / ln(2.0)
            val ramp = (stopsPastStart / PHOTON_CAP_RAMP_STOPS).coerceIn(0.0, 1.0)
            capStart.toDouble() * (limits.shutterMaxNanos.toDouble() / capStart).pow(ramp)
        }
        // Shutter has first claim on the metered energy. Gain rises only once this moving cap is
        // reached, matching IsoExpoSelector's Photo-mode policy.
        var shutter = (energy / limits.isoMin)
            .coerceIn(limits.shutterMinNanos.toDouble(), dynamicCap)
        var iso = energy / shutter

        // Balance is a deliberate trade: >1 favours a faster shutter; <1 favours lower gain.
        shutter /= balance
        iso *= balance
        var isoLimited = false
        var shutterLimited = false

        if (iso > limits.isoMax) {
            iso = limits.isoMax.toDouble()
            shutter = energy / iso
            isoLimited = true
        } else if (iso < limits.isoMin) {
            iso = limits.isoMin.toDouble()
            shutter = energy / iso
        }
        if (shutter > dynamicCap) {
            shutter = dynamicCap
            iso = energy / shutter
            shutterLimited = true
        } else if (shutter < limits.shutterMinNanos) {
            shutter = limits.shutterMinNanos.toDouble()
            iso = energy / shutter
        }
        if (iso > limits.isoMax) {
            iso = limits.isoMax.toDouble()
            isoLimited = true
        }
        if (iso < limits.isoMin) iso = limits.isoMin.toDouble()
        return ExposureBalanceResult(
            iso.roundToInt().coerceIn(limits.isoMin, limits.isoMax),
            shutter.toLong().coerceIn(limits.shutterMinNanos, dynamicCap.toLong()),
            isoLimited,
            shutterLimited
        )
    }

    private const val PHOTON_CAP_RAMP_STOPS = 4.0
}
