// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * CPU half of portrait mode (pure JVM, network-resolution maps only):
 * fuses the relative-depth and person maps into one disparity map with the
 * subject on the focal plane, fits colour guided-filter coefficients at
 * [GUIDE_SIZE] so the GPU can re-fit depth edges to the full-resolution image, and plans the depth
 * layers the GPU renderer blurs (Wadhwa et al. 2018 / Dr.Bokeh-style layered
 * gather; geometric radii, each layer at the pyramid level where its radius
 * is at most [LEVEL_RADIUS] texels — measured fastest on Mali-G615).
 */
object PortraitDepth {
    const val NET_SIZE = 256
    const val LEVEL_RADIUS = 2f
    const val NEAR_RADIUS = 2f
    const val RADIUS_RATIO = 1.41421356f
    const val MAX_LAYERS = 24

    data class Fused(
        /** Disparity 0 (far) .. 1 (near), NET_SIZE^2, subject at [focus]. */
        val disparity: FloatArray,
        val focus: Float,
        val personFraction: Float,
    )

    /**
     * [rawDepth]: network relative inverse depth (bigger = nearer), NET_SIZE^2.
     * [person]: person probability 0..1 or null. [focusPoint]: normalized (x, y)
     * to focus on when no person is found, else the frame centre.
     */
    fun fuse(rawDepth: FloatArray, person: FloatArray?, focusPoint: Pair<Float, Float>? = null): Fused {
        val n = NET_SIZE * NET_SIZE
        require(rawDepth.size == n && (person == null || person.size == n))
        val sorted = rawDepth.copyOf().also { it.sort() }
        val lo = sorted[(n * 0.02).toInt()]
        val hi = sorted[(n * 0.98).toInt()]
        val span = max(hi - lo, 1e-6f)
        val d = FloatArray(n) { ((rawDepth[it] - lo) / span).coerceIn(0f, 1f) }
        var personCount = 0
        val inPerson = ArrayList<Float>()
        if (person != null) for (i in 0 until n) if (person[i] > 0.5f) { personCount++; inPerson.add(d[i]) }
        val personFraction = personCount.toFloat() / n
        val focus = if (personFraction >= MIN_PERSON_FRACTION) {
            median(inPerson)
        } else {
            val (fx, fy) = focusPoint ?: (0.5f to 0.5f)
            val cx = (fx * NET_SIZE).toInt().coerceIn(0, NET_SIZE - 1)
            val cy = (fy * NET_SIZE).toInt().coerceIn(0, NET_SIZE - 1)
            val r = NET_SIZE / 16
            val window = ArrayList<Float>()
            for (y in max(0, cy - r) until min(NET_SIZE, cy + r)) for (x in max(0, cx - r) until min(NET_SIZE, cx + r)) {
                window.add(d[y * NET_SIZE + x])
            }
            median(window)
        }
        if (person != null && personFraction >= MIN_PERSON_FRACTION) {
            // The person (hair included, as soft probability) sits on the focal plane.
            for (i in 0 until n) {
                val m = ((person[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
                d[i] = d[i] + (focus - d[i]) * m
            }
        }
        return Fused(d, focus, personFraction)
    }

    /**
     * He et al. colour guided filter at [GUIDE_SIZE]: [guide] is the display-encoded
     * RGB thumbnail (interleaved, size^2 * 3), [disparity] the fused NET_SIZE^2 map.
     * Returns box-averaged coefficients (a_r, a_g, a_b, b) interleaved, so the GPU
     * evaluates disparity = a . rgb + b against the full-resolution picture. The
     * colour guide (not luma) is what lets hair strands keep their own depth:
     * RealBokeh eval 2026-11-26, r 8 / eps 2e-4 at 1024 removed the sharp halo.
     */
    fun colorGuidedCoefficients(
        guide: FloatArray, size: Int, disparity: FloatArray,
        radius: Int = COLOR_RADIUS, eps: Float = COLOR_EPS,
    ): FloatArray {
        val n = size * size
        require(guide.size == n * 3 && disparity.size == NET_SIZE * NET_SIZE)
        val p = resizeBilinear(disparity, NET_SIZE, size)
        val ch = Array(3) { c -> FloatArray(n) { guide[it * 3 + c] } }
        val mI = Array(3) { boxFilter(ch[it], size, radius) }
        val mP = boxFilter(p, size, radius)
        val tmp = FloatArray(n)
        val cov = Array(3) { c ->
            for (i in 0 until n) tmp[i] = ch[c][i] * p[i]
            boxFilter(tmp, size, radius).also { m -> for (i in 0 until n) m[i] -= mI[c][i] * mP[i] }
        }
        // Covariance of the guide: rr rg rb gg gb bb.
        val pairs = arrayOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 2)
        val sig = Array(6) { k ->
            val (u, v) = pairs[k]
            for (i in 0 until n) tmp[i] = ch[u][i] * ch[v][i]
            boxFilter(tmp, size, radius).also { m -> for (i in 0 until n) m[i] -= mI[u][i] * mI[v][i] }
        }
        val a = Array(3) { FloatArray(n) }
        val b = FloatArray(n)
        for (i in 0 until n) {
            val rr = sig[0][i] + eps; val rg = sig[1][i]; val rb = sig[2][i]
            val gg = sig[3][i] + eps; val gb = sig[4][i]; val bb = sig[5][i] + eps
            // Symmetric 3x3 inverse (adjugate / determinant).
            val i00 = gg * bb - gb * gb
            val i01 = rb * gb - rg * bb
            val i02 = rg * gb - rb * gg
            val i11 = rr * bb - rb * rb
            val i12 = rb * rg - rr * gb
            val i22 = rr * gg - rg * rg
            val det = rr * i00 + rg * i01 + rb * i02
            val inv = if (det > 1e-20f) 1f / det else 0f
            val c0 = cov[0][i]; val c1 = cov[1][i]; val c2 = cov[2][i]
            val a0 = (i00 * c0 + i01 * c1 + i02 * c2) * inv
            val a1 = (i01 * c0 + i11 * c1 + i12 * c2) * inv
            val a2 = (i02 * c0 + i12 * c1 + i22 * c2) * inv
            a[0][i] = a0; a[1][i] = a1; a[2][i] = a2
            b[i] = mP[i] - a0 * mI[0][i] - a1 * mI[1][i] - a2 * mI[2][i]
        }
        val ma = Array(3) { boxFilter(a[it], size, radius) }
        val mb = boxFilter(b, size, radius)
        return FloatArray(n * 4) { val i = it / 4; when (it % 4) { 3 -> mb[i]; else -> ma[it % 4][i] } }
    }

    /** Edge-clamped (2r+1)^2 box mean of a square map, two running-sum passes. */
    fun boxFilter(src: FloatArray, s: Int, r: Int): FloatArray {
        val tmp = FloatArray(s * s)
        val out = FloatArray(s * s)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until s) {
            val row = y * s
            var acc = 0f
            for (x in -r..r) acc += src[row + x.coerceIn(0, s - 1)]
            for (x in 0 until s) {
                tmp[row + x] = acc * norm
                acc += src[row + min(x + r + 1, s - 1)] - src[row + max(x - r, 0)]
            }
        }
        val acc = FloatArray(s)
        for (y in -r..r) { val row = y.coerceIn(0, s - 1) * s; for (x in 0 until s) acc[x] += tmp[row + x] }
        for (y in 0 until s) {
            val row = y * s
            val add = min(y + r + 1, s - 1) * s
            val sub = max(y - r, 0) * s
            for (x in 0 until s) {
                out[row + x] = acc[x] * norm
                acc[x] += tmp[add + x] - tmp[sub + x]
            }
        }
        return out
    }

    /** Pixel-centre aligned bilinear resize of a square single-channel map (GL sampling). */
    fun resizeBilinear(src: FloatArray, from: Int, to: Int): FloatArray {
        val out = FloatArray(to * to)
        val scale = from.toFloat() / to
        val x0 = IntArray(to); val fx = FloatArray(to)
        for (x in 0 until to) {
            val sx = ((x + 0.5f) * scale - 0.5f).coerceIn(0f, (from - 1).toFloat())
            x0[x] = min(sx.toInt(), from - 2).coerceAtLeast(0); fx[x] = sx - x0[x]
        }
        for (y in 0 until to) {
            val y0 = x0[y]; val wy = fx[y]
            val r0 = y0 * from; val r1 = min(y0 + 1, from - 1) * from
            for (x in 0 until to) {
                val a = x0[x]; val w = fx[x]; val a1 = min(a + 1, from - 1)
                val top = src[r0 + a] + (src[r0 + a1] - src[r0 + a]) * w
                val bot = src[r1 + a] + (src[r1 + a1] - src[r1 + a]) * w
                out[y * to + x] = top + (bot - top) * wy
            }
        }
        return out
    }

    /** Area (box) resize of a square interleaved map, any ratio. */
    fun resizeArea(src: FloatArray, from: Int, to: Int, channels: Int): FloatArray {
        if (from == to) return src
        // Per-axis weights: output cell [i*from/to, (i+1)*from/to) over source pixels.
        val starts = IntArray(to); val ends = IntArray(to)
        val weights = ArrayList<FloatArray>(to)
        val scale = from.toDouble() / to
        for (i in 0 until to) {
            val a = i * scale; val b = (i + 1) * scale
            val s0 = a.toInt(); val s1 = min(kotlin.math.ceil(b).toInt(), from)
            starts[i] = s0; ends[i] = s1
            weights.add(FloatArray(s1 - s0) { k ->
                val lo = max(a, (s0 + k).toDouble()); val hi = min(b, (s0 + k + 1).toDouble())
                ((hi - lo) / scale).toFloat()
            })
        }
        val tmp = FloatArray(from * to * channels) // rows: from, cols: to
        for (y in 0 until from) for (x in 0 until to) {
            val w = weights[x]
            for (c in 0 until channels) {
                var acc = 0f
                for (k in w.indices) acc += w[k] * src[(y * from + starts[x] + k) * channels + c]
                tmp[(y * to + x) * channels + c] = acc
            }
        }
        val out = FloatArray(to * to * channels)
        for (y in 0 until to) {
            val w = weights[y]
            for (x in 0 until to) for (c in 0 until channels) {
                var acc = 0f
                for (k in w.indices) acc += w[k] * tmp[((starts[y] + k) * to + x) * channels + c]
                out[(y * to + x) * channels + c] = acc
            }
        }
        return out
    }

    private fun median(v: List<Float>): Float =
        if (v.isEmpty()) 0.5f else v.sorted()[v.size / 2]

    enum class Kind(val shader: Int) { BACK(0), FRONT(1), NEAR(2) }

    data class Layer(
        val kind: Kind,
        val radius: Float,
        val centre: Float,
        val lo: Float,
        val hi: Float,
        val level: Int,
        /** Radius in texels at [level]. */
        val levelRadius: Float,
        /** Near band only: half width of the flat (sharp) top in disparity. */
        val bandHalfWidth: Float = 0f,
    )

    data class Plan(val layers: List<Layer>, val k: Float, val focus: Float, val levels: Int) {
        val maxLevel: Int get() = levels - 1
    }

    /**
     * Thin-lens layer plan: radius r = k |d - focus| with k chosen so the far
     * background (d = 0) gets [farRadiusPx]. Layers with r <= NEAR_RADIUS fold
     * into one near-focus band rendered per pixel in the final composite.
     */
    fun plan(focus: Float, farRadiusPx: Float, maxLevels: Int): Plan {
        val f = focus.coerceIn(0.05f, 0.98f)
        val k = farRadiusPx / f
        data class Raw(val kind: Kind, val r: Float, val c: Float)
        val raw = ArrayList<Raw>()
        raw.add(Raw(Kind.NEAR, 0f, f))
        for (side in 0..1) {
            // Relative (not metric) depth: cap the near side at the far-background
            // blur, or a far focus would give foreground discs of several hundred px.
            val rs = if (side == 0) k * f else min(k * (1f - f), farRadiusPx)
            if (rs <= NEAR_RADIUS) continue
            var r = NEAR_RADIUS * RADIUS_RATIO
            while (true) {
                val rr = min(r, rs)
                raw.add(Raw(if (side == 0) Kind.BACK else Kind.FRONT, rr, if (side == 0) f - rr / k else f + rr / k))
                if (rr >= rs || raw.size >= MAX_LAYERS) break
                r *= RADIUS_RATIO
            }
        }
        raw.sortBy { it.c }
        val band = NEAR_RADIUS / k
        val layers = raw.mapIndexed { i, l ->
            fun edge(j: Int): Float {
                val o = raw[j]
                return if (o.kind == Kind.NEAR) (if (j < i) o.c + band else o.c - band) else o.c
            }
            val lo = if (i == 0) -1e6f else edge(i - 1)
            val hi = if (i == raw.size - 1) 1e6f else edge(i + 1)
            val level = if (l.kind == Kind.NEAR || l.r <= LEVEL_RADIUS) 0
            else min(maxLevels - 1, ceil(ln(l.r / LEVEL_RADIUS) / ln(2f)).toInt())
            Layer(l.kind, l.r, l.c, lo, hi, level, l.r / 2f.pow(level),
                if (l.kind == Kind.NEAR) band else 0f)
        }
        val levels = (layers.maxOf { it.level } + 1)
        return Plan(layers, k, f, levels)
    }

    /** Layers merged at [level], in compositing order: back far->near (near band last), front near->far. */
    fun compositeOrder(plan: Plan, level: Int): List<Layer> {
        val here = plan.layers.filter { it.level == level }
        return here.filter { it.kind != Kind.FRONT }.sortedBy { it.centre } +
            here.filter { it.kind == Kind.FRONT }.sortedByDescending { it.centre }
    }

    fun mipLevels(width: Int, height: Int): Int {
        var l = 1
        var w = width; var h = height
        while (w > 1 || h > 1) { w = max(1, w / 2); h = max(1, h / 2); l++ }
        return l
    }

    private const val MIN_PERSON_FRACTION = 0.01f
    const val GUIDE_SIZE = 1024
    private const val COLOR_RADIUS = 8
    private const val COLOR_EPS = 2e-4f
}
