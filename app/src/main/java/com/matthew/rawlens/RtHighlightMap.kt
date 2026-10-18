// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// CPU reference for RawTherapee "Color propagation" highlight-map stages
// (HLRecovery_inpaint): highlight accumulator + gating, highlight downsample,
// directional extension scans with edge fills, and hue-proximity weighting.
// Op order mirrors upstream float arithmetic; JVM results match the C++ oracle
// within a small ULP envelope (HotSpot FMA fusion; oracle contraction-stable).
//
// Upstream: https://github.com/Beep6581/RawTherapee/blob/dev/rtengine/hilite_recon.cc
// (branch dev, fetched 2026-10-16; local verbatim copy for line parity:
// references/RawTherapee/hilite_recon.cc).
// Ported: accumulator + hipass gating (:479-545), downsample (:547-568),
// directional scans (:580-839), edge fills (:846-910), hue weight (:1054-1106).
// Original copyright: (c) 2008-2011 Emil Martinec, (c) 2019 Ingo Weyrich, GPL-3.0-or-later.
//
// RT runs the four directional scans in OpenMP sections that are cell-disjoint
// (each writes its own map/cells), so the single-threaded order below matches
// every race-free upstream interleaving element by element.

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Clip bounding box expanded by RT's blur border, clamped to the image. */
data class HlRoi(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
}

/**
 * Gated highlight accumulator: per-channel RGB plus density in [3].
 * [hipassAve] is a Float like RT's `const float hipass_ave`: the double
 * quotient is narrowed on assignment, and gate comparisons run in float.
 */
data class HlAccumulator(
    val hipassAve: Float,
    val hipassSum: Double,
    val hipassNorm: Int,
    val full: Array<Array<FloatArray>>
)

/** Downsampled highlight maps plus the four-direction extension maps. */
data class HlMaps(
    val hilite: Array<Array<FloatArray>>, // [4][hfh+1][hfw+1]
    val dir: Array<Array<FloatArray>>, // [8][hfh][hfw], dir*4+c
    val dir0: Array<Array<FloatArray>>, // [4][hfw][hfh] (x-major)
    val dir4: Array<Array<FloatArray>>, // [4][hfw][hfw] (x-major)
    val hfw: Int,
    val hfh: Int
)

/** Hue-proximity weight result for one clipped pixel. */
data class HlHueWeight(val hasWeight: Boolean, val clipfix: FloatArray)

object RtHighlightMap {
    internal const val EPSILON = 0.00001f
    private const val BLUR_BORDER = 256
    private const val RANGE = 2
    private const val PITCH = 4

    /**
     * Clip ROI scan (hilite_recon.cc:407-437). Returns null when nothing is
     * clipped, matching RT's early return.
     */
    fun findRoi(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        maxF: FloatArray
    ): HlRoi? {
        val h = red.size
        val w = red[0].size
        var minX = w - 1
        var maxX = 0
        var minY = h - 1
        var maxY = 0
        for (i in 0 until h) {
            for (j in 0 until w) {
                if (red[i][j] >= maxF[0] || green[i][j] >= maxF[1] || blue[i][j] >= maxF[2]) {
                    if (j < minX) minX = j
                    if (j > maxX) maxX = j
                    if (i < minY) minY = i
                    if (i > maxY) maxY = i
                }
            }
        }
        if (minX > maxX || minY > maxY) return null
        return HlRoi(
            max(0, minX - BLUR_BORDER),
            max(0, minY - BLUR_BORDER),
            min(w - 1, maxX + BLUR_BORDER),
            min(h - 1, maxY + BLUR_BORDER)
        )
    }

    /**
     * Channel-variation map feeding the accumulator: per-pixel absolute blur
     * residual summed over channels. The channel blur uses [RtBoxBlur.boxBlur2]
     * with box=4; the ROI width must be a multiple of 16 (RT pads this as
     * bufferW).
     */
    fun computeVariation(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        roi: HlRoi
    ): Array<FloatArray> {
        val w = roi.width
        val h = roi.height
        val blurR = RtBoxBlur.boxBlur2(red, roi.minY, roi.minX, h, w, 4)
        val blurG = RtBoxBlur.boxBlur2(green, roi.minY, roi.minX, h, w, 4)
        val blurB = RtBoxBlur.boxBlur2(blue, roi.minY, roi.minX, h, w, 4)
        return Array(h) { i ->
            FloatArray(w) { j ->
                abs(blurR[i][j] - red[i + roi.minY][j + roi.minX]) +
                    abs(blurG[i][j] - green[i + roi.minY][j + roi.minX]) +
                    abs(blurB[i][j] - blue[i + roi.minY][j + roi.minX])
            }
        }
    }

    /**
     * Highlight accumulation (:483-505): copies highlight-but-not-blown pixels
     * into [full] and sums [variation] over them. Copies are exact, so with
     * directly specified inputs this is bit-exact to RT.
     */
    fun accumulateRgb(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        variation: Array<FloatArray>,
        thresh: FloatArray,
        maxF: FloatArray,
        roi: HlRoi
    ): HlAccumulator {
        val w = roi.width
        val h = roi.height
        val full = Array(4) { Array(h) { FloatArray(w) } }
        var hipassSum = 0.0
        var hipassNorm = 0
        for (i in 0 until h) {
            for (j in 0 until w) {
                val r = red[i + roi.minY][j + roi.minX]
                val g = green[i + roi.minY][j + roi.minX]
                val b = blue[i + roi.minY][j + roi.minX]
                if ((r > thresh[0] || g > thresh[1] || b > thresh[2]) &&
                    r < maxF[0] && g < maxF[1] && b < maxF[2]
                ) {
                    hipassSum += variation[i][j].toDouble()
                    hipassNorm++
                    full[0][i][j] = r
                    full[1][i][j] = g
                    full[2][i][j] = b
                    full[3][i][j] = 1f
                }
            }
        }
        val hipassAve = (2.0 * hipassSum / (hipassNorm + EPSILON.toDouble())).toFloat()
        return HlAccumulator(hipassAve, hipassSum, hipassNorm, full)
    }

    /**
     * Hipass-variation and edge-density gating (:529-542) applied in place.
     * With directly specified inputs every decision is exact to RT.
     */
    fun applyGates(
        full: Array<Array<FloatArray>>,
        variation: Array<FloatArray>,
        density: Array<FloatArray>,
        hipassAve: Float
    ) {
        val h = full[0].size
        val w = full[0][0].size
        for (i in 0 until h) {
            for (j in 0 until w) {
                if (variation[i][j] > hipassAve) {
                    full[0][i][j] = 0f
                    full[1][i][j] = 0f
                    full[2][i][j] = 0f
                    full[3][i][j] = 0f
                    continue
                }
                if (density[i][j] > EPSILON && density[i][j] < 0.95f) {
                    full[0][i][j] = 0f
                    full[1][i][j] = 0f
                    full[2][i][j] = 0f
                    full[3][i][j] = 0f
                }
            }
        }
    }

    /**
     * Highlight accumulator + hipass/edge gating (:479-545): the pipeline
     * composition of [computeVariation], [accumulateRgb], a box=1 density
     * blur of the accumulated density, and [applyGates].
     */
    fun accumulateAndGate(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        thresh: FloatArray,
        maxF: FloatArray,
        roi: HlRoi
    ): HlAccumulator {
        val w = roi.width
        val h = roi.height
        val variation = computeVariation(red, green, blue, roi)
        val acc = accumulateRgb(red, green, blue, variation, thresh, maxF, roi)
        val density = RtBoxBlur.boxBlur2(acc.full[3], 0, 0, h, w, 1)
        applyGates(acc.full, variation, density, acc.hipassAve)
        return acc
    }

    /**
     * Highlight downsample (:547-568). Returns RT-sized (hfh+1)x(hfw+1)
     * planes; the unwritten fringe row/column stays 0 like RT's CLEAR_DATA.
     */
    fun downsample(full: Array<Array<FloatArray>>): Array<Array<FloatArray>> {
        val h = full[0].size
        val w = full[0][0].size
        val hfh = (h - h % PITCH) / PITCH
        val hfw = (w - w % PITCH) / PITCH
        return Array(4) { m ->
            val small = RtBoxBlur.boxBlurResamp(full[m], RANGE, PITCH)
            Array(hfh + 1) { i ->
                FloatArray(hfw + 1) { j ->
                    if (i < small.size && j < small[0].size) small[i][j] else 0f
                }
            }
        }
    }

    /** Directional extension scans + edge fills (:580-910). */
    fun directionalExtend(hilite: Array<Array<FloatArray>>, hfw: Int, hfh: Int): HlMaps {
        val dir = Array(8) { Array(hfh) { FloatArray(hfw) } }
        val dir0 = Array(4) { Array(hfw) { FloatArray(hfh) } }
        val dir4 = Array(4) { Array(hfw) { FloatArray(hfh) } }
        // From left.
        for (j in 1 until hfw - 1) {
            for (i in 2 until hfh - 2) {
                if (hilite[3][i][j] > EPSILON) {
                    dir0[3][j][i] = 1f
                } else {
                    dir0[3][j][i] = if (dir0[3][j - 1][i - 2] + dir0[3][j - 1][i - 1] + dir0[3][j - 1][i] +
                        dir0[3][j - 1][i + 1] + dir0[3][j - 1][i + 2] == 0f
                    ) 0f else 0.1f
                }
            }
            if (hilite[3][2][j] <= EPSILON) dir[0 + 3][0][j] = dir0[3][j][2]
            if (hilite[3][3][j] <= EPSILON) dir[0 + 3][1][j] = dir0[3][j][3]
            if (hilite[3][hfh - 3][j] <= EPSILON) dir[4 + 3][hfh - 1][j] = dir0[3][j][hfh - 3]
            if (hilite[3][hfh - 4][j] <= EPSILON) dir[4 + 3][hfh - 2][j] = dir0[3][j][hfh - 4]
        }
        for (i in 2 until hfh - 2) {
            if (hilite[3][i][hfw - 2] <= EPSILON) dir4[3][hfw - 1][i] = dir0[3][hfw - 2][i]
        }
        for (c in 0 until 3) {
            for (j in 1 until hfw - 1) {
                for (i in 2 until hfh - 2) {
                    if (hilite[3][i][j] > EPSILON) {
                        dir0[c][j][i] = hilite[c][i][j] / hilite[3][i][j]
                    } else {
                        dir0[c][j][i] = 0.1f * ((dir0[c][j - 1][i - 2] + dir0[c][j - 1][i - 1] + dir0[c][j - 1][i] +
                            dir0[c][j - 1][i + 1] + dir0[c][j - 1][i + 2]) /
                            (dir0[3][j - 1][i - 2] + dir0[3][j - 1][i - 1] + dir0[3][j - 1][i] +
                                dir0[3][j - 1][i + 1] + dir0[3][j - 1][i + 2] + EPSILON))
                    }
                }
                if (hilite[3][2][j] <= EPSILON) dir[0 + c][0][j] = dir0[c][j][2]
                if (hilite[3][3][j] <= EPSILON) dir[0 + c][1][j] = dir0[c][j][3]
                if (hilite[3][hfh - 3][j] <= EPSILON) dir[4 + c][hfh - 1][j] = dir0[c][j][hfh - 3]
                if (hilite[3][hfh - 4][j] <= EPSILON) dir[4 + c][hfh - 2][j] = dir0[c][j][hfh - 4]
            }
            for (i in 2 until hfh - 2) {
                if (hilite[3][i][hfw - 2] <= EPSILON) dir4[c][hfw - 1][i] = dir0[c][hfw - 2][i]
            }
        }
        // From right.
        for (j in hfw - 2 downTo 1) {
            for (i in 2 until hfh - 2) {
                if (hilite[3][i][j] > EPSILON) {
                    dir4[3][j][i] = 1f
                } else {
                    dir4[3][j][i] = if (dir4[3][j + 1][i - 2] + dir4[3][j + 1][i - 1] + dir4[3][j + 1][i] +
                        dir4[3][j + 1][i + 1] + dir4[3][j + 1][i + 2] == 0f
                    ) 0f else 0.1f
                }
            }
            if (hilite[3][2][j] <= EPSILON) dir[0 + 3][0][j] += dir4[3][j][2]
            if (hilite[3][hfh - 3][j] <= EPSILON) dir[4 + 3][hfh - 1][j] += dir4[3][j][hfh - 3]
        }
        for (i in 2 until hfh - 2) {
            if (hilite[3][i][0] <= EPSILON) {
                dir[0 + 3][i - 2][0] += dir4[3][0][i]
                dir[4 + 3][i + 2][0] += dir4[3][0][i]
            }
            if (hilite[3][i][1] <= EPSILON) {
                dir[0 + 3][i - 2][1] += dir4[3][1][i]
                dir[4 + 3][i + 2][1] += dir4[3][1][i]
            }
            if (hilite[3][i][hfw - 2] <= EPSILON) {
                dir[0 + 3][i - 2][hfw - 2] += dir4[3][hfw - 2][i]
                dir[4 + 3][i + 2][hfw - 2] += dir4[3][hfw - 2][i]
            }
        }
        for (c in 0 until 3) {
            for (j in hfw - 2 downTo 1) {
                for (i in 2 until hfh - 2) {
                    if (hilite[3][i][j] > EPSILON) {
                        dir4[c][j][i] = hilite[c][i][j] / hilite[3][i][j]
                    } else {
                        dir4[c][j][i] = 0.1f * ((dir4[c][j + 1][i - 2] + dir4[c][j + 1][i - 1] + dir4[c][j + 1][i] +
                            dir4[c][j + 1][i + 1] + dir4[c][j + 1][i + 2]) /
                            (dir4[3][j + 1][i - 2] + dir4[3][j + 1][i - 1] + dir4[3][j + 1][i] +
                                dir4[3][j + 1][i + 1] + dir4[3][j + 1][i + 2] + EPSILON))
                    }
                }
                if (hilite[3][2][j] <= EPSILON) dir[0 + c][0][j] += dir4[c][j][2]
                if (hilite[3][hfh - 3][j] <= EPSILON) dir[4 + c][hfh - 1][j] += dir4[c][j][hfh - 3]
            }
            for (i in 2 until hfh - 2) {
                if (hilite[3][i][0] <= EPSILON) {
                    dir[0 + c][i - 2][0] += dir4[c][0][i]
                    dir[4 + c][i + 2][0] += dir4[c][0][i]
                }
                if (hilite[3][i][1] <= EPSILON) {
                    dir[0 + c][i - 2][1] += dir4[c][1][i]
                    dir[4 + c][i + 2][1] += dir4[c][1][i]
                }
                if (hilite[3][i][hfw - 2] <= EPSILON) {
                    dir[0 + c][i - 2][hfw - 2] += dir4[c][hfw - 2][i]
                    dir[4 + c][i + 2][hfw - 2] += dir4[c][hfw - 2][i]
                }
            }
        }
        // From top.
        for (i in 1 until hfh - 1) {
            for (j in 2 until hfw - 2) {
                if (hilite[3][i][j] > EPSILON) {
                    dir[0 + 3][i][j] = 1f
                } else {
                    dir[0 + 3][i][j] = if (dir[0 + 3][i - 1][j - 2] + dir[0 + 3][i - 1][j - 1] + dir[0 + 3][i - 1][j] +
                        dir[0 + 3][i - 1][j + 1] + dir[0 + 3][i - 1][j + 2] == 0f
                    ) 0f else 0.1f
                }
            }
        }
        for (j in 2 until hfw - 2) {
            if (hilite[3][hfh - 2][j] <= EPSILON) dir[4 + 3][hfh - 1][j] += dir[0 + 3][hfh - 2][j]
        }
        for (c in 0 until 3) {
            for (i in 1 until hfh - 1) {
                for (j in 2 until hfw - 2) {
                    if (hilite[3][i][j] > EPSILON) {
                        dir[0 + c][i][j] = hilite[c][i][j] / hilite[3][i][j]
                    } else {
                        dir[0 + c][i][j] = 0.1f * ((dir[0 + c][i - 1][j - 2] + dir[0 + c][i - 1][j - 1] +
                            dir[0 + c][i - 1][j] + dir[0 + c][i - 1][j + 1] + dir[0 + c][i - 1][j + 2]) /
                            (dir[0 + 3][i - 1][j - 2] + dir[0 + 3][i - 1][j - 1] + dir[0 + 3][i - 1][j] +
                                dir[0 + 3][i - 1][j + 1] + dir[0 + 3][i - 1][j + 2] + EPSILON))
                    }
                }
            }
            for (j in 2 until hfw - 2) {
                if (hilite[3][hfh - 2][j] <= EPSILON) dir[4 + c][hfh - 1][j] += dir[0 + c][hfh - 2][j]
            }
        }
        // From bottom.
        for (i in hfh - 2 downTo 1) {
            for (j in 2 until hfw - 2) {
                if (hilite[3][i][j] > EPSILON) {
                    dir[4 + 3][i][j] = 1f
                } else {
                    dir[4 + 3][i][j] = if (dir[4 + 3][i + 1][j - 2] + dir[4 + 3][i + 1][j - 1] + dir[4 + 3][i + 1][j] +
                        dir[4 + 3][i + 1][j + 1] + dir[4 + 3][i + 1][j + 2] == 0f
                    ) 0f else 0.1f
                }
            }
        }
        for (c in 0 until 4) {
            for (i in hfh - 2 downTo 1) {
                for (j in 2 until hfw - 2) {
                    if (hilite[3][i][j] > EPSILON) {
                        dir[4 + c][i][j] = hilite[c][i][j] / hilite[3][i][j]
                    } else {
                        dir[4 + c][i][j] = 0.1f * ((dir[4 + c][i + 1][j - 2] + dir[4 + c][i + 1][j - 1] +
                            dir[4 + c][i + 1][j] + dir[4 + c][i + 1][j + 1] + dir[4 + c][i + 1][j + 2]) /
                            (dir[4 + 3][i + 1][j - 2] + dir[4 + 3][i + 1][j - 1] + dir[4 + 3][i + 1][j] +
                                dir[4 + 3][i + 1][j + 1] + dir[4 + 3][i + 1][j + 2] + EPSILON))
                    }
                }
            }
        }
        fillEdges(dir, hfw, hfh)
        fillEdgesT(dir0, hfw, hfh)
        fillEdgesT(dir4, hfw, hfh)
        return HlMaps(hilite, dir, dir0, dir4, hfw, hfh)
    }

    private fun fillEdges(dir: Array<Array<FloatArray>>, hfw: Int, hfh: Int) {
        for (d in 0 until 2) {
            for (i in 1 until hfh - 1) {
                for (c in 0 until 4) {
                    dir[d * 4 + c][i][0] = dir[d * 4 + c][i][1]
                    dir[d * 4 + c][i][hfw - 1] = dir[d * 4 + c][i][hfw - 2]
                }
            }
            for (j in 1 until hfw - 1) {
                for (c in 0 until 4) {
                    dir[d * 4 + c][0][j] = dir[d * 4 + c][1][j]
                    dir[d * 4 + c][hfh - 1][j] = dir[d * 4 + c][hfh - 2][j]
                }
            }
            for (c in 0 until 4) {
                // Chained assignment evaluates right to left: every corner
                // cell takes the [2][2] (resp. [2][hfw-3] etc.) value.
                val k = d * 4 + c
                val tl = dir[k][2][2]
                dir[k][0][0] = tl
                dir[k][1][0] = tl
                dir[k][0][1] = tl
                dir[k][1][1] = tl
                val tr = dir[k][2][hfw - 3]
                dir[k][0][hfw - 1] = tr
                dir[k][1][hfw - 1] = tr
                dir[k][0][hfw - 2] = tr
                dir[k][1][hfw - 2] = tr
                val bl = dir[k][hfh - 3][2]
                dir[k][hfh - 1][0] = bl
                dir[k][hfh - 2][0] = bl
                dir[k][hfh - 1][1] = bl
                dir[k][hfh - 2][1] = bl
                val br = dir[k][hfh - 3][hfw - 3]
                dir[k][hfh - 1][hfw - 1] = br
                dir[k][hfh - 2][hfw - 1] = br
                dir[k][hfh - 1][hfw - 2] = br
                dir[k][hfh - 2][hfw - 2] = br
            }
        }
    }

    private fun fillEdgesT(m: Array<Array<FloatArray>>, hfw: Int, hfh: Int) {
        for (i in 1 until hfh - 1) {
            for (c in 0 until 4) {
                m[c][0][i] = m[c][1][i]
                m[c][hfw - 1][i] = m[c][hfw - 2][i]
            }
        }
        for (j in 1 until hfw - 1) {
            for (c in 0 until 4) {
                m[c][j][0] = m[c][j][1]
                m[c][j][hfh - 1] = m[c][j][hfh - 2]
            }
        }
        for (c in 0 until 4) {
            // Chained assignment evaluates right to left: every corner cell
            // takes the far value, not the pre-existing neighbor.
            val tl = m[c][2][2]
            m[c][0][0] = tl
            m[c][0][1] = tl
            m[c][1][0] = tl
            m[c][1][1] = tl
            val tr = m[c][hfw - 3][2]
            m[c][hfw - 1][0] = tr
            m[c][hfw - 1][1] = tr
            m[c][hfw - 2][0] = tr
            m[c][hfw - 2][1] = tr
            val bl = m[c][2][hfh - 3]
            m[c][0][hfh - 1] = bl
            m[c][0][hfh - 2] = bl
            m[c][1][hfh - 1] = bl
            m[c][1][hfh - 2] = bl
            val br = m[c][hfw - 3][hfh - 3]
            m[c][hfw - 1][hfh - 1] = br
            m[c][hfw - 1][hfh - 2] = br
            m[c][hfw - 2][hfh - 1] = br
            m[c][hfw - 2][hfh - 2] = br
        }
    }

    /**
     * Per-pixel channel correction with buffer fill (:1114-1163). For every
     * pixel at or above clipping: rebuilds the blend estimate, derives
     * hue-proximity weights, corrects fully-clipped triplets by scaled hue
     * (kept at white-point luminance) or partially-clipped pixels by channel
     * ratios, clamps to white point, and records the half-resolution
     * corrected buffers plus the blend mask. Mutates [red]/[green]/[blue].
     */
    fun reconstructAndFill(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        t: RtHlThresholds,
        maps: HlMaps,
        roi: HlRoi,
        rbuf: Array<FloatArray>,
        gbuf: Array<FloatArray>,
        bbuf: Array<FloatArray>,
        mask: Array<FloatArray>
    ) {
        val pitch = 4
        for (i in 0 until roi.height) {
            val i1 = min((i - i % pitch) / pitch, maps.hfh - 1)
            for (j in 0 until roi.width) {
                val yy = i + roi.minY
                val xx = j + roi.minX
                val pixel = floatArrayOf(red[yy][xx], green[yy][xx], blue[yy][xx])
                if (pixel[0] < t.maxF[0] && pixel[1] < t.maxF[1] && pixel[2] < t.maxF[2]) continue
                val j1 = min((j - j % pitch) / pitch, maps.hfw - 1)
                val blend = RtColorPropagation.blendEstimate(pixel, t)
                var y = EPSILON + blend[0] + blend[1] + blend[2]
                val norm = floatArrayOf(blend[0] / y, blend[1] / y, blend[2] / y)
                val (hasWeight, clipfix) = hueWeight(norm, maps, i1, j1)
                if (!hasWeight) continue
                var maskval = 1f
                if (pixel[0] > t.maxF[0] && pixel[1] > t.maxF[1] && pixel[2] > t.maxF[2]) {
                    val mult = t.whitePt / (0.299f * clipfix[0] + 0.587f * clipfix[1] + 0.114f * clipfix[2])
                    red[yy][xx] = clipfix[0] * mult
                    green[yy][xx] = clipfix[1] * mult
                    blue[yy][xx] = clipfix[2] * mult
                } else {
                    val nc0 = if (pixel[0] <= t.maxF[0]) 1f else 0f
                    val nc1 = if (pixel[1] <= t.maxF[1]) 1f else 0f
                    val nc2 = if (pixel[2] <= t.maxF[2]) 1f else 0f
                    if (nc0 == 0f) {
                        red[yy][xx] = max(
                            pixel[0],
                            clipfix[0] * ((nc1 * pixel[1] + nc2 * pixel[2]) /
                                (nc1 * clipfix[1] + nc2 * clipfix[2] + EPSILON))
                        )
                    }
                    if (nc1 == 0f) {
                        green[yy][xx] = max(
                            pixel[1],
                            clipfix[1] * ((nc2 * pixel[2] + nc0 * pixel[0]) /
                                (nc2 * clipfix[2] + nc0 * clipfix[0] + EPSILON))
                        )
                    }
                    if (nc2 == 0f) {
                        blue[yy][xx] = max(
                            pixel[2],
                            clipfix[2] * ((nc0 * pixel[0] + nc1 * pixel[1]) /
                                (nc0 * clipfix[0] + nc1 * clipfix[1] + EPSILON))
                        )
                    }
                    maskval = 1f - (nc0 + nc1 + nc2) / 5f
                }
                y = 0.299f * red[yy][xx] + 0.587f * green[yy][xx] + 0.114f * blue[yy][xx]
                if (y > t.whitePt) {
                    val mult = t.whitePt / y
                    red[yy][xx] *= mult
                    green[yy][xx] *= mult
                    blue[yy][xx] *= mult
                }
                val ii = i / 2
                val jj = j / 2
                rbuf[ii][jj] = red[yy][xx]
                gbuf[ii][jj] = green[yy][xx]
                bbuf[ii][jj] = blue[yy][xx]
                mask[ii][jj] = maskval
            }
        }
    }

    /**
     * Guided-result merge-back (:1190-1211): blends each full-resolution
     * pixel toward the bilinearly upsampled guided buffers by the mask.
     */
    fun mergeBack(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        rbuf: Array<FloatArray>,
        gbuf: Array<FloatArray>,
        bbuf: Array<FloatArray>,
        mask: Array<FloatArray>,
        roi: HlRoi
    ) {
        for (y in 0 until roi.height) {
            val fy = y * 0.5f
            val yy = y / 2
            for (x in 0 until roi.width) {
                val xx = x / 2
                val m = mask[yy][xx]
                if (m > 0f) {
                    val fx = x * 0.5f
                    val ry = y + roi.minY
                    val rx = x + roi.minX
                    red[ry][rx] = intp(m, RtGuidedFilter.bilinearSample(rbuf, fx, fy), red[ry][rx])
                    green[ry][rx] = intp(m, RtGuidedFilter.bilinearSample(gbuf, fx, fy), green[ry][rx])
                    blue[ry][rx] = intp(m, RtGuidedFilter.bilinearSample(bbuf, fx, fy), blue[ry][rx])
                }
            }
        }
    }

    private fun intp(a: Float, b: Float, c: Float): Float = a * (b - c) + c

    /**
     * Hue-proximity weighting (:1054-1106). [rgbBlendNorm] is the blend
     * estimate already divided by Y; returns clipfix and whether any
     * direction contributed.
     */
    fun hueWeight(
        rgbBlendNorm: FloatArray,
        maps: HlMaps,
        i1: Int,
        j1: Int
    ): HlHueWeight {
        var hasWeight = false
        val clipfix = FloatArray(3)
        fun sq(x: Float) = x * x
        var yhi = 1f / (maps.dir0[0][j1][i1] + maps.dir0[1][j1][i1] + maps.dir0[2][j1][i1])
        if (yhi < 2f) {
            val dirwt = 1f / ((1f + 65535f * (sq(rgbBlendNorm[0] - maps.dir0[0][j1][i1] * yhi) +
                sq(rgbBlendNorm[1] - maps.dir0[1][j1][i1] * yhi) +
                sq(rgbBlendNorm[2] - maps.dir0[2][j1][i1] * yhi))) *
                (maps.dir0[3][j1][i1] + EPSILON))
            hasWeight = true
            clipfix[0] = dirwt * maps.dir0[0][j1][i1]
            clipfix[1] = dirwt * maps.dir0[1][j1][i1]
            clipfix[2] = dirwt * maps.dir0[2][j1][i1]
        }
        for (d in 0 until 2) {
            yhi = 1f / (maps.dir[d * 4 + 0][i1][j1] + maps.dir[d * 4 + 1][i1][j1] + maps.dir[d * 4 + 2][i1][j1])
            if (yhi < 2f) {
                val dirwt = 1f / ((1f + 65535f * (sq(rgbBlendNorm[0] - maps.dir[d * 4 + 0][i1][j1] * yhi) +
                    sq(rgbBlendNorm[1] - maps.dir[d * 4 + 1][i1][j1] * yhi) +
                    sq(rgbBlendNorm[2] - maps.dir[d * 4 + 2][i1][j1] * yhi))) *
                    (maps.dir[d * 4 + 3][i1][j1] + EPSILON))
                hasWeight = true
                clipfix[0] += dirwt * maps.dir[d * 4 + 0][i1][j1]
                clipfix[1] += dirwt * maps.dir[d * 4 + 1][i1][j1]
                clipfix[2] += dirwt * maps.dir[d * 4 + 2][i1][j1]
            }
        }
        yhi = 1f / (maps.dir4[0][j1][i1] + maps.dir4[1][j1][i1] + maps.dir4[2][j1][i1])
        if (yhi < 2f) {
            val dirwt = 1f / ((1f + 65535f * (sq(rgbBlendNorm[0] - maps.dir4[0][j1][i1] * yhi) +
                sq(rgbBlendNorm[1] - maps.dir4[1][j1][i1] * yhi) +
                sq(rgbBlendNorm[2] - maps.dir4[2][j1][i1] * yhi))) *
                (maps.dir4[3][j1][i1] + EPSILON))
            hasWeight = true
            clipfix[0] += dirwt * maps.dir4[0][j1][i1]
            clipfix[1] += dirwt * maps.dir4[1][j1][i1]
            clipfix[2] += dirwt * maps.dir4[2][j1][i1]
        }
        return HlHueWeight(hasWeight, clipfix)
    }
}
