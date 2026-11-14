// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StabPathSolverTest {
    private val w = StabPathSolver.Weights()

    @Test fun `band cholesky solves the assembled system`() {
        val n = 40
        val a = 1.3
        val b = 0.7
        val chol = StabPathSolver.BandCholesky.factor(n, a, b)
        val x = DoubleArray(n) { sin(it * 0.37) + it * 0.01 }
        // A x = a·x + b·Σ DᵀD x
        val ax = DoubleArray(n) { a * x[it] }
        val d = DoubleArray(n)
        for (order in 1..3) {
            StabPathSolver.diff(x, order, d)
            val c = when (order) {
                1 -> doubleArrayOf(-1.0, 1.0)
                2 -> doubleArrayOf(1.0, -2.0, 1.0)
                else -> doubleArrayOf(-1.0, 3.0, -3.0, 1.0)
            }
            for (i in 0 until n - order) for (j in c.indices) ax[i + j] += b * c[j] * d[i]
        }
        chol.solveInPlace(ax)
        for (i in 0 until n) assertEquals(x[i], ax[i], 1e-9)
    }

    @Test fun `zero box returns the raw path`() {
        val p = DoubleArray(50) { sin(it * 0.5) }
        val r = StabPathSolver.solve(p, DoubleArray(50), DoubleArray(50), w, 1.0)
        for (i in p.indices) assertEquals(p[i], r.path[i], 1e-12)
    }

    @Test fun `shake inside the box becomes a still path`() {
        val n = 120
        val p = DoubleArray(n) { 0.004 * sin(it * 1.7) + 0.002 * sin(it * 0.9) }
        val box = DoubleArray(n) { 0.01 }
        val r = StabPathSolver.solve(p, DoubleArray(n) { -box[it] }, box, w, 0.01)
        // The shake fills 60 % of the box, so λ lets a slow drift through
        // (~3 % of the span), but frame-to-frame jitter is gone.
        val rawSpan = p.max() - p.min()
        val outSpan = r.path.max() - r.path.min()
        assertTrue("span $outSpan vs $rawSpan", outSpan < 0.05 * rawSpan)
        var rawVel = 0.0
        var outVel = 0.0
        for (i in 0 until n - 1) {
            rawVel = maxOf(rawVel, abs(p[i + 1] - p[i]))
            outVel = maxOf(outVel, abs(r.path[i + 1] - r.path[i]))
        }
        assertTrue("velocity $outVel vs $rawVel", outVel < 0.01 * rawVel)
        assertTrue(StabPathSolver.objective(r.path, p, w) < StabPathSolver.objective(p, p, w))
    }

    @Test fun `pan plus shake becomes a constant-velocity pan, always feasible`() {
        val n = 150
        val p = DoubleArray(n) { 0.01 * it + 0.003 * sin(it * 2.1) }
        val box = 0.006
        val r = StabPathSolver.solve(p, DoubleArray(n) { -box }, DoubleArray(n) { box }, w, box)
        for (i in 0 until n) assertTrue(abs(r.path[i] - p[i]) <= box + 1e-9)
        // Interior velocity stays at the pan rate (shake velocity ~0.006/frame).
        for (i in 10 until n - 12) assertEquals(0.01, r.path[i + 1] - r.path[i], 1e-4)
    }

    @Test fun `big moves ease in before the box runs out`() {
        // Still, then a 0.1 rad step over 10 frames, then still.
        val n = 160
        val p = DoubleArray(n) { i -> 0.1 * ((i - 70).coerceIn(0, 10) / 10.0) }
        val box = 0.02
        val r = StabPathSolver.solve(p, DoubleArray(n) { -box }, DoubleArray(n) { box }, w, box)
        // Starts moving before the raw camera does (look-ahead) ...
        assertTrue(r.path[69] - r.path[0] > 1e-4)
        // ... and its peak jerk is far below the raw step's.
        var jerkRaw = 0.0
        var jerkOut = 0.0
        val d = DoubleArray(n)
        StabPathSolver.diff(p, 3, d); for (v in d) jerkRaw = maxOf(jerkRaw, abs(v))
        StabPathSolver.diff(r.path, 3, d); for (v in d) jerkOut = maxOf(jerkOut, abs(v))
        assertTrue("jerk $jerkOut vs $jerkRaw", jerkOut < jerkRaw * 0.25)
    }
}
