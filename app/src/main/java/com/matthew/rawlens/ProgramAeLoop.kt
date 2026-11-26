// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.ln

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
    companion object {
        /** rawr kRequestDeadbandEv. */
        const val REQUEST_DEADBAND_EV = 1.0 / 48.0
        private val LN2 = ln(2.0)
    }

    /** Applied (live) pair; also seeded from hardware AE before the RAW loop runs. */
    var iso: Int? = null
    var shutterNanos: Long? = null
    var isoLimited = false
    var shutterLimited = false
    /** Digital gain on top of [iso] x [shutterNanos] once both are maxed ([DigitalGain]). */
    var digitalGain = 1f
    private var maxDigitalGain = 1.0
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
    /** [targetLimits] with ISO capped at the sensor's analog max (rawr). */
    private var analogLimits: ExposureBalanceLimits? = null
    /** Digital factor a locked ISO above the analog max carries (request runs at the analog max). */
    private var isoLockDigital = 1.0

    /**
     * Exposure a still taken now gets: live pair x [digitalGain]. The viewfinder
     * scales each frame by this over that frame's own exposure (rawr residual
     * gain), so request latency, integer ISO and flicker steps never show.
     */
    val stillTotal: Double
        get() {
            val i = iso ?: return 0.0
            val t = shutterNanos ?: return 0.0
            return i.toDouble() * t * digitalGain
        }

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
        analogLimits = null
        isoLockDigital = 1.0
        converged = false
        brightness = Float.NaN
        targetTotal = 0.0
        filteredTotal = 0.0
        digitalGain = 1f
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
        /** SENSOR_MAX_ANALOG_SENSITIVITY; requests never go above it (0 = no cap). */
        analogIsoMax: Int = 0,
    ): Step? {
        if (currentIso <= 0 || currentShutterNanos <= 0L) return null
        val metered = RawProgramMeter.brightness(sample, profile.metering).toDouble()
        brightness = metered.toFloat()
        val histogram = ProgramAgc.Histogram(RawProgramMeter.frameGreenBins(sample))
        // H1 uses average for subject placement and a separate brightness/area ceiling.
        // Feeding the highlight statistic to the grey-target AGC would darken it to 16%.
        val subject = if (profile.metering == ProgramMetering.HIGHLIGHT_WEIGHTED)
            RawProgramMeter.meanBrightness(sample).toDouble() else metered
        val tuning = ProgramBrightnessCurve.evaluate(
            profile.brightnessTuning, subject, currentIso, currentShutterNanos, profile
        )
        val constraintMode = if (profile.brightnessTuning == ProgramBrightnessTuning.HIGHLIGHTS)
            ProgramAgc.ConstraintMode.HIGHLIGHT else mode
        val gain = ProgramAgc.computeGain(
            subject, histogram, Math.pow(2.0, profile.evBias.toDouble()), constraintMode,
            tuning.target, tuning.constraintScale
        )
        val protectedGain = if (profile.metering == ProgramMetering.HIGHLIGHT_WEIGHTED || lighting != ActiveDLighting.OFF)
            RawProgramMeter.protectedGain(sample, gain.gain, lighting) else gain.gain
        val current = currentIso.toDouble() * currentShutterNanos
        // Past the highest ISO and shutter the rest becomes digital gain (rawr-style),
        // capped per lens. The metered raw never includes that gain, so the AGC
        // keeps measuring against the physical exposure and stays stable.
        // ISO above the analog max is HAL integer gain (clips, coarse steps):
        // requests stop at the analog max and the same reach comes from float
        // digital gain instead, so the cap never makes a scene darker.
        val analogMax = if (analogIsoMax > 0) analogIsoMax.coerceIn(limits.isoMin, limits.isoMax) else limits.isoMax
        val analog = limits.copy(isoMax = analogMax)
        val profileDigital = Math.pow(2.0, profile.digitalGainEv.toDouble())
        maxDigitalGain = profileDigital * limits.isoMax / analogMax
        val maxTotal = limits.isoMax.toDouble() * limits.shutterMaxNanos * profileDigital
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
        analogLimits = analog
        isoLockDigital = if (lockMode == ProgramLockMode.ISO_LOCK && lIso > analogMax)
            lIso.toDouble() / analogMax else 1.0
        this.flickerPeriodNanos = flickerPeriodNanos
        val target = split(targetTotal, analog)
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
        val limits = analogLimits ?: targetLimits ?: return null
        if (targetTotal <= 0.0) return null
        filteredTotal = ProgramAgc.filter(filteredTotal, targetTotal, frames, frameIndex)
        frameIndex += frames.coerceAtLeast(1)
        var pair = split(filteredTotal, limits)
        val liveIso = iso
        val liveShutter = shutterNanos
        // rawr request deadband: a pair within 1/48 EV of the live one is not
        // re-submitted (the viewfinder residual gain covers the difference),
        // so the filter's tail does not rebuild the repeating request each frame.
        if (liveIso != null && liveShutter != null && liveIso > 0 && liveShutter > 0L &&
            (pair.iso != liveIso || pair.shutterNanos != liveShutter) &&
            pair.isoLimited == isoLimited && pair.shutterLimited == shutterLimited &&
            abs(ln(pair.iso.toDouble() * pair.shutterNanos / (liveIso.toDouble() * liveShutter))) <
            REQUEST_DEADBAND_EV * LN2
        ) pair = pair.copy(iso = liveIso, shutterNanos = liveShutter)
        isoLimited = pair.isoLimited
        shutterLimited = pair.shutterLimited
        val physical = pair.iso.toDouble() * pair.shutterNanos
        val extra = if ((pair.isoLimited || isoLockDigital > 1.0) && physical > 0.0) filteredTotal / physical else 1.0
        digitalGain = if (extra > 1.02) extra.coerceAtMost(maxDigitalGain).toFloat() else 1f
        val changed = iso != pair.iso || shutterNanos != pair.shutterNanos
        iso = pair.iso
        shutterNanos = pair.shutterNanos
        converged = filteredTotal * (1.0 - ProgramAgc.STABLE_REGION) < targetTotal &&
            filteredTotal * (1.0 + ProgramAgc.STABLE_REGION) > targetTotal
        return changed to converged
    }

    /** Total exposure -> ISO + shutter: PhotonCamera curve, then flicker rounding. */
    private fun split(total: Double, limits: ExposureBalanceLimits): ExposureBalanceResult {
        // A locked ISO past the analog max: the shutter is set for the user's ISO,
        // the request runs at the analog max and tick() adds the rest digitally.
        val r = AutoExposureBalance.applyProgramEnergy(
            (total / isoLockDigital).coerceAtLeast(1.0), balance, limits, lockMode,
            lockedIso.coerceAtMost(limits.isoMax), lockedShutterNanos
        )
        if (lockMode != ProgramLockMode.NONE) return r
        val (shutter, isoScale) = ProgramAgc.roundForFlicker(r.shutterNanos, flickerPeriodNanos)
        if (shutter == r.shutterNanos) return r
        val iso = (r.iso * isoScale).toInt().coerceIn(limits.isoMin, limits.isoMax)
        return r.copy(iso = iso, shutterNanos = shutter, isoLimited = r.isoLimited || iso == limits.isoMax)
    }
}
