package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.parity.NpyReader
import com.rawlens.srpipeline.parity.toFlow
import com.rawlens.srpipeline.parity.toImage2D
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-path exactness of the module align loop against the recorded CPU
 * flow (`parity/gen_fixtures.py`, 8-frame burst crop fixtures).
 *
 * Provenance: the `gpu align loop matches recorded crop flow` golden pair
 * misses its bound by ~6e-08 (1.15931e-05 vs 1.154e-05) while this
 * device-free leg lands at 8.9e-08 — two orders inside. With the pristine
 * (unsteered) `ica.spv` the GPU leg lands exactly on the port's recorded
 * 1.153349876e-05, so the golden gap is entirely GPU-kernel-side
 * (FINAL-14 Mali steering moving Metal numerics), not host inputs.
 * Pyramid/gradient inputs contribute at most ~1e-07 end to end; this test
 * pins that seat so a future host-side regression cannot hide behind the
 * GPU-leg goldens.
 *
 * Pure Kotlin, no native library: runs in the CI-default suite.
 */
class AlignLoopHostParityTest {

    private fun fixDir() = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))

    private fun cropConfig() = AlignLoopConfig(
        factors = listOf(1, 2, 4, 4),
        tileSizes = listOf(16, 16, 16, 8),
        searchRadii = listOf(1, 4, 4, 4),
        metrics = listOf(BlockMetric.L1, BlockMetric.L2, BlockMetric.L2, BlockMetric.L2),
    )

    private fun maxAbsFlow(a: Array<Array<FloatArray>>, b: Array<Array<FloatArray>>): Float {
        var m = 0f
        for (ty in b.indices) for (tx in b[ty].indices) for (c in 0..1)
            m = maxOf(m, kotlin.math.abs(a[ty][tx][c] - b[ty][tx][c]))
        return m
    }

    private fun copyFlow(f: Array<Array<FloatArray>>): Array<Array<FloatArray>> =
        Array(f.size) { ty -> Array(f[ty].size) { tx -> f[ty][tx].copyOf() } }

    @Test
    fun `module loop matches recorded crop flow within host seat`() {
        val d = fixDir()
        val r = NpyReader
        val ref = r.load(File(d, "align_crop_in_ref_grey.npy")).toImage2D()
        val moving = r.load(File(d, "align_crop_in_mov_grey.npy")).toImage2D()
        val want = r.load(File(d, "align_crop_out_flow.npy")).toFlow()
        val config = cropConfig()

        val init = initAlignment(ref, config)
        val got = alignLevels(init, moving, config)
        val loopGap = maxAbsFlow(got, want)
        println("host loop vs recorded flow maxabs=$loopGap")
        assertTrue("host loop gap too large: $loopGap", loopGap <= 1e-6f)

        val perLevel = alignLevelsPerLevel(init, moving, config)
        for (l in perLevel.indices) {
            val wantLvl = r.load(File(d, "align_crop_out_lvl$l.npy")).toFlow()
            val gap = maxAbsFlow(perLevel[l], wantLvl)
            println("host level $l vs recorded maxabs=$gap")
            assertTrue("host level $l gap too large: $gap", gap <= 1e-6f)
        }
    }

    @Test
    fun `ica refine matches recorded flow within host seat`() {
        val d = fixDir()
        val r = NpyReader
        val ref = r.load(File(d, "ica_in_ref_lvl.npy")).toImage2D()
        val moving = r.load(File(d, "ica_in_mov_lvl.npy")).toImage2D()
        val want = r.load(File(d, "ica_out_flow.npy")).toFlow()
        val hi = Hessian.initIca(ref, 16)
        val flow = copyFlow(r.load(File(d, "ica_in_flow_init.npy")).toFlow())
        icaRefine(ref, hi.gradX, hi.gradY, hi.hessian, moving,
            flow, nIter = 3, clipRadius = 4f, tileSize = 16)
        val gap = maxAbsFlow(flow, want)
        println("host ica-refine vs recorded maxabs=$gap")
        assertTrue("host ica gap too large: $gap", gap <= 1e-6f)
    }

    @Test
    fun `l2 block match is bit-exact vs recorded flow`() {
        val d = fixDir()
        val r = NpyReader
        val ref = r.load(File(d, "l2_in_ref_lvl.npy")).toImage2D()
        val moving = r.load(File(d, "l2_in_moving_lvl.npy")).toImage2D()
        val want = r.load(File(d, "l2_out_flow.npy")).toFlow()
        val flow = copyFlow(r.load(File(d, "l2_in_flow_init.npy")).toFlow())
        l2BlockMatch(ref, moving, flow, tileSize = 16, searchRadius = 4)
        val gap = maxAbsFlow(flow, want)
        println("host l2-match vs recorded maxabs=$gap")
        assertTrue("host l2 gap too large: $gap", gap == 0f)
    }
}
