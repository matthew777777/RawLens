// SPDX-FileCopyrightText: 2022-2026 darktable developers
// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Bayer-only twin of darktable basic.cl opposed kernels, pinned in docs/refactor/upstream.
 * Input is black-subtracted, sensor-white-normalized CFA before WB and demosaic.
 * Select temperature-disabled / late-correction-disabled upstream settings (coefficients 1).
 * Reduction follows OpenCL row order, rather than the CPU OpenMP reduction tree.
 */
internal object OpposedHighlights {
    const val CLIP = 0.987f
    private fun clipped(cfa: UnpackedRawCfa, index: Int): Boolean =
        cfa.highlightClipping?.isClipped(index) ?: (cfa.values[index] >= CLIP)

    fun cells(cfa: UnpackedRawCfa) = IntArray(4) {
        cfa.pattern.colorOrdinalAt(it and 1, it shr 1)
    }
    fun reference(input: FloatArray, w: Int, h: Int, cells: IntArray, x: Int, y: Int): Float {
        val mean = FloatArray(3)
        val count = FloatArray(3)
        for (yy in maxOf(0, y - 1) until minOf(h - 1, y + 2)) {
            for (xx in maxOf(0, x - 1) until minOf(w - 1, x + 2)) {
                val c = cells[2 * (yy and 1) + (xx and 1)]
                mean[c] += maxOf(0f, input[yy * w + xx])
                count[c] += 1f
            }
        }
        for (c in 0..2) mean[c] = if (count[c] > 0f)
            Math.cbrt((mean[c] / count[c]).toDouble()).toFloat() else 0f
        val opp = floatArrayOf(0.5f * (mean[1] + mean[2]),
            0.5f * (mean[0] + mean[2]), 0.5f * (mean[0] + mean[1]))
        val v = opp[cells[2 * (y and 1) + (x and 1)]]
        return (v * v) * v
    }
    data class Analysis(val mask: Array<FloatArray>, val rows: FloatArray, val chroma: FloatArray)
    fun analyze(cfa: UnpackedRawCfa): Analysis {
        val w = cfa.width; val h = cfa.height; val mw = w / 3; val mh = h / 3
        val cells = cells(cfa); val input = cfa.values
        val mask = Array(3) { FloatArray(mw * mh) }
        for (my in 0 until mh) for (mx in 0 until mw)
            for (y in 3 * my until 3 * my + 3) for (x in 3 * mx until 3 * mx + 3) {
                val c = cells[2 * (y and 1) + (x and 1)]
                if (clipped(cfa, y * w + x)) mask[c][my * mw + mx] = 1f
            }
        val dilated = Array(3) { mask[it].copyOf() }
        for (y in 3 until mh - 3) for (x in 3 until mw - 3) for (c in 0..2) {
            var m = 0f
            for (dy in -3..3) for (dx in -3..3) {
                if (kotlin.math.abs(dx) != 3 || kotlin.math.abs(dy) != 3)
                    m = maxOf(m, mask[c][(y + dy) * mw + x + dx])
            }
            dilated[c][y * mw + x] = m
        }
        val rows = FloatArray(h * 8)
        for (y in 0 until h) for (x in 0 until w) {
            val c = cells[2 * (y and 1) + (x and 1)]
            val ix = (y / 3) * mw + x / 3
            val m = dilated[c].getOrElse(ix) { 0f }
            val v = input[y * w + x]
            if (m != 0f && !clipped(cfa, y * w + x) && v > 0.2f * CLIP && v < CLIP) {
                rows[y * 8 + c] += v - reference(input, w, h, cells, x, y)
                rows[y * 8 + 4 + c] += 1f
            }
            if (m != 0f) rows[y * 8 + 3] += 1f
        }
        return Analysis(dilated, rows, reduce(rows, h))
    }
    fun reduce(rows: FloatArray, h: Int): FloatArray {
        val sums = FloatArray(3); val counts = FloatArray(3)
        for (y in 0 until h) for (c in 0..2) {
            sums[c] += rows[y * 8 + c]; counts[c] += rows[y * 8 + 4 + c]
        }
        return FloatArray(3) { if (counts[it] > 100f) sums[it] / counts[it] else 0f }
    }
    fun cpu(cfa: UnpackedRawCfa, shading: LensShadingModel? = null): UnpackedRawCfa {
        if (shading != null && !shading.alreadyApplied) {
            val gains = FloatArray(cfa.values.size) { i -> shading.gainAt(
                cfa.sensorCropLeft + i % cfa.width, cfa.sensorCropTop + i / cfa.width,
                cfa.pattern.colorAt(i % cfa.width, i / cfa.width)) }
            val normalized = cfa.copy(values = FloatArray(gains.size) { cfa.values[it] / gains[it] })
            val result = cpu(normalized)
            return cfa.copy(values = FloatArray(gains.size) { i ->
                if (result.values[i] > normalized.values[i]) maxOf(cfa.values[i], result.values[i] * gains[i])
                else cfa.values[i]
            })
        }
        val chroma = analyze(cfa).chroma; val cells = cells(cfa)
        val out = cfa.values.copyOf()
        for (y in 0 until cfa.height) for (x in 0 until cfa.width) {
            val i = y * cfa.width + x; val v = cfa.values[i]
            if (clipped(cfa, i)) {
                val c = cells[2 * (y and 1) + (x and 1)]
                out[i] = maxOf(v, reference(cfa.values, cfa.width, cfa.height, cells, x, y) + chroma[c])
            }
        }
        return cfa.copy(values = out)
    }
}
