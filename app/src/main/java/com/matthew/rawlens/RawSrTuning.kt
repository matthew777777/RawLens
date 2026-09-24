// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.sqrt

/** IPOL equations 22–24: LINEAR SNR, not dB. All spatial kernel constants retain RAW units. */
@ConsistentCopyVisibility
data class RawSrTuning private constructor(
    val snr: Double,
    val rawTileSize: Int,
    val kDetail: Double,
    val kDenoise: Double,
    val dTh: Double,
    val dTr: Double
) {
    val alignmentTileQuads: Int get() = rawTileSize / RAW_PIXELS_PER_QUAD
    val kStretch: Double get() = K_STRETCH
    val kShrink: Double get() = K_SHRINK
    val t: Double get() = T
    val s1: Double get() = S1
    val s2: Double get() = S2
    val mTh: Double get() = M_TH

    /** Do not retune search/Hessian/residual thresholds with SNR; only tile size changes here. */
    fun alignmentConfig() = RawSrAlignmentConfig(tileSize = alignmentTileQuads)

    enum class Status { ESTIMATED, ZERO_NOISE, MISSING_PROFILE, INVALID_PROFILE, INVALID_BRIGHTNESS }
    data class Estimate(val tuning: RawSrTuning, val brightness: Double?,
                        val linearSnr: Double?, val variance: Double?, val status: Status) {
        fun debugSummary() = "RAW SR SNR status=$status brightness=$brightness linearSnr=$linearSnr " +
            "variance=$variance clippedSnr=${tuning.snr} tileRaw=${tuning.rawTileSize} " +
            "tileQuads=${tuning.alignmentTileQuads} kDetail=${tuning.kDetail} kDenoise=${tuning.kDenoise} " +
            "Dth=${tuning.dTh} Dtr=${tuning.dTr} kStretch=${tuning.kStretch} kShrink=${tuning.kShrink} " +
            "t=${tuning.t} s1=${tuning.s1} s2=${tuning.s2} Mth=${tuning.mTh}"
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

        fun forSnr(linearSnr: Double): RawSrTuning {
            require(!linearSnr.isNaN()) { "SNR must not be NaN" }
            val clipped = linearSnr.coerceIn(MIN_SNR, MAX_SNR)
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
         * Camera2 has four raster-CFA S/O pairs; DNG fixtures may have three RGB pairs.
         * Evaluate the captured model at normalized pre-LSC mean brightness. Average
         * per-sensor-sample variances: R:G:B = 1:2:1. Do NOT divide by four again (that
         * would estimate the noise of an averaged quad and inflate single-frame SNR).
         * This analytic Camera2 sigma is not IPOL's future clipped-noise Monte Carlo LUT.
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
            val snr = if (brightness == 0.0) 0.0 else if (variance == 0.0) Double.POSITIVE_INFINITY
                else brightness / sqrt(variance)
            return Estimate(forSnr(snr), brightness, snr, variance,
                if (variance == 0.0 && brightness > 0.0) Status.ZERO_NOISE else Status.ESTIMATED)
        }

        /** Constant-memory reference scan. Never mutate/copy the Camera2 plane or apply lens gains. */
        fun fromReference(reference: RawSrPackedFrame): Estimate {
            require(reference.width % 2 == 0 && reference.height % 2 == 0) { "SNR sampling requires complete Bayer quads" }
            val input = reference.uploadInput()
            val source = input.buffer.duplicate().order(ByteOrder.nativeOrder())
            val base = source.position()
            val crop = input.crop
            var brightness = 0.0
            for (y in 0 until crop.height) for (x in 0 until crop.width) {
                val offset = base + (crop.top + y) * input.layout.rowStride + (crop.left + x) * input.layout.pixelStride
                val code = source.getShort(offset).toInt() and 65535
                val black = input.normalization.blackAt(input.sensorCropLeft + x, input.sensorCropTop + y).toDouble()
                brightness += ((code - black) / (input.normalization.whiteLevel - black)).coerceIn(0.0, 1.0)
            }
            return estimate(brightness / (crop.width.toLong() * crop.height), reference.noiseProfile)
        }
    }
}
