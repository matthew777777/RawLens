// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.pow

/**
 * Settings for RAW-based Expose-To-The-Right single capture.
 *
 * Unlike the PROGRAM custom curve (which drives sensor ISO + shutter live from
 * RAW mid-tone brightness), ETTR measures the sensor mosaic itself: per-channel saturation after black
 * subtraction at the 99.9th percentile, then pushes total exposure until the hottest
 * meaningful CFA channel sits [headroomEv] below clipping.
 */
data class EttrSettings(
    val enabled: Boolean = false,
    /** Safety margin below clipping, in EV, to absorb demosaic overshoot. */
    val headroomEv: Float = 0.3f,
    /**
     * Gain ceiling for ETTR brightening, mirroring the PROGRAM ISO ceiling.
     * Zero means the sensor maximum. When the ceiling binds, ETTR keeps the
     * hand-motion shutter cap and accepts a darker (sharp) frame rather than
     * trading noise for blur.
     */
    val isoLimit: Int = 0,
    /**
     * Reconstruction allowance: the hottest CFA channel may kiss white (a small
     * clipped fraction) while every other channel must stay valid, so highlight
     * inpainting has donor channels to rebuild from. Off by default; this
     * deliberately trades a whisper of green (usually) for fatter shadows.
     */
    val allowSingleChannelClip: Boolean = false
)

/** Per-channel mosaic levels, normalized to 0..1 after black subtraction. */
data class EttrChannelLevels(
    val r: Float,
    val gr: Float,
    val gb: Float,
    val b: Float
) {
    val hottest: Float get() = maxOf(r, gr, gb, b)
}

internal data class EttrLimits(
    val isoMin: Int,
    val isoMax: Int,
    val shutterMinNanos: Long,
    val shutterMaxNanos: Long,
    /** Hand-motion ceiling; never exceeded when brightening. */
    val safeShutterNanos: Long
)

internal data class EttrExposure(
    val iso: Int,
    val shutterNanos: Long,
    val converged: Boolean,
    val hottestChannel: Float,
    /** EV shift actually applied to the baseline for this step. */
    val appliedShiftEv: Double
)

/**
 * Pure ETTR exposure solver.
 *
 * Priority is fixed: longest safe shutter first, then the lowest useful sensor gain,
 * then analog ISO only once the safe shutter ceiling is reached. Darkening mirrors
 * it: gain falls to minimum before the shutter shortens, keeping the well as full
 * as highlight safety allows.
 */
internal object RawEttrMeter {
    /** Percentile of each channel's distribution treated as "meaningful" signal. */
    const val TARGET_PERCENTILE = 0.999
    /** Per-update clamp so the closed loop cannot oscillate across frames. */
    const val MAX_STEP_EV = 2.0
    /** Residual below this counts as converged (about 1/6 stop). */
    const val CONVERGED_TOLERANCE_EV = 0.15

    fun targetLevel(headroomEv: Float): Double =
        2.0.pow(-headroomEv.coerceIn(0f, 2f).toDouble())

    /**
     * EV shift needed to move [hottest] (normalized 0..1 percentile level) to the
     * headroom target. Positive means brighten. Zero/negative hottest means no
     * measurable signal: no shift.
     */
    fun correctionEv(hottest: Float, headroomEv: Float): Double {
        if (!hottest.isFinite() || hottest <= 0f) return 0.0
        val target = targetLevel(headroomEv)
        return (ln(target / hottest.coerceAtLeast(1e-6f)) / ln(2.0))
            .coerceIn(-MAX_STEP_EV, MAX_STEP_EV)
    }

    fun solve(
        baselineIso: Int,
        baselineShutterNanos: Long,
        correctionEv: Double,
        limits: EttrLimits,
        hottestChannel: Float = Float.NaN
    ): EttrExposure {
        val safeCeiling = limits.safeShutterNanos
            .coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        val isoMin = limits.isoMin.coerceAtLeast(1)
        val isoMax = limits.isoMax.coerceAtLeast(isoMin)
        val baseIso = baselineIso.coerceIn(isoMin, isoMax)
        val baseShutter = baselineShutterNanos
            .coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        val desired = baseIso.toDouble() * baseShutter * 2.0.pow(correctionEv)

        // Shutter has first claim at minimum gain, capped by the hand-motion ceiling.
        var shutter = (desired / isoMin)
            .coerceIn(limits.shutterMinNanos.toDouble(), safeCeiling.toDouble())
        var iso = desired / shutter
        var limited = false
        if (iso > isoMax) {
            // Safe shutter is exhausted: raise gain, then (if still starved) admit that
            // the ceilings prevent reaching the target. The shutter never crosses the
            // hand-motion cap to compensate: motion blur destroys detail that ETTR is
            // trying to preserve, while a darker frame can still be pushed in post.
            iso = isoMax.toDouble()
            shutter = (desired / iso).coerceIn(
                limits.shutterMinNanos.toDouble(), safeCeiling.toDouble()
            )
            limited = shutter < desired / iso
        } else if (iso < isoMin) {
            iso = isoMin.toDouble()
            shutter = (desired / iso).coerceIn(
                limits.shutterMinNanos.toDouble(), safeCeiling.toDouble()
            )
        }
        val finalIso = iso.toInt().coerceIn(isoMin, isoMax)
        val finalShutter = shutter.toLong().coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        val applied = ln((finalIso.toDouble() * finalShutter) / (baseIso * baseShutter)) / ln(2.0)
        val residual = correctionEv - applied
        return EttrExposure(
            iso = finalIso,
            shutterNanos = finalShutter,
            converged = !limited && kotlin.math.abs(residual) <= CONVERGED_TOLERANCE_EV,
            hottestChannel = hottestChannel,
            appliedShiftEv = applied
        )
    }

    /**
     * Longest handheld-safe exposure from gyro motion and lens geometry.
     *
     * The allowable angular blur scales inversely with equivalent focal length
     * (longer lenses magnify shake) and doubles with optical stabilization.
     * Readings inside the gyro bias/drift band ([MOTION_DEADBAND_RPS]) carry no
     * hand-motion information — phone gyro bias instability alone is ~0.002 rad/s —
     * so a near-stationary hand falls back to [ceilingNanos]; anything real is
     * capped by blur allowance / motion.
     */
    fun safeShutterNanos(
        motionRadiansPerSecond: Float,
        focalLength35mmEquiv: Float,
        oisEnabled: Boolean,
        ceilingNanos: Long,
        sensorMinNanos: Long,
        sensorMaxNanos: Long
    ): Long {
        val ceiling = ceilingNanos.coerceIn(sensorMinNanos, sensorMaxNanos)
        if (!motionRadiansPerSecond.isFinite() || motionRadiansPerSecond <= MOTION_DEADBAND_RPS) {
            return ceiling
        }
        val focalScale = (24f / focalLength35mmEquiv.coerceIn(10f, 200f)).coerceIn(0.25f, 2f)
        var allowRadians = BASE_BLUR_ALLOWANCE_RADIANS * focalScale
        if (oisEnabled) allowRadians *= OIS_BLUR_RELAXATION
        val motionCap = (allowRadians / motionRadiansPerSecond * 1e9).toLong()
        return motionCap.coerceIn(sensorMinNanos, ceiling)
    }

    /** Gyro readings at or below this are bias/drift, not hands. */
    const val MOTION_DEADBAND_RPS = 0.01f
    private const val BASE_BLUR_ALLOWANCE_RADIANS = 0.006f
    private const val OIS_BLUR_RELAXATION = 2f

    /**
     * Reconstruction-allowance band: the hottest color may clip up to [REC_CLIP_HI]
     * (0.1%) while every other color must stay below [REC_SECOND_EPS]. Colors are
     * R, pooled green, B — Gr/Gb are one color for reconstruction, so green
     * blowing in both Bayer phases still leaves R and B as donors. The search
     * walks down from +[MAX_STEP_EV] in [REC_STEP_EV] increments and takes the
     * largest gain satisfying both, so a cliff tail (no gain inside the band) holds
     * at unity instead of jumping it, and a second color touching white vetoes
     * any brightening. Measured saturation counts at every gain: the AE frame
     * proved those pixels, so no lower estimate un-proves them.
     */
    const val REC_CLIP_HI = 1e-3
    const val REC_SECOND_EPS = 1e-4
    const val REC_STEP_EV = 1.0 / 12.0

    /**
     * Fraction of this channel's samples that would sit at white under [gain]:
     * measured-saturated pixels plus bins the gain pushes over the top. Bin 255
     * holds both, so its non-saturated part is prorated by how far past white the
     * gain reaches into it.
     */
    fun clipFractionAtGain(bins: IntArray, saturated: Int, total: Int, gain: Double): Double {
        if (total <= 0 || gain <= 0.0) return 0.0
        var tail = saturated.toDouble()
        for (b in bins.size - 2 downTo 0) {
            if ((b + 0.5) / bins.size * gain < 1.0) break
            tail += bins[b]
        }
        if (gain >= 1.0) {
            val topBin = (bins[bins.size - 1] - saturated).coerceAtLeast(0)
            tail += topBin * ((1.0 - 1.0 / gain) * bins.size).coerceIn(0.0, 1.0)
        }
        return tail / total
    }

    fun gainForClipBand(
        bins: Array<IntArray>,
        saturated: IntArray,
        totals: IntArray,
        hi: Double = REC_CLIP_HI,
        secondEps: Double = REC_SECOND_EPS,
        maxGainEv: Double = MAX_STEP_EV,
        minGainEv: Double = -MAX_STEP_EV,
        stepEv: Double = REC_STEP_EV
    ): Double {
        // Judge R, pooled green, B: bins arrive in canonical R/Gr/Gb/B order.
        val pooled = poolGreen(bins, saturated, totals)
        var gainDb = maxGainEv
        while (gainDb >= minGainEv - 1e-9) {
            val gain = 2.0.pow(gainDb)
            var hottest = 0.0
            var second = 0.0
            for (entry in pooled) {
                val fraction = clipFractionAtGain(entry.bins, entry.saturated, entry.total, gain)
                if (fraction > hottest) {
                    second = hottest
                    hottest = fraction
                } else if (fraction > second) {
                    second = fraction
                }
            }
            if (hottest <= hi && second <= secondEps) return gain
            gainDb -= stepEv
        }
        return 2.0.pow(minGainEv)
    }

    private class PooledChannel(val bins: IntArray, val saturated: Int, val total: Int)

    private fun poolGreen(
        bins: Array<IntArray>,
        saturated: IntArray,
        totals: IntArray
    ): List<PooledChannel> {
        fun channelBins(index: Int): IntArray = bins.getOrElse(index) { IntArray(0) }
        fun channelCount(counts: IntArray, index: Int): Int = counts.getOrElse(index) { 0 }
        val greenBins = IntArray(RawEttrSampler.BIN_COUNT) { b ->
            channelBins(1).getOrElse(b) { 0 } + channelBins(2).getOrElse(b) { 0 }
        }
        return listOf(
            PooledChannel(channelBins(0), channelCount(saturated, 0), channelCount(totals, 0)),
            PooledChannel(
                greenBins,
                channelCount(saturated, 1) + channelCount(saturated, 2),
                channelCount(totals, 1) + channelCount(totals, 2)
            ),
            PooledChannel(channelBins(3), channelCount(saturated, 3), channelCount(totals, 3))
        )
    }
}

/** One ETTR metering sample: per-channel percentiles plus the raw material for the
 * reconstruction-allowance gain search (bins, measured-saturated counts, totals,
 * all in canonical R/Gr/Gb/B order). [centerWeightedGreen] is the pooled-green
 * spatial mean weighted toward the frame center (mirrors center-weighted AE zones);
 * [spotGreen] is the pooled-green mean over the small center spot (mirrors the SPOT
 * AE region); both are NaN when no green samples were taken. */
class EttrRawSample(
    val levels: EttrChannelLevels,
    val bins: Array<IntArray>,
    val saturated: IntArray,
    val totals: IntArray,
    val centerWeightedGreen: Float = Float.NaN,
    val spotGreen: Float = Float.NaN
)

/**
 * Sparse per-channel RAW sampler feeding the ETTR loop.
 *
 * Unlike [RawHistogramSampler] (which merges both greens for display), ETTR keeps
 * R/Gr/Gb/B independent: white balance gains saturate channels unevenly, so a
 * merged green would hide the true clipping channel. Each channel keeps a 256-bin
 * histogram and reports its 99.9th percentile, ignoring a handful of hot pixels.
 * The denser-than-histogram sampling resolves the highlight tail finely enough
 * for the reconstruction-allowance clip band (~30k samples per channel).
 */
object RawEttrSampler {
    const val BIN_COUNT = 256
    /** Center-spot scale for PROGRAM spot metering; mirrors the hardware SPOT region. */
    const val SPOT_SCALE = 0.158
    private const val TARGET_BLOCKS = 32_000
    /** Sparse PROGRAM-only density; ETTR-grade tails keep full density. */
    private const val TARGET_BLOCKS_PROGRAM = 4_000

    fun sample(image: Image, characteristics: CameraCharacteristics): EttrRawSample? =
        sampleGrid(image, characteristics, TARGET_BLOCKS * 4)

    /**
     * Sparse metering for PROGRAM alone (~4k blocks): means need far fewer pixels
     * than ETTR's 99.9th-percentile tail and clip band, so the PROGRAM-only loop
     * costs a fraction of a full sample.
     */
    fun sampleProgram(image: Image, characteristics: CameraCharacteristics): EttrRawSample? =
        sampleGrid(image, characteristics, TARGET_BLOCKS_PROGRAM * 4)

    /**
     * Systematic pixel-grid sampler shared by the full and sparse paths. The hot loop
     * performs zero divisions and zero virtual calls per pixel: black floors,
     * reciprocal ranges and CFA channels are 4-entry phase LUTs, and spatial zones
     * come from precomputed column/row tables. Rows advance sequentially for cache
     * locality across the ~25 MB Bayer buffer.
     */
    private fun sampleGrid(
        image: Image,
        characteristics: CameraCharacteristics,
        targetPixels: Int
    ): EttrRawSample? {
        val plane = image.planes.singleOrNull() ?: return null
        if (plane.pixelStride < 2 || image.width < 2 || image.height < 2) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: return null
        if (cfa !in 0..3) return null
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.coerceAtLeast(1) ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val limit = buffer.limit()
        val blackLut = IntArray(4) { i -> black?.getOffsetForIndex(i % 2, i / 2) ?: 0 }
        val invLut = FloatArray(4) { i -> invRange(white, blackLut[i]) }
        val channelLut = phaseChannelLut(cfa)
        val bins = Array(4) { IntArray(BIN_COUNT) }
        val saturated = IntArray(4)
        val totals = IntArray(4)
        var weightedGreenSum = 0.0
        var weightedGreenWeight = 0.0
        var spotGreenSum = 0.0
        var spotGreenCount = 0L
        val step = kotlin.math.sqrt(
            (width.toLong() * height / targetPixels.toDouble()).coerceAtLeast(1.0)
        ).toInt().coerceAtLeast(1)
        val colCount = (width + step - 1) / step
        val colZone = DoubleArray(colCount) { i -> zoneCoord((i * step).coerceAtMost(width - 1), width) }

        var y = 0
        while (y < height) {
            val nyZone = zoneCoord(y, height)
            val phaseRow = (y and 1) shl 1
            var xi = 0
            var x = 0
            while (x < width) {
                val phase = phaseRow or (x and 1)
                val offset = y * rowStride + x * pixelStride
                if (offset + 1 < limit) {
                    val value = buffer.getShort(offset).toInt() and 0xffff
                    val normalized = ((value - blackLut[phase]).coerceAtLeast(0) * invLut[phase])
                        .coerceIn(0f, 1f)
                    val channel = channelLut[phase]
                    if (value >= white) saturated[channel]++
                    bins[channel][(normalized * (BIN_COUNT - 1)).toInt()]++
                    totals[channel]++
                    if (channel == 1 || channel == 2) {
                        // One table lookup serves both the center-weighted mean and
                        // the spot mean; no coordinate math remains in the loop.
                        val zone = maxOf(colZone[xi], nyZone)
                        val weight = weightForZone(zone)
                        weightedGreenSum += weight * normalized
                        weightedGreenWeight += weight
                        if (zone <= SPOT_SCALE) {
                            spotGreenSum += normalized
                            spotGreenCount++
                        }
                    }
                }
                xi++
                x += step
            }
            y += step
        }
        if (totals.any { it == 0 }) return null
        // Bins are stored in canonical order (R=0, Gr=1, Gb=2, B=3); channelAt()
        // already maps each CFA layout onto those indices.
        val levels = EttrChannelLevels(
            r = percentileLevel(bins[0], totals[0], RawEttrMeter.TARGET_PERCENTILE),
            gr = percentileLevel(bins[1], totals[1], RawEttrMeter.TARGET_PERCENTILE),
            gb = percentileLevel(bins[2], totals[2], RawEttrMeter.TARGET_PERCENTILE),
            b = percentileLevel(bins[3], totals[3], RawEttrMeter.TARGET_PERCENTILE)
        )
        val centerWeightedGreen = if (weightedGreenWeight > 0.0) {
            (weightedGreenSum / weightedGreenWeight).toFloat().coerceIn(0f, 1f)
        } else Float.NaN
        val spotGreen = if (spotGreenCount > 0) {
            (spotGreenSum / spotGreenCount).toFloat().coerceIn(0f, 1f)
        } else Float.NaN
        return EttrRawSample(levels, bins, saturated, totals, centerWeightedGreen, spotGreen)
    }

    /** Canonical 2x2 Bayer-phase channels for one CFA layout; index is `(y&1)*2+(x&1)`. */
    internal fun phaseChannelLut(cfa: Int): IntArray =
        IntArray(4) { i -> channelAt(cfa, i % 2, i / 2) }

    /** Reciprocal normalization range so the hot loop multiplies instead of dividing. */
    internal fun invRange(whiteLevel: Int, blackFloor: Int): Float =
        1f / (whiteLevel - blackFloor).coerceAtLeast(1)

    /** Normalized sample level 0..1 without branching into framework accessors. */
    internal fun normalizeSample(value: Int, blackFloor: Int, invRange: Float): Float =
        ((value - blackFloor).coerceAtLeast(0) * invRange).coerceIn(0f, 1f)

    /** Chebyshev half-extent from the frame center in 0..1 (1 at the frame edge). */
    internal fun zoneCoord(v: Int, size: Int): Double {
        if (size <= 0) return 1.0
        return kotlin.math.abs((v + 0.5) / size * 2.0 - 1.0)
    }

    /**
     * Level of the top-[fraction] percentile: walks down from saturation until the
     * clipped tail outweighs the allowed hot-pixel fraction. [total] is this
     * channel's own sample count.
     */
    fun percentileLevel(bins: IntArray, total: Int, fraction: Double): Float {
        if (total <= 0) return 0f
        val allowed = ((1.0 - fraction) * total).coerceAtLeast(1.0)
        var tail = 0.0
        for (bin in bins.indices.reversed()) {
            tail += bins[bin]
            if (tail >= allowed) return (bin + 0.5f) / bins.size
        }
        return 0f
    }

    /**
     * Center weight for PROGRAM metering, mirroring the hardware center-weighted AE
     * zones (nested 20/45/70% rectangles, innermost strongest). Chebyshev distance
     * keeps the zones rectangular like [centeredMeteringRectangle].
     */
    fun centerWeight(x: Int, y: Int, width: Int, height: Int): Double =
        weightForZone(maxOf(zoneCoord(x, width), zoneCoord(y, height)))

    private fun weightForZone(zone: Double): Double = when {
        zone <= 0.20 -> 500.0
        zone <= 0.45 -> 300.0
        zone <= 0.70 -> 200.0
        else -> 50.0
    }

    /**
     * True inside the small center spot used for PROGRAM spot metering. Same scale
     * as the hardware SPOT region (0.158 of the frame), Chebyshev distance keeping
     * it rectangular like [centeredMeteringRectangle].
     */
    fun isSpot(x: Int, y: Int, width: Int, height: Int): Boolean =
        maxOf(zoneCoord(x, width), zoneCoord(y, height)) <= SPOT_SCALE

    /** Canonical channel order R=0, Gr=1, Gb=2, B=3 for every CFA layout. */
    private fun channelAt(cfa: Int, x: Int, y: Int): Int = when (cfa) {        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
            if (y == 0) if (x == 0) 0 else 1 else if (x == 0) 2 else 3
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
            if (y == 0) if (x == 0) 1 else 0 else if (x == 0) 3 else 2
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
            if (y == 0) if (x == 0) 1 else 3 else if (x == 0) 0 else 2
        else -> if (y == 0) if (x == 0) 3 else 1 else if (x == 0) 2 else 0
    }
}
