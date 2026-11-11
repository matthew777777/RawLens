// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.HdrPlusMotionMeter.MotionSummary
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

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
            " fm=[" + frameStrengths.joinToString(",") {
                String.format(Locale.US, "%.1f", it)
            } + "]"
        else ""
        val maps = if (strengthMaps == null) ""
        else String.format(Locale.US, " maps(mean=%.1f)",
            strengthMaps.sumOf { map -> map.sumOf { it.toDouble() } } /
                strengthMaps.sumOf { it.size }.coerceAtLeast(1))
        return String.format(
            Locale.US, "HDR+ auto: strength=%.1f (%s) path=%s (%s) ref=F%d kept=%d/%d%s%s%s",
            strength, if (strengthAuto) "auto" else "manual",
            if (highQuality) "hq" else "fast", if (pathAuto) "auto" else "manual",
            keepIndices[refIndex] + 1, keepIndices.size, selectedFrames, dropped, fm, maps
        )
    }
}

/**
 * Scene measurements and manual overrides to a merge plan. Nothing here is a
 * tuned scene threshold: every decision compares the measured frame
 * differences with what the burst's noise model predicts for noise alone.
 *
 * - Motion share: the part of a difference noise does not explain (0..1).
 * - Usable share of a frame: the mean still share over its map cells.
 * - Drop: a frame whose usable share is below the merge's own leak, 1/(N+1),
 *   cannot add anything measurable. Reference: sharpness x usable share.
 *   (Whole-frame differences cannot drop: handheld texture shift is alignable.)
 * - Path: frequency (HQ) merge when the companions offer at least one whole
 *   frame of still content to average, spatial (Fast) otherwise.
 * - Strength: the slider value whose noise tolerance equals the target gain
 *   ([HdrPlusMergeModel]) on the chosen path, per burst, frame and cell.
 *
 * See docs/refactor/hdrplus-scene-auto.md.
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
        val sharps = metering.frameSharpness
        val deltas = metering.pairDeltas
        val sigmas = metering.frameNoiseSigma
        val model = metering.noiseModel
        val measured = sharps.size == frameCount && deltas.size == frameCount - 1
        val noiseKnown = model != null && metering.codeRange.isFinite() && metering.codeRange > 0.0 &&
            sigmas.size == frameCount && sigmas.all { it.isFinite() }
        if (!measured || !noiseKnown) return fallback(frameCount, ref, manualStrength, manualQuality)

        // Motion share per pair: whole frame, and per map cell when the dense
        // meter ran.
        val pairMotion = DoubleArray(frameCount - 1) { p ->
            motionShare(deltas[p], pairNoiseDifference(sigmas[p], sigmas[p + 1]))
        }
        val mw = metering.mapCellsX
        val mh = metering.mapCellsY
        val madDense = metering.pairMadDense
        val cellSigmas = metering.frameNoiseSigmaCells
        val cellsUsable = mw > 0 && mh > 0 && mw.toLong() * mh <= Int.MAX_VALUE &&
            madDense.size == frameCount - 1 && madDense.all { it.size == mw * mh } &&
            cellSigmas.size == frameCount && cellSigmas.all { it.size == mw * mh }
        val cellMotion = if (!cellsUsable) null else Array(frameCount - 1) { p ->
            DoubleArray(mw * mh) { i ->
                motionShare(madDense[p][i], pairNoiseDifference(cellSigmas[p][i], cellSigmas[p + 1][i]))
            }
        }
        /** Cell [i]'s motion share over [pairs]: the kindest when [lenient], else the harshest. */
        fun cellMotionOver(pairs: List<Int>, i: Int, lenient: Boolean): Double {
            var value = cellMotion!![pairs[0]][i]
            for (k in 1 until pairs.size) {
                val other = cellMotion[pairs[k]][i]
                value = if (lenient) minOf(value, other) else maxOf(value, other)
            }
            return value
        }
        /** Mean still share of a frame against its neighbors in [pairs]. */
        fun usableShare(pairs: List<Int>, lenient: Boolean): Double {
            if (cellMotion == null) {
                val motions = pairs.map { pairMotion[it] }
                return 1.0 - if (lenient) motions.min() else motions.max()
            }
            var sum = 0.0
            for (i in 0 until mw * mh) sum += 1.0 - cellMotionOver(pairs, i, lenient)
            return sum / (mw * mh)
        }

        // Drop frames that share less with either neighbor than the merge
        // leaks anyway. Needs local evidence: whole-frame differences of a
        // handheld burst are mostly alignable texture shift.
        val leak = 1.0 / (frameCount + 1)
        val dropped = if (cellMotion == null) emptySet() else (0 until frameCount).filter { o ->
            usableShare(adjacentPairs(o, frameCount), lenient = true) < leak
        }.toSet()
        /** A frame's pairs that do not involve a dropped neighbor (all of
         * them when none is left: unknown stays conservative). */
        fun livePairs(o: Int): List<Int> {
            val all = adjacentPairs(o, frameCount)
            return all.filter { it !in dropped && (it + 1) !in dropped }.ifEmpty { all }
        }
        val strictShare = DoubleArray(frameCount) { usableShare(livePairs(it), lenient = false) }
        // Reference: sharp detail that also matches its neighbors. Averaged
        // over the neighbors so end frames (one neighbor) are not favored.
        fun score(i: Int): Double = if (!sharps[i].isFinite()) -1.0
        else sharps[i] * livePairs(i).map { usableShare(listOf(it), lenient = false) }.average()

        var kept = (0 until frameCount).filter { it !in dropped }
        if (kept.size < HdrPlusSettings.MIN_MERGE_FRAMES) {
            // Never starve the merge: keep the two best-scoring frames.
            kept = (0 until frameCount)
                .sortedWith(compareByDescending<Int> { score(it) }.thenBy { abs(it - ref) })
                .take(HdrPlusSettings.MIN_MERGE_FRAMES).sorted()
        }
        val keptDropped = (0 until frameCount).filter { it !in kept.toSet() }
        val best = kept.sortedWith(
            compareByDescending<Int> { score(it) }.thenBy { abs(it - ref) }
        ).firstOrNull() ?: ref

        // Path: is there a whole frame of still content among the companions?
        val stillFrames = kept.filter { it != best }.sumOf { strictShare[it] }
        val highQuality = manualQuality ?: (stillFrames >= 1.0)

        // Strength on the chosen path.
        val gains = if (highQuality) HdrPlusMergeModel.frequencyGains(model!!, metering.codeRange)
        else HdrPlusMergeModel.spatialGains(metering.spatialNoiseReference, sigmas[best])
        fun strengthAt(motion: Double): Float = if (gains == null) HdrPlusSettings.DEFAULT_STRENGTH
        else HdrPlusMergeModel.strengthFor(gains, HdrPlusMergeModel.targetGain(kept.size, motion))
        val frameStrengths = if (manualStrength != null) null
        else kept.map { o -> strengthAt(livePairs(o).maxOf { pairMotion[it] }) }
        val strengthMaps = if (frameStrengths == null || cellMotion == null || gains == null) null
        else {
            // Strength depends on motion only through this curve: tabulate it.
            val curve = FloatArray(MOTION_STEPS + 1) { strengthAt(it.toDouble() / MOTION_STEPS) }
            kept.map { o ->
                val pairs = livePairs(o)
                dilateMinimum(FloatArray(mw * mh) { i ->
                    curve[(cellMotionOver(pairs, i, lenient = false) * MOTION_STEPS + 0.5).toInt()]
                }, mw, mh)
            }
        }
        // Report what is applied: the maps when present, else the most
        // cautious frame.
        val autoStrength = strengthMaps?.let { maps ->
            (maps.sumOf { map -> map.sumOf { it.toDouble() } } / maps.sumOf { it.size }).toFloat()
        } ?: frameStrengths?.min() ?: HdrPlusSettings.DEFAULT_STRENGTH
        return HdrPlusAutoDecision(
            strength = manualStrength ?: autoStrength,
            highQuality = highQuality,
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
     * Share (0..1) of an observed mean absolute frame difference that noise
     * does not explain, in power terms: 0 = the frames differ by noise only,
     * 1 = noise is negligible next to the change. Unknown counts as motion.
     */
    internal fun motionShare(observed: Double, noise: Double): Double {
        if (!observed.isFinite() || !noise.isFinite() || observed < 0.0 || noise < 0.0) return 1.0
        if (observed <= noise) return 0.0
        val ratio = noise / observed
        return 1.0 - ratio * ratio
    }

    /** Expected |a - b| of two noise-only samples with these sigmas
     * (Gaussian: 2 sigma / sqrt(pi) for equal sigmas). */
    private fun pairNoiseDifference(sigmaA: Double, sigmaB: Double): Double =
        (sigmaA + sigmaB) / sqrt(PI)

    /** A 3x3 minimum extends a cell's caution to its neighbors (merge tiles
     * straddle cells) without averaging it away. */
    private fun dilateMinimum(map: FloatArray, mw: Int, mh: Int): FloatArray =
        FloatArray(map.size) { i ->
            val x = i % mw
            val y = i / mw
            var value = map[i]
            for (dy in -1..1) for (dx in -1..1) {
                value = minOf(value, map[(y + dy).coerceIn(0, mh - 1) * mw +
                    (x + dx).coerceIn(0, mw - 1)])
            }
            value
        }

    /** Resolution of the tabulated motion-to-strength curve; the slider
     * itself has fewer distinct steps ([HdrPlusSettings.STRENGTH_SLIDER_STEPS]). */
    private const val MOTION_STEPS = 2 * HdrPlusSettings.STRENGTH_SLIDER_STEPS

    /** Plan when noise or motion could not be measured: keep all, default
     * strength, and the cheaper path unless the user chose one. */
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
}
