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

/** Shutter-first, dual-axis exposure balance ported from PhotonCamera's dev exposure curve.
 *
 * Custom PROGRAM AE distributes metered energy (ISO × shutter) directly to sensor
 * controls — never digital gain — with a shutter-vs-ISO priority balance, per-lens
 * lower/upper bounds, and single-axis locks (ISO-only / shutter-only auto).
 */
internal object AutoExposureBalance {
    fun apply(
        meteredIso: Int,
        meteredShutterNanos: Long,
        multiplier: Float,
        limits: ExposureBalanceLimits
    ): ExposureBalanceResult = applyInternal(
        meteredIso.toDouble() * meteredShutterNanos, multiplier.coerceIn(0.25f, 4f),
        limits, ProgramLockMode.NONE, 0, 0L
    )

    /**
     * PROGRAM profile entry point. [balance01] is the UI slider 0..1
     * (0 = ISO priority, 0.5 = balanced, 1 = shutter priority).
     */
    fun applyProgram(
        meteredIso: Int,
        meteredShutterNanos: Long,
        balance01: Float,
        limits: ExposureBalanceLimits,
        lockMode: ProgramLockMode = ProgramLockMode.NONE,
        lockedIso: Int = 0,
        lockedShutterNanos: Long = 0L
    ): ExposureBalanceResult = applyInternal(
        meteredIso.toDouble() * meteredShutterNanos,
        ProgramAeProfile.balanceToMultiplier(balance01),
        limits, lockMode, lockedIso, lockedShutterNanos
    )

    /** Energy-direct entry point for RAW-driven closed loops (PROGRAM custom AE). */
    fun applyProgramEnergy(
        energy: Double,
        balance01: Float,
        limits: ExposureBalanceLimits,
        lockMode: ProgramLockMode = ProgramLockMode.NONE,
        lockedIso: Int = 0,
        lockedShutterNanos: Long = 0L
    ): ExposureBalanceResult = applyInternal(
        energy,
        ProgramAeProfile.balanceToMultiplier(balance01),
        limits, lockMode, lockedIso, lockedShutterNanos
    )

    private fun applyInternal(
        energy: Double,
        multiplier: Float,
        limits: ExposureBalanceLimits,
        lockMode: ProgramLockMode,
        lockedIso: Int,
        lockedShutterNanos: Long
    ): ExposureBalanceResult {
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
        // Single-axis locks: one control is fixed, the other absorbs the metered
        // energy within its own [min, max]. The fixed axis never compensates when
        // the free axis clamps — the frame accepts under/over exposure instead,
        // which is exactly "lock shutter and let ISO do auto" and vice versa.
        if (lockMode == ProgramLockMode.ISO_LOCK) {
            val iso = lockedIso.coerceIn(limits.isoMin, limits.isoMax).toDouble()
            val clampedLow = energy / iso < limits.shutterMinNanos
            val clampedHigh = energy / iso > dynamicCap
            val shutter = (energy / iso).coerceIn(limits.shutterMinNanos.toDouble(), dynamicCap)
            return ExposureBalanceResult(
                iso.roundToInt().coerceIn(limits.isoMin, limits.isoMax),
                shutter.toLong().coerceIn(limits.shutterMinNanos, dynamicCap.toLong()),
                isoLimited = true,
                shutterLimited = clampedLow || clampedHigh
            )
        }
        if (lockMode == ProgramLockMode.SHUTTER_LOCK) {
            val shutter = lockedShutterNanos
                .coerceIn(limits.shutterMinNanos, dynamicCap.toLong()).toDouble()
            var iso = energy / shutter
            val clampedLow = iso < limits.isoMin
            val clampedHigh = iso > limits.isoMax
            iso = iso.coerceIn(limits.isoMin.toDouble(), limits.isoMax.toDouble())
            return ExposureBalanceResult(
                iso.roundToInt().coerceIn(limits.isoMin, limits.isoMax),
                shutter.toLong().coerceIn(limits.shutterMinNanos, dynamicCap.toLong()),
                isoLimited = clampedLow || clampedHigh,
                shutterLimited = true
            )
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
