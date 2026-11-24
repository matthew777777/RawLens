// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.pow

/**
 * LK-R HDR capture plan (Lecouat et al. 2022 on Lite): which forward frames to add to the ZSL 0 EV
 * frames, decided from the press-moment frame itself. Total frame count stays what the user set, so
 * the merge costs the same: the bracket frames replace the oldest ZSL frames.
 *
 * No fixed thresholds (memory: auto decisions derive from measurements):
 *  - Short frames when the frame's saturation plateau (samples at the frame's own maximum, the clip:
 *    an unclipped natural scene has only a handful of samples at its maximum) covers at least one
 *    merge tile of sensor area: smaller clipped specks (lamps, glints) are left white, as before.
 *  - A long frame when the shadows are read-noise limited (measured noise model of this frame:
 *    b > a * level at the 10th percentile), where a longer exposure gains more than one more frame,
 *    and the long frame keeps more than half the scene unclipped (median x 2^[LONG_EV] < clip).
 */
object LkrHdrDecision {
    /** Bracket steps (EV, shutter only), as the debug bracket (LKR_HDR_DEBUG_EVS). */
    const val SHORT_EV = -2f
    const val LONG_EV = 1.5f
    const val SHORT_FRAMES = 2

    data class Stats(
        /** Fraction of samples on the saturation plateau. */
        val clippedFraction: Double,
        /** Median and 10th percentile, black-subtracted, as a fraction of the clip (0..1). */
        val median: Double,
        val p10: Double,
        /** Measured noise of this frame, normalised units (variance = a x + b); null = unknown. */
        val noise: RawNoiseEstimate?
    )

    data class Plan(val evs: FloatArray) {
        val any: Boolean get() = evs.isNotEmpty()
        override fun equals(other: Any?) = other is Plan && evs.contentEquals(other.evs)
        override fun hashCode() = evs.contentHashCode()
        override fun toString() = "Plan(${evs.joinToString()})"
    }

    /**
     * [tileFraction] = one merge tile's share of the frame area. [longFits] = the long shutter stays
     * within the hand-shake cap.
     */
    fun decide(mode: LkrHdrMode, stats: Stats?, tileFraction: Double, longFits: Boolean, totalFrames: Int): Plan {
        if (mode == LkrHdrMode.OFF || totalFrames < 3) return Plan(FloatArray(0))
        val shorts: Boolean
        val long: Boolean
        if (mode == LkrHdrMode.ON) {
            shorts = true
            long = longFits
        } else {
            if (stats == null) return Plan(FloatArray(0))
            shorts = stats.clippedFraction >= tileFraction
            val n = stats.noise
            long = longFits && n != null && n.b > n.a * stats.p10 &&
                stats.median * 2.0.pow(LONG_EV.toDouble()) < 1.0
        }
        // Keep at least two 0 EV frames (reference + one) for the motion test.
        val room = totalFrames - 2
        val s = if (shorts) minOf(SHORT_FRAMES, room) else 0
        val l = if (long && room - s >= 1) 1 else 0
        return Plan(FloatArray(s) { SHORT_EV } + FloatArray(l) { LONG_EV })
    }

    /**
     * Measures one RAW16 frame on a sparse grid (every [step]th 2x2 cell, all four CFA phases).
     * [black] in Camera2 reading order; [white] = white level. Null for unusable input.
     */
    internal fun measure(view: RawImageView, black: FloatArray, white: Float, step: Int = 8): Stats? {
        val buf = view.buffer ?: return null
        if (view.pixelStride != 2 || view.rowStride < view.width * 2 || black.size != 4) return null
        val sh = buf.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val rs = view.rowStride / 2
        val w = view.width and 1.inv()
        val h = view.height and 1.inv()
        val n = ((w / 2 + step - 1) / step) * ((h / 2 + step - 1) / step) * 4
        val v = IntArray(n)
        var k = 0
        var max = 0
        for (y in 0 until h step 2 * step) for (x in 0 until w step 2 * step) for (p in 0..3) {
            val i = (y + p / 2) * rs + x + (p and 1)
            if (i >= sh.limit()) continue
            val s = sh.get(i).toInt() and 0xffff
            v[k++] = s
            if (s > max) max = s
        }
        if (k < 64) return null
        var plateau = 0
        for (i in 0 until k) if (v[i] >= max) plateau++
        val sorted = v.copyOf(k).also { it.sort() }
        val bl = black.average().toFloat()
        val range = (maxOf(max.toFloat(), white) - bl).coerceAtLeast(1f)
        fun level(q: Double) = ((sorted[((k - 1) * q).toInt()] - bl) / range).toDouble().coerceAtLeast(0.0)
        // A frame whose maximum is a single outlier is not clipped: the plateau must be its clip.
        val clipped = if (plateau >= 2) plateau.toDouble() / k else 0.0
        val noise = if (view.rowStride == view.width * 2) RawSceneNoise.sample(
            buf.duplicate().also { it.rewind() }, view.width, view.height, black, white
        )?.let { RawSceneNoise.fit(listOf(it)) } else null
        return Stats(clipped, level(0.5), level(0.1), noise)
    }
}
