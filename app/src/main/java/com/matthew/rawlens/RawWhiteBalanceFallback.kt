// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Data-driven white balance for HALs that run no AWB on a stream.
 *
 * Field log 2026-10-29 (Xiaomi 24030PN60G, HyperOS 3): aux lenses opened by
 * their direct IDs (3/4/5) report COLOR_CORRECTION_GAINS 1,1,1,1, a NaN
 * SENSOR_NEUTRAL_COLOR_POINT and AWB state INACTIVE forever, while the same
 * sensor through its logical route (6/4) reports real values with identical
 * matrices. Unity WB renders green in the viewfinder, the JPEG and every RAW
 * editor. When that shape is detected, an estimate from the frame's own Bayer
 * data stands in for the missing as-shot neutral.
 *
 * With the sensor's colour matrices the estimate is a posterior over a
 * log-chroma histogram (u = ln R/G, v = ln B/G) around the camera-space white
 * locus ([Locus]: 2300..9000 K blackbody light through this sensor):
 *
 *  - Gray-cell votes: every 4x4-pixel cell votes its chroma, weighted by how
 *    close it is to the locus measured against ITS OWN chroma noise (from a
 *    noise model fitted to this frame's green-pair differences; never the
 *    HAL noise profile). The histogram is square-rooted so one large surface
 *    cannot outvote several smaller ones by area alone. Cells whose local
 *    log-contrast is equal in R, G and B (Grayness Index, Qian et al. CVPR
 *    2019) vote extra.
 *  - Aggregate votes that place the light ALONG the locus, which a beige
 *    wall or skin (both on the locus) otherwise biases warm: gray-edge
 *    (van de Weijer 2007; flat walls have no edges), bright pixels (Joze
 *    2012; specular highlights carry the illuminant) and shades-of-gray p=6.
 *  - Priors: a tint prior that allows fluorescent/LED green-magenta offsets
 *    when evidence supports them (no hard projection onto the locus), and a
 *    position prior from scene brightness (bright = daylight, dim = indoor),
 *    weak and centred on daylight when no exposure metadata is given.
 */
internal object RawWhiteBalanceFallback {
    /** Cells sampled per axis: 64x48 cells of 4x4 pixels, ~50k reads. */
    private const val GRID_X = 64
    private const val GRID_Y = 48
    /** Cells with any site above this normalized level are clipped: excluded. */
    private const val CLIP = 0.92
    private const val MIN_CELLS = 64
    /** Gray-cell count at which the gray vote reaches half its full weight. */
    private const val GRAY_HALF = 24.0
    /** Share of gray evidence in one bin beyond which it saturates (tuned on the benchmark). */
    private const val AREA_KNEE = 0.1
    /**
     * Intrinsic spread of near-neutral real surfaces around the locus in log
     * chroma, added in quadrature to each cell's measured noise.
     */
    internal const val SURFACE_SPREAD = 0.04
    /** Tint prior width (log chroma) perpendicular to the locus. */
    private const val TINT_SIGMA = 0.06
    /** Histogram bin width and margin around the locus (log chroma). */
    private const val BIN = 0.02
    private const val MARGIN = 0.4
    /** Width of an aggregate estimate's blob in the histogram. */
    private const val BLOB_SIGMA = 0.05
    /** Aggregate vote weights relative to the full gray vote (1.0). */
    private const val EDGE_WEIGHT = 1.0
    private const val BRIGHT_WEIGHT = 0.6
    private const val SOG_WEIGHT = 0.25
    /** Plausible camera-space neutral range (R/G and B/G) for daylight..tungsten. */
    private const val NEUTRAL_MIN = 0.1
    private const val NEUTRAL_MAX = 2.0
    /** Position prior centre without exposure metadata. */
    private const val DAYLIGHT_K = 5500.0

    /**
     * True when the HAL supplied no usable white balance: the neutral point is
     * missing or invalid AND the gains are missing or exactly unity. Valid
     * gains alone are still honored downstream (derived neutral), so only
     * the fully empty shape triggers the fallback.
     */
    fun halAwbMissing(neutral: DoubleArray?, gains: FloatArray?): Boolean {
        val neutralOk = neutral != null && neutral.size == 3 &&
            neutral.all { it.isFinite() && it > 1e-6 }
        if (neutralOk) return false
        return gains == null || gains.size != 4 ||
            gains.any { !it.isFinite() || it <= 0f } ||
            gains.all { abs(it - 1f) < 1e-3f }
    }

    /**
     * Camera-space white locus as a polyline in log chroma
     * (u = ln R/G, v = ln B/G), ordered by ascending [kelvin].
     */
    class Locus internal constructor(
        internal val kelvin: DoubleArray,
        internal val u: DoubleArray,
        internal val v: DoubleArray
    ) {
        init {
            require(kelvin.size >= 2 && u.size == kelvin.size && v.size == kelvin.size)
        }

        /** Nearest locus point as (u, v, distance, fractional index). */
        fun project(pu: Double, pv: Double): DoubleArray {
            var best = doubleArrayOf(u[0], v[0], Double.MAX_VALUE, 0.0)
            for (i in 0 until kelvin.size - 1) {
                val du = u[i + 1] - u[i]
                val dv = v[i + 1] - v[i]
                val len2 = du * du + dv * dv
                val t = if (len2 <= 0.0) 0.0 else
                    (((pu - u[i]) * du + (pv - v[i]) * dv) / len2).coerceIn(0.0, 1.0)
                val qu = u[i] + du * t
                val qv = v[i] + dv * t
                val d = sqrt((pu - qu) * (pu - qu) + (pv - qv) * (pv - qv))
                if (d < best[2]) best = doubleArrayOf(qu, qv, d, i + t)
            }
            return best
        }

        /** Locus point at a fractional index (clamped). */
        fun at(index: Double): DoubleArray {
            val i = index.coerceIn(0.0, (kelvin.size - 1).toDouble())
            val lo = i.toInt().coerceAtMost(kelvin.size - 2)
            val t = i - lo
            return doubleArrayOf(u[lo] + (u[lo + 1] - u[lo]) * t, v[lo] + (v[lo + 1] - v[lo]) * t)
        }

        /** Fractional index of the locus sample nearest [k] kelvin. */
        fun indexOf(k: Double): Double {
            for (i in 0 until kelvin.size - 1) {
                if (k <= kelvin[i + 1]) {
                    val span = kelvin[i + 1] - kelvin[i]
                    return i + if (span > 0) ((k - kelvin[i]) / span).coerceIn(0.0, 1.0) else 0.0
                }
            }
            return (kelvin.size - 1).toDouble()
        }

        companion object {
            /**
             * Locus from Camera2-order colour matrices (XYZ -> camera) and
             * optional calibrations, interpolated in 1/T between the two
             * reference illuminants as DNG prescribes. Null when unusable.
             */
            fun from(
                colorMatrix1: ImmutableDoubleValues?, colorMatrix2: ImmutableDoubleValues?,
                calibration1: ImmutableDoubleValues?, calibration2: ImmutableDoubleValues?,
                illuminant1: Int?, illuminant2: Int?
            ): Locus? {
                fun prepared(cm: ImmutableDoubleValues?, cal: ImmutableDoubleValues?): Matrix3? {
                    val m = SceneLinearColorProcessor.camera2MatrixOrNull(cm) ?: return null
                    val withCal = SceneLinearColorProcessor.camera2MatrixOrNull(cal)?.let { it * m } ?: m
                    // Scalar normalization (DNG NormalizeColorMatrix): ratios stay intact.
                    val d50 = withCal * Vec3(0.96422, 1.0, 0.82521)
                    val peak = d50.maxComponent()
                    if (!peak.isFinite() || peak <= 1e-9) return null
                    return Matrix3(withCal.values().map { it / peak }.toDoubleArray())
                }
                val m1 = prepared(colorMatrix1, calibration1) ?: return null
                val m2 = prepared(colorMatrix2, calibration2)
                val t1 = SceneLinearColorProcessor.ILLUMINANT_KELVIN[illuminant1]?.toDouble()
                val t2 = SceneLinearColorProcessor.ILLUMINANT_KELVIN[illuminant2]?.toDouble()
                val ks = ArrayList<Double>()
                val us = ArrayList<Double>()
                val vs = ArrayList<Double>()
                val steps = 28
                for (s in 0..steps) {
                    val k = 2300.0 * (9000.0 / 2300.0).pow(s.toDouble() / steps)
                    val m = if (m2 == null || t1 == null || t2 == null || t1 == t2) m1 else {
                        val g = ((1 / k - 1 / t1) / (1 / t2 - 1 / t1)).coerceIn(0.0, 1.0)
                        Matrix3.lerp(m1, m2, g)
                    }
                    val cam = m * planckXyz(k)
                    if (!cam.isFinite() || cam.x <= 0 || cam.y <= 0 || cam.z <= 0) continue
                    ks += k; us += ln(cam.x / cam.y); vs += ln(cam.z / cam.y)
                }
                if (ks.size < 8) return null
                return Locus(ks.toDoubleArray(), us.toDoubleArray(), vs.toDoubleArray())
            }

            /** Blackbody white (Y = 1) via the Kim et al. cubic spline, 1667..25000 K. */
            internal fun planckXyz(k: Double): Vec3 {
                val x = if (k <= 4000) -0.2661239e9 / k.pow(3) - 0.2343589e6 / k.pow(2) + 0.8776956e3 / k + 0.179910
                    else -3.0258469e9 / k.pow(3) + 2.1070379e6 / k.pow(2) + 0.2226347e3 / k + 0.240390
                val y = when {
                    k <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
                    k <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
                    else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
                }
                return Vec3(x / y, 1.0, (1 - x - y) / y)
            }
        }

        /** Histogram geometry for this locus (bin -> locus distance and index), built once. */
        internal val grid: Grid by lazy { Grid(this) }
    }

    /** Log-chroma histogram lattice around a [Locus]. */
    internal class Grid(locus: Locus) {
        val u0 = locus.u.min() - MARGIN
        val v0 = locus.v.min() - MARGIN
        val nu = ((locus.u.max() + MARGIN - u0) / BIN).toInt() + 1
        val nv = ((locus.v.max() + MARGIN - v0) / BIN).toInt() + 1
        val dist = DoubleArray(nu * nv)
        val index = DoubleArray(nu * nv)

        init {
            for (j in 0 until nv) for (i in 0 until nu) {
                val p = locus.project(u0 + i * BIN, v0 + j * BIN)
                dist[j * nu + i] = p[2]
                index[j * nu + i] = p[3]
            }
        }

        fun newHistogram() = DoubleArray(nu * nv)

        /** Bilinear splat of weight [w] at (u, v); outside the lattice is dropped. */
        fun splat(h: DoubleArray, u: Double, v: Double, w: Double) {
            val fu = (u - u0) / BIN
            val fv = (v - v0) / BIN
            val i = fu.toInt()
            val j = fv.toInt()
            if (fu < 0 || fv < 0 || i >= nu - 1 || j >= nv - 1) return
            val tu = fu - i
            val tv = fv - j
            h[j * nu + i] += w * (1 - tu) * (1 - tv)
            h[j * nu + i + 1] += w * tu * (1 - tv)
            h[(j + 1) * nu + i] += w * (1 - tu) * tv
            h[(j + 1) * nu + i + 1] += w * tu * tv
        }

        /** Adds an isotropic Gaussian blob of total mass [w] centred at (u, v). */
        fun blob(h: DoubleArray, u: Double, v: Double, sigma: Double, w: Double) {
            val r = (3 * sigma / BIN).toInt() + 1
            val ci = ((u - u0) / BIN).toInt()
            val cj = ((v - v0) / BIN).toInt()
            var sum = 0.0
            val tmp = ArrayList<Pair<Int, Double>>()
            for (j in max(0, cj - r)..min(nv - 1, cj + r)) for (i in max(0, ci - r)..min(nu - 1, ci + r)) {
                val du = u0 + i * BIN - u
                val dv = v0 + j * BIN - v
                val g = exp(-(du * du + dv * dv) / (2 * sigma * sigma))
                tmp += (j * nu + i) to g
                sum += g
            }
            if (sum > 0) for ((k, g) in tmp) h[k] += w * g / sum
        }
    }

    /** Estimate plus its confidence in 0..1 (peak mass share times evidence strength). */
    class Estimate(val neutral: DoubleArray, val confidence: Double)

    /**
     * Exposure value at ISO 100 of the capture settings, or null when any is
     * unreported. Combined with the frame's own signal level it gives scene
     * brightness for the locus position prior.
     */
    fun exposureEv100(exposureNs: Long?, iso: Int?, aperture: Float?): Double? {
        if (exposureNs == null || exposureNs <= 0 || iso == null || iso <= 0) return null
        val n = aperture?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: return null
        return log2(n * n / (exposureNs * 1e-9)) - log2(iso / 100.0)
    }

    /**
     * Camera-space neutral (R/G, 1, B/G) from a 16-bit Bayer plane, or null
     * when the frame offers too little usable signal or the result is
     * implausible. [cfa] is the Camera2 arrangement (0..3); [black] is in
     * Camera2 order (TL, TR, BL, BR of each 2x2 quad); reads are absolute and
     * bounds-checked against the buffer limit. With a [locus] the estimate is
     * the histogram posterior of the class doc; without one it is gray-world.
     * [ev100] (see [exposureEv100]) feeds the brightness prior when known.
     */
    fun estimate(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
        cfa: Int, black: FloatArray, white: Float, locus: Locus? = null, ev100: Double? = null
    ): DoubleArray? = estimateDetailed(buffer, rowStride, pixelStride, width, height,
        cfa, black, white, locus, ev100)?.neutral

    /** [estimate] with its confidence, for the viewfinder [Tracker]. */
    fun estimateDetailed(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
        cfa: Int, black: FloatArray, white: Float, locus: Locus? = null, ev100: Double? = null
    ): Estimate? {
        if (pixelStride != 2 || rowStride <= 0 || width < 8 || height < 8) return null
        if (black.size != 4 || !white.isFinite()) return null
        val pattern = runCatching { BayerPattern.fromCamera2(cfa) }.getOrNull() ?: return null
        val range = DoubleArray(4) { (white - black[it]).toDouble() }
        if (range.any { it <= 1.0 }) return null
        val cells = sampleCells(buffer, rowStride, width, height, pattern, black, range) ?: return null
        val valid = cells.validIndices()
        if (valid.size < MIN_CELLS) return null
        val noise = NoiseModel.fit(cells, valid)
        // Too dark to tell colour from noise: chroma noise swamps any surface.
        var meanG = 0.0
        for (k in valid) meanG += cells.g[k]
        meanG /= valid.size
        if (noise.chromaSigma(meanG, meanG, meanG) > 0.5) return null
        val result = if (locus == null) grayWorld(cells, valid)
            else posterior(cells, valid, noise, locus, ev100?.let { it + log2(meanG / MID_GRAY) })
        return result?.takeIf { e -> e.neutral.all { it.isFinite() } &&
            e.neutral[0] in NEUTRAL_MIN..NEUTRAL_MAX && e.neutral[2] in NEUTRAL_MIN..NEUTRAL_MAX }
    }

    /** Normalized raw level a metered mid-gray lands at, for scene EV. */
    private const val MID_GRAY = 0.12

    /** Per-cell channel means (normalized) and green-pair difference energy. */
    internal class Cells(val nx: Int, val ny: Int) {
        val r = DoubleArray(nx * ny)
        val g = DoubleArray(nx * ny)
        val b = DoubleArray(nx * ny)
        /** Mean of (G1 - G2)^2 / 2 over the cell's quads: per-site green variance + texture. */
        val gd2 = DoubleArray(nx * ny)
        val ok = BooleanArray(nx * ny)

        fun validIndices(): IntArray = (0 until nx * ny).filter { ok[it] && g[it] > 0 }.toIntArray()
    }

    private fun sampleCells(
        buffer: ByteBuffer, rowStride: Int, width: Int, height: Int,
        pattern: BayerPattern, black: FloatArray, range: DoubleArray
    ): Cells? {
        val stepX = max(4, (width / GRID_X) and 1.inv())
        val stepY = max(4, (height / GRID_Y) and 1.inv())
        val nx = (width - 4) / stepX + 1
        val ny = (height - 4) / stepY + 1
        if (nx < 2 || ny < 2) return null
        val cells = Cells(nx, ny)
        val lastReadable = buffer.limit() - 2L
        val color = IntArray(4) { i ->
            when (pattern.colorAt(i and 1, i shr 1)) { CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2 }
        }
        val v = DoubleArray(4)
        for (cy in 0 until ny) for (cx in 0 until nx) {
            val x0 = cx * stepX
            val y0 = cy * stepY
            if ((y0 + 3).toLong() * rowStride + (x0 + 3).toLong() * 2 > lastReadable) continue
            var r = 0.0; var g = 0.0; var b = 0.0; var d2 = 0.0
            var usable = true
            quads@ for (qy in 0..1) for (qx in 0..1) {
                var g1 = -1.0
                var g2 = 0.0
                for (i in 0..3) {
                    val offset = (y0 + qy * 2 + (i shr 1)).toLong() * rowStride +
                        (x0 + qx * 2 + (i and 1)).toLong() * 2
                    val code = buffer.getShort(offset.toInt()).toInt() and 0xffff
                    val n = (code - black[i]) / range[i]
                    if (n >= CLIP) { usable = false; break@quads }
                    v[i] = n
                    when (color[i]) {
                        0 -> r += n
                        1 -> if (g1 < -0.5) g1 = n else g2 = n
                        else -> b += n
                    }
                }
                g += g1 + g2
                d2 += (g1 - g2) * (g1 - g2) / 2
            }
            if (!usable) continue
            val k = cy * nx + cx
            cells.r[k] = max(r / 4, 0.0)
            cells.g[k] = g / 8
            cells.b[k] = max(b / 4, 0.0)
            cells.gd2[k] = d2 / 4
            cells.ok[k] = true
        }
        return cells
    }

    /**
     * Per-site variance a*level + b fitted to this frame. Each cell's green
     * pairs give (G1-G2)^2/2 = site variance + texture; the lower quartile per
     * level bin rejects texture (a chi-square(4)/4 lower quartile is 0.48 of
     * its mean, corrected for).
     */
    internal class NoiseModel(val a: Double, val b: Double) {
        fun siteVar(level: Double) = a * max(level, 0.0) + b

        /** Log-chroma noise sigma (u and v combined) of a cell with these means. */
        fun chromaSigma(r: Double, g: Double, bl: Double): Double {
            val lg = siteVar(g) / (8 * g * g)
            val lr = siteVar(r) / (4 * max(r, 1e-6).pow(2))
            val lb = siteVar(bl) / (4 * max(bl, 1e-6).pow(2))
            return sqrt(lr + lb + 2 * lg)
        }

        /** Log-ratio variance of one channel mean over [sites] sites at [level]. */
        fun logVar(level: Double, sites: Int) = siteVar(level) / (sites * max(level, 1e-6).pow(2))

        companion object {
            private const val BINS = 8
            private const val CHI4_Q25 = 0.48

            fun fit(cells: Cells, valid: IntArray): NoiseModel {
                val order = valid.sortedBy { cells.g[it] }
                val xs = ArrayList<Double>()
                val ys = ArrayList<Double>()
                val per = order.size / BINS
                if (per >= 4) for (bin in 0 until BINS) {
                    val slice = order.subList(bin * per, if (bin == BINS - 1) order.size else (bin + 1) * per)
                    val d = slice.map { cells.gd2[it] }.sorted()
                    xs += slice.map { cells.g[it] }.average()
                    ys += d[d.size / 4] / CHI4_Q25
                }
                if (xs.size < 2) return NoiseModel(0.0, ys.firstOrNull() ?: 1e-8)
                val mx = xs.average()
                val my = ys.average()
                var sxy = 0.0; var sxx = 0.0
                for (i in xs.indices) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx).pow(2) }
                var a = if (sxx > 0) sxy / sxx else 0.0
                var b = my - a * mx
                if (a < 0) { a = 0.0; b = my }
                if (b < 0) { b = 0.0; a = my / max(mx, 1e-9) }
                return NoiseModel(a, max(b, 1e-10))
            }
        }
    }

    private fun grayWorld(cells: Cells, valid: IntArray): Estimate? {
        var r = 0.0; var g = 0.0; var b = 0.0
        for (k in valid) { r += cells.r[k]; g += cells.g[k]; b += cells.b[k] }
        if (g <= 0) return null
        return Estimate(doubleArrayOf(r / g, 1.0, b / g), 0.5)
    }

    private fun posterior(cells: Cells, valid: IntArray, noise: NoiseModel, locus: Locus, sceneEv: Double?): Estimate? {
        val grid = locus.grid
        val nx = cells.nx
        // Gray-cell votes, with a Grayness-Index bonus for textured cells.
        val gray = grid.newHistogram()
        var grayCount = 0.0
        for (k in valid) {
            val r = cells.r[k]; val g = cells.g[k]; val b = cells.b[k]
            if (r <= 0 || b <= 0) continue
            val u = ln(r / g)
            val v = ln(b / g)
            val sigma = noise.chromaSigma(r, g, b)
            val s2 = SURFACE_SPREAD * SURFACE_SPREAD + sigma * sigma
            val d = locus.project(u, v)[2]
            if (d * d > 6.25 * s2) continue
            // Gray-vs-coloured posterior, not the raw tail: thousands of
            // off-white cells at 2 sigma must not outvote a real neutral.
            val like = exp(-d * d / (2 * s2)).let { it * it * it }
            if (like > 0.5) grayCount++
            grid.splat(gray, u, v, g * like * (1 + 2 * grayness(cells, k, nx, noise)))
        }
        val evidence = grid.newHistogram()
        var grayMass = 0.0
        // Saturating compression: a surface covering more than ~AREA_KNEE of
        // the gray evidence stops gaining votes, so a large beige wall and a
        // small white object compete as surfaces, not by area.
        var rawMass = 0.0
        for (x in gray) rawMass += x
        val knee = AREA_KNEE * rawMass
        for (i in gray.indices) { gray[i] = gray[i] / (gray[i] + knee); grayMass += gray[i] }
        var strength = 0.0
        if (grayMass > 0) {
            val w = grayCount / (grayCount + GRAY_HALF)
            for (i in gray.indices) evidence[i] = gray[i] / grayMass * w
            strength += w
        }
        // Aggregates: each gives one chroma, placed as a blob (projected to the
        // locus and down-weighted by how far off it the raw aggregate fell).
        fun aggregate(rgb: DoubleArray?, weight: Double) {
            if (rgb == null || rgb.any { !(it > 0) }) return
            val u = ln(rgb[0] / rgb[1]); val v = ln(rgb[2] / rgb[1])
            val p = locus.project(u, v)
            val keep = max(0.2, exp(-p[2] * p[2] / (2 * 0.15 * 0.15)))
            // Near the locus keep the aggregate's own tint; far off, only its direction.
            val t = exp(-p[2] * p[2] / (2 * TINT_SIGMA * TINT_SIGMA))
            grid.blob(evidence, p[0] + (u - p[0]) * t, p[1] + (v - p[1]) * t, BLOB_SIGMA, weight * keep)
            strength += weight * keep
        }
        aggregate(grayEdge(cells, valid, nx, noise), EDGE_WEIGHT)
        aggregate(brightPixels(cells, valid), BRIGHT_WEIGHT)
        aggregate(shadesOfGray(cells, valid), SOG_WEIGHT)
        if (strength <= 0) return null
        // Priors: tint around the locus, position along it from brightness.
        val (centre, spread) = positionPrior(locus, sceneEv)
        val post = DoubleArray(evidence.size)
        var total = 0.0
        var best = -1
        for (i in post.indices) {
            if (evidence[i] <= 0) continue
            val d = grid.dist[i]
            val di = grid.index[i] - centre
            post[i] = evidence[i] * exp(-d * d / (2 * TINT_SIGMA * TINT_SIGMA) - di * di / (2 * spread * spread))
            total += post[i]
            if (best < 0 || post[i] > post[best]) best = i
        }
        if (best < 0 || total <= 0) return null
        // Sub-bin peak: centroid of the 5x5 neighbourhood.
        val bi = best % grid.nu
        val bj = best / grid.nu
        var su = 0.0; var sv = 0.0; var sw = 0.0
        for (j in max(0, bj - 2)..min(grid.nv - 1, bj + 2)) for (i in max(0, bi - 2)..min(grid.nu - 1, bi + 2)) {
            val w = post[j * grid.nu + i]
            su += w * (grid.u0 + i * BIN); sv += w * (grid.v0 + j * BIN); sw += w
        }
        val u = su / sw
        val v = sv / sw
        val confidence = (sw / total) * min(1.0, strength / (1 + EDGE_WEIGHT))
        return Estimate(doubleArrayOf(exp(u), 1.0, exp(v)), confidence.coerceIn(0.0, 1.0))
    }

    /**
     * Grayness 0..1: local log-contrast to the right/down neighbour is equal
     * in R, G and B (beyond noise) while the contrast itself is significant.
     */
    private fun grayness(cells: Cells, k: Int, nx: Int, noise: NoiseModel): Double {
        var best = 0.0
        val cx = k % nx
        for (n in intArrayOf(if (cx + 1 < nx) k + 1 else -1, k + nx)) {
            if (n < 0 || n >= cells.ok.size || !cells.ok[n]) continue
            val r0 = cells.r[k]; val g0 = cells.g[k]; val b0 = cells.b[k]
            val r1 = cells.r[n]; val g1 = cells.g[n]; val b1 = cells.b[n]
            if (r0 <= 0 || b0 <= 0 || r1 <= 0 || b1 <= 0 || g1 <= 0) continue
            val vg = noise.logVar(g0, 8) + noise.logVar(g1, 8)
            val vr = noise.logVar(r0, 4) + noise.logVar(r1, 4)
            val vb = noise.logVar(b0, 4) + noise.logVar(b1, 4)
            val dg = ln(g0 / g1)
            if (dg * dg < 9 * vg) continue
            val er = ln(r0 / r1) - dg
            val eb = ln(b0 / b1) - dg
            val tol = vr + vb + 2 * vg + (0.05 * dg).pow(2)
            best = max(best, exp(-(er * er + eb * eb) / (2 * tol)))
        }
        return best
    }

    /** Gray-edge (p = 2) over neighbour differences that rise above noise. */
    private fun grayEdge(cells: Cells, valid: IntArray, nx: Int, noise: NoiseModel): DoubleArray? {
        val e = DoubleArray(3)
        var count = 0
        for (k in valid) {
            val cx = k % nx
            for (n in intArrayOf(if (cx + 1 < nx) k + 1 else -1, k + nx)) {
                if (n < 0 || n >= cells.ok.size || !cells.ok[n]) continue
                val dg = cells.g[k] - cells.g[n]
                val vg = (noise.siteVar(cells.g[k]) + noise.siteVar(cells.g[n])) / 8
                if (dg * dg < 9 * vg) continue
                e[0] += (cells.r[k] - cells.r[n]).pow(2)
                e[1] += dg * dg
                e[2] += (cells.b[k] - cells.b[n]).pow(2)
                count++
            }
        }
        if (count < 16) return null
        return DoubleArray(3) { sqrt(e[it]) }
    }

    /** Mean of the brightest 3 % of unclipped cells (at least 4). */
    private fun brightPixels(cells: Cells, valid: IntArray): DoubleArray? {
        val n = max(4, valid.size * 3 / 100)
        if (valid.size < n * 4) return null
        val top = valid.sortedByDescending { cells.r[it] + cells.g[it] + cells.b[it] }.take(n)
        return doubleArrayOf(top.sumOf { cells.r[it] }, top.sumOf { cells.g[it] }, top.sumOf { cells.b[it] })
    }

    /** Shades-of-gray, p = 6 (Finlayson & Trezzi 2004). */
    private fun shadesOfGray(cells: Cells, valid: IntArray): DoubleArray {
        val s = DoubleArray(3)
        for (k in valid) { s[0] += cells.r[k].pow(6); s[1] += cells.g[k].pow(6); s[2] += cells.b[k].pow(6) }
        return DoubleArray(3) { s[it].pow(1.0 / 6) }
    }

    /**
     * Locus-index centre and spread. Scene EV >= 13 is sunlit outdoors
     * (centre 5800 K, tight); <= 7 is indoor light (centre 3600 K, broad);
     * unknown is a weak daylight prior.
     */
    private fun positionPrior(locus: Locus, sceneEv: Double?): Pair<Double, Double> {
        if (sceneEv == null || !sceneEv.isFinite()) return locus.indexOf(DAYLIGHT_K) to 10.0
        val t = ((sceneEv - 7) / 6).coerceIn(0.0, 1.0)
        val k = 3600.0 * (5800.0 / 3600.0).pow(t)
        return locus.indexOf(k) to (9.0 - 5.0 * t)
    }

    /** Camera2-order gains (R, Geven, Godd, B) that neutralize [neutral]. */
    fun gainsFor(neutral: DoubleArray): FloatArray =
        floatArrayOf((1.0 / neutral[0]).toFloat(), 1f, 1f, (1.0 / neutral[2]).toFloat())

    private var locusCacheKey: CameraCharacteristics? = null
    private var locusCache: Locus? = null

    /** [Locus] for [c], memoized per characteristics identity (shared by VF and saves). */
    fun locusFor(c: CameraCharacteristics): Locus? = synchronized(this) {
        if (c === locusCacheKey) return locusCache
        val locus = runCatching {
            Locus.from(
                c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1).toImmutableDoubles(),
                c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2).toImmutableDoubles(),
                c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1).toImmutableDoubles(),
                c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2).toImmutableDoubles(),
                c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
                c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            )
        }.getOrNull()
        locusCacheKey = c
        locusCache = locus
        locus
    }

    /**
     * Viewfinder-side state: re-estimates at most every [intervalMs] while the
     * HAL WB is missing and filters the estimates with a Kalman filter in log
     * chroma (Qian 2017 / FFCC style temporal smoothing), so the preview never
     * pulses. Confident estimates move it faster; a sustained large innovation
     * (scene cut) inflates the state variance so it converges in about a
     * second instead of crawling. The rendered neutral holds still until the
     * filter moves past a just-noticeable step. Camera thread only.
     */
    class Tracker(private val intervalMs: Long = 500L) {
        var neutral: DoubleArray? = null
            private set
        private var lastMs: Long? = null
        private var stateU = 0.0
        private var stateV = 0.0
        private var variance = 0.0
        private var outliers = 0

        fun reset() {
            neutral = null
            lastMs = null
            outliers = 0
        }

        /** Returns the neutral to render with, or null when the HAL WB is usable. */
        fun update(nowMs: Long, halMissing: Boolean, estimate: () -> Estimate?): DoubleArray? {
            if (!halMissing) {
                reset()
                return null
            }
            val last = lastMs
            if (last == null || nowMs - last >= intervalMs) {
                lastMs = nowMs
                estimate()?.let { fresh -> absorb(fresh, if (last == null) 0.0 else (nowMs - last) / 1000.0) }
            }
            return neutral
        }

        private fun absorb(e: Estimate, dtSec: Double) {
            val mu = ln(e.neutral[0])
            val mv = ln(e.neutral[2])
            // Measurement variance: 0.02 log chroma at full confidence.
            val r = (0.02 * 0.02) / max(e.confidence, 0.05)
            if (neutral == null) {
                stateU = mu; stateV = mv; variance = r
                neutral = e.neutral.copyOf()
                return
            }
            variance += PROCESS_VAR_PER_SEC * dtSec
            val innov2 = (mu - stateU).pow(2) + (mv - stateV).pow(2)
            if (innov2 > 9 * 2 * (variance + r)) {
                // Two consecutive outliers: the scene changed, reopen the filter.
                if (++outliers >= 2) variance += innov2
            } else outliers = 0
            val gain = variance / (variance + r)
            stateU += gain * (mu - stateU)
            stateV += gain * (mv - stateV)
            variance *= 1 - gain
            val shown = requireNotNull(neutral)
            if (abs(ln(shown[0]) - stateU) > JND || abs(ln(shown[2]) - stateV) > JND) {
                neutral = doubleArrayOf(exp(stateU), 1.0, exp(stateV))
            }
        }

        private companion object {
            /** Illuminant drift allowed per second of wall time (log chroma variance). */
            const val PROCESS_VAR_PER_SEC = 0.0004
            /** Smallest rendered step, ~0.4 % in a channel ratio. */
            const val JND = 0.004
        }
    }
}
