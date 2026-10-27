// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * PROGRAM custom-AE live state: RAW brightness drives sensor ISO + shutter
 * directly (AE_OFF repeating + still). ETTR is AUTO-only and never touches
 * this pair. Camera thread only.
 *
 * The control law is libcamera's RPi AGC ([ProgramAgc]): each metering sample
 * sets a target total exposure; the controller runs one AGC filter step
 * per preview-frame interval and splits the filtered exposure into
 * ISO + shutter (PhotonCamera handheld curve, profile balance/locks, flicker
 * rounding). Replaces the earlier EMA + capped steps + glide + 0.9 highlight
 * guard, which had no reference (docs/refactor/exposure-audit.md).
 */
internal class ProgramAeLoop {
    /** Applied (live) pair; also seeded from hardware AE before the RAW loop runs. */
    var iso: Int? = null
    var shutterNanos: Long? = null
    var isoLimited = false
    var shutterLimited = false
    var lastUpdateMs = Long.MIN_VALUE
    /** Metered mean of the last sample (debug readout). */
    var brightness = Float.NaN
    /** Filtered exposure within the AGC stable region of the target. */
    var converged = false
    /** Target pair (split of the target exposure), for the readout and the glide test. */
    var targetIso: Int? = null
    var targetShutterNanos: Long? = null
    var targetLimits: ExposureBalanceLimits? = null
    /** Target and filtered total exposure (ISO x ns); readable for tests. */
    var targetTotal = 0.0
        private set
    var filteredTotal = 0.0
        private set
    /** Sensor frames since loop (re)entry; the AGC's first frames adapt instantly. */
    private var frameIndex = 0
    private var balance = 0.5f
    private var lockMode = ProgramLockMode.NONE
    private var lockedIso = 0
    private var lockedShutterNanos = 0L
    private var flickerPeriodNanos = 0L

    enum class Step { START, GLIDE }

    /** Drops the applied pair and its limit flags. */
    fun clearLivePair() {
        iso = null
        shutterNanos = null
        isoLimited = false
        shutterLimited = false
    }

    /** Forgets targets and filter state; the next sample restarts the AGC. */
    fun resetLoop() {
        targetIso = null
        targetShutterNanos = null
        targetLimits = null
        converged = false
        brightness = Float.NaN
        targetTotal = 0.0
        filteredTotal = 0.0
        frameIndex = 0
        lastUpdateMs = Long.MIN_VALUE
    }

    /**
     * One metering sample: AGC gain against the exposure the sampled frame
     * was taken with ([currentIso] x [currentShutterNanos]), target clamped to
     * the profile's largest total exposure. Null without a usable exposure.
     * [Step.START] means the loop just (re)started: apply [tick] at once.
     */
    fun onSample(
        sample: EttrRawSample,
        profile: ProgramAeProfile,
        limits: ExposureBalanceLimits,
        currentIso: Int,
        currentShutterNanos: Long,
        flickerPeriodNanos: Long,
        mode: ProgramAgc.ConstraintMode = ProgramAgc.ConstraintMode.NORMAL,
        lighting: ActiveDLighting = ActiveDLighting.OFF,
    ): Step? {
        if (currentIso <= 0 || currentShutterNanos <= 0L) return null
        val metered = RawProgramMeter.brightness(sample, profile.metering).toDouble()
        brightness = metered.toFloat()
        val histogram = ProgramAgc.Histogram(RawProgramMeter.frameGreenBins(sample))
        // H1 uses average for subject placement and a separate brightness/area ceiling.
        // Feeding the highlight statistic to the grey-target AGC would darken it to 16%.
        val subject = if (profile.metering == ProgramMetering.HIGHLIGHT_WEIGHTED)
            RawProgramMeter.meanBrightness(sample).toDouble() else metered
        val gain = ProgramAgc.computeGain(subject, histogram, Math.pow(2.0, profile.evBias.toDouble()), mode)
        val protectedGain = if (profile.metering == ProgramMetering.HIGHLIGHT_WEIGHTED || lighting != ActiveDLighting.OFF)
            RawProgramMeter.protectedGain(sample, gain.gain, lighting) else gain.gain
        val current = currentIso.toDouble() * currentShutterNanos
        val maxTotal = limits.isoMax.toDouble() * limits.shutterMaxNanos
        targetTotal = (current * protectedGain).coerceIn(1.0, maxTotal)
        val (lIso, lShutter) = ProgramAeSolver.resolveLockedValues(
            profile, limits, iso ?: currentIso, shutterNanos ?: currentShutterNanos,
            currentIso, currentShutterNanos
        )
        balance = profile.balance
        lockMode = profile.lockMode
        lockedIso = lIso
        lockedShutterNanos = lShutter
        targetLimits = limits
        this.flickerPeriodNanos = flickerPeriodNanos
        val target = split(targetTotal, limits)
        targetIso = target.iso
        targetShutterNanos = target.shutterNanos
        val starting = filteredTotal <= 0.0
        if (starting) filteredTotal = current
        if (ProgramAgc.desaturate(gain)) filteredTotal *= ProgramAgc.FAST_REDUCE_THRESHOLD
        return if (starting) Step.START else Step.GLIDE
    }

    /**
     * Runs the AGC filter for [frames] sensor frames and splits the result.
     * Null without a target; else (pair changed, settled in the stable region).
     */
    fun tick(frames: Int): Pair<Boolean, Boolean>? {
        val limits = targetLimits ?: return null
        if (targetTotal <= 0.0) return null
        filteredTotal = ProgramAgc.filter(filteredTotal, targetTotal, frames, frameIndex)
        frameIndex += frames.coerceAtLeast(1)
        val pair = split(filteredTotal, limits)
        isoLimited = pair.isoLimited
        shutterLimited = pair.shutterLimited
        val changed = iso != pair.iso || shutterNanos != pair.shutterNanos
        iso = pair.iso
        shutterNanos = pair.shutterNanos
        converged = filteredTotal * (1.0 - ProgramAgc.STABLE_REGION) < targetTotal &&
            filteredTotal * (1.0 + ProgramAgc.STABLE_REGION) > targetTotal
        return changed to converged
    }

    /** Total exposure -> ISO + shutter: PhotonCamera curve, then flicker rounding. */
    private fun split(total: Double, limits: ExposureBalanceLimits): ExposureBalanceResult {
        val r = AutoExposureBalance.applyProgramEnergy(
            total.coerceAtLeast(1.0), balance, limits, lockMode, lockedIso, lockedShutterNanos
        )
        if (lockMode != ProgramLockMode.NONE) return r
        val (shutter, isoScale) = ProgramAgc.roundForFlicker(r.shutterNanos, flickerPeriodNanos)
        if (shutter == r.shutterNanos) return r
        val iso = (r.iso * isoScale).toInt().coerceIn(limits.isoMin, limits.isoMax)
        return r.copy(iso = iso, shutterNanos = shutter, isoLimited = r.isoLimited || iso == limits.isoMax)
    }
}
