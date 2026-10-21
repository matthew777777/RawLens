// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs

/**
 * HDR+ reference choice and blurred-frame rejection for ZSL merges.
 *
 * Hasinoff et al. 2016 ("Burst photography for high dynamic range and
 * low-light imaging on mobile cameras", sec. 4) pick the reference as the
 * sharpest of the first three frames — "lucky imaging", limited to the
 * first frames to keep shutter lag low — judged by gradient magnitude in
 * the green channel. ZSL bursts end at the shutter press, so the "first"
 * frames are the NEWEST ones. Sharpness here is that measure at FULL
 * resolution (a decimated thumbnail hides the 2-10 px of hand shake that
 * matters): mean absolute difference of every green photosite to its two
 * forward diagonal green neighbours over the central half of the frame,
 * divided by the mean green level so a brightness step between frames
 * does not read as detail. Frames of one burst share exposure, so the
 * shared noise floor cancels in the comparison.
 */
internal object BurstSharpness {
    /** Candidates for the reference: the newest frames (HDR+: first three). */
    const val REFERENCE_CANDIDATES = 3

    /**
     * A frame below this fraction of the reference's sharpness carries
     * visible motion blur; it is dropped from the merge while at least
     * [MfsrSettings.MIN_MERGE_FRAMES] sharp frames remain. Measured on
     * device bursts (docs/refactor/jpeg-pipeline-study.md, ZSL MFSR).
     */
    const val REJECT_BELOW = 0.80

    /**
     * Green-gradient sharpness. [value] reads the normalized (or raw, any
     * monotone affine) sample at sensor-crop coordinates; [isGreen] gives
     * the CFA colour.
     */
    fun measure(width: Int, height: Int, isGreen: (Int, Int) -> Boolean, value: (Int, Int) -> Float): Double {
        val x0 = width / 4
        val x1 = width - width / 4 - 1
        val y0 = height / 4
        val y1 = height - height / 4 - 1
        var grad = 0.0
        var level = 0.0
        var n = 0L
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (!isGreen(x, y)) continue
                val c = value(x, y)
                grad += abs(value(x + 1, y + 1) - c) + abs(value(x - 1, y + 1) - c)
                level += c
                n++
            }
        }
        if (n == 0L || level <= 0.0) return 0.0
        return grad / level
    }

    /**
     * Merge plan over frames in capture order with their [sharpness]:
     * reference = sharpest of the newest [REFERENCE_CANDIDATES]; then the
     * remaining frames sharpest first; frames under [REJECT_BELOW] of the
     * reference are dropped unless that would leave fewer than [minFrames].
     * Returns indices, reference first.
     */
    fun plan(sharpness: List<Double>, minFrames: Int): Plan {
        require(sharpness.isNotEmpty())
        val newest = sharpness.indices.reversed().take(REFERENCE_CANDIDATES)
        val ref = newest.maxByOrNull { sharpness[it] } ?: sharpness.lastIndex
        val rest = sharpness.indices.filter { it != ref }.sortedByDescending { sharpness[it] }
        val refSharp = sharpness[ref]
        val sharpEnough = rest.filter { refSharp <= 0.0 || sharpness[it] >= REJECT_BELOW * refSharp }
        val keep = if (1 + sharpEnough.size >= minFrames) sharpEnough
        else rest.take((minFrames - 1).coerceAtLeast(0))
        return Plan(listOf(ref) + keep, rest.filter { it !in keep })
    }

    data class Plan(
        /** Merge order: reference first, then sharpest first. */
        val order: List<Int>,
        /** Rejected as blurred, sharpest first. */
        val rejected: List<Int>
    )
}
