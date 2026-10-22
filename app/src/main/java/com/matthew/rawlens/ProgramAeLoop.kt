// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.pow

/**
 * PROGRAM custom-AE live state: RAW brightness drives sensor ISO + shutter
 * directly (AE_OFF repeating + still). ETTR is AUTO-only and never touches
 * this pair. Camera thread only.
 *
 * Extracted from RawCameraController (A2, docs/refactor/controller-remaining-plan.md):
 * the state, one sample step and one glide step, unchanged. The controller
 * keeps the mode/capture gates, camera ranges, the 10 Hz ramp timer and the
 * repeating-request pushes. (Reference audit of these heuristics:
 * docs/refactor/exposure-audit.md.)
 */
internal class ProgramAeLoop {
    /** Applied (live) pair; also seeded from hardware AE before the RAW loop runs. */
    var iso: Int? = null
    var shutterNanos: Long? = null
    var isoLimited = false
    var shutterLimited = false
    var lastUpdateMs = Long.MIN_VALUE
    var brightness = Float.NaN
    /** EMA of sampled brightness; single-sample noise must not yank the target. */
    var brightnessEma = Float.NaN
    var converged = false
    // Glide target: the solver output the applied pair (iso/shutterNanos)
    // eases toward in adaptive steps (fast far away, gentle up close).
    var targetIso: Int? = null
    var targetShutterNanos: Long? = null
    var targetLimits: ExposureBalanceLimits? = null
    var targetLockMode: ProgramLockMode = ProgramLockMode.NONE
    var targetLockedIso: Int = 0
    var targetLockedShutterNanos: Long = 0L
    /**
     * Fast start: the first metering result after (re)entry applies immediately
     * instead of gliding in, so the preview never sits overexposed while the ramp
     * would otherwise walk stops toward the target. Tracking glides from there.
     */
    var fastStart = true

    enum class Step { FAST_START, GLIDE, HOLD }

    /** Drops the applied pair and its limit flags. */
    fun clearLivePair() {
        iso = null
        shutterNanos = null
        isoLimited = false
        shutterLimited = false
    }

    /** Forgets targets and smoothing; the next sample fast-starts. */
    fun resetLoop() {
        targetIso = null
        targetShutterNanos = null
        targetLimits = null
        converged = false
        brightness = Float.NaN
        brightnessEma = Float.NaN
        fastStart = true
        lastUpdateMs = Long.MIN_VALUE
    }

    /**
     * One PROGRAM iteration: RAW brightness (center/average/spot per profile)
     * vs biased mid-gray target gives an EV shift; the closed-loop energy is
     * redistributed by balance/limits/locks directly into sensor ISO + shutter.
     * Returns null without a usable baseline, else what the caller must do;
     * [lastShiftEv] holds the shift for logging.
     */
    fun onSample(
        sample: EttrRawSample,
        profile: ProgramAeProfile,
        limits: ExposureBalanceLimits,
        lastIso: Int,
        lastExposureNanos: Long,
    ): Step? {
        val baselineIso = iso ?: lastIso
        val baselineShutter = shutterNanos ?: lastExposureNanos
        if (baselineIso <= 0 || baselineShutter <= 0L) return null
        val measured = RawProgramMeter.brightness(sample, profile.metering)
        brightnessEma = if (brightnessEma.isFinite()) {
            (EMA_ALPHA * measured + (1.0 - EMA_ALPHA) * brightnessEma)
                .toFloat().coerceIn(0f, 1f)
        } else measured
        brightness = brightnessEma
        // Cropped scans carry a full-frame guard level so a dark metered region
        // cannot blind the highlight guard to bright surroundings; full-frame
        // scans already report the global hottest channel.
        val hottest = sample.guardHottest.takeIf { it.isFinite() } ?: sample.levels.hottest
        // Loop (re)entry measures with full authority so one snap lands on the
        // correct exposure; tracking nudges stay capped for smoothness.
        val stepCap = if (fastStart) RawProgramMeter.MAX_STEP_EV
        else RawProgramMeter.PROGRAM_MAX_STEP_EV
        val shift = RawProgramMeter.guardShiftEv(
            RawProgramMeter.correctionEv(brightnessEma, profile.evBias, stepCap),
            hottest
        )
        lastShiftEv = shift
        val baselineEnergy = baselineIso.toDouble() * baselineShutter
        // Small capped steps only (PROGRAM_MAX_STEP_EV): the ramp below owns
        // smoothness, so the full measured nudge becomes the glide target.
        val targetEnergy = baselineEnergy * 2.0.pow(shift)
        val (lockedIsoEff, lockedShutterEff) = ProgramAeSolver.resolveLockedValues(
            profile, limits, baselineIso, baselineShutter, lastIso, lastExposureNanos
        )
        val result = AutoExposureBalance.applyProgramEnergy(
            targetEnergy.coerceAtLeast(1.0),
            profile.balance,
            limits,
            profile.lockMode,
            lockedIsoEff,
            lockedShutterEff
        )
        targetIso = result.iso
        targetShutterNanos = result.shutterNanos
        targetLimits = limits
        targetLockMode = profile.lockMode
        targetLockedIso = lockedIsoEff
        targetLockedShutterNanos = lockedShutterEff
        isoLimited = result.isoLimited
        shutterLimited = result.shutterLimited
        converged = shift == 0.0 && !result.isoLimited && !result.shutterLimited ||
            kotlin.math.abs(shift) <= RawProgramMeter.CONVERGED_TOLERANCE_EV
        return if (fastStart) {
            // Loop (re)entry: land on the measured exposure immediately instead of
            // gliding in from whatever the previous engine left behind. Tracking
            // glides from there.
            fastStart = false
            iso = result.iso
            shutterNanos = result.shutterNanos
            Step.FAST_START
        } else if (iso != result.iso || shutterNanos != result.shutterNanos) {
            Step.GLIDE
        } else {
            Step.HOLD
        }
    }

    /** Shift of the last [onSample], for the fast-start log line. */
    var lastShiftEv = 0.0
        private set

    /**
     * One glide step of the applied pair toward the target, preserving the
     * target's ISO/shutter ratio (or the locked axis). Null without a target;
     * else (pair changed, snapped to target).
     */
    fun rampStep(): Pair<Boolean, Boolean>? {
        val tIso = targetIso ?: return null
        val tShutter = targetShutterNanos ?: return null
        val limits = targetLimits ?: return null
        val curIso = iso ?: tIso
        val curShutter = shutterNanos ?: tShutter
        val (nextIso, nextShutter, snapped) = ProgramAeSolver.rampStep(
            curIso, curShutter, tIso, tShutter, limits,
            targetLockMode, targetLockedIso, targetLockedShutterNanos
        )
        val changed = iso != nextIso || shutterNanos != nextShutter
        if (changed) {
            iso = nextIso
            shutterNanos = nextShutter
        }
        return changed to snapped
    }

    private companion object {
        /** EMA weight for sampled brightness; halves single-sample noise. */
        const val EMA_ALPHA = 0.5
    }
}
