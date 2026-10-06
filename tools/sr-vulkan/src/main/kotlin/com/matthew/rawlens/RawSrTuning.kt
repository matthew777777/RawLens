// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Reference-parity tuning (Jamy-L `params.update_snr_config` +
 * `utils_image.estimate_image_snr`): SNR is measured in dB from per-sample
 * normalized values over valid (0,1)-exclusive pixels,
 * `20*log10(sqrt(sum(v^2)/sum(a*v+b)))`, clipped to [6,30] dB. The tile-size
 * steps (64/32/16 at 14/22 dB) and the kDetail/kDenoise/Dth/Dtr lerps below
 * are the reference endpoints verbatim. `Estimate.linearSnr` reports the
 * exact linear equivalent (`10^(dB/20)`) for diagnostics; `forSnr` takes dB.
 *
 * Two deliberate extensions sit beside the reference path (both default off):
 * a decoupled flat-region kernel radius ([flatSigma]) so flat smoothing can
 * be retuned without moving edge sharpness, and fully fixed tunings ([fixed],
 * [decoupledSharp]) for A/B presets. `forSnr`/`estimate`/`fromReference`
 * always return the coupled reference form (`flatSigma == null`,
 * `detailFloor == null`, constants at their reference values).
 */
@ConsistentCopyVisibility
data class RawSrTuning private constructor(
    val snr: Double,
    val rawTileSize: Int,
    val kDetail: Double,
    val kDenoise: Double,
    val dTh: Double,
    val dTr: Double,
    val kStretch: Double = K_STRETCH,
    val kShrink: Double = K_SHRINK,
    val t: Double = T,
    val s1: Double = S1,
    val s2: Double = S2,
    val mTh: Double = M_TH,
    /**
     * Decoupled flat-region kernel radius in kDetail units, or null for the
     * coupled Jamy-L form (flat radius = kDetail * kDenoise). Non-null
     * blends `(1-d) * kDetail * axis + d * flatSigma`, so edges stay
     * kDetail-sharp while flats smooth at [flatSigma]. See
     * [RawSrKernelCovariance] for the exact law.
     */
    val flatSigma: Double? = null,
    /**
     * Minimum kernel radius along either axis, or null for no floor
     * (reference). Guardrail against degenerate razor radii (kDetail /
     * kShrink can reach ~0.01): without it a single tap wins by
     * hundreds of nats and any subpixel misregistration turns into
     * latch teeth. Wide (flat) kernels never bind it. Note the floor
     * is not a smoothing control: undersmoothed flats (too-small
     * flatSigma for the scene SNR) dominate visible speckle far more.
     */
    val detailFloor: Double? = null,
    /**
     * Bilateral flow-field regularization sigma in raw px (RawLens
     * improvement, not reference): each tile's vector is replaced by a
     * 3x3 bilateral average (range kernel on the vector difference),
     * collapsing sub-sigma chatter steps that render as tile seams
     * while preserving supra-sigma motion discontinuities. Applies to
     * the sensor-space field before robustness and merge (both stay
     * consistent). Null (reference) disables.
     */
    val flowRegularizeSigma: Double? = null
) {
    val alignmentTileQuads: Int get() = rawTileSize / RAW_PIXELS_PER_QUAD

    /** Copies this tuning with a decoupled flat radius; rejects non-positive input. */
    fun withFlatSigma(flatSigma: Double): RawSrTuning {
        require(flatSigma.isFinite() && flatSigma > 0.0) { "flatSigma must be finite and positive" }
        return copy(flatSigma = flatSigma)
    }

    /** Copies this tuning with a new detail radius; rejects non-positive input. */
    fun withKDetail(kDetail: Double): RawSrTuning {
        require(kDetail.isFinite() && kDetail > 0.0) { "kDetail must be finite and positive" }
        return copy(kDetail = kDetail)
    }

    /** Copies this tuning with a detail floor; rejects non-positive input. */
    fun withDetailFloor(detailFloor: Double): RawSrTuning {
        require(detailFloor.isFinite() && detailFloor > 0.0) { "detailFloor must be finite and positive" }
        return copy(detailFloor = detailFloor)
    }

    /** Copies this tuning with a regularization sigma; rejects non-positive input. */
    fun withFlowRegularizeSigma(flowRegularizeSigma: Double): RawSrTuning {
        require(flowRegularizeSigma.isFinite() && flowRegularizeSigma > 0.0) {
            "flowRegularizeSigma must be finite and positive"
        }
        return copy(flowRegularizeSigma = flowRegularizeSigma)
    }

    /**
     * Do not retune search/Hessian/residual thresholds with SNR; only tile size changes here.
     * Tile size is RAW pixels (reference `update_snr_config` {64, 32, 16});
     * passing [alignmentTileQuads] here halved every tile and crashed for
     * high-SNR tuning (8 is not a valid checkout tile size).
     */
    fun alignmentConfig() = RawSrAlignmentConfig(tileSize = rawTileSize)

    /**
     * One-line tuning record for DNG provenance blocks (RAWR-style PARAMETERS
     * section): every kernel/robustness constant plus the SNR the adaptive
     * path scanned (fixed presets record their label SNR instead).
     */
    fun describe(): String = "tileRaw=$rawTileSize kDetail=$kDetail kDenoise=$kDenoise " +
        "Dth=$dTh Dtr=$dTr kStretch=$kStretch kShrink=$kShrink t=$t s1=$s1 s2=$s2 " +
        "mTh=$mTh flatSigma=$flatSigma detailFloor=$detailFloor flowReg=$flowRegularizeSigma snr=$snr"

    enum class Status { ESTIMATED, ZERO_NOISE, MISSING_PROFILE, INVALID_PROFILE, INVALID_BRIGHTNESS }
    data class Estimate(val tuning: RawSrTuning, val brightness: Double?,
                        val linearSnr: Double?, val variance: Double?, val status: Status) {
        fun debugSummary() = "RAW SR SNR status=$status brightness=$brightness linearSnr=$linearSnr " +
            "variance=$variance clippedSnr=${tuning.snr} tileRaw=${tuning.rawTileSize} " +
            "tileQuads=${tuning.alignmentTileQuads} kDetail=${tuning.kDetail} kDenoise=${tuning.kDenoise} " +
            "Dth=${tuning.dTh} Dtr=${tuning.dTr} kStretch=${tuning.kStretch} kShrink=${tuning.kShrink} " +
            "t=${tuning.t} s1=${tuning.s1} s2=${tuning.s2} Mth=${tuning.mTh} flatSigma=${tuning.flatSigma} " +
            "DetailFloor=${tuning.detailFloor} flowReg=${tuning.flowRegularizeSigma}"
    }

    companion object {
        const val MIN_SNR = 6.0
        const val MAX_SNR = 30.0
        const val RAW_PIXELS_PER_QUAD = 2
        const val K_STRETCH = 4.0
        const val K_SHRINK = 2.0
        const val T = 0.12
        const val S1 = 2.0
        const val S2 = 12.0
        const val M_TH = 0.8

        /**
         * Fully fixed tuning for A/B presets: every constant settable,
         * including the decoupled [flatSigma]. Tile size stays on the
         * reference 64/32/16 steps (the alignment shader contract);
         * [snrDb] is a diagnostics label only (fixed tunings never adapt).
         */
        fun fixed(
            rawTileSize: Int = 16,
            kDetail: Double,
            kDenoise: Double,
            dTh: Double,
            dTr: Double,
            kStretch: Double = K_STRETCH,
            kShrink: Double = K_SHRINK,
            t: Double = T,
            s1: Double = S1,
            s2: Double = S2,
            mTh: Double = M_TH,
            flatSigma: Double? = null,
            detailFloor: Double? = null,
            flowRegularizeSigma: Double? = null,
            snrDb: Double = MAX_SNR
        ): RawSrTuning {
            require(rawTileSize in listOf(16, 32, 64)) {
                "Fixed tile size must be a reference step (16/32/64 raw px)"
            }
            for ((name, value) in listOf(
                "kDetail" to kDetail, "kDenoise" to kDenoise, "dTh" to dTh, "dTr" to dTr,
                "kStretch" to kStretch, "kShrink" to kShrink, "t" to t, "s1" to s1,
                "s2" to s2, "mTh" to mTh)) {
                require(value.isFinite() && value > 0.0) { "$name must be finite and positive" }
            }
            if (flatSigma != null) {
                require(flatSigma.isFinite() && flatSigma > 0.0) {
                    "flatSigma must be finite and positive"
                }
            }
            if (detailFloor != null) {
                require(detailFloor.isFinite() && detailFloor > 0.0) {
                    "detailFloor must be finite and positive"
                }
            }
            if (flowRegularizeSigma != null) {
                require(flowRegularizeSigma.isFinite() && flowRegularizeSigma > 0.0) {
                    "flowRegularizeSigma must be finite and positive"
                }
            }
            require(snrDb.isFinite()) { "snrDb must be finite (diagnostics label)" }
            return RawSrTuning(snrDb, rawTileSize, kDetail, kDenoise, dTh, dTr,
                kStretch, kShrink, t, s1, s2, mTh, flatSigma, detailFloor, flowRegularizeSigma)
        }

        /**
         * Fixed decoupled-sharp preset measured from RAWR MF12 multiframe
         * EXIF (2026-09-25): kDetail 0.08, kDenoise 5.0, Dth/Dtr 0.25/0.30,
         * kStretch/kShrink 1.0/8.0, robustness T/S1/S2 0.09/1.5/9.0, motion
         * threshold 0.65, flat sigma 0.5 (decoupled). Detail floor 0.4 is a
         * RawLens latch-guardrail addition (RAWR EXIF: floor off); without
         * it the 0.08/8 across-edge radii reach ~0.01. The default flat
         * reproduces RAWR EXIF (0.5, mid-SNR); production passes the
         * scene's scanned width instead ([RawSrKernelPreset.resolve]), so
         * bright bursts keep reference-caliber flats. Caveats:
         * EXIF carries no tile size (16 raw px matches the high-SNR
         * reference step); mTh is read as raw pixels like the reference
         * M_TH; the measured pipeline's kernel-guide scaling may differ
         * from ours, so validate on device before treating this as more
         * than an A/B starting point.
         */
        fun decoupledSharp(flatSigma: Double = 0.5, snrDb: Double = MAX_SNR): RawSrTuning = fixed(
            rawTileSize = 16, kDetail = 0.08, kDenoise = 5.0, dTh = 0.25, dTr = 0.30,
            kStretch = 1.0, kShrink = 8.0, t = 0.09, s1 = 1.5, s2 = 9.0, mTh = 0.65,
            flatSigma = flatSigma, detailFloor = 0.4, snrDb = snrDb)

        /**
         * Reference `update_snr_config` verbatim: [snrDb] is dB, clipped to
         * [6,30]; tile 64/32/16 raw px; kDetail [0.33,0.25], kDenoise [5,3],
         * Dth [0.81,0.71], Dtr [1.24,1.0] lerped over the dB range.
         */
        fun forSnr(snrDb: Double): RawSrTuning {
            require(!snrDb.isNaN()) { "SNR must not be NaN" }
            val clipped = snrDb.coerceIn(MIN_SNR, MAX_SNR)
            val fraction = (clipped - MIN_SNR) / (MAX_SNR - MIN_SNR)
            fun interpolate(low: Double, high: Double) = low + fraction * (high - low)
            return RawSrTuning(clipped, when {
                clipped <= 14.0 -> 64
                clipped <= 22.0 -> 32
                else -> 16
            }, interpolate(0.33, 0.25), interpolate(5.0, 3.0),
                interpolate(0.81, 0.71), interpolate(1.24, 1.0))
        }

        /**
         * Analytic adapter over a NORMALIZED-domain S/O profile (reference
         * alpha/beta convention: variance = S*v + O at normalized v, as in
         * DNG NoiseProfile tags and `estimate_image_snr`). Four raster-CFA
         * pairs or three RGB pairs (R:G:B = 1:2:1 averaged per
         * sensor sample). This is NOT the Camera2 code-domain profile —
         * code-domain S/O must go through [fromReference], which normalizes
         * via [RawSrCovarianceGuide.noiseTables]; feeding codes here
         * understates noise by ~white². Evaluate the model at normalized
         * pre-LSC mean brightness. Do NOT divide by four again (that would
         * estimate the noise of an averaged quad and inflate single-frame
         * SNR). Production's only caller passes null (explicit low-SNR
         * fallback); the merge path tunes via [fromReference].
         */
        fun estimate(normalizedBrightness: Double, noiseProfile: ImmutableDoubleValues?): Estimate {
            fun fallback(status: Status, brightness: Double?) =
                Estimate(forSnr(MIN_SNR), brightness, null, null, status)
            if (!normalizedBrightness.isFinite())
                return fallback(Status.INVALID_BRIGHTNESS, null)
            val brightness = normalizedBrightness.coerceIn(0.0, 1.0)
            if (noiseProfile == null) return fallback(Status.MISSING_PROFILE, brightness)
            val profile = noiseProfile.toDoubleArray()
            if (profile.size !in listOf(6, 8) || profile.any { !it.isFinite() || it < 0.0 })
                return fallback(Status.INVALID_PROFILE, brightness)
            var variance = 0.0
            for (phase in 0..3) {
                // Eight coefficients are four CFA phases, six are DNG R/G/B.
                val channel = if (profile.size == 8) phase else intArrayOf(0, 1, 1, 2)[phase]
                variance += (profile[channel * 2] * brightness + profile[channel * 2 + 1]) * 0.25
            }
            if (!variance.isFinite()) return fallback(Status.INVALID_PROFILE, brightness)
            val linear = if (brightness == 0.0) 0.0 else if (variance == 0.0) Double.POSITIVE_INFINITY
                else brightness / sqrt(variance)
            // Analytic adapter: mean-brightness linear ratio converted to the
            // dB domain `forSnr` tunes on (extreme outcomes unchanged: 0 dB ->
            // MIN clip, +inf dB -> MAX clip, matching the old linear clips).
            val snrDb = 20.0 * kotlin.math.log10(linear)
            return Estimate(forSnr(snrDb), brightness, linear, variance,
                if (variance == 0.0 && brightness > 0.0) Status.ZERO_NOISE else Status.ESTIMATED)
        }

        /**
         * Reference `estimate_image_snr` (constant-memory scan): per-sample
         * normalized value `v`, valid pixels only (`0 < v < 1`, exclusive),
         * per-phase normalized model `a*v + b`, `SNRdB =
         * 20*log10(sqrt(sum(v^2)/sum(a*v+b)))`. Never mutates/copies the
         * Camera2 plane or applies lens gains. The phase lookup is
         * pattern-aware (identical to the reference on even-origin RGGB).
         * No valid pixel (fully clipped frame) falls back to MIN_SNR, mirroring
         * the reference NaN warn-and-clip; `linearSnr` reports the exact
         * linear equivalent `10^(dB/20)`.
         */
        fun fromReference(reference: RawSrPackedFrame): Estimate {
            require(reference.width % 2 == 0 && reference.height % 2 == 0) { "SNR sampling requires complete Bayer quads" }
            val input = reference.uploadInput()
            fun fallback(status: Status, brightness: Double?) =
                Estimate(forSnr(MIN_SNR), brightness, null, null, status)
            val tables = RawSrCovarianceGuide.noiseTables(input, reference.noiseProfile)
            when (tables.modelClass) {
                RawSrCovarianceGuide.ModelClass.MISSING ->
                    return fallback(Status.MISSING_PROFILE, null)
                RawSrCovarianceGuide.ModelClass.INVALID ->
                    return fallback(Status.INVALID_PROFILE, null)
                else -> {}
            }
            val source = input.buffer.duplicate().order(ByteOrder.nativeOrder())
            val base = source.position()
            val crop = input.crop
            var brightness = 0.0
            var top = 0.0
            var bot = 0.0
            var valid = 0L
            for (y in 0 until crop.height) for (x in 0 until crop.width) {
                val offset = base + (crop.top + y) * input.layout.rowStride + (crop.left + x) * input.layout.pixelStride
                val code = source.getShort(offset).toInt() and 65535
                val black = input.normalization.blackAt(input.sensorCropLeft + x, input.sensorCropTop + y).toDouble()
                val white = input.normalization.whiteLevel.toDouble()
                val v = (code - black) / (white - black)
                brightness += v.coerceIn(0.0, 1.0)
                if (v > 0.0 && v < 1.0) {
                    val phase = ((y and 1) shl 1) or (x and 1)
                    top += v * v
                    bot += tables.alpha[phase] * v + tables.beta[phase]
                    valid++
                }
            }
            val meanBrightness = brightness / (crop.width.toLong() * crop.height)
            val zeroNoise = tables.modelClass == RawSrCovarianceGuide.ModelClass.ZERO_NOISE
            if (zeroNoise && top > 0.0 && top.isFinite()) {
                // Reference zero-noise limit: signal over zero variance clips
                // to MAX_SNR, exactly like the analytic adapter below.
                return Estimate(forSnr(Double.POSITIVE_INFINITY), meanBrightness,
                    Double.POSITIVE_INFINITY, 0.0, Status.ZERO_NOISE)
            }
            if (valid == 0L || top <= 0.0 || bot <= 0.0 || !top.isFinite() || !bot.isFinite()) {
                return fallback(Status.INVALID_BRIGHTNESS, meanBrightness)
            }
            val linear = sqrt(top / bot)
            val snrDb = 20.0 * kotlin.math.log10(linear)
            if (!snrDb.isFinite()) return fallback(Status.INVALID_BRIGHTNESS, meanBrightness)
            return Estimate(forSnr(snrDb), meanBrightness, linear, bot / valid, Status.ESTIMATED)
        }
    }
}
