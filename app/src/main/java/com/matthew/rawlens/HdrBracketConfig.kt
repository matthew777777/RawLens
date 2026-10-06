// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Flexible exposure-bracket planning shared by Android capture and desktop
 * tooling (phone + `tools/sr-vulkan` parity).
 *
 * The classic handheld bracket is 3 frames at (-2, 0, +2) EV around the
 * metered base exposure (shutter-only, DSLR-style). This planner generalizes
 * to arbitrary stop lists and multi-frame-per-stop bursts in the raymerge
 * style — e.g. stops [-3,-2,-1,0,+1] with 5/3/8/2/6 frames per stop (24
 * frames total) — while keeping the SR path constant-exposure: multi-EV
 * bursts route to the HDR path ([HdrRawMerge] + [HdrTileDeghost]), never to
 * [RawSrBurstPlanner] (whose 1.10 exposure-ratio gate intentionally rejects
 * mixed exposures per the HDR+ constant-exposure capture policy).
 *
 * DSLR rule: bracketing changes shutter only; ISO stays at the metered base
 * so noise character matches across the stack and the merge's photon
 * weighting stays calibrated.
 */
object HdrBracketConfig {
    /** Classic handheld bracket around the metered base. */
    val CLASSIC_2EV = intArrayOf(-2, 0, 2)

    /** Wide bracket for high-contrast scenes. */
    val CLASSIC_4EV = intArrayOf(-4, 0, 4)

    /**
     * Raymerge-style dense bracket from the production log (F0..F23):
     * [-3,-2,-1,0,+1] with extra frames on the shadows where photon noise
     * dominates. Total frames = sum(framesPerStop).
     */
    val DENSE_STOPS = intArrayOf(-3, -2, -1, 0, 1)
    val DENSE_FRAMES_PER_STOP = intArrayOf(5, 3, 8, 2, 6)

    const val MIN_STOPS = 2
    const val MAX_STOPS = 7
    const val MAX_EV_ABS = 4
    const val MAX_TOTAL_FRAMES = 30

    /**
     * One planned bracket frame: its EV offset (stops, negative = darker)
     * and its position in the capture sequence.
     */
    data class Frame(val sequenceIndex: Int, val evStops: Int)

    data class Plan(
        /** Stops in capture order (ascending EV: darkest first, like darktable input order). */
        val stops: IntArray,
        /** Frames captured at each stop (parallel to [stops]). */
        val framesPerStop: IntArray,
        /** Flat capture sequence: one entry per shutter press. */
        val frames: List<Frame>
    ) {
        val totalFrames: Int get() = frames.size
        /** Distinct EVs covered, ascending. */
        val coveredEvs: List<Int> get() = stops.sorted().distinct()
        /** True when every stop in [stops] has at least one frame. */
        val allEvsCovered: Boolean get() = stops.all { s -> frames.any { it.evStops == s } }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Plan) return false
            return stops.contentEquals(other.stops) &&
                framesPerStop.contentEquals(other.framesPerStop) && frames == other.frames
        }

        override fun hashCode(): Int {
            var result = stops.contentHashCode()
            result = 31 * result + framesPerStop.contentHashCode()
            result = 31 * result + frames.hashCode()
            return result
        }
    }

    /**
     * Plans a bracket from explicit stops (EV offsets) with a uniform frame
     * count per stop. Stops are captured darkest-first.
     */
    fun plan(stops: IntArray, framesPerStop: Int = 1): Plan {
        require(stops.size in MIN_STOPS..MAX_STOPS) {
            "Bracket needs $MIN_STOPS..$MAX_STOPS stops, got ${stops.size}"
        }
        require(stops.all { it in -MAX_EV_ABS..MAX_EV_ABS }) {
            "Bracket stops must lie in ±$MAX_EV_ABS EV"
        }
        require(stops.toSet().size == stops.size) { "Bracket stops must be distinct" }
        require(framesPerStop in 1..MAX_TOTAL_FRAMES) { "framesPerStop out of range" }
        return plan(stops, IntArray(stops.size) { framesPerStop })
    }

    /**
     * Plans a bracket with per-stop frame counts (raymerge-style dense
     * bursts: more frames on the shadows). [framesPerStop] parallels [stops].
     */
    fun plan(stops: IntArray, framesPerStop: IntArray): Plan {
        require(stops.size in MIN_STOPS..MAX_STOPS) {
            "Bracket needs $MIN_STOPS..$MAX_STOPS stops, got ${stops.size}"
        }
        require(stops.all { it in -MAX_EV_ABS..MAX_EV_ABS }) {
            "Bracket stops must lie in ±$MAX_EV_ABS EV"
        }
        require(stops.toSet().size == stops.size) { "Bracket stops must be distinct" }
        require(framesPerStop.size == stops.size) { "framesPerStop must parallel stops" }
        require(framesPerStop.all { it in 1..MAX_TOTAL_FRAMES }) { "framesPerStop out of range" }
        val total = framesPerStop.sum()
        require(total in MIN_STOPS..MAX_TOTAL_FRAMES) {
            "Bracket needs $MIN_STOPS..$MAX_TOTAL_FRAMES total frames, got $total"
        }
        val order = stops.indices.sortedBy { stops[it] }
        val sortedStops = IntArray(stops.size) { stops[order[it]] }
        val sortedCounts = IntArray(stops.size) { framesPerStop[order[it]] }
        val frames = ArrayList<Frame>(total)
        for (s in sortedStops.indices) repeat(sortedCounts[s]) {
            frames.add(Frame(frames.size, sortedStops[s]))
        }
        return Plan(sortedStops, sortedCounts, frames)
    }

    /** Classic ±[stops] EV bracket (3 frames) for back-compat callers. */
    fun classic(stops: Int): Plan {
        require(stops == 2 || stops == 4) { "Classic bracket must be ±2 or ±4 EV" }
        return plan(if (stops == 2) CLASSIC_2EV else CLASSIC_4EV)
    }

    /**
     * Shutter for a bracket frame from the metered base (DSLR rule: shutter
     * scales by 2^stops, ISO frozen). Clamped to the sensor range by the
     * caller (the capture path coerces into SENSOR_INFO_EXPOSURE_TIME_RANGE).
     */
    fun shutterNanos(baseNanos: Long, evStops: Int): Long {
        require(baseNanos > 0) { "Base exposure must be positive" }
        val factor = if (evStops < 0) 1.0 / (1 shl -evStops) else (1 shl evStops).toDouble()
        return (baseNanos * factor).toLong().coerceAtLeast(1L)
    }

    /**
     * Reference frame for geometric alignment: the middle exposure by actual
     * captured exposure (exposure*ISO), robust to shutter clamping. [exposures]
     * parallels the captured frame list.
     */
    fun referenceIndex(exposures: List<Double>): Int {
        require(exposures.size >= 2) { "Need at least two exposures" }
        require(exposures.all { it.isFinite() && it > 0.0 }) { "Exposures must be finite and positive" }
        return exposures.indices.sortedBy { exposures[it] }[exposures.size / 2]
    }

    /**
     * EV of each captured frame relative to the reference, from actual
     * exposure*ISO (handles clamped shutters). Null entries mark uncomputable
     * frames (missing metadata).
     */
    fun evRelativeToReference(
        exposures: List<Double?>,
        reference: Int
    ): List<Double?> {
        if (reference !in exposures.indices) return exposures.map { null }
        val ref = exposures[reference] ?: return exposures.map { null }
        if (!ref.isFinite() || ref <= 0.0) return exposures.map { null }
        return exposures.map { ev ->
            if (ev == null || !ev.isFinite() || ev <= 0.0) null else kotlin.math.log2(ev / ref)
        }
    }

    /**
     * Raymerge-style F-list log line per frame: `F0[*B*] EV=0: Accepted …`.
     * [isReference] marks the anchor with `[*B*]`.
     */
    fun frameLogLine(
        index: Int,
        evStops: Int,
        verdict: String,
        detail: String = "",
        isReference: Boolean = false
    ): String {
        val anchor = if (isReference) "[*B*]" else ""
        val tail = if (detail.isEmpty()) "" else " $detail"
        return "F$index$anchor EV=${if (evStops > 0) "+$evStops" else "$evStops"}: $verdict$tail"
    }

    /**
     * Coverage summary matching the raymerge footer: merged/kept counts plus
     * the all-EVs-covered guarantee the JPEG path requires.
     */
    fun coverageSummary(plan: Plan, keptPerStop: Map<Int, Int>): String {
        val kept = plan.stops.sumOf { keptPerStop[it] ?: 0 }
        val total = plan.totalFrames
        val coverage = if (plan.stops.all { (keptPerStop[it] ?: 0) > 0 }) "all EVs covered"
        else "MISSING EVs: ${plan.stops.filter { (keptPerStop[it] ?: 0) == 0 }}"
        return "Frames merged: $kept/$total ($coverage)"
    }
}
