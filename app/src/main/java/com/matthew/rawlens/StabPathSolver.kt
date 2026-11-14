// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whole-take L1 camera-path optimizer for one rotation axis, after Grundmann,
 * Kwatra & Essa, "Auto-Directed Video Stabilization with Robust L1 Optimal
 * Camera Paths" (CVPR 2011), applied to gyro rotation instead of 2-D motion.
 *
 *   minimize   w1·‖D1 s‖₁ + w2·‖D2 s‖₁ + w3·‖D3 s‖₁ + (λ/2)·‖s − p‖²
 *   subject to lo_i ≤ s_i − p_i ≤ hi_i
 *
 * p is the raw camera path, s the virtual path, and [lo, hi] the per-frame
 * correction room the crop allows. The L1 derivative terms produce what a
 * camera operator would do: still segments (D1 = 0), constant-velocity pans
 * (D2 = 0), and eased transitions (D3 small), with corrections starting as
 * early as the future needs them. This replaces VidUltra's causal
 * alpha-easing and slew heuristics.
 *
 * Weights differ from Grundmann's (10, 1, 100, λ≈0): there the velocity term
 * rewards a shorter total path, so the optimum "saves" travel by spending
 * the whole margin — pans start late, end early and change speed (measured:
 * up to 50 % rate wobble on a steady pan). A small w1 plus a λ pull toward
 * the raw camera (in normalised box units, so λ=1 costs ½ per frame at the
 * box edge) keeps pans within 0.5 % of constant velocity while still shots
 * stay locked (see StabPathSolverTest / StabPlannerTest).
 *
 * Solved with ADMM (splits z_k = D_k s, u = s ∈ box). The s-update matrix
 * (λ+ρ)I + ρ·Σ w-independent D_kᵀD_k is constant and banded (half-bandwidth 3),
 * so it is factored once with a banded Cholesky. Cost per iteration is O(n).
 *
 * Pure JVM, deterministic.
 */
internal object StabPathSolver {
    data class Weights(
        val w1: Double = 0.1,
        val w2: Double = 1.0,
        val w3: Double = 100.0,
        val lambda: Double = 1.0,
    )

    /**
     * Weights are tuned per frame at 30 fps. Σ|D_k s| scales as fps^(1−k) and
     * the λ sum as fps, so w_k·(fps/30)^k keeps the balance fps-independent.
     */
    fun weightsForFps(fps: Int, base: Weights = Weights()): Weights {
        val r = fps.coerceAtLeast(1) / 30.0
        return base.copy(w1 = base.w1 * r, w2 = base.w2 * r * r, w3 = base.w3 * r * r * r)
    }

    /** ADMM state; pass back as `warm` to resume (e.g. after tightening the box). */
    class State internal constructor(
        internal val x: DoubleArray,
        internal val z: Array<DoubleArray>,
        internal val y: Array<DoubleArray>,
        internal val u: DoubleArray,
        internal val w: DoubleArray,
        internal var rho: Double,
    )

    class Result(val path: DoubleArray, val iterations: Int, val converged: Boolean, val state: State)

    /**
     * @param p raw path (n), any units; it is internally normalised by [scale].
     * @param lo,hi per-frame correction bounds (lo ≤ 0 ≤ hi expected).
     * @param scale typical magnitude of the bounds (e.g. their median), for conditioning.
     * @param warm state from a previous solve of the same p/weights/scale
     *   (bounds may differ): typically converges in a fraction of the iterations.
     *
     * Speed-ups over textbook ADMM: over-relaxation (α = 1.6) and residual
     * balancing of ρ every [RHO_PERIOD] iterations (Boyd et al. §3.4.1),
     * which refactors the banded matrix in O(n).
     */
    fun solve(
        p: DoubleArray,
        lo: DoubleArray,
        hi: DoubleArray,
        weights: Weights,
        scale: Double,
        maxIterations: Int = 3000,
        tolerance: Double = 1e-4,
        rho0: Double = 1.0,
        warm: State? = null,
        /** Per-frame multiplier on λ (≥ 0), e.g. to follow the real camera in blurred frames. */
        lambdaScale: DoubleArray? = null,
    ): Result {
        val n = p.size
        require(lo.size == n && hi.size == n) { "bounds size" }
        if (n < 4) {
            val path = DoubleArray(n) { p[it] + clampToBox(0.0, lo[it], hi[it]) }
            return Result(path, 0, true, State(path.copyOf(), Array(3) { DoubleArray(n) }, Array(3) { DoubleArray(n) }, path.copyOf(), DoubleArray(n), rho0))
        }
        val k = if (scale.isFinite() && scale > 0) 1.0 / scale else 1.0
        val q = DoubleArray(n) { p[it] * k }
        val bl = DoubleArray(n) { q[it] + lo[it] * k }
        val bh = DoubleArray(n) { q[it] + hi[it] * k }
        val lamI = DoubleArray(n) { weights.lambda * (lambdaScale?.getOrNull(it)?.coerceAtLeast(0.0) ?: 1.0) }
        val ws = doubleArrayOf(weights.w1, weights.w2, weights.w3)

        val st = if (warm != null && warm.x.size == n) warm else {
            val x0 = DoubleArray(n) { clampToBox(q[it], bl[it], bh[it]) }
            val z0 = Array(3) { DoubleArray(n) }
            for (o in 0 until 3) diff(x0, o + 1, z0[o])
            State(x0, z0, Array(3) { DoubleArray(n) }, x0.copyOf(), DoubleArray(n), rho0)
        }
        val x = st.x
        val z = st.z
        val y = st.y
        val u = st.u
        val w = st.w
        var rho = st.rho
        var chol = BandCholesky.factor(n, DoubleArray(n) { lamI[it] + rho }, rho)
        val rhs = DoubleArray(n)
        val tmp = DoubleArray(n)
        val dx = DoubleArray(n)
        var it = 0
        var converged = false
        while (it < maxIterations) {
            it++
            for (i in 0 until n) rhs[i] = lamI[i] * q[i] + rho * (u[i] - w[i])
            for (o in 0 until 3) {
                for (i in 0 until n) tmp[i] = z[o][i] - y[o][i]
                addDiffTranspose(tmp, o + 1, rho, rhs)
            }
            chol.solveInPlace(rhs)
            System.arraycopy(rhs, 0, x, 0, n)
            var primal = 0.0
            var dual = 0.0
            var norm = 0.0
            for (o in 0 until 3) {
                diff(x, o + 1, dx)
                val t = ws[o] / rho
                val zo = z[o]
                val yo = y[o]
                for (i in 0 until n - o - 1) {
                    val r = dx[i] - zo[i]
                    primal += r * r
                    val xh = ALPHA * dx[i] + (1 - ALPHA) * zo[i]
                    val v = xh + yo[i]
                    val nz = if (v > t) v - t else if (v < -t) v + t else 0.0
                    dual += (nz - zo[i]) * (nz - zo[i])
                    zo[i] = nz
                    yo[i] += xh - nz
                }
            }
            for (i in 0 until n) {
                val r = x[i] - u[i]
                primal += r * r
                val xh = ALPHA * x[i] + (1 - ALPHA) * u[i]
                val nu = clampToBox(xh + w[i], bl[i], bh[i])
                dual += (nu - u[i]) * (nu - u[i])
                u[i] = nu
                w[i] += xh - nu
                norm += x[i] * x[i]
            }
            val ref = max(1.0, sqrt(norm))
            val rp = sqrt(primal)
            val rd = rho * sqrt(dual)
            if (rp < tolerance * ref && rd < tolerance * ref * rho) {
                converged = true
                break
            }
            if (it % RHO_PERIOD == 0) {
                val f = if (rp > RHO_MU * rd) 2.0 else if (rd > RHO_MU * rp) 0.5 else 1.0
                if (f != 1.0 && rho * f in RHO_MIN..RHO_MAX) {
                    rho *= f
                    // scaled duals: y = Λ/ρ
                    for (o in 0 until 3) for (i in 0 until n) y[o][i] /= f
                    for (i in 0 until n) w[i] /= f
                    chol = BandCholesky.factor(n, DoubleArray(n) { lamI[it] + rho }, rho)
                }
            }
        }
        st.rho = rho
        return Result(DoubleArray(n) { u[it] / k }, it, converged, st)
    }

    private const val ALPHA = 1.6
    private const val RHO_PERIOD = 25
    private const val RHO_MU = 10.0
    private const val RHO_MIN = 1e-3
    private const val RHO_MAX = 1e4

    /** L1 objective of [s] (for tests / diagnostics). */
    fun objective(s: DoubleArray, p: DoubleArray, weights: Weights): Double {
        val n = s.size
        var acc = 0.0
        val d = DoubleArray(n)
        val ws = doubleArrayOf(weights.w1, weights.w2, weights.w3)
        for (o in 0 until 3) {
            diff(s, o + 1, d)
            for (i in 0 until n - o - 1) acc += ws[o] * abs(d[i])
        }
        for (i in 0 until n) acc += 0.5 * weights.lambda * (s[i] - p[i]) * (s[i] - p[i])
        return acc
    }

    private fun clampToBox(v: Double, lo: Double, hi: Double): Double = if (lo > hi) 0.5 * (lo + hi) else min(hi, max(lo, v))

    /** Forward difference of [order] 1..3; out[i] for i < n−order, rest 0. */
    internal fun diff(x: DoubleArray, order: Int, out: DoubleArray) {
        val n = x.size
        for (i in 0 until n) {
            out[i] = if (i >= n - order) 0.0 else when (order) {
                1 -> x[i + 1] - x[i]
                2 -> x[i + 2] - 2 * x[i + 1] + x[i]
                else -> x[i + 3] - 3 * x[i + 2] + 3 * x[i + 1] - x[i]
            }
        }
    }

    /** out += scale·Dᵀ v (v valid on 0 until n−order). */
    private fun addDiffTranspose(v: DoubleArray, order: Int, scale: Double, out: DoubleArray) {
        val n = v.size
        val c = when (order) {
            1 -> doubleArrayOf(-1.0, 1.0)
            2 -> doubleArrayOf(1.0, -2.0, 1.0)
            else -> doubleArrayOf(-1.0, 3.0, -3.0, 1.0)
        }
        for (i in 0 until n - order) {
            val s = scale * v[i]
            for (j in c.indices) out[i + j] += c[j] * s
        }
    }

    /**
     * Banded Cholesky of A = a·I + b·(D1ᵀD1 + D2ᵀD2 + D3ᵀD3), half-bandwidth 3.
     * Built by assembling the band from the difference stencils, so the
     * truncated rows at the path ends are exact.
     */
    internal class BandCholesky private constructor(private val n: Int, private val l: Array<DoubleArray>) {
        fun solveInPlace(b: DoubleArray) {
            // L y = b
            for (i in 0 until n) {
                var s = b[i]
                for (j in 1..BW) if (i - j >= 0) s -= l[j][i - j] * b[i - j]
                b[i] = s / l[0][i]
            }
            // Lᵀ x = y
            for (i in n - 1 downTo 0) {
                var s = b[i]
                for (j in 1..BW) if (i + j < n) s -= l[j][i] * b[i + j]
                b[i] = s / l[0][i]
            }
        }

        companion object {
            private const val BW = 3

            fun factor(n: Int, a: Double, b: Double): BandCholesky = factor(n, DoubleArray(n) { a }, b)

            /** A = diag(a) + b·ΣDᵀD (per-frame diagonal). */
            fun factor(n: Int, a: DoubleArray, b: Double): BandCholesky {
                // band[j][i] = A[i+j][i]
                val band = Array(BW + 1) { DoubleArray(n) }
                for (i in 0 until n) band[0][i] = a[i]
                val stencils = arrayOf(
                    doubleArrayOf(-1.0, 1.0),
                    doubleArrayOf(1.0, -2.0, 1.0),
                    doubleArrayOf(-1.0, 3.0, -3.0, 1.0),
                )
                for (c in stencils) {
                    for (r in 0 until n - c.size + 1) {
                        for (p in c.indices) for (q in 0..p) {
                            band[p - q][r + q] += b * c[p] * c[q]
                        }
                    }
                }
                // In-place banded Cholesky: l[j][i] = L[i+j][i].
                val l = band
                for (i in 0 until n) {
                    var d = l[0][i]
                    for (k in 1..BW) if (i - k >= 0) d -= l[k][i - k] * l[k][i - k]
                    require(d > 0) { "not SPD" }
                    val di = sqrt(d)
                    l[0][i] = di
                    for (j in 1..BW) {
                        if (i + j >= n) break
                        var s = l[j][i]
                        for (k in 1..BW - j) if (i - k >= 0) s -= l[j + k][i - k] * l[k][i - k]
                        l[j][i] = s / di
                    }
                }
                return BandCholesky(n, l)
            }
        }
    }
}
