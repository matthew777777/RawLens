// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// CPU reference for RawTherapee's separable box blurs used by "Color
// propagation" highlight reconstruction (HLRecovery_inpaint). Op order mirrors
// upstream float arithmetic; JVM results match the C++ oracle within a small
// ULP envelope (observed maxima 2-17, asserted per-test) because HotSpot fuses
// a*b+c recurrences into FMA while the oracle rounds twice (verified by
// RtBoxBlurTest; oracle contraction-stable across -O0/-O2).
//
// Upstream: https://github.com/Beep6581/RawTherapee/blob/dev/rtengine/hilite_recon.cc
// (branch dev, fetched 2026-10-16; local verbatim copy for line parity:
// references/RawTherapee/hilite_recon.cc).
// Ported: boxblur2 (:48-129), boxblur_resamp (:131-293).
// Original copyright: (c) 2008-2011 Emil Martinec, (c) 2019 Ingo Weyrich, GPL-3.0-or-later.
//
// RT calls boxblur2 with box=4 (channel blur) and box=1 (highlight density),
// and boxblur_resamp with box=range=2, samp=pitch=4 (highlight downsample).

package com.matthew.rawlens

/** Separable sliding-window box blurs, line-by-line from RT hilite_recon.cc. */
object RtBoxBlur {
    /**
     * Exact port of boxblur2. Blurs the [h]x[w] window at ([startY],[startX])
     * with half-width [box] (full width 2*box+1, shrinking at edges).
     * Requires w % 16 == 0, matching RT's `assert((bufferW % numCols) == 0)`
     * with bufferW == w (no padding columns).
     */
    fun boxBlur2(
        src: Array<FloatArray>,
        startY: Int,
        startX: Int,
        h: Int,
        w: Int,
        box: Int
    ): Array<FloatArray> {
        // RT asserts bufferW % 16 == 0 for its SIMD stride but loops over
        // the true width; the port's vertical pass batches columns in 16s.
        // Columns are independent, so the remainder (w % 16) runs the
        // identical recurrence scalar — bit-exact to the blocked path for
        // the same columns (gated in RtBoxBlurTest).
        val temp = Array(h) { FloatArray(w) }
        val dst = Array(h) { FloatArray(w) }
        for (row in 0 until h) {
            var len = box + 1
            temp[row][0] = src[row + startY][startX] / len
            for (j in 1..box) {
                temp[row][0] += src[row + startY][j + startX] / len
            }
            for (col in 1..box) {
                temp[row][col] = (temp[row][col - 1] * len + src[row + startY][col + box + startX]) / (len + 1)
                len++
            }
            for (col in box + 1 until w - box) {
                temp[row][col] = temp[row][col - 1] +
                    (src[row + startY][col + box + startX] - src[row + startY][col - box - 1 + startX]) / len
            }
            for (col in w - box until w) {
                temp[row][col] = (temp[row][col - 1] * len - src[row + startY][col - box - 1 + startX]) / (len - 1)
                len--
            }
        }
        var col = 0
        while (col < w - 16 + 1) {
            val acc = FloatArray(16)
            var len = (box + 1).toFloat()
            for (n in 0 until 16) acc[n] = temp[0][col + n] / len
            for (i in 1..box) {
                for (n in 0 until 16) acc[n] += temp[i][col + n] / len
            }
            for (n in 0 until 16) dst[0][col + n] = acc[n]
            for (row in 1..box) {
                for (n in 0 until 16) {
                    acc[n] = (acc[n] * len + temp[row + box][col + n]) / (len + 1)
                    dst[row][col + n] = acc[n]
                }
                len++
            }
            val rlen = 1f / len
            for (row in box + 1 until h - box) {
                for (n in 0 until 16) {
                    acc[n] += (temp[row + box][col + n] - temp[row - box - 1][col + n]) * rlen
                    dst[row][col + n] = acc[n]
                }
            }
            for (row in h - box until h) {
                for (n in 0 until 16) {
                    acc[n] = (dst[row - 1][col + n] * len - temp[row - box - 1][col + n]) / (len - 1)
                    dst[row][col + n] = acc[n]
                }
                len--
            }
            col += 16
        }
        // Scalar tail: same statements, same order, one column at a time.
        for (c in (w / 16) * 16 until w) {
            var len = (box + 1).toFloat()
            var acc = temp[0][c] / len
            for (i in 1..box) {
                acc += temp[i][c] / len
            }
            dst[0][c] = acc
            for (row in 1..box) {
                acc = (acc * len + temp[row + box][c]) / (len + 1)
                dst[row][c] = acc
                len++
            }
            val rlen = 1f / len
            for (row in box + 1 until h - box) {
                acc += (temp[row + box][c] - temp[row - box - 1][c]) * rlen
                dst[row][c] = acc
            }
            for (row in h - box until h) {
                acc = (dst[row - 1][c] * len - temp[row - box - 1][c]) / (len - 1)
                dst[row][c] = acc
                len--
            }
        }
        return dst
    }

    /**
     * Exact port of boxblur_resamp. Blurs [src] (HxW) with half-width [box]
     * and downsamples by [samp]. Returns dst sized ((H-1)/samp+1)x(W/samp),
     * zero where RT leaves unwritten rows (RT's wider hilite planes stay
     * CLEAR_DATA there). Replicates RT's 8-column blocked vertical path
     * (reciprocal multiply) versus scalar remainder path (division): the two
     * are NOT bit-identical, so the column split is load-bearing for parity.
     */
    fun boxBlurResamp(src: Array<FloatArray>, box: Int, samp: Int): Array<FloatArray> {
        require(samp != 0)
        val h = src.size
        val w = src[0].size
        val tempW = (w - 1) / samp + 1
        val temp = Array(h) { FloatArray(tempW) }
        val dstW = w / samp
        val dstH = (h - 1) / samp + 1
        val dst = Array(dstH) { FloatArray(dstW) }
        for (row in 0 until h) {
            var len = box + 1
            var acc = src[row][0] / len
            for (j in 1..box) acc += src[row][j] / len
            temp[row][0] = acc
            for (col in 1..box) {
                acc = (acc * len + src[row][col + box]) / (len + 1)
                if (col % samp == 0) temp[row][col / samp] = acc
                len++
            }
            val oneByLen = 1f / len.toFloat()
            for (col in box + 1 until w - box) {
                acc += (src[row][col + box] - src[row][col - box - 1]) * oneByLen
                if (col % samp == 0) temp[row][col / samp] = acc
            }
            for (col in w - box until w) {
                acc = (acc * len - src[row][col - box - 1]) / (len - 1)
                if (col % samp == 0) temp[row][col / samp] = acc
                len--
            }
        }
        val blockedEnd = dstW - dstW % 8
        var col = 0
        while (col < blockedEnd - (8 - 1)) {
            val acc = FloatArray(8)
            var len = (box + 1).toFloat()
            for (n in 0 until 8) acc[n] = temp[0][col + n] / len
            for (i in 1..box) {
                for (n in 0 until 8) acc[n] += temp[i][col + n] / len
            }
            for (n in 0 until 8) dst[0][col + n] = acc[n]
            for (row in 1..box) {
                for (n in 0 until 8) {
                    acc[n] = (acc[n] * len + temp[row + box][col + n]) / (len + 1)
                }
                if (row % samp == 0) {
                    for (n in 0 until 8) dst[row / samp][col + n] = acc[n]
                }
                len++
            }
            val rlen = 1f / len
            for (row in box + 1 until h - box) {
                for (n in 0 until 8) {
                    acc[n] += (temp[row + box][col + n] - temp[row - box - 1][col + n]) * rlen
                }
                if (row % samp == 0) {
                    for (n in 0 until 8) dst[row / samp][col + n] = acc[n]
                }
            }
            for (row in h - box until h) {
                for (n in 0 until 8) {
                    acc[n] = (acc[n] * len - temp[row - box - 1][col + n]) / (len - 1)
                }
                if (row % samp == 0) {
                    for (n in 0 until 8) dst[row / samp][col + n] = acc[n]
                }
                len--
            }
            col += 8
        }
        for (c in blockedEnd until dstW) {
            var len = box + 1
            var acc = temp[0][c] / len
            for (i in 1..box) acc += temp[i][c] / len
            dst[0][c] = acc
            for (row in 1..box) {
                acc = (acc * len + temp[row + box][c]) / (len + 1)
                if (row % samp == 0) dst[row / samp][c] = acc
                len++
            }
            for (row in box + 1 until h - box) {
                acc += (temp[row + box][c] - temp[row - box - 1][c]) / len
                if (row % samp == 0) dst[row / samp][c] = acc
            }
            for (row in h - box until h) {
                acc = (acc * len - temp[row - box - 1][c]) / (len - 1)
                if (row % samp == 0) dst[row / samp][c] = acc
                len--
            }
        }
        return dst
    }
}
