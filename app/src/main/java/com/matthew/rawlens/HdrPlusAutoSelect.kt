// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.HdrPlusMotionMeter.MotionSummary
import java.util.Locale
import kotlin.math.roundToInt

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
 * Pure auto brain: metering + ISO + manual overrides in, merge plan out.
 * Drops outlier frames (relative MAD rule + brightness band, GCam-style
 * frame selection), picks the sharpest low-motion base, and maps motion
 * to strength/path on the fitted curve. No Android dependencies.
 */
object HdrPlusAutoSelect {
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
        // Drop frames whose every adjacent pair is a MAD outlier, or whose
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
        val levelDark = if (medianMean.isFinite()) {
            (1.0 - (medianMean - HdrPlusAutoTuning.DARK_LEVEL_FULL) /
                (HdrPlusAutoTuning.DARK_LEVEL_NONE - HdrPlusAutoTuning.DARK_LEVEL_FULL))
                .coerceIn(0.0, 1.0)
        } else 0.0
        val isoDark = if (sensitivityIso > 0) {
            ((sensitivityIso - HdrPlusAutoTuning.ISO_REF).toDouble() /
                (HdrPlusAutoTuning.ISO_FULL - HdrPlusAutoTuning.ISO_REF)).coerceIn(0.0, 1.0)
        } else 0.0
        val dark01 = maxOf(levelDark, isoDark)
        val roundedAuto = curveStrength(motion01, dark01, gyro01)
        val fastPath = (maxHot.isFinite() && maxHot > HdrPlusAutoTuning.FAST_PATH_HOT_FRACTION) ||
            (maxGyro.isFinite() && maxGyro > HdrPlusAutoTuning.GYRO_FAST_PATH_RAD_S)
        // Per-frame strengths (step 4): the same curve with each kept
        // frame's own local pixel motion instead of the burst max, so
        // clean frames merge hard while shaky ones merge faintly.
        // Darkness stays burst-level (scene-global median); gyro is
        // excluded — it already gates the path burst-wide, and a
        // sibling frame's gyro spike is no evidence about this frame
        // (including it flattens mixed bursts to the floor: measured
        // on 163726, all frames 3.0 either way). The frames only relax
        // upward from decision.strength, never below it.
        val frameStrengths = if (manualStrength != null) null
        else kept.map { o -> curveStrength(hotFractionToMotion(localHot(o)), dark01, 0.0) }
        val finalHighQuality = manualQuality ?: !fastPath
        // Strength maps (step-4 maps, both paths): per-block neighbor
        // mismatch maxes curved to strengths, so still regions merge hard
        // while moving ones stay faint inside one frame. Manual strength
        // and unusable grids fall back to frame strengths.
        val ratios = metering.pairMismatchRatios
        val mw = metering.mapCellsX
        val mh = metering.mapCellsY
        val mapsUsable = ratios.size == frameCount - 1 && mw > 0 && mh > 0 &&
            ratios.all { it.size == mw * mh }
        val strengthMaps = if (manualStrength != null || !mapsUsable) null
        else kept.map { o ->
            val local = DoubleArray(mw * mh) { i ->
                adjacentPairs(o, frameCount).map { p -> ratios[p][i] }.maxOrNull()
                    ?: Double.NaN
            }
            buildStrengthMap(local, mw, mh, dark01)
        }
        return HdrPlusAutoDecision(
            strength = manualStrength ?: roundedAuto,
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

    /**
     * Curves per-block neighbor-mismatch maxes to strengths and smooths
     * with a 3x3 box blur (replicate edges) so 32px transitions stay
     * invisible. Non-finite ratios go strict (safe direction). Full float
     * precision — no slider rounding (that would band the map).
     */
    private fun buildStrengthMap(
        local: DoubleArray,
        mw: Int,
        mh: Int,
        dark01: Double
    ): FloatArray {
        val t = HdrPlusAutoTuning
        val curved = DoubleArray(local.size) { i ->
            val r = local[i].takeIf { it.isFinite() } ?: t.MAP_RATIO_MOTION
            val s = when {
                r <= t.MAP_RATIO_CLEAN -> t.AUTO_STRENGTH_MAX.toDouble()
                r >= t.MAP_RATIO_MOTION -> t.AUTO_STRENGTH_MIN.toDouble()
                else -> t.AUTO_STRENGTH_MAX + (t.AUTO_STRENGTH_MIN - t.AUTO_STRENGTH_MAX) *
                    (r - t.MAP_RATIO_CLEAN) / (t.MAP_RATIO_MOTION - t.MAP_RATIO_CLEAN)
            }
            (s + t.STRENGTH_DARK_BOOST * dark01)
                .coerceIn(t.AUTO_STRENGTH_MIN.toDouble(), t.AUTO_STRENGTH_MAX.toDouble())
        }
        return FloatArray(local.size) { i ->
            val x = i % mw
            val y = i / mw
            var sum = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                sum += curved[(y + dy).coerceIn(0, mh - 1) * mw + (x + dx).coerceIn(0, mw - 1)]
            }
            (sum / 9.0).toFloat()
        }
    }

    /**
     * Shared auto-strength curve at slider granularity (keeps logs
     * and labels clean). Burst strength evaluates it at the burst-max
     * motion; per-frame strengths at each frame's local motion.
     */
    private fun curveStrength(motion01: Double, dark01: Double, gyro01: Double): Float {
        val autoStrength = (HdrPlusAutoTuning.STRENGTH_BASE +
            HdrPlusAutoTuning.STRENGTH_DARK_BOOST * dark01 -
            HdrPlusAutoTuning.STRENGTH_MOTION_PENALTY * motion01 -
            HdrPlusAutoTuning.STRENGTH_GYRO_PENALTY * gyro01)
            .coerceIn(
                HdrPlusAutoTuning.AUTO_STRENGTH_MIN.toDouble(),
                HdrPlusAutoTuning.AUTO_STRENGTH_MAX.toDouble()
            )
        return ((autoStrength * 10).roundToInt() / 10f)
            .coerceIn(HdrPlusAutoTuning.AUTO_STRENGTH_MIN, HdrPlusAutoTuning.AUTO_STRENGTH_MAX)
    }

    private fun hotFractionToMotion(maxHot: Double): Double {
        if (!maxHot.isFinite()) return 0.0
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
            strength = manualStrength ?: 8f,
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
