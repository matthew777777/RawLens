package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Smap] (robustness.py `compute_s` / `cpu_compute_s` contract).
 *
 * Goldens cross-checked with the venv reference python (`-B`, read-only,
 * real `compute_s` with `M_th = 0.8`, `s1 = 2`, `s2 = 12`):
 * ```
 * 5x5 zero flow, centre spike (5,-5) -> 3x3 core s1=2, border s2=12
 * 3x3 near-constant flow             -> all s2=12
 * ```
 */
class SmapTest {

    @Test
    fun `centre spike selects s1 in its neighbourhood`() {
        val flow = Array(5) { Array(5) { floatArrayOf(0f, 0f) } }
        flow[2][2] = floatArrayOf(5f, -5f)
        val s = Smap.compute(flow)
        for (y in 0 until 5) {
            for (x in 0 until 5) {
                val expected = if (y in 1..3 && x in 1..3) 2f else 12f
                assertEquals("S[$y,$x]", expected, s[y][x], 0f)
            }
        }
    }

    @Test
    fun `smooth flow selects s2 everywhere`() {
        val flow = Array(3) { Array(3) { floatArrayOf(0.1f, -0.1f) } }
        flow[0][0] = floatArrayOf(0.1f, 0.1f)
        flow[2][2] = floatArrayOf(0.2f, -0.1f)
        val s = Smap.compute(flow)
        for (row in s) for (v in row) assertEquals(12f, v, 0f)
    }

    @Test
    fun `defaults match explicit robustness config values`() {
        val flow = Array(3) { Array(3) { floatArrayOf(0f, 0f) } }
        val a = Smap.compute(flow)
        val b = Smap.compute(flow, 0.8f, 2f, 12f)
        for (y in a.indices) for (x in a[y].indices) assertEquals(b[y][x], a[y][x], 0f)
    }
}
