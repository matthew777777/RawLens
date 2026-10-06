// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max

/**
 * CPU reference for the [VfRcd] GPU port (RawTherapee RCD v2.3): same
 * stages, same formulas, same clamp-first edges — full-res coordinates,
 * normalized 0..1 (RT's x65536 scale folded out, as on GPU).
 *
 * Pure Kotlin (no Android): JVM-testable ([RcdReferenceTest]) and reused
 * by the on-device parity probe, which gates mean abs diff against the
 * GPU readback (FP16 store quantization dominates the gap).
 */
object RcdReference {
    const val EPS = 1e-5f
    const val EPSSQ = 1e-10f

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

    private class Engine(
        private val codes: IntArray,
        private val sensorWidth: Int, private val sensorHeight: Int, private val pitch: Int,
        private val left: Int, private val top: Int, private val width: Int, private val height: Int,
        private val channels: IntArray,
        levels: FloatArray,
        white: Float,
    ) {
        private val invRange = FloatArray(4) { i -> 1f / max(1f, white - levels[i]) }
        private val black = levels.copyOf()
        // rgb planes over the crop (mode 0 writes G + mosaic, 1/2 refine).
        private val r = FloatArray(width * height)
        private val g = FloatArray(width * height)
        private val b = FloatArray(width * height)

        // Phase-preserving edge mirror (NOT clamp: clamping flips CFA
        // phase at borders; single reflection covers +-4 on dims >= 8).
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

        /**
         * CFA color 0=R 1=G 2=B (unclamped phase, as on GPU). Canonical 1
         * and 2 are both green (Gr/Gb); the remainder is blue.
         */
        private fun colorAt(x: Int, y: Int): Int {
            val q = quadOf(x, y)
            if (channels[0] == q) return 0
            if (channels[1] == q || channels[2] == q) return 1
            return 2
        }

        private fun intp(d: Float, h: Float, v: Float): Float = d * (h - v) + v

        private fun hpfV(x: Int, y: Int): Float {
            val v = (fetchN(x, y - 3) - fetchN(x, y - 1) - fetchN(x, y + 1) + fetchN(x, y + 3)) -
                3f * (fetchN(x, y - 2) + fetchN(x, y + 2)) + 6f * fetchN(x, y)
            return v * v
        }

        private fun hpfH(x: Int, y: Int): Float {
            val v = (fetchN(x - 3, y) - fetchN(x - 1, y) - fetchN(x + 1, y) + fetchN(x + 3, y)) -
                3f * (fetchN(x - 2, y) + fetchN(x + 2, y)) + 6f * fetchN(x, y)
            return v * v
        }

        private fun vhDir(x: Int, y: Int): Float {
            val vStat = max(EPSSQ, hpfV(x, y - 1) + hpfV(x, y) + hpfV(x, y + 1))
            val hStat = max(EPSSQ, hpfH(x - 1, y) + hpfH(x, y) + hpfH(x + 1, y))
            return vStat / (vStat + hStat)
        }

        private fun lpf(x: Int, y: Int): Float =
            fetchN(x, y) +
                0.5f * (fetchN(x, y - 1) + fetchN(x, y + 1) + fetchN(x - 1, y) + fetchN(x + 1, y)) +
                0.25f * (fetchN(x - 1, y - 1) + fetchN(x + 1, y - 1) +
                    fetchN(x - 1, y + 1) + fetchN(x + 1, y + 1))

        private fun hpfP(x: Int, y: Int): Float {
            val v = (fetchN(x - 3, y - 3) - fetchN(x - 1, y - 1) -
                fetchN(x + 1, y + 1) + fetchN(x + 3, y + 3)) -
                3f * (fetchN(x - 2, y - 2) + fetchN(x + 2, y + 2)) + 6f * fetchN(x, y)
            return v * v
        }

        private fun hpfQ(x: Int, y: Int): Float {
            val v = (fetchN(x + 3, y - 3) - fetchN(x + 1, y - 1) -
                fetchN(x - 1, y + 1) + fetchN(x - 3, y + 3)) -
                3f * (fetchN(x + 2, y - 2) + fetchN(x - 2, y + 2)) + 6f * fetchN(x, y)
            return v * v
        }

        private fun pqDir(x: Int, y: Int): Float {
            val pStat = max(EPSSQ, hpfP(x - 1, y - 1) + hpfP(x, y) + hpfP(x + 1, y + 1))
            val qStat = max(EPSSQ, hpfQ(x + 1, y - 1) + hpfQ(x, y) + hpfQ(x - 1, y + 1))
            return pStat / (pStat + qStat)
        }

        private fun refineVH(x: Int, y: Int, central: Float): Float {
            val nb = 0.25f * (vhDir(x - 1, y - 1) + vhDir(x + 1, y - 1) +
                vhDir(x - 1, y + 1) + vhDir(x + 1, y + 1))
            return if (abs(0.5f - central) < abs(0.5f - nb)) nb else central
        }

        private fun refinePQ(x: Int, y: Int, central: Float): Float {
            val nb = 0.25f * (pqDir(x - 1, y - 1) + pqDir(x + 1, y - 1) +
                pqDir(x - 1, y + 1) + pqDir(x + 1, y + 1))
            return if (abs(0.5f - central) < abs(0.5f - nb)) nb else central
        }

        private fun greenAtRB(x: Int, y: Int): Float {
            val cfai = fetchN(x, y)
            val nGrad = EPS + (abs(fetchN(x, y - 1) - fetchN(x, y + 1)) + abs(cfai - fetchN(x, y - 2))) +
                (abs(fetchN(x, y - 1) - fetchN(x, y - 3)) + abs(fetchN(x, y - 2) - fetchN(x, y - 4)))
            val sGrad = EPS + (abs(fetchN(x, y - 1) - fetchN(x, y + 1)) + abs(cfai - fetchN(x, y + 2))) +
                (abs(fetchN(x, y + 1) - fetchN(x, y + 3)) + abs(fetchN(x, y + 2) - fetchN(x, y + 4)))
            val wGrad = EPS + (abs(fetchN(x - 1, y) - fetchN(x + 1, y)) + abs(cfai - fetchN(x - 2, y))) +
                (abs(fetchN(x - 1, y) - fetchN(x - 3, y)) + abs(fetchN(x - 2, y) - fetchN(x - 4, y)))
            val eGrad = EPS + (abs(fetchN(x - 1, y) - fetchN(x + 1, y)) + abs(cfai - fetchN(x + 2, y))) +
                (abs(fetchN(x + 1, y) - fetchN(x + 3, y)) + abs(fetchN(x + 2, y) - fetchN(x + 4, y)))
            val lpfi = lpf(x, y)
            val nEst = fetchN(x, y - 1) * (lpfi + lpfi) / (EPS + lpfi + lpf(x, y - 2))
            val sEst = fetchN(x, y + 1) * (lpfi + lpfi) / (EPS + lpfi + lpf(x, y + 2))
            val wEst = fetchN(x - 1, y) * (lpfi + lpfi) / (EPS + lpfi + lpf(x - 2, y))
            val eEst = fetchN(x + 1, y) * (lpfi + lpfi) / (EPS + lpfi + lpf(x + 2, y))
            val vEst = (sGrad * nEst + nGrad * sEst) / (nGrad + sGrad)
            val hEst = (wGrad * eEst + eGrad * wEst) / (eGrad + wGrad)
            return intp(refineVH(x, y, vhDir(x, y)), hEst, vEst)
        }

        private fun gAt(x: Int, y: Int): Float = g[mirrorY(y) * width + mirrorX(x)]

        fun run(): FloatArray {
            // Mode 0: green full-res + R/B mosaic init.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    when (colorAt(x, y)) {
                        0 -> {
                            r[i] = fetchN(x, y)
                            g[i] = greenAtRB(x, y)
                        }
                        1 -> g[i] = fetchN(x, y)
                        else -> {
                            b[i] = fetchN(x, y)
                            g[i] = greenAtRB(x, y)
                        }
                    }
                }
            }
            // Mode 1: R/B at opposite colors.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val c = colorAt(x, y)
                    if (c == 1) continue
                    val i = y * width + x
                    val gHere = g[i]
                    val pqDisc = refinePQ(x, y, pqDir(x, y))
                    val nw = EPS + abs(fetchN(x - 1, y - 1) - fetchN(x + 1, y + 1)) +
                        abs(fetchN(x - 1, y - 1) - fetchN(x - 3, y - 3)) +
                        abs(gHere - gAt(x - 2, y - 2))
                    val ne = EPS + abs(fetchN(x + 1, y - 1) - fetchN(x - 1, y + 1)) +
                        abs(fetchN(x + 1, y - 1) - fetchN(x + 3, y - 3)) +
                        abs(gHere - gAt(x + 2, y - 2))
                    val sw = EPS + abs(fetchN(x + 1, y - 1) - fetchN(x - 1, y + 1)) +
                        abs(fetchN(x - 1, y + 1) - fetchN(x - 3, y + 3)) +
                        abs(gHere - gAt(x - 2, y + 2))
                    val se = EPS + abs(fetchN(x - 1, y - 1) - fetchN(x + 1, y + 1)) +
                        abs(fetchN(x + 1, y + 1) - fetchN(x + 3, y + 3)) +
                        abs(gHere - gAt(x + 2, y + 2))
                    val nwE = fetchN(x - 1, y - 1) - gAt(x - 1, y - 1)
                    val neE = fetchN(x + 1, y - 1) - gAt(x + 1, y - 1)
                    val swE = fetchN(x - 1, y + 1) - gAt(x - 1, y + 1)
                    val seE = fetchN(x + 1, y + 1) - gAt(x + 1, y + 1)
                    val pEst = (nw * seE + se * nwE) / (nw + se)
                    val qEst = (ne * swE + sw * neE) / (ne + sw)
                    val v = gHere + intp(pqDisc, qEst, pEst)
                    if (c == 0) b[i] = v else r[i] = v
                }
            }
            // Mode 2: R/B at green sites.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (colorAt(x, y) != 1) continue
                    val i = y * width + x
                    val vhDisc = refineVH(x, y, vhDir(x, y))
                    val rgb1 = g[i]
                    val n1 = EPS + abs(rgb1 - gAt(x, y - 2))
                    val s1 = EPS + abs(rgb1 - gAt(x, y + 2))
                    val w1 = EPS + abs(rgb1 - gAt(x - 2, y))
                    val e1 = EPS + abs(rgb1 - gAt(x + 2, y))
                    val gN = gAt(x, y - 1)
                    val gS = gAt(x, y + 1)
                    val gW = gAt(x - 1, y)
                    val gE = gAt(x + 1, y)
                    for (cc in intArrayOf(0, 2)) {
                        val cN = chanAt(x, y - 1, cc)
                        val cS = chanAt(x, y + 1, cc)
                        val cW = chanAt(x - 1, y, cc)
                        val cE = chanAt(x + 1, y, cc)
                        val cN3 = chanAt(x, y - 3, cc)
                        val cS3 = chanAt(x, y + 3, cc)
                        val cW3 = chanAt(x - 3, y, cc)
                        val cE3 = chanAt(x + 3, y, cc)
                        val sn = abs(cN - cS)
                        val ew = abs(cW - cE)
                        val nG = n1 + sn + abs(cN - cN3)
                        val sG = s1 + sn + abs(cS - cS3)
                        val wG = w1 + ew + abs(cW - cW3)
                        val eG = e1 + ew + abs(cE - cE3)
                        val vEst = (nG * (cS - gS) + sG * (cN - gN)) / (nG + sG)
                        val hEst = (eG * (cW - gW) + wG * (cE - gE)) / (eG + wG)
                        val v = rgb1 + intp(vhDisc, hEst, vEst)
                        if (cc == 0) r[i] = v else b[i] = v
                    }
                }
            }
            val out = FloatArray(width * height * 3)
            for (i in r.indices) {
                out[i * 3] = r[i]
                out[i * 3 + 1] = g[i]
                out[i * 3 + 2] = b[i]
            }
            return out
        }

        /**
         * Mode-2 channel read: mosaic value from CFA, interpolated from
         * the planes (same mosaic-vs-image selection as the shader; the
         * decision uses unclamped phase, values clamp).
         */
        private fun chanAt(x: Int, y: Int, c: Int): Float {
            if (colorAt(x, y) == c) return fetchN(x, y)
            val i = mirrorY(y) * width + mirrorX(x)
            return if (c == 0) r[i] else b[i]
        }
    }
}
