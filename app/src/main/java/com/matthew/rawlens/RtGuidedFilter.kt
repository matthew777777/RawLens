// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// CPU reference for RawTherapee's guided filter used by "Color propagation"
// highlight reconstruction (HLRecovery_inpaint tail): ring-buffer box blur,
// subsample / upsample-output, the four linear-model passes, the gamma guide
// table, and the highlight-parameter table. Op order mirrors upstream float
// arithmetic; JVM results match the C++ oracle within a small ULP envelope
// (HotSpot FMA fusion; oracle contraction-stable).
//
// Upstream: https://github.com/Beep6581/RawTherapee/blob/dev/rtengine
// (branch dev, fetched 2026-10-16; local references: guidedfilter.cc,
// boxblur.cc scalar vertical path, rescale.h bilinear, LUT.h float indexing,
// color.h rgbLuminance, sleef.h pow_F inputs).
// Ported: boxblur ring blur (boxblur.cc:505-745 scalar path), apply MUL /
// SUBMUL / DIVEPSILON / SUBMUL2 (guidedfilter.cc:52-96), guidedFilter
// (:112-202), rescaleNearest (:204-216), rescaleBilinear + getBilinearValue
// (rescale.h), LUTf float indexing (LUT.h), rgbLuminance 3-vector (color.h),
// gamma table + xyz guide (hilite_recon.cc:937-957), blur params (:959-977).
// Original copyright: (c) 2008-2019 Emil Martinec, Ingo Weyrich, et al., GPL-3.0-or-later.

package com.matthew.rawlens

import kotlin.math.min

/** Guided-filter stage of RT highlight reconstruction, line-by-line. */
object RtGuidedFilter {
    /**
     * Ring-buffer box blur (RT boxblur, scalar path). Faithful port of the
     * radius clamp; callers must still honor RT's contract
     * radius <= (min(w,h)-1)/2 (the in-engine f_mean clamp guarantees it;
     * larger radii read out of bounds upstream too).
     */
    fun boxBlurRing(src: Array<FloatArray>, dst: Array<FloatArray>, radius: Int, w: Int, h: Int) {
        var rad = radius
        if (rad > w - 1) rad = w - 1
        if (rad > h - 1) rad = h - 1
        if (rad == 0) {
            if (src !== dst) {
                for (row in 0 until h) {
                    for (col in 0 until w) dst[row][col] = src[row][col]
                }
            }
            return
        }
        val lineBuffer = FloatArray(rad + 1)
        for (row in 0 until h) {
            var len = (rad + 1).toFloat()
            var tempval = src[row][0]
            lineBuffer[0] = tempval
            for (j in 1..rad) tempval += src[row][j]
            tempval /= len
            dst[row][0] = tempval
            for (col in 1..rad) {
                lineBuffer[col] = src[row][col]
                tempval = (tempval * len + src[row][col + rad]) / (len + 1)
                dst[row][col] = tempval
                len++
            }
            var pos = 0
            for (col in rad + 1 until w - rad) {
                val oldVal = lineBuffer[pos]
                lineBuffer[pos] = src[row][col]
                tempval += (src[row][col + rad] - oldVal) / len
                dst[row][col] = tempval
                pos++
                pos = if (pos <= rad) pos else 0
            }
            for (col in w - rad until w) {
                tempval = (tempval * len - lineBuffer[pos]) / (len - 1)
                dst[row][col] = tempval
                len--
                pos++
                pos = if (pos <= rad) pos else 0
            }
        }
        val rowBuffer = Array(rad + 1) { FloatArray(8) }
        var col = 0
        while (col < w - 8 + 1) {
            var len = (rad + 1).toFloat()
            for (k in 0 until 8) rowBuffer[0][k] = dst[0][col + k]
            for (i in 1..rad) {
                for (k in 0 until 8) dst[0][col + k] += dst[i][col + k]
            }
            for (k in 0 until 8) dst[0][col + k] /= len
            for (row in 1..rad) {
                for (k in 0 until 8) {
                    rowBuffer[row][k] = dst[row][col + k]
                    dst[row][col + k] = (dst[row - 1][col + k] * len + dst[row + rad][col + k]) / (len + 1)
                }
                len++
            }
            var pos = 0
            for (row in rad + 1 until h - rad) {
                for (k in 0 until 8) {
                    val oldVal = rowBuffer[pos][k]
                    rowBuffer[pos][k] = dst[row][col + k]
                    dst[row][col + k] = dst[row - 1][col + k] + (dst[row + rad][col + k] - oldVal) / len
                }
                pos++
                pos = if (pos <= rad) pos else 0
            }
            for (row in h - rad until h) {
                for (k in 0 until 8) {
                    dst[row][col + k] = (dst[row - 1][col + k] * len - rowBuffer[pos][k]) / (len - 1)
                }
                len--
                pos++
                pos = if (pos <= rad) pos else 0
            }
            col += 8
        }
        val remaining = w % 8
        if (remaining > 0) {
            val cc = w - remaining
            var len = (rad + 1).toFloat()
            for (k in 0 until remaining) rowBuffer[0][k] = dst[0][cc + k]
            for (row in 1..rad) {
                for (k in 0 until remaining) dst[0][cc + k] += dst[row][cc + k]
            }
            for (k in 0 until remaining) dst[0][cc + k] /= len
            for (row in 1..rad) {
                for (k in 0 until remaining) {
                    rowBuffer[row][k] = dst[row][cc + k]
                    dst[row][cc + k] = (dst[row - 1][cc + k] * len + dst[row + rad][cc + k]) / (len + 1)
                }
                len++
            }
            // Faithful quirk: the remainder path multiplies by the reciprocal
            // while the blocked path above divides by len; the two differ by
            // rounding, so this split is load-bearing for parity.
            val rlen = 1f / len
            var pos = 0
            for (row in rad + 1 until h - rad) {
                for (k in 0 until remaining) {
                    val oldVal = rowBuffer[pos][k]
                    rowBuffer[pos][k] = dst[row][cc + k]
                    dst[row][cc + k] = dst[row - 1][cc + k] + (dst[row + rad][cc + k] - oldVal) * rlen
                }
                pos++
                pos = if (pos <= rad) pos else 0
            }
            for (row in h - rad until h) {
                for (k in 0 until remaining) {
                    dst[row][cc + k] = (dst[row - 1][cc + k] * len - rowBuffer[pos][k]) / (len - 1)
                }
                len--
                pos++
                pos = if (pos <= rad) pos else 0
            }
        }
    }

    /** Bilinear sample with edge clamp (RT getBilinearValue, rescale.h). */
    fun bilinearSample(src: Array<FloatArray>, x: Float, y: Float): Float {
        val w = src[0].size
        val h = src.size
        val xi = x.toInt()
        val yi = y.toInt()
        val xf = x - xi
        val yf = y - yi
        val xi1 = min(xi + 1, w - 1)
        val yi1 = min(yi + 1, h - 1)
        val bl = src[yi][xi]
        val br = src[yi][xi1]
        val tl = src[yi1][xi]
        val tr = src[yi1][xi1]
        val b = xf * (br - bl) + bl
        val t = xf * (tr - tl) + tl
        return yf * (t - b) + b
    }

    /** Nearest downsample of a parent window (RT rescaleNearest). */
    fun rescaleNearestInto(
        parent: Array<FloatArray>,
        minX: Int,
        minY: Int,
        viewW: Int,
        viewH: Int,
        dst: Array<FloatArray>
    ) {
        val nh = dst.size
        val nw = dst[0].size
        for (y in 0 until nh) {
            val sy = y * viewH / nh
            for (x in 0 until nw) {
                dst[y][x] = parent[sy + minY][x * viewW / nw + minX]
            }
        }
    }

    /** Bilinear resample (RT rescaleBilinear); plain copy when sizes match. */
    fun rescaleBilinearInto(src: Array<FloatArray>, dst: Array<FloatArray>) {
        val h = src.size
        val w = src[0].size
        val nh = dst.size
        val nw = dst[0].size
        if (w == nw && h == nh) {
            for (y in 0 until h) {
                for (x in 0 until w) dst[y][x] = src[y][x]
            }
            return
        }
        val colScale = w.toFloat() / nw
        val rowScale = h.toFloat() / nh
        for (y in 0 until nh) {
            val ym = y * rowScale
            for (x in 0 until nw) {
                dst[y][x] = bilinearSample(src, x * colScale, ym)
            }
        }
    }

    private fun subsamplingFor(w: Int, h: Int, r: Int): Int {
        if (r == 1) return 1
        if (maxOf(w, h) <= 600) return 1
        for (s in 5 downTo 1) {
            if (r % s == 0) return s
        }
        return (r / 2).coerceIn(2, 4)
    }

    private fun boxMeanInPlace(v: Array<FloatArray>, r: Int) {
        val h = v.size
        val w = v[0].size
        var rad = r
        val lim = (min(w, h) - 1) / 2 - 1
        rad = rad.coerceIn(0, lim)
        boxBlurRing(v, v, rad, w, h)
    }

    /**
     * Guided filter (guidedfilter.cc:112-202), single-threaded.
     * [subsampling] is explicit: the highlight pipeline always passes 1.
     */
    fun guidedFilter(
        guide: Array<FloatArray>,
        src: Array<FloatArray>,
        dst: Array<FloatArray>,
        r: Int,
        eps: Float,
        subsampling: Int
    ) {
        val w0 = src[0].size
        val h0 = src.size
        var sub = subsampling
        if (sub <= 0) sub = subsamplingFor(w0, h0, r)
        val w = w0 / sub
        val h = h0 / sub
        val imgI = Array(h) { FloatArray(w) }
        val imgP = Array(h) { FloatArray(w) }
        if (w == w0 && h == h0) {
            for (y in 0 until h0) {
                for (x in 0 until w0) {
                    imgI[y][x] = guide[y][x]
                    imgP[y][x] = src[y][x]
                }
            }
        } else {
            val colScale = w0.toFloat() / w
            val rowScale = h0.toFloat() / h
            for (y in 0 until h) {
                val ym = y * rowScale
                for (x in 0 until w) {
                    imgI[y][x] = bilinearSample(guide, x * colScale, ym)
                    imgP[y][x] = bilinearSample(src, x * colScale, ym)
                }
            }
        }
        val r1 = r.toFloat() / sub
        val meanI = Array(h) { FloatArray(w) }
        val meanP = Array(h) { FloatArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                meanI[y][x] = imgI[y][x]
                meanP[y][x] = imgP[y][x]
            }
        }
        boxMeanInPlace(meanI, r1.toInt())
        boxMeanInPlace(meanP, r1.toInt())
        val corrIp = Array(h) { y -> FloatArray(w) { x -> imgI[y][x] * imgP[y][x] } }
        val corrI = Array(h) { y -> FloatArray(w) { x -> imgI[y][x] * imgI[y][x] } }
        boxMeanInPlace(corrIp, r1.toInt())
        boxMeanInPlace(corrI, r1.toInt())
        val avec = Array(h) { FloatArray(w) }
        val bvec = Array(h) { FloatArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val varI = corrI[y][x] - meanI[y][x] * meanI[y][x]
                val covIp = corrIp[y][x] - meanI[y][x] * meanP[y][x]
                val a = covIp / (varI + eps)
                avec[y][x] = a
                bvec[y][x] = meanP[y][x] - a * meanI[y][x]
            }
        }
        boxMeanInPlace(avec, r1.toInt())
        boxMeanInPlace(bvec, r1.toInt())
        val colScale = w.toFloat() / w0
        val rowScale = h.toFloat() / h0
        for (y in 0 until h0) {
            val ym = y * rowScale
            for (x in 0 until w0) {
                dst[y][x] = bilinearSample(avec, x * colScale, ym) * guide[y][x] +
                    bilinearSample(bvec, x * colScale, ym)
            }
        }
    }

    /** Gamma table gamma[i] = pow_F(i/65535, 2.2) (hilite_recon.cc:944-946). */
    fun gammaTable(): FloatArray =
        FloatArray(65536) { i -> RtFastMath.powF(i.toFloat() / 65535f, 2.2f) }

    /** LUTf float indexing (LUT.h): truncation, clip below/above, lerp. */
    fun lutSample(lut: FloatArray, index: Float): Float {
        val idx = index.toInt()
        if (index < 0f) return lut[0]
        if (index > 65534f) return lut[65535]
        val diff = index - idx.toFloat()
        val p1 = lut[idx]
        val p2 = lut[idx + 1] - p1
        return p1 + p2 * diff
    }

    /** Second-row luminance (Color::rgbLuminance with xyz_cam row). */
    fun rgbLuminance(r: Float, g: Float, b: Float, w: FloatArray): Float =
        r * w[0] + g * w[1] + b * w[2]

    /** Highlight blur/gain parameters from the hLbl slider (hilite_recon.cc:959-977). */
    data class GuidedParams(val radius1: Int, val gainThresh: Float, val radius2: Int)

    fun guidedParams(hlbl: Int): GuidedParams {
        val vals = arrayOf(
            floatArrayOf(4.0f, 0.3f, 0.3f),
            floatArrayOf(3.0f, 1.0f, 0.1f),
            floatArrayOf(3.0f, 2.0f, 0.01f),
            floatArrayOf(2.0f, 3.0f, 0.001f)
        )
        val b = hlbl - 1
        val bb = b.coerceIn(0, 3)
        // RT passes the float radii to an int parameter: truncation.
        return GuidedParams(vals[bb][0].toInt(), vals[bb][2], vals[bb][1].toInt())
    }

    /**
     * Full highlight guided pass: mask self-guidance plus per-channel guided
     * smoothing (hilite_recon.cc:1165-1187). Arrays are filtered in place.
     */
    fun highlightGuidedPass(
        guide: Array<FloatArray>,
        mask: Array<FloatArray>,
        rbuf: Array<FloatArray>,
        gbuf: Array<FloatArray>,
        bbuf: Array<FloatArray>,
        params: GuidedParams
    ) {
        guidedFilter(guide, mask, mask, params.radius1, params.gainThresh, 1)
        // RT computes 0.01 * 65535 in double then narrows to float.
        val rgbEps = (0.01 * 65535.0).toFloat()
        guidedFilter(guide, rbuf, rbuf, params.radius2, rgbEps, 1)
        guidedFilter(guide, gbuf, gbuf, params.radius2, rgbEps, 1)
        guidedFilter(guide, bbuf, bbuf, params.radius2, rgbEps, 1)
    }
}
