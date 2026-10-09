// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CPU reference for the [VfMhc] GPU port (Malvar-He-Cutler 2004 family):
 * MHC's row/col/opp chroma kernels applied as blind mosaic convolutions,
 * same mirror-first edges, normalized 0..1 — but GREEN at R/B sites is
 * edge-directed (the fixed MHC green kernel zippers ±0.075 along
 * high-contrast edges; see kernelGreen below), and CHROMA (R/B at every
 * site) is edge-directed difference-domain (the fixed chroma kernels
 * blur across edges while green stays sharp, which reads as cyan/red
 * halos around every contrast edge; see kernelChromaEW/NS/kernelOppDir).
 *
 * Outputs are clamped at 0 like RawTherapee (AMaZE clips negative
 * reconstruction excursions before white balance: a camera matrix turns
 * one channel's edge undershoot into a complementary fringe); positive
 * overshoot stays unclipped like the MHC paper (the grade handles >1).
 *
 * Pure Kotlin (no Android): JVM-testable ([MhcReferenceTest]) and reused
 * by the on-device parity probe, which gates mean abs diff against the
 * GPU readback (one FP16 store round-trip dominates the gap).
 */
object MhcReference {
    /**
     * Green edge-gate band (normalized 0..1, shared with the shaders):
     * below [GREEN_EDGE_T0] the directional pick rules, above
     * [GREEN_EDGE_T1] the symmetric MHC mean rules, linear blend between.
     */
    const val GREEN_EDGE_T0 = 0.03f
    const val GREEN_EDGE_T1 = 0.08f

    /**
     * Bilateral side floor (normalized 0..1, shared with the shaders).
     * Side weights are cross-distances (weight W by E's mismatch): a
     * step then picks its side ~600:1, flats stay 50/50.
     */
    const val BILATERAL_EPS = 1e-3f

    /**
     * Level-ratio regularizer (normalized 0..1, shared with the
     * shaders). Side chroma differences rescale to the center's light
     * level via (anchor + eps)/(side + eps); eps pins the ratio to 1 in
     * true black (fetchN clips at 0, so the denominator never vanishes
     * and no Cauchy speckle escapes the shadows).
     */
    const val LEVEL_EPS = 0.002f

    /**
     * Sensor-white desaturation band (normalized 0..1, shared with the
     * fused shader; same anchors as the AMaZE JPEG path): approaching
     * RAW clip, chroma converges on the camera neutral BEFORE white
     * balance, so clipped white renders white instead of magenta.
     */
    const val DESAT_LO = 0.70f
    const val DESAT_HI = 0.99f

    /**
     * Demosaiced output ceiling (shared with the shaders): legit kernel
     * ringing never exceeds ~1.3; past that is level-ratio explosion on
     * hue-violating pixels (side green ~0, side red ~1 -> R = 220). Both
     * sides clamp identically (parity-safe), and the grade clips >= 1 to
     * white, so the cap is downstream-invisible (BT.709/HLG
     * bit-identical; S-Log superwhite either way, nearer truth).
     */
    const val OUTPUT_CLAMP_HI = 4f

    /**
     * Demosaic the crop [(left, top), width x height] of the sensor plane.
     *
     * @param codes sensor codes row-major with [pitch] stride (full plane).
     * @param channels quad-offset map (channels[] convention).
     * @param levels per-quad black levels; [white] level.
     * @return interleaved RGB floats, width*height*3, crop row-major.
     */
    fun demosaic(
        codes: IntArray,
        sensorWidth: Int, sensorHeight: Int, pitch: Int,
        left: Int, top: Int, width: Int, height: Int,
        channels: IntArray,
        levels: FloatArray,
        white: Float,
    ): FloatArray {
        require(channels.size == 4 && levels.size == 4)
        return Engine(
            codes, sensorWidth, sensorHeight, pitch, left, top, width, height,
            channels, levels, white
        ).run()
    }

    /**
     * Sensor-domain white-point protection (CPU mirror of the fused
     * shader's pre-shade step; same formula as the AMaZE final stage).
     * As any demosaiced channel approaches RAW clipping, progressively
     * remove chroma by converging on the camera's measured neutral
     * white. Must run on demosaiced sensor levels BEFORE white balance:
     * afterwards cannot undo the magenta generated when a clipped
     * channel is amplified by white balance.
     *
     * @param rgb interleaved demosaiced sensor levels (clamped >= 0).
     * @param gains WB gains (R, G, B); the neutral is min(g)/g.
     * @return new array (the input is untouched).
     */
    fun desaturateHighlights(rgb: FloatArray, gains: FloatArray): FloatArray {
        require(gains.size >= 3)
        val g = FloatArray(3) { max(gains[it], 1e-6f) }
        val minG = min(g[0], min(g[1], g[2]))
        val neutral = FloatArray(3) { minG / g[it] }
        val out = FloatArray(rgb.size)
        var i = 0
        while (i < rgb.size) {
            val b = max(
                smoothstep(DESAT_LO, DESAT_HI, rgb[i]),
                max(
                    smoothstep(DESAT_LO, DESAT_HI, rgb[i + 1]),
                    smoothstep(DESAT_LO, DESAT_HI, rgb[i + 2])
                )
            )
            out[i] = rgb[i] + (neutral[0] - rgb[i]) * b
            out[i + 1] = rgb[i + 1] + (neutral[1] - rgb[i + 1]) * b
            out[i + 2] = rgb[i + 2] + (neutral[2] - rgb[i + 2]) * b
            i += 3
        }
        return out
    }

    /** GLSL-exact smoothstep (edge0 < edge1). */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Level ratio for chroma rescaling: (num + eps)/(den + eps) with
     * both terms floored at eps. On the CPU fetchN already clips taps
     * at 0, so the floors are no-ops there (except the green anchor,
     * which can ring negative in theory); on the GPU the taps are
     * unclipped FMA (sub-black noise reaches -0.067), so the floors
     * also bar division by ~zero (no INF/NaN speckle). Both sides
     * compute the identical expression for parity.
     */
    fun levelRatio(num: Float, den: Float): Float =
        max(num + LEVEL_EPS, LEVEL_EPS) / max(den + LEVEL_EPS, LEVEL_EPS)

    private class Engine(
        private val codes: IntArray,
        private val sensorWidth: Int, private val sensorHeight: Int, private val pitch: Int,
        private val left: Int, private val top: Int, private val width: Int, private val height: Int,
        private val channels: IntArray,
        levels: FloatArray,
        white: Float,
    ) {
        private val invRange = FloatArray(4) { i -> 1f / maxOf(1f, white - levels[i]) }
        private val black = levels.copyOf()

        // Phase-preserving edge mirror (single reflection covers +-2).
        private fun mirrorX(x: Int) = if (x < 0) -x else if (x >= width) 2 * width - 2 - x else x
        private fun mirrorY(y: Int) = if (y < 0) -y else if (y >= height) 2 * height - 2 - y else y

        private fun quadOf(x: Int, y: Int): Int =
            (((y + top) and 1) shl 1) or ((x + left) and 1)

        private fun fetchN(x: Int, y: Int): Float {
            val cx = mirrorX(x)
            val cy = mirrorY(y)
            val q = quadOf(cx, cy)
            val code = codes[(cy + top) * pitch + (cx + left)].toFloat()
            return ((code - black[q]) * invRange[q]).coerceIn(0f, 1f)
        }

        /** CFA color 0=R 1=G 2=B (canonical 1 and 2 are both green). */
        private fun colorAt(x: Int, y: Int): Int {
            val q = quadOf(x, y)
            if (channels[0] == q) return 0
            if (channels[1] == q || channels[2] == q) return 1
            return 2
        }

        /**
         * Edge-gate bank shared by green and chroma (same 9 taps the
         * green kernel reads; see kernelGreen for the activity metric).
         * [t] is the texture gate (both directions active -> symmetric
         * MHC); [f] is the flat gate (neither direction active ->
         * symmetric MHC, which is exact on flats and cannot hallucinate
         * anchor edges into chroma on chroma-only edges); [g] combines
         * them for chroma (green keeps [t]: it has no cross-channel
         * anchor to hallucinate with).
         */
        private class Gate(
            val dh: Float, val dv: Float,
            val t: Float, val g: Float, val hEdge: Boolean,
        )

        private fun gateOf(x: Int, y: Int): Gate {
            val c = fetchN(x, y)
            val w = fetchN(x - 1, y)
            val e = fetchN(x + 1, y)
            val n = fetchN(x, y - 1)
            val s = fetchN(x, y + 1)
            val w2 = fetchN(x - 2, y)
            val e2 = fetchN(x + 2, y)
            val n2 = fetchN(x, y - 2)
            val s2 = fetchN(x, y + 2)
            val ch = 2f * c - w2 - e2
            val cv = 2f * c - n2 - s2
            val dh = abs(w - e) + abs(ch) * 0.5f
            val dv = abs(n - s) + abs(cv) * 0.5f
            val t = ((min(dh, dv) - GREEN_EDGE_T0) / (GREEN_EDGE_T1 - GREEN_EDGE_T0))
                .coerceIn(0f, 1f)
            val f = ((GREEN_EDGE_T0 - max(dh, dv)) / GREEN_EDGE_T0).coerceIn(0f, 1f)
            return Gate(dh, dv, t, max(t, f), dh < dv)
        }

        /**
         * Edge-directed green at R/B sites (zipper fix). The fixed MHC
         * green kernel averages across edges, so interpolated sites err
         * while mosaic sites pass through — the alternating row-to-row
         * zipper. Instead: two second-order directional estimates (their
         * mean is exactly the MHC kernel, so flat fields and ramps are
         * bit-identical), steered by per-direction activity = green
         * gradient + same-color curvature. The curvature term matters:
         * green-only sensing goes blind on one side of a luma edge (or
         * on a fence line), where the ±2 R/B taps still disagree.
         * Corners/texture (both directions active) blend back to the
         * symmetric mean — a naive pick regresses checkerboards.
         * Same 13 taps, same stencil; R/B kernels below are untouched.
         */
        private fun kernelGreen(x: Int, y: Int): Float {
            val c = fetchN(x, y)
            val w = fetchN(x - 1, y)
            val e = fetchN(x + 1, y)
            val n = fetchN(x, y - 1)
            val s = fetchN(x, y + 1)
            val w2 = fetchN(x - 2, y)
            val e2 = fetchN(x + 2, y)
            val n2 = fetchN(x, y - 2)
            val s2 = fetchN(x, y + 2)
            val ch = 2f * c - w2 - e2
            val cv = 2f * c - n2 - s2
            val gh = (w + e) * 0.5f + ch * 0.25f
            val gv = (n + s) * 0.5f + cv * 0.25f
            val dh = abs(w - e) + abs(ch) * 0.5f
            val dv = abs(n - s) + abs(cv) * 0.5f
            val gd = if (dh < dv) gh else gv
            val gm = (gh + gv) * 0.5f
            val m = min(dh, dv)
            val t = ((m - GREEN_EDGE_T0) / (GREEN_EDGE_T1 - GREEN_EDGE_T0)).coerceIn(0f, 1f)
            return gd * (1f - t) + gm * t
        }

        private fun kernelRow(x: Int, y: Int): Float {
            var v = 5f * fetchN(x, y)
            v += 4f * (fetchN(x - 1, y) + fetchN(x + 1, y))
            v -= fetchN(x - 2, y) + fetchN(x + 2, y)
            v -= fetchN(x - 1, y - 1) + fetchN(x + 1, y - 1) +
                fetchN(x - 1, y + 1) + fetchN(x + 1, y + 1)
            v += 0.5f * (fetchN(x, y - 2) + fetchN(x, y + 2))
            return v * 0.125f
        }

        private fun kernelCol(x: Int, y: Int): Float {
            var v = 5f * fetchN(x, y)
            v += 4f * (fetchN(x, y - 1) + fetchN(x, y + 1))
            v -= fetchN(x, y - 2) + fetchN(x, y + 2)
            v -= fetchN(x - 1, y - 1) + fetchN(x + 1, y - 1) +
                fetchN(x - 1, y + 1) + fetchN(x + 1, y + 1)
            v += 0.5f * (fetchN(x - 2, y) + fetchN(x + 2, y))
            return v * 0.125f
        }

        private fun kernelOpp(x: Int, y: Int): Float {
            var v = 6f * fetchN(x, y)
            v += 2f * (fetchN(x - 1, y - 1) + fetchN(x + 1, y - 1) +
                fetchN(x - 1, y + 1) + fetchN(x + 1, y + 1))
            v -= 1.5f * (fetchN(x, y - 2) + fetchN(x, y + 2) + fetchN(x - 2, y) + fetchN(x + 2, y))
            return v * 0.125f
        }

        /**
         * Edge-directed chroma along the E/W axis (R at GR-type green, B
         * at GB-type): the mosaic channel value plus a level-rescaled
         * bilateral pick of the two sides' (mosaic - green) differences.
         * Anchoring on the center green (exact here: mosaic passthrough)
         * hands chroma green's sharp edge profile, which the fixed
         * row/col kernels cannot follow (±0.19 error, alternating
         * row-to-row, while green sits at 0 — the cyan/red halos). The
         * green estimate at each side tap is centered on that tap
         * (ramps stay exact), and the level ratio rescales side
         * differences to the center's light level (fence lines stay
         * exact under per-channel sensor scaling, where difference-only
         * anchoring mixes line and off-line levels). Same 13 taps; the
         * symmetric MHC kernel below stays the texture/flat fallback.
         *
         * @param gate edge bank from [gateOf] (shared with green).
         */
        private fun kernelChromaEW(x: Int, y: Int, gate: Gate): Float {
            val c = fetchN(x, y)
            val w = fetchN(x - 1, y)
            val e = fetchN(x + 1, y)
            val w2 = fetchN(x - 2, y)
            val e2 = fetchN(x + 2, y)
            // Side greens: along-edge taps on a V edge (the side column),
            // centered row mean on an H edge (same row, same side).
            val gwW: Float
            val gwE: Float
            if (gate.hEdge) {
                gwW = (c + w2) * 0.5f
                gwE = (c + e2) * 0.5f
            } else {
                gwW = (fetchN(x - 1, y - 1) + fetchN(x - 1, y + 1)) * 0.5f
                gwE = (fetchN(x + 1, y - 1) + fetchN(x + 1, y + 1)) * 0.5f
            }
            // Cross-distance side weights: weight W by E's mismatch, so a
            // step picks its side ~600:1 and flats stay 50/50. Ratio-equal
            // to reciprocal weights (same normalized blend, no RCPs).
            val wW = BILATERAL_EPS + abs(c - e2)
            val wE = BILATERAL_EPS + abs(c - w2)
            val rW = levelRatio(c, gwW)
            val rE = levelRatio(c, gwE)
            return c + (wW * (w - gwW) * rW + wE * (e - gwE) * rE) / (wW + wE)
        }

        /** Transpose of [kernelChromaEW] for the N/S axis. */
        private fun kernelChromaNS(x: Int, y: Int, gate: Gate): Float {
            val c = fetchN(x, y)
            val n = fetchN(x, y - 1)
            val s = fetchN(x, y + 1)
            val n2 = fetchN(x, y - 2)
            val s2 = fetchN(x, y + 2)
            val gnN: Float
            val gnS: Float
            if (gate.hEdge) {
                gnN = (fetchN(x - 1, y - 1) + fetchN(x + 1, y - 1)) * 0.5f
                gnS = (fetchN(x - 1, y + 1) + fetchN(x + 1, y + 1)) * 0.5f
            } else {
                gnN = (c + n2) * 0.5f
                gnS = (c + s2) * 0.5f
            }
            val wN = BILATERAL_EPS + abs(c - s2)
            val wS = BILATERAL_EPS + abs(c - n2)
            val rN = levelRatio(c, gnN)
            val rS = levelRatio(c, gnS)
            return c + (wN * (n - gnN) * rN + wS * (s - gnS) * rS) / (wN + wS)
        }

        /**
         * Edge-directed opposite chroma (R at B sites, B at R sites):
         * green-anchored difference-domain like the axis kernels — the
         * interpolated green here plus a level-rescaled bilateral pick
         * of the steered sides' (diagonal-pair - cardinal-green)
         * differences. The cardinal green at each side pair's center is
         * geometrically centered (ramps exact); the green anchor is
         * line-exact, so fence lines resolve instead of averaging away
         * (±0.19 symmetric error). A vertical edge varies along x, so it
         * picks the W/E-side pair; horizontal picks N/S. Same 13 taps.
         *
         * @param gHere edge-directed green at this site ([kernelGreen]).
         * @param gate edge bank from [gateOf] (shared with green).
         */
        private fun kernelOppDir(x: Int, y: Int, gHere: Float, gate: Gate): Float {
            val c = fetchN(x, y)
            val w = fetchN(x - 1, y)
            val e = fetchN(x + 1, y)
            val n = fetchN(x, y - 1)
            val s = fetchN(x, y + 1)
            val w2 = fetchN(x - 2, y)
            val e2 = fetchN(x + 2, y)
            val n2 = fetchN(x, y - 2)
            val s2 = fetchN(x, y + 2)
            val nw = fetchN(x - 1, y - 1)
            val ne = fetchN(x + 1, y - 1)
            val sw = fetchN(x - 1, y + 1)
            val se = fetchN(x + 1, y + 1)
            val dw = (nw + sw) * 0.5f - w
            val de = (ne + se) * 0.5f - e
            val dn = (nw + ne) * 0.5f - n
            val ds = (sw + se) * 0.5f - s
            val dir: Float
            if (gate.hEdge) {
                val wN = BILATERAL_EPS + abs(c - s2)
                val wS = BILATERAL_EPS + abs(c - n2)
                val rN = levelRatio(gHere, n)
                val rS = levelRatio(gHere, s)
                dir = gHere + (wN * dn * rN + wS * ds * rS) / (wN + wS)
            } else {
                val wW = BILATERAL_EPS + abs(c - e2)
                val wE = BILATERAL_EPS + abs(c - w2)
                val rW = levelRatio(gHere, w)
                val rE = levelRatio(gHere, e)
                dir = gHere + (wW * dw * rW + wE * de * rE) / (wW + wE)
            }
            return dir * (1f - gate.g) + kernelOpp(x, y) * gate.g
        }

        fun run(): FloatArray {
            val out = FloatArray(width * height * 3)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val o = (y * width + x) * 3
                    // Clamp negatives like RawTherapee (a camera matrix
                    // turns edge undershoot into complementary fringes);
                    // overshoot stays unclipped like the MHC paper.
                    when (colorAt(x, y)) {
                        0 -> {
                            out[o] = fetchN(x, y)
                            val g = kernelGreen(x, y)
                            out[o + 1] = g.coerceIn(0f, OUTPUT_CLAMP_HI)
                            out[o + 2] = kernelOppDir(x, y, g, gateOf(x, y)).coerceIn(0f, OUTPUT_CLAMP_HI)
                        }
                        2 -> {
                            val g = kernelGreen(x, y)
                            out[o] = kernelOppDir(x, y, g, gateOf(x, y)).coerceIn(0f, OUTPUT_CLAMP_HI)
                            out[o + 1] = g.coerceIn(0f, OUTPUT_CLAMP_HI)
                            out[o + 2] = fetchN(x, y)
                        }
                        else -> {
                            // Bayer phase picks the AXIS (level-correct:
                            // the axis taps hold the estimated channel);
                            // the edge steers WITHIN the axis (sharp).
                            val gate = gateOf(x, y)
                            out[o + 1] = fetchN(x, y)
                            if (colorAt(x + 1, y) == 0) {
                                val dir = kernelChromaEW(x, y, gate)
                                out[o] = (dir * (1f - gate.g) + kernelRow(x, y) * gate.g).coerceIn(0f, OUTPUT_CLAMP_HI)
                                val dirB = kernelChromaNS(x, y, gate)
                                out[o + 2] = (dirB * (1f - gate.g) + kernelCol(x, y) * gate.g).coerceIn(0f, OUTPUT_CLAMP_HI)
                            } else {
                                val dir = kernelChromaNS(x, y, gate)
                                out[o] = (dir * (1f - gate.g) + kernelCol(x, y) * gate.g).coerceIn(0f, OUTPUT_CLAMP_HI)
                                val dirB = kernelChromaEW(x, y, gate)
                                out[o + 2] = (dirB * (1f - gate.g) + kernelRow(x, y) * gate.g).coerceIn(0f, OUTPUT_CLAMP_HI)
                            }
                        }
                    }
                }
            }
            return out
        }
    }
}
