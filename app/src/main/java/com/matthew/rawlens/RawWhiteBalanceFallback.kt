// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
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
 * Plain gray-world fails on scenes dominated by one colour (grass, sky, a
 * painted wall). With the sensor's colour matrices available, the estimate
 * is constrained to the camera-space white locus ([Locus]: the neutrals of
 * 2300..9000 K blackbody light through this sensor): only quads whose
 * chromaticity lies near the locus vote, weighted toward brighter quads, and
 * the result is projected onto the locus. A green lawn or a blue sky sits far
 * off it and cannot drag the white point; when nothing near-neutral exists
 * the whole-frame mean is projected and pulled halfway toward daylight.
 */
internal object RawWhiteBalanceFallback {
    /** Quads sampled per axis: 64x48 = 3072 quads, well under a millisecond. */
    private const val GRID_X = 64
    private const val GRID_Y = 48
    /** Quads with any site above this normalized level are clipped: excluded. */
    private const val CLIP = 0.92
    /** Quads with green below this are noise-dominated: excluded. */
    private const val DARK = 0.01
    private const val MIN_QUADS = 64
    /** Gray candidates needed before their vote is trusted over the fallback. */
    private const val MIN_GRAY_QUADS = 24
    /** Log-chroma distance from the locus within which a quad counts as gray. */
    internal const val GRAY_TOLERANCE = 0.12
    /** Plausible camera-space neutral range (R/G and B/G) for daylight..tungsten. */
    private const val NEUTRAL_MIN = 0.1
    private const val NEUTRAL_MAX = 2.0
    /** No-gray-evidence fallback pulls the projected point toward this. */
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
    }

    /**
     * Camera-space neutral (R/G, 1, B/G) from a 16-bit Bayer plane, or null
     * when the frame offers too little usable signal or the result is
     * implausible. [cfa] is the Camera2 arrangement (0..3); [black] is in
     * Camera2 order (TL, TR, BL, BR of each 2x2 quad); reads are absolute and
     * bounds-checked against the buffer limit. With a [locus] the estimate is
     * locus-constrained (see the class doc); without one it is gray-world.
     */
    fun estimate(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
        cfa: Int, black: FloatArray, white: Float, locus: Locus? = null
    ): DoubleArray? {
        if (pixelStride != 2 || rowStride <= 0 || width < 4 || height < 4) return null
        if (black.size != 4 || !white.isFinite()) return null
        val pattern = runCatching { BayerPattern.fromCamera2(cfa) }.getOrNull() ?: return null
        val range = DoubleArray(4) { (white - black[it]).toDouble() }
        if (range.any { it <= 1.0 }) return null
        val lastReadable = buffer.limit() - 2
        val stepX = maxOf(2, (width / GRID_X) and 1.inv())
        val stepY = maxOf(2, (height / GRID_Y) and 1.inv())
        // Whole-frame sums (gray-world) and locus-gray sums side by side.
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var grayR = 0.0; var grayG = 0.0; var grayB = 0.0
        var quads = 0
        var grayQuads = 0
        val v = DoubleArray(4)
        var y = 0
        while (y + 1 < height) {
            var x = 0
            while (x + 1 < width) {
                var usable = true
                for (i in 0..3) {
                    val offset = (y + (i shr 1)).toLong() * rowStride + (x + (i and 1)).toLong() * 2
                    if (offset > lastReadable) { usable = false; break }
                    val code = buffer.getShort(offset.toInt()).toInt() and 0xffff
                    val n = (code - black[i]) / range[i]
                    if (n >= CLIP) { usable = false; break }
                    v[i] = n.coerceAtLeast(0.0)
                }
                if (usable) {
                    var r = 0.0; var g = 0.0; var b = 0.0
                    for (i in 0..3) when (pattern.colorAt(i and 1, i shr 1)) {
                        CfaColor.RED -> r += v[i]
                        CfaColor.GREEN -> g += v[i] * 0.5
                        CfaColor.BLUE -> b += v[i]
                    }
                    if (g >= DARK) {
                        sumR += r; sumG += g; sumB += b
                        quads++
                        if (locus != null && r > 0.0 && b > 0.0) {
                            val d = locus.project(ln(r / g), ln(b / g))[2]
                            if (d < GRAY_TOLERANCE) {
                                // Brighter, closer-to-locus quads carry the vote.
                                val w = g * (1.0 - d / GRAY_TOLERANCE)
                                grayR += r * w; grayG += g * w; grayB += b * w
                                grayQuads++
                            }
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }
        if (quads < MIN_QUADS || sumG <= 0.0) return null
        val neutral = if (locus == null) {
            doubleArrayOf(sumR / sumG, 1.0, sumB / sumG)
        } else if (grayQuads >= MIN_GRAY_QUADS && grayG > 0.0 && grayR > 0.0 && grayB > 0.0) {
            val p = locus.project(ln(grayR / grayG), ln(grayB / grayG))
            doubleArrayOf(exp(p[0]), 1.0, exp(p[1]))
        } else if (sumR > 0.0 && sumB > 0.0) {
            // No near-neutral evidence (one-colour frame): the whole-frame
            // mean only picks a direction along the locus, halved toward daylight.
            val p = locus.project(ln(sumR / sumG), ln(sumB / sumG))
            val q = locus.at((p[3] + locus.indexOf(DAYLIGHT_K)) / 2)
            doubleArrayOf(exp(q[0]), 1.0, exp(q[1]))
        } else return null
        return neutral.takeIf { n -> n.all { it.isFinite() } &&
            n[0] in NEUTRAL_MIN..NEUTRAL_MAX && n[2] in NEUTRAL_MIN..NEUTRAL_MAX }
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
     * HAL WB is missing and eases toward each estimate so the preview never
     * pulses. Camera thread only.
     */
    class Tracker(private val intervalMs: Long = 500L, private val alpha: Double = 0.35) {
        var neutral: DoubleArray? = null
            private set
        private var lastMs: Long? = null

        fun reset() {
            neutral = null
            lastMs = null
        }

        /** Returns the neutral to render with, or null when the HAL WB is usable. */
        fun update(nowMs: Long, halMissing: Boolean, estimate: () -> DoubleArray?): DoubleArray? {
            if (!halMissing) {
                reset()
                return null
            }
            if (lastMs == null || nowMs - lastMs!! >= intervalMs) {
                lastMs = nowMs
                estimate()?.let { fresh ->
                    val held = neutral
                    neutral = if (held == null) fresh else
                        DoubleArray(3) { held[it] + (fresh[it] - held[it]) * alpha }
                }
            }
            return neutral
        }
    }
}
