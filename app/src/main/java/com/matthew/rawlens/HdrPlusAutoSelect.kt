// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.HdrPlusMotionMeter.MotionSummary
import java.util.Locale

/**
 * The merged burst the auto brain decided on. [keepIndices] are original
 * capture positions in ascending order; [refIndex] is relative to the
 * kept subset; [droppedIndices] lists what was cut, ascending.
 * [frameStrengths] holds one strength per kept frame in merge (kept)
 * order, or null for a uniform [strength] merge (manual strength, or
 * unusable metering). [strengthMaps] holds one row-major strength map
 * per kept frame (kept order, mapCellsX x mapCellsY strengths), or
 * null when maps are off (manual strength or unusable ratio grids);
 * a present map supersedes [frameStrengths] on both paths.
 * Pure data — see [HdrPlusAutoSelect].
 */
data class HdrPlusAutoDecision(
    val strength: Float,
    val highQuality: Boolean,
    val keepIndices: List<Int>,
    val refIndex: Int,
    val droppedIndices: List<Int>,
    val strengthAuto: Boolean,
    val pathAuto: Boolean,
    val frameStrengths: List<Float>? = null,
    val strengthMaps: List<FloatArray>? = null
) {
    /** Greppable one-line report for the merge log. */
    fun logLine(selectedFrames: Int): String {
        val dropped = if (droppedIndices.isEmpty()) ""
        else " dropped=[" + droppedIndices.joinToString(",") { "F${it + 1}" } + "]"
        val nonUniform = frameStrengths != null && frameStrengths.toSet().size > 1
        val fm = if (nonUniform)
            " fm=[" + frameStrengths!!.joinToString(",") {
                String.format(Locale.US, "%.1f", it)
            } + "]"
        else ""
        val maps = if (strengthMaps != null) " maps" else ""
        return String.format(
            Locale.US, "HDR+ auto: strength=%.1f (%s) path=%s (%s) ref=F%d kept=%d/%d%s%s%s",
            strength, if (strengthAuto) "auto" else "manual",
            if (highQuality) "hq" else "fast", if (pathAuto) "auto" else "manual",
            keepIndices[refIndex] + 1, keepIndices.size, selectedFrames, dropped, fm, maps
        )
    }
}

/**
 * Scene measurements and manual overrides to a merge plan. Frame rejection
 * and path selection retain the existing compatibility policy; strength is
 * derived from measured RAW noise, then attenuated by motion. See
 * docs/refactor/hdrplus-scene-auto.md for references and calibration limits.
 */
object HdrPlusAutoSelect {
    @Suppress("UNUSED_PARAMETER") // ISO retained for source compatibility, not a noise measurement.
    fun select(
        metering: MotionSummary,
        frameCount: Int,
        sensitivityIso: Int,
        manualStrength: Float?,
        manualQuality: Boolean?,
        preferredRef: Int
    ): HdrPlusAutoDecision {
        require(frameCount >= HdrPlusSettings.MIN_MERGE_FRAMES) {
            "Need at least ${HdrPlusSettings.MIN_MERGE_FRAMES} frames"
        }
        val ref = preferredRef.coerceIn(0, frameCount - 1)
        val means = metering.frameMeanLevel
        val sharps = metering.frameSharpness
        val deltas = metering.pairDeltas
        val hots = metering.pairHotFraction
        val usable = means.size == frameCount && sharps.size == frameCount &&
            deltas.size == frameCount - 1 && hots.size == frameCount - 1
        if (!usable) return fallback(frameCount, ref, manualStrength, manualQuality)

        val medianDelta = deltas.medianFinite()
        val medianMean = means.medianFinite()
        // Drop frames whose every adjacent pair exceeds the median ratio, or whose
        // brightness left the plausible band. Ends have a single pair.
        val dropped = (0 until frameCount).filter { i ->
            val madOut = medianDelta.isFinite() && medianDelta > 0.0 &&
                adjacentPairs(i, frameCount).all { p ->
                    val d = deltas[p]
                    d.isFinite() && d > HdrPlusAutoTuning.OUTLIER_PAIR_RATIO * medianDelta
                }
            val mean = means[i]
            val brightOut = mean.isFinite() && medianMean.isFinite() && medianMean > 0.0 &&
                (mean < HdrPlusAutoTuning.BRIGHTNESS_RATIO_MIN * medianMean ||
                    mean > HdrPlusAutoTuning.BRIGHTNESS_RATIO_MAX * medianMean)
            madOut || brightOut
        }.toSet()

        fun localHot(i: Int): Double =
            adjacentPairs(i, frameCount).mapNotNull { hots[it].takeIf { v -> v.isFinite() } }
                .maxOrNull() ?: Double.NaN

        fun score(i: Int): Double {
            val sharp = sharps[i]
            if (!sharp.isFinite()) return -1.0
            val motion = localHot(i).takeIf { it.isFinite() } ?: 0.0
            return sharp / (1.0 + HdrPlusAutoTuning.LOCAL_MOTION_GAIN * motion)
        }

        var kept = (0 until frameCount).filter { it !in dropped }
        if (kept.size < HdrPlusSettings.MIN_MERGE_FRAMES) {
            // Never starve the merge: keep the two best-scoring frames.
            kept = (0 until frameCount)
                .sortedWith(compareByDescending<Int> { score(it) }.thenBy { kotlin.math.abs(it - ref) })
                .take(HdrPlusSettings.MIN_MERGE_FRAMES).sorted()
        }
        val keptDropped = (0 until frameCount).filter { it !in kept.toSet() }
        val best = kept.sortedWith(
            compareByDescending<Int> { score(it) }.thenBy { kotlin.math.abs(it - ref) }
        ).firstOrNull() ?: ref
        // Motion is assessed on all pairs (conservative: drops never hide
        // street motion from the strength curve).
        val maxHot = hots.filter { it.isFinite() }.maxOrNull() ?: Double.NaN
        val motion01 = hotFractionToMotion(maxHot)
        val maxGyro = metering.maxGyro.takeIf { it.isFinite() } ?: Double.NaN
        val gyro01 = if (maxGyro.isFinite()) {
            ((maxGyro - HdrPlusAutoTuning.GYRO_STILL_RAD_S) /
                (HdrPlusAutoTuning.GYRO_MOTION_RAD_S - HdrPlusAutoTuning.GYRO_STILL_RAD_S))
                .coerceIn(0.0, 1.0)
        } else 0.0
        val noiseStrength = HdrPlusSceneNoise.strength(
            metering.frameRelativeNoise.takeIf { it.size == frameCount } ?: emptyList())
        val autoStrength = attenuate(noiseStrength, maxOf(motion01, gyro01))
        val fastPath = (maxHot.isFinite() && maxHot > HdrPlusAutoTuning.FAST_PATH_HOT_FRACTION) ||
            (maxGyro.isFinite() && maxGyro > HdrPlusAutoTuning.GYRO_FAST_PATH_RAD_S)
        // Gyro is burst-wide: local maps must not remove this safety constraint.
        val frameStrengths = if (manualStrength != null) null else kept.map { o ->
            attenuate(noiseStrength, maxOf(hotFractionToMotion(localHot(o)), gyro01))
        }
        val finalHighQuality = manualQuality ?: !fastPath
        val ratios = metering.pairMismatchRatios
        val mw = metering.mapCellsX
        val mh = metering.mapCellsY
        val mapsUsable = ratios.size == frameCount - 1 && mw > 0 && mh > 0 && mw.toLong() * mh <= Int.MAX_VALUE &&
            ratios.all { it.size == mw * mh }
        // Dense-MAD danger grids (native meter): same neighbor-max
        // discipline as the ratios; absent without native metering.
        val madDense = metering.pairMadDense
        val dangerUsable = mapsUsable && madDense.size == frameCount - 1 &&
            madDense.all { it.size == mw * mh }
        val strengthMaps = if (manualStrength != null || !mapsUsable) null
        else kept.mapIndexed { slot, o ->
            val local = DoubleArray(mw * mh) { i ->
                adjacentPairs(o, frameCount).map { p -> ratios[p][i] }.maxOrNull()
                    ?: Double.NaN
            }
            val danger = if (!dangerUsable) null
            else DoubleArray(mw * mh) { i ->
                adjacentPairs(o, frameCount).map { p -> madDense[p][i] }.maxOrNull()
                    ?: Double.NaN
            }
            buildStrengthMap(local, mw, mh, frameStrengths!![slot], danger)
        }
        return HdrPlusAutoDecision(
            strength = manualStrength ?: autoStrength,
            highQuality = finalHighQuality,
            keepIndices = kept,
            refIndex = kept.indexOf(best),
            droppedIndices = keptDropped,
            strengthAuto = manualStrength == null,
            pathAuto = manualQuality == null,
            frameStrengths = frameStrengths,
            strengthMaps = strengthMaps
        )
    }

    /** Maps can only tighten a frame's noise/motion budget. A minimum filter
     * extends rejection to neighboring cells without averaging it away. */
    private fun buildStrengthMap(
        local: DoubleArray, mw: Int, mh: Int, ceiling: Float,
        danger: DoubleArray? = null
    ): FloatArray {
        val t = HdrPlusAutoTuning
        fun risk(v: Double, lo: Double, hi: Double): Double =
            if (!v.isFinite() || v < 0.0) 1.0 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        val bounded = FloatArray(local.size) { i ->
            val mismatch = risk(local[i], t.MAP_RATIO_CLEAN, t.MAP_RATIO_MOTION)
            val dense = danger?.let { risk(it[i], t.MAP_DANGER_CLEAN, t.MAP_DANGER_STRICT) } ?: 0.0
            attenuate(ceiling, maxOf(mismatch, dense))
        }
        return FloatArray(local.size) { i ->
            val x = i % mw
            val y = i / mw
            var value = bounded[i]
            for (dy in -1..1) for (dx in -1..1) {
                value = minOf(value, bounded[(y + dy).coerceIn(0, mh - 1) * mw +
                    (x + dx).coerceIn(0, mw - 1)])
            }
            value
        }
    }

    private fun attenuate(strength: Float, risk: Double): Float =
        (HdrPlusSettings.MIN_STRENGTH +
            (strength - HdrPlusSettings.MIN_STRENGTH) * (1.0 - risk)).toFloat()

    private fun hotFractionToMotion(maxHot: Double): Double {
        if (!maxHot.isFinite() || maxHot < 0.0) return 1.0
        return ((maxHot - HdrPlusAutoTuning.STILL_HOT_FRACTION) /
            (HdrPlusAutoTuning.MOTION_HOT_FRACTION - HdrPlusAutoTuning.STILL_HOT_FRACTION))
            .coerceIn(0.0, 1.0)
    }

    /** Conservative plan when metering is unusable: keep all, Fast, mild. */
    private fun fallback(
        frameCount: Int,
        ref: Int,
        manualStrength: Float?,
        manualQuality: Boolean?
    ): HdrPlusAutoDecision {
        val kept = (0 until frameCount).toList()
        return HdrPlusAutoDecision(
            strength = manualStrength ?: HdrPlusSettings.DEFAULT_STRENGTH,
            highQuality = manualQuality ?: false,
            keepIndices = kept,
            refIndex = kept.indexOf(ref),
            droppedIndices = emptyList(),
            strengthAuto = manualStrength == null,
            pathAuto = manualQuality == null
        )
    }

    private fun adjacentPairs(frame: Int, frameCount: Int): List<Int> {
        val pairs = ArrayList<Int>(2)
        if (frame > 0) pairs += frame - 1
        if (frame < frameCount - 1) pairs += frame
        return pairs
    }

    private fun List<Double>.medianFinite(): Double {
        val finite = filter { it.isFinite() }.sorted()
        if (finite.isEmpty()) return Double.NaN
        val mid = finite.size / 2
        return if (finite.size % 2 == 1) finite[mid]
        else (finite[mid - 1] + finite[mid]) / 2.0
    }
}
