package com.rawlens.srpipeline.stages

/**
 * Static hot/dead pixel suppression for bursts (checklist M / C18).
 *
 * Rule per pixel, against its 8 same-colour Bayer neighbours (+-2 px):
 * - hot:  v > [threshold] and v > [multiplier] * max(neighbours)
 * - dead: max(neighbours) > [threshold] and v < min(neighbours) / [multiplier]
 * (the darktable `hotpixels` iop rule: threshold plus a multiple of every
 * same-colour neighbour; HDR+, Hasinoff et al. 2016, also suppresses hot
 * pixels before its merge).
 *
 * Isolation (added after the burst A/B): a defect is ONE pixel, so it must
 * also be an outlier against its 4 adjacent (+-1 px, other-colour) pixels by
 * [adjacentMultiplier]. A light source lights its adjacent pixels too: a
 * clipped lamp core on a steady camera passed the same-colour rule AND the
 * burst confirmation, and was "corrected" to black (night burst 194618).
 *
 * Burst-specific confirmation: a sensor defect sits at the same sensor
 * position in every frame, while handheld scene content (point lights,
 * stars, speculars) moves. A reference-frame candidate is kept only when it
 * is also an outlier, at the same position, in every verification frame.
 * Confirmed pixels are replaced in every frame by the median of their
 * same-colour neighbours (the still path's RawDefectCorrector replacement).
 *
 * Works on the engine's RGGB-normalised rows (`[y][x]`, values normalised
 * [0,1]); a verification frame is any (y, x) -> value accessor in the same
 * coordinates, so callers can verify without decoding a full frame.
 */
object HotPixels {

    data class Params(val threshold: Float = 0.05f, val multiplier: Float = 2f, val adjacentMultiplier: Float = 1.5f)

    private val OFFS = arrayOf(
        intArrayOf(-2, -2), intArrayOf(-2, 0), intArrayOf(-2, 2), intArrayOf(0, -2),
        intArrayOf(0, 2), intArrayOf(2, -2), intArrayOf(2, 0), intArrayOf(2, 2),
    )

    /** True when (y, x) is a hot or dead outlier in [at] (h x w frame). */
    fun isOutlier(at: (Int, Int) -> Float, h: Int, w: Int, y: Int, x: Int, p: Params = Params()): Boolean {
        if (y < 2 || x < 2 || y >= h - 2 || x >= w - 2) return false
        val v = at(y, x)
        var mx = -Float.MAX_VALUE
        var mn = Float.MAX_VALUE
        for (o in OFFS) {
            val n = at(y + o[0], x + o[1])
            if (n > mx) mx = n
            if (n < mn) mn = n
        }
        val a0 = at(y - 1, x); val a1 = at(y + 1, x); val a2 = at(y, x - 1); val a3 = at(y, x + 1)
        val amx = maxOf(maxOf(a0, a1), maxOf(a2, a3)); val amn = minOf(minOf(a0, a1), minOf(a2, a3))
        val hot = v > p.threshold && v > p.multiplier * maxOf(mx, 0f) && v > p.adjacentMultiplier * maxOf(amx, 0f)
        val dead = mx > p.threshold && v < mn / p.multiplier && v < amn / p.adjacentMultiplier
        return hot || dead
    }

    /** Reference-frame candidates, packed as y * w + x. */
    fun candidates(rows: Array<FloatArray>, p: Params = Params()): IntArray {
        val h = rows.size
        val w = rows[0].size
        val out = ArrayList<Int>()
        // same rule as isOutlier, inlined on arrays (full frame, hot loop)
        for (y in 2 until h - 2) {
            val up = rows[y - 2]; val r = rows[y]; val dn = rows[y + 2]
            for (x in 2 until w - 2) {
                val v = r[x]
                val a = up[x - 2]; val b = up[x]; val c = up[x + 2]; val d = r[x - 2]
                val e = r[x + 2]; val f = dn[x - 2]; val g = dn[x]; val k = dn[x + 2]
                val mx = maxOf(maxOf(maxOf(a, b), maxOf(c, d)), maxOf(maxOf(e, f), maxOf(g, k)))
                val q0 = rows[y - 1][x]; val q1 = rows[y + 1][x]; val q2 = r[x - 1]; val q3 = r[x + 1]
                if (v > p.threshold && v > p.multiplier * maxOf(mx, 0f) &&
                    v > p.adjacentMultiplier * maxOf(maxOf(maxOf(q0, q1), maxOf(q2, q3)), 0f)) { out += y * w + x; continue }
                if (mx > p.threshold) {
                    val mn = minOf(minOf(minOf(a, b), minOf(c, d)), minOf(minOf(e, f), minOf(g, k)))
                    if (v < mn / p.multiplier && v < minOf(minOf(q0, q1), minOf(q2, q3)) / p.adjacentMultiplier) out += y * w + x
                }
            }
        }
        return out.toIntArray()
    }

    /** Keeps the [cands] that are outliers in every verification frame. */
    fun confirm(cands: IntArray, h: Int, w: Int, verify: List<(Int, Int) -> Float>, p: Params = Params()): IntArray =
        cands.filter { idx -> verify.all { isOutlier(it, h, w, idx / w, idx % w, p) } }.toIntArray()

    /**
     * Replaces each listed pixel by the median of its same-colour neighbours,
     * read from an unmodified copy of the affected rows (neighbouring defects
     * never feed each other).
     */
    fun correct(rows: Array<FloatArray>, defects: IntArray) {
        if (defects.isEmpty()) return
        val h = rows.size
        val w = rows[0].size
        val values = FloatArray(defects.size)
        val nb = FloatArray(8)
        for ((k, idx) in defects.withIndex()) {
            val y = idx / w
            val x = idx % w
            var c = 0
            for (o in OFFS) {
                val yy = y + o[0]
                val xx = x + o[1]
                if (yy in 0 until h && xx in 0 until w) nb[c++] = rows[yy][xx]
            }
            nb.sort(0, c)
            values[k] = if (c % 2 == 1) nb[c / 2] else 0.5f * (nb[c / 2 - 1] + nb[c / 2])
        }
        for ((k, idx) in defects.withIndex()) rows[idx / w][idx % w] = values[k]
    }
}
