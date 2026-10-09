// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.ln
import kotlin.math.min

data class AdaptiveExposureResult(
    val correctionEv: Double,
    val logAverage: Double,
    val highlight: Double,
    val lowKey: Boolean
)

/** Tunable highlight guards, wired from [JpegOutputSettings]; defaults are sky-safe. */
data class AdaptiveExposureTuning(
    /** Hard white guard for the p99.5 spike. 1.0 = never push past white. */
    val highlightHeadroom: Double = 1.0,
    /** Soft shoulder for broad p95 highlight mass (skies/walls). */
    val highlightSoftHeadroom: Double = 0.85
) {
    init {
        require(highlightHeadroom.isFinite() && highlightSoftHeadroom.isFinite())
    }

    fun bounded(): AdaptiveExposureTuning = copy(
        highlightHeadroom = highlightHeadroom.coerceIn(0.5, 1.5),
        highlightSoftHeadroom = highlightSoftHeadroom.coerceIn(0.6, 1.0)
    )

    companion object {
        fun fromOutputSettings(settings: JpegOutputSettings): AdaptiveExposureTuning =
            AdaptiveExposureTuning(
                highlightHeadroom = settings.resolvedForPlatform().highlightHeadroom.toDouble(),
                highlightSoftHeadroom = settings.resolvedForPlatform().highlightSoftHeadroom.toDouble()
            ).bounded()
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
    val ldrFitRangeEv: Double = 4.0
) {
    init {
        require(spikeHeadroom.isFinite() && broadHeadroom.isFinite() &&
            maxCorrectionEv.isFinite() && ldrLiftBudgetEv.isFinite() &&
            ldrFitRangeEv.isFinite())
        require(spikeHeadroom > 0.0 && broadHeadroom > 0.0 && maxCorrectionEv > 0.0)
    }

    fun bounded(): HdrAdaptiveTuning = copy(
        spikeHeadroom = spikeHeadroom.coerceIn(1.0, 32.0),
        broadHeadroom = broadHeadroom.coerceIn(0.6, 4.0),
        maxCorrectionEv = maxCorrectionEv.coerceIn(1.5, 8.0),
        ldrLiftBudgetEv = ldrLiftBudgetEv.coerceIn(0.0, 3.0),
        ldrFitRangeEv = ldrFitRangeEv.coerceIn(2.0, 8.0)
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
        var count = 0
        var index = 0
        while (index < cfa.values.size) {
            val value = cfa.values[index]
            if (value.isFinite() && value > SHADOW_FLOOR) samples[count++] = value
            index += stride
        }
        return analyzeSamples(samples, count, tuning, ettrHeadroomEv)
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
            if (value.isFinite() && value > SHADOW_FLOOR) samples[count++] = value
            index += stride
        }
        return analyzeSamples(samples, count, tuning, ettrHeadroomEv)
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
        var count = 0
        var index = 0
        while (index < cfa.values.size) {
            val value = cfa.values[index] * exposureGain
            if (value.isFinite() && value > SHADOW_FLOOR) samples[count++] = value.toFloat()
            index += stride
        }
        return analyzeHdrSamples(samples, count, tuning)
    }

    internal fun analyzeHdrSamples(
        samples: FloatArray,
        count: Int,
        tuning: HdrAdaptiveTuning = HdrAdaptiveTuning()
    ): AdaptiveExposureResult {
        if (count < MIN_SAMPLES) {
            return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        }
        Arrays.sort(samples, 0, count)
        val low = percentile(samples, count, 0.05)
        val highForAverage = percentile(samples, count, 0.95)
        var logSum = 0.0
        var logCount = 0
        for (index in 0 until count) {
            val value = samples[index]
            if (value in low..highForAverage) {
                logSum += log2(value.toDouble())
                logCount++
            }
        }
        if (logCount == 0) return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        val logAverage = logSum / logCount
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
     *   or null when the frame was not ETTR-exposed. ETTR frames are already optimally
     *   bright by construction (the loop placed the hottest channel at 2^-headroom),
     *   so positive develop lift only erodes the headroom ETTR deliberately kept and
     *   washes bright skies white. Non-null applies two tiers: frames whose measured
     *   highlights confirm ETTR-like brightness (p99.5 at/above [ETTR_VERIFY_HIGHLIGHT])
     *   keep zero positive lift — the exposure is already right; darker frames (stale
     *   loop, ISO-limited night) fall back to the residual-to-target cap, which
     *   degrades to the legacy clamp instead of leaving them dark.
     */
    internal fun analyzeSamples(
        samples: FloatArray,
        count: Int,
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
        ettrHeadroomEv: Float? = null
    ): AdaptiveExposureResult {
        if (count < MIN_SAMPLES) {
            return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        }
        Arrays.sort(samples, 0, count)
        val low = percentile(samples, count, 0.05)
        val highForAverage = percentile(samples, count, 0.95)
        var logSum = 0.0
        var logCount = 0
        for (index in 0 until count) {
            val value = samples[index]
            if (value in low..highForAverage) {
                logSum += log2(value.toDouble())
                logCount++
            }
        }
        if (logCount == 0) return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        val logAverage = logSum / logCount
        val highlight = percentile(samples, count, 0.995).toDouble()
        // Broad-area highlight level (sky, walls): keeps large bright regions
        // from flattening even when the p99.5 spike itself is still safe.
        val highlightBroad = percentile(samples, count, 0.95).toDouble()
        var correction = log2(TARGET_MIDDLE / exp2(logAverage))
        // Do not move the measured upper tail past display-referred white before AgX rolls it off.
        // Hard guard on the spike (p99.5 -> headroom) plus a soft shoulder on the broad
        // highlight mass (p95 -> soft headroom) so skies keep gradation instead of flat white.
        val bounded = tuning.bounded()
        correction = min(correction, log2(bounded.highlightHeadroom / highlight.coerceAtLeast(1e-6)))
        correction = min(correction, log2(bounded.highlightSoftHeadroom / highlightBroad.coerceAtLeast(1e-6)))
        if (ettrHeadroomEv != null) {
            require(ettrHeadroomEv.isFinite()) { "ETTR headroom must be finite" }
            correction = min(
                correction,
                log2(RawEttrMeter.targetLevel(ettrHeadroomEv) / highlight.coerceAtLeast(1e-6))
            )
            // Verified ETTR brightness: the loop's exposure is already right,
            // so no positive develop lift at all (a bright haze gradient needs
            // ~0 EV to stay blue; any lift pushes it past white). Absolute
            // level, deliberately headroom-independent: big-headroom frames
            // stay dark enough to miss it and keep the residual tier above.
            if (highlight >= ETTR_VERIFY_HIGHLIGHT) correction = min(correction, 0.0)
        }

        val median = percentile(samples, count, 0.50).toDouble()
        val upper = percentile(samples, count, 0.90).toDouble()
        val lowKey = median < LOW_KEY_MEDIAN && upper < LOW_KEY_UPPER
        if (lowKey && correction > 0.0) correction *= LOW_KEY_POSITIVE_SCALE
        correction = correction.coerceIn(-MAX_CORRECTION_EV, MAX_CORRECTION_EV)
        return AdaptiveExposureResult(correction, logAverage, highlight, lowKey)
    }

    private fun percentile(sorted: FloatArray, count: Int, fraction: Double): Float =
        sorted[(((count - 1) * fraction).toInt()).coerceIn(0, count - 1)]

    private fun log2(value: Double): Double = ln(value) / LN_2
    private fun exp2(value: Double): Double = kotlin.math.exp(value * LN_2)

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
    /**
     * p99.5 level confirming ETTR-like brightness: ETTR-daylight frames sit
     * at 0.55+ while stale/limited dark frames sit well below, so verified
     * frames take zero positive lift and dark ones keep the residual tier.
     */
    private const val ETTR_VERIFY_HIGHLIGHT = 0.45
    private const val LOW_KEY_MEDIAN = 0.012
    private const val LOW_KEY_UPPER = 0.05
    private const val LOW_KEY_POSITIVE_SCALE = 0.25
    private const val LN_2 = 0.6931471805599453
}
