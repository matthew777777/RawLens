// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.roundToInt

/**
 * Pure parts of the PROGRAM custom-AE loop: the effective ISO/shutter limits
 * of a profile on a sensor and the locked axis values. The control law is
 * [ProgramAgc] (libcamera RPi AGC); the loop state is [ProgramAeLoop]. Sensor
 * ranges are passed as plain numbers so the JVM tests need no Android types.
 *
 * The shutter start (1/30 s) and the handheld endpoint used for the auto safe
 * shutter (1/15 s) match PhotonCamera's photo-mode handheld curve
 * (IsoExpoSelector PHOTO_HANDHELD_CAP_START / PHOTO_HANDHELD_CAP_END).
 */
internal object ProgramAeSolver {
    /** Shutter at which the curve starts trading ISO for exposure time (1/30 s). */
    const val SHUTTER_START_NANOS = 1_000_000_000L / 30

    /**
     * Profile limits clamped to the sensor; null when the profile's range is
     * empty on this sensor. [autoSafeShutterNanos] supplies the handheld
     * ceiling when the profile asks for it.
     */
    fun resolveLimits(
        profile: ProgramAeProfile,
        isoLower: Int,
        isoUpper: Int,
        shutterLower: Long,
        shutterUpper: Long,
        autoSafeShutterNanos: () -> Long,
    ): ExposureBalanceLimits? {
        val isoMinEff = (profile.isoMin.takeIf { it > 0 } ?: isoLower)
            .coerceIn(isoLower, isoUpper)
        val isoMaxEff = (profile.isoMax.takeIf { it > 0 } ?: isoUpper)
            .coerceIn(isoLower, isoUpper)
        if (isoMinEff > isoMaxEff) return null
        val shutterMinEff = (profile.shutterMinNanos.takeIf { it > 0L } ?: shutterLower)
            .coerceIn(shutterLower, shutterUpper)
        val shutterMaxEff = when {
            profile.shutterMaxNanos > 0L -> profile.shutterMaxNanos.coerceIn(shutterLower, shutterUpper)
            profile.useAutoSafeShutter -> autoSafeShutterNanos()
            else -> shutterUpper
        }.coerceIn(shutterLower, shutterUpper)
        if (shutterMinEff > shutterMaxEff) return null
        val startEff = minOf(SHUTTER_START_NANOS, shutterMaxEff).coerceIn(shutterMinEff, shutterMaxEff)
        return ExposureBalanceLimits(
            isoMin = isoMinEff,
            isoMax = isoMaxEff,
            shutterMinNanos = shutterMinEff,
            shutterMaxNanos = shutterMaxEff,
            shutterStartNanos = startEff
        )
    }

    /**
     * Effective locked values for the solver. An explicitly stored locked value
     * always wins; otherwise the actual sensor exposure ([sensorIso] /
     * [sensorShutterNanos]) seeds the lock (a stable reference, unlike the
     * moving live pair, which would let a "locked" axis drift with the scene).
     * UI lock engagement is expected to store explicit values; this fallback
     * only covers legacy profiles.
     */
    fun resolveLockedValues(
        profile: ProgramAeProfile,
        limits: ExposureBalanceLimits,
        fallbackIso: Int,
        fallbackShutter: Long,
        sensorIso: Int,
        sensorShutterNanos: Long,
    ): Pair<Int, Long> {
        val lockedIsoEff = when (profile.lockMode) {
            ProgramLockMode.ISO_LOCK -> profile.lockedIso.takeIf { it > 0 }
                ?: sensorIso.coerceIn(limits.isoMin, limits.isoMax)
            else -> fallbackIso.coerceIn(limits.isoMin, limits.isoMax)
        }.coerceIn(limits.isoMin, limits.isoMax)
        val lockedShutterEff = when (profile.lockMode) {
            ProgramLockMode.SHUTTER_LOCK -> profile.lockedShutterNanos.takeIf { it > 0L }
                ?: sensorShutterNanos.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
            else -> fallbackShutter.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        }.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        return lockedIsoEff to lockedShutterEff
    }
}
