// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class AdaptiveExposureResult(
    val correctionEv: Double,
    val logAverage: Double,
    val highlight: Double,
    val lowKey: Boolean
)

/** Tunable highlight guards, wired from [JpegOutputSettings]; defaults are sky-safe. */
data class AdaptiveExposureTuning(
    /** Hard white guard for the p99 spike (1% clip budget for tiny clipped tails). 1.0 = never push past white. */
    val highlightHeadroom: Double = 1.0,
    /** Soft shoulder for broad p95 highlight mass (skies/walls). */
    val highlightSoftHeadroom: Double = 0.85,
    /**
     * Scene-linear shoulder strength applied to meter samples before any
     * statistic, so the meter sees what AgX sees. Must equal the shoulder the
     * develop will use ([JpegOutputSettings.highlightShoulder]). 0 = pinned
     * Filament AgX only, 1 = full exponential shoulder (knee 0.9, scale 0.8).
     */
    val highlightShoulder: Float = 1f,
    /**
     * Fixed-center spatial weight for the brightness placement (log-average
     * only — highlight guards and the low-key gate stay global, so edges can
     * never be less protected). 0 = uniform metering (legacy, bit-exact).
     * Higher values let a mid-frame subject outvote bright edges/skies.
     */
    val centerWeight: Float = 0f,
    /**
     * Vote center in the SAMPLED region's normalized coordinates (null =
     * region center). Follows the tap-to-meter point when set: callers map
     * their own space into the region (full-frame saves and the preview
     * region are view-proportional, so a view-normalized tap applies
     * directly). Guards ignore it by design.
     */
    val focusX: Float? = null,
    val focusY: Float? = null
) {
    init {
        require(highlightHeadroom.isFinite() && highlightSoftHeadroom.isFinite())
        require(highlightShoulder.isFinite())
        require(centerWeight.isFinite())
        require(focusX == null || focusX.isFinite())
        require(focusY == null || focusY.isFinite())
    }

    fun bounded(): AdaptiveExposureTuning = copy(
        highlightHeadroom = highlightHeadroom.coerceIn(0.5, 1.5),
        highlightSoftHeadroom = highlightSoftHeadroom.coerceIn(0.6, 1.0),
        highlightShoulder = highlightShoulder.coerceIn(0f, 1f),
        centerWeight = centerWeight.coerceIn(0f, 3f),
        focusX = focusX?.coerceIn(0f, 1f),
        focusY = focusY?.coerceIn(0f, 1f)
    )

    companion object {
        /**
         * Resolves user output settings into meter tuning. The user's
         * broad-mass guard is a scene-linear level stated under default AgX
         * settings; its display-encoded value is re-solved into scene-linear
         * space under the user's AgX range/contrast/shoulder — so the meter
         * tracks the AgX window instead of a fixed constant, while the
         * slider keeps its meaning. At default settings this reproduces the
         * slider value exactly. [focus] is the tap-to-meter point in the
         * sampled region's normalized coordinates (null = region center);
         * callers mapping from another space pass the mapped point.
         */
        fun fromOutputSettings(
            settings: JpegOutputSettings,
            focus: Pair<Float, Float>? = null
        ): AdaptiveExposureTuning {
            val resolved = settings.resolvedForPlatform()
            val encodedAnchor = AgxDisplayTransform.scopeCurve(
                resolved.highlightSoftHeadroom, JpegOutputSettings()
            )
            return AdaptiveExposureTuning(
                highlightHeadroom = resolved.highlightHeadroom.toDouble(),
                highlightSoftHeadroom = AgxDisplayTransform.invertScopeCurve(
                    encodedAnchor, resolved
                ).toDouble(),
                highlightShoulder = resolved.highlightShoulder,
                centerWeight = resolved.centerWeight,
                focusX = focus?.first,
                focusY = focus?.second
            ).bounded()
        }
    }
}
/**
 * HDR-merge adaptive tuning (fixed internal defaults, not user settings).
 *
 * Single-frame tuning pins pre-AgX highlights at/below white, which is
 * correct when the sensor range fits the display — but an HDR merge
 * carries stops of valid highlights above reference white that AgX's
 * shoulder + 6.5EV highlight range are designed to roll off. Pinning them
 * at 1.0 forces the whole image dark whenever anything clips. HDR tuning
 * instead lets spikes ride to the top of AgX's range while broad bright
 * areas keep shoulder gradation, and budgets the lift by measured dynamic
 * range so low-DR merges (and night scenes without bright content) still
 * develop like single frames.
 *
 * The broad anchor deliberately matches LDR (0.85): HDR's extra range
 * extends upward to spikes and downward to clean shadows — pushing broad
 * mass higher washes midtones out (verified on the IMG_20261008_142321_830
 * interior: 1.25 renders walls near-white). The lift past the LDR budget
 * comes from the spike room plus the DR budget, not a brighter anchor.
 */
data class HdrAdaptiveTuning(
    /** p99.5 spikes may ride this high pre-AgX (AgX top is ~16). */
    val spikeHeadroom: Double = 16.0,
    /** p95 broad mass lands at/below this level (LDR anchor). */
    val broadHeadroom: Double = 0.85,
    /** Safety rail; the guards and DR budget bind first in practice. */
    val maxCorrectionEv: Double = 6.0,
    /** LDR-equivalent lift budget for scenes that fit LDR rendering. */
    val ldrLiftBudgetEv: Double = 1.5,
    /** Spike-to-midtone DR (stops) below which the LDR budget applies. */
    val ldrFitRangeEv: Double = 4.0,
    /**
     * Scene-linear shoulder strength applied to meter samples before any
     * statistic. Must equal the shoulder the develop will use: the HDR
     * bracket develop caps it (see below), so bracket call sites pass the
     * capped value, never the raw user setting.
     */
    val highlightShoulder: Float = 1f,
    /**
     * Fixed-center spatial weight for the brightness placement, same
     * semantics as [AdaptiveExposureTuning.centerWeight]: log-average only,
     * guards stay global. 0 = uniform (legacy).
     */
    val centerWeight: Float = 0f,
    /** Vote center, same semantics as [AdaptiveExposureTuning.focusX]. */
    val focusX: Float? = null,
    /** Vote center, same semantics as [AdaptiveExposureTuning.focusY]. */
    val focusY: Float? = null
) {
    init {
        require(spikeHeadroom.isFinite() && broadHeadroom.isFinite() &&
            maxCorrectionEv.isFinite() && ldrLiftBudgetEv.isFinite() &&
            ldrFitRangeEv.isFinite())
        require(spikeHeadroom > 0.0 && broadHeadroom > 0.0 && maxCorrectionEv > 0.0)
        require(highlightShoulder.isFinite())
        require(centerWeight.isFinite())
        require(focusX == null || focusX.isFinite())
        require(focusY == null || focusY.isFinite())
    }

    fun bounded(): HdrAdaptiveTuning = copy(
        spikeHeadroom = spikeHeadroom.coerceIn(1.0, 32.0),
        broadHeadroom = broadHeadroom.coerceIn(0.6, 4.0),
        maxCorrectionEv = maxCorrectionEv.coerceIn(1.5, 8.0),
        ldrLiftBudgetEv = ldrLiftBudgetEv.coerceIn(0.0, 3.0),
        ldrFitRangeEv = ldrFitRangeEv.coerceIn(2.0, 8.0),
        highlightShoulder = highlightShoulder.coerceIn(0f, 1f),
        centerWeight = centerWeight.coerceIn(0f, 3f),
        focusX = focusX?.coerceIn(0f, 1f),
        focusY = focusY?.coerceIn(0f, 1f)
    )
}

/** One instance is frozen per shutter press and shared by every frame in that logical capture. */
class SharedAdaptiveExposure {
    private var correctionEv: Double? = null

    @Synchronized
    fun resolve(analyze: () -> AdaptiveExposureResult): AdaptiveExposureResult {
        correctionEv?.let { return AdaptiveExposureResult(it, Double.NaN, Double.NaN, false) }
        return analyze().also { correctionEv = it.correctionEv }
    }
}

/** Robust global RAW exposure placement before the deterministic display transform. */
object AdaptiveDevelopmentExposure {
    class Workspace internal constructor() {
        // The historical floor-derived sampling stride can yield just under 2*MAX_SAMPLES.
        // Keep it unchanged so this allocation optimization cannot alter exposure placement.
        internal val samples = FloatArray(MAX_SAMPLES * 2)
        /** Parallel spatial weights for [samples]; only filled when center weighting is armed. */
        internal val weights = FloatArray(MAX_SAMPLES * 2)
    }

    fun workspace(): Workspace = Workspace()

    fun analyze(
        cfa: UnpackedRawCfa,
        workspace: Workspace = Workspace(),
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
        ettrHeadroomEv: Float? = null
    ): AdaptiveExposureResult {
        require(cfa.values.size == cfa.width * cfa.height)
        val stride = maxOf(1, cfa.values.size / MAX_SAMPLES)
        val samples = workspace.samples
        val bounded = tuning.bounded()
        val weights = if (bounded.centerWeight > 0f) workspace.weights else null
        var count = 0
        var index = 0
        while (index < cfa.values.size) {
            val value = cfa.values[index]
            if (value.isFinite() && value > SHADOW_FLOOR) {
                samples[count] = value
                if (weights != null) {
                    weights[count] = spatialWeight(
                        index % cfa.width, index / cfa.width, cfa.width, cfa.height,
                        bounded.centerWeight, bounded.focusX ?: 0.5f, bounded.focusY ?: 0.5f
                    )
                }
                count++
            }
            index += stride
        }
        return analyzeSamples(samples, count, tuning, ettrHeadroomEv, weights)
    }

    /** Samples packed RAW directly, applying the same normalization and lens gain as GLES. */
    fun analyzeRaw(
        source: ByteBuffer,
        layout: RawPlaneLayout,
        normalization: RawNormalization,
        crop: RawCrop,
        lensShading: LensShadingModel?,
        workspace: Workspace = Workspace(),
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
        ettrHeadroomEv: Float? = null
    ): AdaptiveExposureResult {
        require(crop.left + crop.width <= layout.width && crop.top + crop.height <= layout.height)
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val origin = input.position()
        val pixelCount = crop.width * crop.height
        val stride = maxOf(1, pixelCount / MAX_SAMPLES)
        val samples = workspace.samples
        val bounded = tuning.bounded()
        val weights = if (bounded.centerWeight > 0f) workspace.weights else null
        var count = 0
        var index = 0
        while (index < pixelCount) {
            val x = index % crop.width
            val y = index / crop.width
            val planeX = crop.left + x
            val planeY = crop.top + y
            val sensorX = layout.sensorOriginX + planeX
            val sensorY = layout.sensorOriginY + planeY
            val byteOffset = origin + planeY * layout.rowStride + planeX * layout.pixelStride
            val code = input.getShort(byteOffset).toInt() and 0xffff
            val black = normalization.blackAt(sensorX, sensorY)
            var value = (code - black) / (normalization.whiteLevel - black)
            if (lensShading != null && !lensShading.alreadyApplied) {
                value *= lensShading.gainAt(
                    sensorX, sensorY, normalization.sensorPattern.colorAt(sensorX, sensorY)
                )
            }
            if (value.isFinite() && value > SHADOW_FLOOR) {
                samples[count] = value
                if (weights != null) {
                    weights[count] = spatialWeight(
                        x, y, crop.width, crop.height,
                        bounded.centerWeight, bounded.focusX ?: 0.5f, bounded.focusY ?: 0.5f
                    )
                }
                count++
            }
            index += stride
        }
        return analyzeSamples(samples, count, tuning, ettrHeadroomEv, weights)
    }

    /**
     * HDR-merge adaptive exposure: like [analyze], but the merge's
     * display compensation ([exposureGain], linear, = 2^displayEv) is
     * folded into sampling so every statistic — log-average, highlight
     * tails, low-key gate — is measured in display-referred (reference
     * brightness) terms, and the returned correction is the *additional*
     * lift beyond the compensation (callers add both).
     *
     * Guards are HDR-aware ([HdrAdaptiveTuning]): spikes ride to AgX's
     * range top instead of pinning mids whenever anything clips, broad
     * bright mass keeps shoulder gradation, and the lift budget grows
     * past the LDR budget only by measured excess dynamic range — so a
     * high-DR interior lifts to natural brightness while low-DR and
     * night merges still develop like single frames.
     */
    fun analyzeHdr(
        cfa: UnpackedRawCfa,
        exposureGain: Double,
        workspace: Workspace = Workspace(),
        tuning: HdrAdaptiveTuning = HdrAdaptiveTuning()
    ): AdaptiveExposureResult {
        require(cfa.values.size == cfa.width * cfa.height)
        require(exposureGain.isFinite() && exposureGain > 0.0) {
            "HDR exposure gain must be finite and positive"
        }
        val stride = maxOf(1, cfa.values.size / MAX_SAMPLES)
        val samples = workspace.samples
        val bounded = tuning.bounded()
        val weights = if (bounded.centerWeight > 0f) workspace.weights else null
        var count = 0
        var index = 0
        while (index < cfa.values.size) {
            val value = cfa.values[index] * exposureGain
            if (value.isFinite() && value > SHADOW_FLOOR) {
                samples[count] = value.toFloat()
                if (weights != null) {
                    weights[count] = spatialWeight(
                        index % cfa.width, index / cfa.width, cfa.width, cfa.height,
                        bounded.centerWeight, bounded.focusX ?: 0.5f, bounded.focusY ?: 0.5f
                    )
                }
                count++
            }
            index += stride
        }
        return analyzeHdrSamples(samples, count, tuning, weights)
    }

    internal fun analyzeHdrSamples(
        samples: FloatArray,
        count: Int,
        tuning: HdrAdaptiveTuning = HdrAdaptiveTuning(),
        weights: FloatArray? = null
    ): AdaptiveExposureResult {
        // AE-1: the log-average takes log2 of every sample between p5 and p95,
        // so a zero there made it -inf (max lift, HDR-scene ceiling) and a
        // negative made it NaN. Callers already pass only > SHADOW_FLOOR;
        // enforce the contract here (no-op for them).
        val count = keepMeterable(samples, weights, count)
        if (count < MIN_SAMPLES) {
            return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        }
        // Meter what AgX sees: the scene-linear shoulder compresses above-knee
        // content before the log domain, so apply it before any statistic.
        applyShoulder(samples, count, tuning.bounded().highlightShoulder)
        sortSamples(samples, weights, count)
        val low = percentile(samples, count, 0.05)
        val highForAverage = percentile(samples, count, 0.95)
        // Center weighting shapes only the brightness placement: guards below
        // stay global, so edges can never be less protected than unweighted.
        var logSum = 0.0
        var weightSum = 0.0
        var logCount = 0
        for (index in 0 until count) {
            val value = samples[index]
            if (value in low..highForAverage) {
                val w = weights?.get(index)?.toDouble()?.coerceAtLeast(0.0) ?: 1.0
                logSum += w * log2(value.toDouble())
                weightSum += w
                logCount++
            }
        }
        if (logCount == 0 || weightSum <= 0.0) return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        val logAverage = logSum / weightSum
        val midLevel = exp2(logAverage)
        val highlight = percentile(samples, count, 0.995).toDouble()
        val highlightBroad = percentile(samples, count, 0.95).toDouble()
        val bounded = tuning.bounded()
        var correction = log2(TARGET_MIDDLE / midLevel)
        // Spikes ride to AgX's range top; broad mass keeps shoulder
        // gradation; the lift budget only exceeds the LDR budget by
        // measured excess dynamic range (spike-to-midtone stops past what
        // fits LDR rendering).
        correction = min(correction, log2(bounded.spikeHeadroom / highlight.coerceAtLeast(1e-6)))
        correction = min(correction, log2(bounded.broadHeadroom / highlightBroad.coerceAtLeast(1e-6)))
        val dynamicRangeEv = log2(highlight.coerceAtLeast(1e-6) / midLevel.coerceAtLeast(1e-9))
        val budget = bounded.ldrLiftBudgetEv +
            maxOf(0.0, dynamicRangeEv - bounded.ldrFitRangeEv)
        correction = min(correction, budget)

        val median = percentile(samples, count, 0.50).toDouble()
        val upper = percentile(samples, count, 0.90).toDouble()
        val lowKey = median < LOW_KEY_MEDIAN && upper < LOW_KEY_UPPER
        if (lowKey && correction > 0.0) correction *= LOW_KEY_POSITIVE_SCALE
        correction = correction.coerceIn(-MAX_CORRECTION_EV, bounded.maxCorrectionEv)
        return AdaptiveExposureResult(correction, logAverage, highlight, lowKey)
    }

    /**
     * Shared with the VF live preview estimator ([VfGpuImport.estimatePreviewCorrectionEv]).
     *
     * @param ettrHeadroomEv capture-time ETTR headroom (see [EttrSettings.headroomEv]),
     *   or null when the frame was not ETTR-exposed. ETTR frames are already near-optimally
     *   bright by construction (the loop placed the hottest channel near 2^-headroom),
     *   so develop lift is capped at the residual needed to complete the measured p99.5
     *   to the loop target: bright frames take a small completion lift that keeps skies
     *   blue, while dark/stale frames (loop still tracking, ISO-limited night) keep most
     *   of the legacy lift instead of rendering too dark.
     */
    internal fun analyzeSamples(
        samples: FloatArray,
        count: Int,
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
        ettrHeadroomEv: Float? = null,
        weights: FloatArray? = null
    ): AdaptiveExposureResult {
        // AE-1: the log-average takes log2 of every sample between p5 and p95,
        // so a zero there made it -inf (max lift, HDR-scene ceiling) and a
        // negative made it NaN. Callers already pass only > SHADOW_FLOOR;
        // enforce the contract here (no-op for them).
        val count = keepMeterable(samples, weights, count)
        if (count < MIN_SAMPLES) {
            return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        }
        // Meter what AgX sees: the scene-linear shoulder compresses above-knee
        // content before the log domain, so apply it before any statistic.
        applyShoulder(samples, count, tuning.bounded().highlightShoulder)
        sortSamples(samples, weights, count)
        val low = percentile(samples, count, 0.05)
        val highForAverage = percentile(samples, count, 0.95)
        // Center weighting shapes only the brightness placement: guards below
        // stay global, so edges can never be less protected than unweighted.
        var logSum = 0.0
        var weightSum = 0.0
        var logCount = 0
        for (index in 0 until count) {
            val value = samples[index]
            if (value in low..highForAverage) {
                val w = weights?.get(index)?.toDouble()?.coerceAtLeast(0.0) ?: 1.0
                logSum += w * log2(value.toDouble())
                weightSum += w
                logCount++
            }
        }
        if (logCount == 0 || weightSum <= 0.0) return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        val logAverage = logSum / weightSum
        // Reported hot tail (logs/API). The guard below meters p99 instead, so
        // up to ~1% of tiny clipped highlights may ride into AgX white instead
        // of vetoing the whole frame's lift; scenes with broader bright areas
        // stay capped by the p95 broad guard below.
        val highlight = percentile(samples, count, 0.995).toDouble()
        val spikeGuardLevel = percentile(samples, count, 0.99).toDouble()
        // Broad-area highlight level (sky, walls): keeps large bright regions
        // from flattening even when the p99 spike itself is still safe.
        val highlightBroad = percentile(samples, count, 0.95).toDouble()
        var correction = log2(TARGET_MIDDLE / exp2(logAverage))
        // Do not move the measured upper tail past display-referred white before AgX rolls it off.
        // Hard guard on the spike (p99 -> headroom, 1% clip budget) plus a soft shoulder on the broad
        // highlight mass (p95 -> soft headroom) so skies keep gradation instead of flat white.
        val bounded = tuning.bounded()
        correction = min(correction, log2(bounded.highlightHeadroom / spikeGuardLevel.coerceAtLeast(1e-6)))
        correction = min(correction, log2(bounded.highlightSoftHeadroom / highlightBroad.coerceAtLeast(1e-6)))
        if (ettrHeadroomEv != null) {
            require(ettrHeadroomEv.isFinite()) { "ETTR headroom must be finite" }
            // Single residual tier: complete p99.5 to the loop target and stop.
            // A hard-zero tier for verified-bright frames (shipped, reverted
            // 2026-10-09) held them at 0 EV and rendered too-dark shadows on
            // device; the residual keeps the hot tail at target — skies stay
            // blue — while mids still lift (replica-verified on 175945/180030).
            correction = min(
                correction,
                log2(RawEttrMeter.targetLevel(ettrHeadroomEv) / highlight.coerceAtLeast(1e-6))
            )
        }

        val midLevel = exp2(logAverage)
        // HDR-scene detector: spike-to-midtone range past what fits LDR
        // rendering, with broad mass clearly above night levels. A daytime
        // backlit interior fires it (dim mids, small bright tail); night
        // streets and uniform LDR content do not.
        val dynamicRangeEv = log2(highlight.coerceAtLeast(1e-6) / midLevel.coerceAtLeast(1e-9))
        val hdrScene = dynamicRangeEv > HDR_SCENE_RANGE_EV && highlightBroad > LOW_KEY_UPPER
        val maxPositiveEv = if (hdrScene) HDR_SCENE_MAX_EV else MAX_CORRECTION_EV

        val median = percentile(samples, count, 0.50).toDouble()
        val upper = percentile(samples, count, 0.90).toDouble()
        val lowKey = median < LOW_KEY_MEDIAN && upper < LOW_KEY_UPPER
        // A daytime HDR scene is low-key by histogram but must still lift: its
        // broad mass carries the room while only tiny tails clip.
        if (lowKey && correction > 0.0 && !hdrScene) correction *= LOW_KEY_POSITIVE_SCALE
        // Night-tier relief: a low-key frame whose p99 spike stands two stops
        // clear of the p95 broad mass (lamps/lit concrete straddling p99, as on
        // night platforms and buses) is spike-pinned far darker than a sparser
        // lamp street the guard never sees. A second tier lets those lamps ride
        // to 4x white (AgX shoulder) up to the LDR cap, so lit night reads as
        // lit instead of crushed black. Uniform streets (sep ~0) and day content
        // never enter the window; ETTR frames skip it so the residual cap stays
        // exact. Strictly non-darkening via max().
        if (ettrHeadroomEv == null && lowKey && !hdrScene && correction > 0.0) {
            val spikeLevel = spikeGuardLevel.coerceAtLeast(1e-6)
            val broadLevel = highlightBroad.coerceAtLeast(1e-6)
            if (log2(spikeLevel / broadLevel) >= NIGHT_SEP_MIN_EV) {
                val tierRelief = min(
                    min(
                        log2(TARGET_MIDDLE / midLevel.coerceAtLeast(1e-9)),
                        log2(NIGHT_SPIKE_HEADROOM / spikeLevel)
                    ),
                    min(log2(bounded.highlightSoftHeadroom / broadLevel), NIGHT_TIER_MAX_EV)
                )
                correction = max(correction, tierRelief)
            }
        }
        correction = correction.coerceIn(-MAX_CORRECTION_EV, maxPositiveEv)
        return AdaptiveExposureResult(correction, logAverage, highlight, lowKey)
    }

    /**
     * Pre-tonemap shoulder for meter samples: the exact blend
     * [AgxDisplayTransform] applies before AgX's log domain (strength 0 is
     * identity = pinned Filament AgX). Below-knee values pass through
     * untouched, so dim/night content meters bit-exactly with or without it.
     */
    /** Compacts samples (and weights) to finite, positive values; returns the new count. */
    private fun keepMeterable(samples: FloatArray, weights: FloatArray?, count: Int): Int {
        var kept = 0
        for (index in 0 until count) {
            val value = samples[index]
            if (value.isFinite() && value > 0f) {
                samples[kept] = value
                if (weights != null) weights[kept] = weights[index]
                kept++
            }
        }
        return kept
    }

    private fun applyShoulder(samples: FloatArray, count: Int, shoulder: Float) {
        val strength = shoulder.coerceIn(0f, 1f)
        if (strength <= 0f) return
        for (index in 0 until count) {
            val value = samples[index]
            val compressed = AgxDisplayTransform.compressHighlight(value)
            samples[index] = if (strength >= 1f) compressed
            else value + strength * (compressed - value)
        }
    }

    /**
     * Spatial vote for one meter site: 1 + [strength] at ([focusX], [focusY])
     * (region-normalized, default the region center), decaying elliptically
     * toward 1 at the far corners (corners keep one vote each, so edges
     * still meter — they just no longer outvote the subject). Coordinates
     * are region-local with the region's own dims, so crops and quads meter
     * around their own center unless a tap moves the vote.
     */
    internal fun spatialWeight(
        x: Int, y: Int, width: Int, height: Int, strength: Float,
        focusX: Float = 0.5f, focusY: Float = 0.5f
    ): Float {
        if (strength <= 0f) return 1f
        val halfW = (width * 0.5f).coerceAtLeast(0.5f)
        val halfH = (height * 0.5f).coerceAtLeast(0.5f)
        val nx = (x + 0.5f - focusX * width) / halfW
        val ny = (y + 0.5f - focusY * height) / halfH
        val r2 = (nx * nx + ny * ny) * 0.5f
        return 1f + strength * kotlin.math.exp((-2f * r2).toDouble()).toFloat()
    }

    /**
     * Sorts [samples] ascending, permuting parallel [weights] alongside so
     * each weight stays glued to its value. Null weights keep the
     * allocation-free [Arrays.sort] path bit-exact; the weighted path pays
     * one transient index order (only armed when center weighting is on).
     */
    private fun sortSamples(samples: FloatArray, weights: FloatArray?, count: Int) {
        if (weights == null) {
            Arrays.sort(samples, 0, count)
            return
        }
        val order = (0 until count).sortedBy { samples[it] }
        val sortedValues = FloatArray(count) { i -> samples[order[i]] }
        val sortedWeights = FloatArray(count) { i -> weights[order[i]] }
        sortedValues.copyInto(samples, 0, 0, count)
        sortedWeights.copyInto(weights, 0, 0, count)
    }

    private fun percentile(sorted: FloatArray, count: Int, fraction: Double): Float =
        sorted[(((count - 1) * fraction).toInt()).coerceIn(0, count - 1)]

    private fun log2(value: Double): Double = ln(value) / LN_2
    private fun exp2(value: Double): Double = kotlin.math.exp(value * LN_2)

    /**
     * Exponential chase of a display EV toward a meter reading: big scene
     * moves (|err| > 1 EV, never flicker) snap the TARGET in one update — the
     * viewfinder's per-offer one-pole still glides the RENDER smoothly, so no
     * delay and no staircase — while small errors ease in at half rate, which
     * averages flicker oscillation out instead of pumping after it. Holds
     * inside [deadband]. Pure and unit-tested; the viewfinder calls it on
     * every meter update.
     */
    fun chaseToward(current: Float, reading: Float, deadband: Float): Float {
        require(deadband >= 0f)
        if (!current.isFinite()) return if (reading.isFinite()) reading else 0f
        if (!reading.isFinite()) return current
        val err = reading - current
        if (kotlin.math.abs(err) <= deadband) return current
        val alpha = if (kotlin.math.abs(err) > 1f) 1f else 0.5f
        return current + err * alpha
    }

    private const val MAX_SAMPLES = 65_536
    private const val MIN_SAMPLES = 64
    internal const val SHADOW_FLOOR = 1e-4f
    private const val TARGET_MIDDLE = 0.18
    // Defaults for [AdaptiveExposureTuning]; wired from JpegOutputSettings.
    // Previously hard 4.0 to "preserve room for AgX's shoulder", but that lifted garden
    // skies (p99.5=0.69) by +1.5EV to ~1.95x white which AgX maps to flat white.
    const val DEFAULT_HIGHLIGHT_HEADROOM = 1.0
    // Soft shoulder for broad highlights (p95 -> 0.85): large skies/walls keep
    // gradation instead of clipping when the spike guard alone would allow +1EV.
    const val DEFAULT_HIGHLIGHT_SOFT_HEADROOM = 0.85
    private const val MAX_CORRECTION_EV = 1.5
    // HDR-scene (single frame, e.g. backlit interior) allowances. These fire
    // only past the LDR-fit range with lit broad mass, so uniform and night
    // LDR content keeps the legacy cap and damping bit-exact.
    private const val HDR_SCENE_RANGE_EV = 4.0
    private const val HDR_SCENE_MAX_EV = 2.5
    private const val LOW_KEY_MEDIAN = 0.012
    private const val LOW_KEY_UPPER = 0.05
    private const val LOW_KEY_POSITIVE_SCALE = 0.25
    // Night-tier relief (single frame, e.g. lit platform or bus interior):
    // the p99 spike must stand this far clear of the p95 broad mass before
    // the tier applies, so uniform night and day content stay bit-exact.
    private const val NIGHT_SEP_MIN_EV = 2.0
    // Tier lamps may ride this high pre-AgX (shoulder, never flat white).
    private const val NIGHT_SPIKE_HEADROOM = 4.0
    // Tier lift never passes the LDR cap: lit night, never day.
    private const val NIGHT_TIER_MAX_EV = 1.5
    private const val LN_2 = 0.6931471805599453
}
