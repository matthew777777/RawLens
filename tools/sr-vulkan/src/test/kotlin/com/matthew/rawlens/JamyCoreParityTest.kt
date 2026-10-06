// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jamy-L core parity proof: the RawLens CPU chain (tuning estimator, guide
 * stats, kernels, robustness, linear merge) must reproduce the independently
 * transcribed reference stages in `tools/jamy_parity_oracle.py` on the same
 * synthetic burst, to float-noise levels.
 *
 * Fixture: 64x48 RGGB burst (ref + 2 shifted frames, analytic texture,
 * per-phase noise, per-phase blacks). Alignment is bypassed with forced
 * flows, so this pins tuning/robustness/kernel/merge math only; alignment
 * architecture is a documented IPOL-paper deviation, not a port.
 *
 * One sampling deviation is intentional: warp/merge flow lookup is
 * bilinear-everywhere (Sabre-style dense warp), not the reference
 * tile snap, so the warp is C0-continuous and no tile tears can form.
 * Uniform-flow stages still compare on the full field (bilinear ==
 * snap there); the step-flow robustness stage compares off-band only,
 * with the step band pinned separately below. Merge robustness fetch
 * stays reference-verbatim nearest. Reference-first vs reference-last
 * merge order accounts for 1-ulp summation differences.
 */
class JamyCoreParityTest {
    private val rawW = 128
    private val rawH = 96
    private val quadsW = 64
    private val quadsH = 48
    private val blackLevels = listOf(64f, 65f, 67f, 66f) // sensor row-major [R,Gr,Gb,B]
    private val white = 4000f
    private val profile = ImmutableDoubleValues(doubleArrayOf(0.018, 0.0016, 0.011, 0.0011, 0.024, 0.0021))
    private val tuning = RawSrTuning.forSnr(18.0)
    private val config = RawSrAlignmentConfig()

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("jamyparity/$name")
            ?: error("missing golden $name")
        return stream.readBytes()
    }

    private fun readU16(name: String, count: Int): IntArray {
        val bytes = resource(name)
        assertEquals(count * 2, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count) { buf.short.toInt() and 0xffff }
    }

    private fun readF32(name: String, count: Int): FloatArray {
        val bytes = resource(name)
        assertEquals(count * 4, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(count)
        buf.get(out)
        return out
    }

    private fun packedFrame(index: Int): RawSrPackedFrame {
        val codes = readU16("codes_f$index.u16", rawW * rawH)
        val plane = ByteBuffer.allocateDirect(rawW * rawH * 2).order(ByteOrder.nativeOrder())
        for (c in codes) plane.putShort(c.toShort())
        plane.flip()
        return RawSrPackedFrame(
            plane, RawPlaneLayout(rawW, rawH, rawW * 2, 2, 0, 0),
            RawCrop(0, 0, rawW, rawH),
            RawNormalization(BayerPattern.RGGB, blackLevels, white),
            null, profile
        )
    }

    /** Forced flow field from raw-unit (dx, dy) with a per-tile selector. */
    private fun forcedFlow(sel: (tx: Int, ty: Int) -> Pair<Double, Double>): RawSrAlignmentField {
        // Raw lattice (Jamy-L convention): the oracle flows pass through
        // unscaled over 16px tiles (same 8x6 physical tiles as before).
        val tileSize = 16
        val cols = rawW / tileSize
        val rows = rawH / tileSize
        val tiles = List(cols * rows) { i ->
            val tx = i % cols
            val ty = i / cols
            val (dxRaw, dyRaw) = sel(tx, ty)
            RawSrTileFlow(0f, 0f, dxRaw.toFloat(), dyRaw.toFloat(), 0f, true)
        }
        return RawSrAlignmentField(rawW, rawH, tileSize, cols, rows, tiles)
    }

    private fun mergeSamples(packed: RawSrPackedFrame): FloatArray {
        // Plain unpack: Jamy-L parity bypasses the hot-pixel stage, so the
        // chain consumes unaltered samples (HotPixelChainParityTest pins it).
        val input = packed.uploadInput()
        val cfa = RawSensorUnpacker.unpackNormalized(input.buffer, input.layout, input.normalization, input.crop)
        return cfa.values
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var m = 0.0
        for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i] - b[i]).toDouble())
        return m
    }

    @Test fun snrEstimatorMatchesOracleBitwise() {
        // Oracle manifest: linear=5.566559371639228 dB=14.911736910280215.
        val estimate = RawSrTuning.fromReference(packedFrame(0))
        assertEquals(5.566559371639228, estimate.linearSnr!!, 0.0)
        assertEquals(14.911736910280215, estimate.tuning.snr, 1e-12)
        assertEquals(RawSrTuning.Status.ESTIMATED, estimate.status)
    }

    @Test fun guideStatsMatchOracle() {
        val refStats = RawSrRobustness.referenceStatsFromPacked(packedFrame(0))
        val meanGold = readF32("A_stats_mean_ref.f32", quadsW * quadsH * 3)
        val varGold = readF32("A_stats_var_ref.f32", quadsW * quadsH * 3)
        val meanGot = FloatArray(quadsW * quadsH * 3)
        val varGot = FloatArray(quadsW * quadsH * 3)
        for (o in 0 until quadsW * quadsH) for (c in 0..2) {
            meanGot[o * 3 + c] = refStats.mean[c][o]
            varGot[o * 3 + c] = refStats.variance[c][o]
        }
        val dMean = maxAbs(meanGold, meanGot)
        val dVar = maxAbs(varGold, varGot)
        println("stats: maxAbsMean=$dMean maxAbsVar=$dVar")
        assertTrue("mean diff $dMean", dMean < 1e-6)
        assertTrue("var diff $dVar", dVar < 1e-6)
        for (i in 1..2) {
            val mov = RawSrRobustness.movingStatsFromPacked(packedFrame(i))
            val movGold = readF32("A_movstats_mean_f$i.f32", quadsW * quadsH * 3)
            val movGot = FloatArray(quadsW * quadsH * 3)
            for (o in 0 until quadsW * quadsH) for (c in 0..2) movGot[o * 3 + c] = mov.mean[c][o]
            val d = maxAbs(movGold, movGot)
            println("movstats f$i: maxAbs=$d")
            assertTrue("movstats f$i diff $d", d < 1e-6)
        }
    }

    @Test fun kernelsMatchOracle() {
        for (i in 0..2) {
            val guide = RawSrCovarianceGuide.guide(packedFrame(i))
            val cov = RawSrKernelCovariance.covariance(guide.gray, tuning)
            val gold = readF32("A_cov_f$i.f32", quadsW * quadsH * 4)
            val d = maxAbs(gold, cov.values)
            println("cov f$i: maxAbs=$d status=${guide.status}")
            // Double-precision GAT/guide vs the oracle's float32, amplified
            // by ill-conditioned eigenvectors at near-isotropic quads (8% of
            // this fixture): the oracle's own f64-vs-f32 guide self-diff is
            // 8.6e-5, the same class. Float noise; formula errors would
            // exceed this bound 50x over. RGB quotients self-correct
            // (see the 1e-6 merge bound below).
            assertTrue("cov f$i diff $d", d < 2e-4)
        }
    }

    @Test fun robustnessConstantFlowMatchesOracle() {
        val shifts = listOf(1.7 to -0.6, -0.9 to 2.3)
        val refStats = RawSrRobustness.referenceStatsFromPacked(packedFrame(0))
        for (i in 1..2) {
            val (dx, dy) = shifts[i - 1]
            val mov = RawSrRobustness.movingStatsFromPacked(packedFrame(i))
            val flow = forcedFlow { _, _ -> dx to dy }
            val frame = RawSrRobustness.evaluateWithStats(refStats, mov, flow, tuning, config, null)
            val gold = readF32("A_r_f$i.f32", quadsW * quadsH)
            val full = maxAbs(gold, frame.r)
            var interior = 0.0
            for (y in 4 until quadsH - 4) for (x in 4 until quadsW - 4)
                interior = maxOf(interior, kotlin.math.abs(gold[y * quadsW + x] - frame.r[y * quadsW + x]).toDouble())
            println("robustness A f$i: full=$full interior=$interior")
            assertTrue("interior r f$i diff $interior", interior == 0.0)
            assertTrue("full r f$i diff $full", full < 1e-6)
            // Shifted frames legitimately flag OOB warp strips (the oracle
            // writes +inf there, likewise forcing r = 0): every flagged
            // quad must read r = 0 on both sides, and no other flag exists.
            for (o in 0 until quadsW * quadsH) {
                if (frame.flags[o] != 0) {
                    assertEquals(RawSrRobustness.FLAG_OUT_OF_BOUNDS, frame.flags[o])
                    assertEquals(0f, frame.r[o], 0f)
                    assertEquals(0f, gold[o], 0f)
                }
            }
        }
    }

    @Test fun robustnessStepFlowMatchesOracleOffBand() {
        // The step straddles tile columns 3|4 (raw x = 64): the smooth
        // lattice blends across it for quad x in 28..35, and the 5x5
        // local minimum spreads that ±2, so quads 26..37 intentionally
        // diverge from the snapped-warp oracle (the warp that renders
        // is the blend, and r scores the warp that renders). Outside
        // that band both corners sit on one side and the lookup equals
        // the snap, so off-band r must match the oracle to the same
        // float-noise bound as scenario A. The band itself must stay
        // finite in [0, 1] and genuinely differ (else the bilinear warp
        // would be dead code on this fixture).
        val refStats = RawSrRobustness.referenceStatsFromPacked(packedFrame(0))
        val mov = RawSrRobustness.movingStatsFromPacked(packedFrame(1))
        val flow = forcedFlow { tx, _ -> if (tx < 4) 1.7 to -0.6 else -2.9 to 2.3 }
        val frame = RawSrRobustness.evaluateWithStats(refStats, mov, flow, tuning, config, null)
        val gold = readF32("B_r.f32", quadsW * quadsH)
        var offBand = 0.0
        var bandDiff = 0.0
        for (y in 0 until quadsH) for (x in 0 until quadsW) {
            val o = y * quadsW + x
            val got = frame.r[o]
            assertTrue("r finite at ($x,$y)", got.isFinite())
            assertTrue("r in [0,1] at ($x,$y)", got >= 0f && got <= 1f)
            val d = kotlin.math.abs(gold[o] - got).toDouble()
            if (x in 26..37) bandDiff = maxOf(bandDiff, d) else offBand = maxOf(offBand, d)
        }
        println("robustness B: offBand=$offBand bandDiff=$bandDiff")
        assertTrue("off-band r diff $offBand", offBand < 1e-6)
        assertTrue("step band must diverge (bandDiff=$bandDiff)", bandDiff > 1e-3)
    }

    private fun mergeFrames(): Triple<RawSrBayerMerge.MergeFrame, List<RawSrBayerMerge.MergeFrame>, RawSrTuning> {
        val shifts = listOf(0.0 to 0.0, 1.7 to -0.6, -0.9 to 2.3)
        val packeds = (0..2).map(::packedFrame)
        val refStats = RawSrRobustness.referenceStatsFromPacked(packeds[0])
        val frames = packeds.mapIndexed { i, packed ->
            val samples = mergeSamples(packed)
            val guide = RawSrCovarianceGuide.guide(packed)
            val cov = RawSrKernelCovariance.covariance(guide.gray, tuning)
            val (dx, dy) = shifts[i]
            val flow = if (i == 0) null else forcedFlow { _, _ -> dx to dy }
            val robust = if (i == 0) null else RawSrRobustness.evaluateWithStats(
                refStats, RawSrRobustness.movingStatsFromPacked(packed), flow!!, tuning, config, null)
            RawSrBayerMerge.MergeFrame(
                rawW, rawH, samples, BayerPattern.RGGB, 0, 0, cov, flow, robust)
        }
        return Triple(frames[0], frames.subList(1, 3), tuning)
    }

    @Test fun linearMergeMatchesOracle() {
        val (ref, moving, _) = mergeFrames()
        // Reference-verbatim spelling: the golden fixtures predate the
        // chroma latch guard.
        val merged = RawSrBayerMerge.merge(ref, moving, scale = RawSrLinearScale.X1,
            chromaSigmaMpy = 1.0)
        assertEquals(rawW, merged.width)
        assertEquals(rawH, merged.height)
        val rgbGold = readF32("A_merged.f32", rawW * rawH * 3)
        val denGold = readF32("A_den.f32", rawW * rawH * 3)
        // Full field, tight: flow lookup and robustness fetch are
        // reference-verbatim nearest on both sides now, so the OOB /
        // local-min border band snaps identically too (quotient noise
        // scales with level, not with support, so tiny-denominator
        // border pixels stay in bound).
        val rgbFull = maxAbs(rgbGold, merged.rgb)
        val denFull = maxAbs(denGold, merged.denominator)
        println("merge: rgbFull=$rgbFull denFull=$denFull")
        assertTrue("rgb full $rgbFull", rgbFull < 2e-5)
        // Denominator inherits the kernel float noise above without the
        // quotient's self-correction.
        assertTrue("den full $denFull", denFull < 1e-4)
        // Rc is the finite-sanitized moving-frame r sum (reference excluded).
        var rc = RawSrRobustness.accumulate(null, moving[0].robustness!!)
        rc = RawSrRobustness.accumulate(rc, moving[1].robustness!!)
        val r1 = readF32("A_r_f1.f32", quadsW * quadsH)
        val r2 = readF32("A_r_f2.f32", quadsW * quadsH)
        val rcGold = FloatArray(quadsW * quadsH) { r1[it] + r2[it] }
        val dRc = maxAbs(rcGold, rc.values)
        println("rc: maxAbs=$dRc")
        assertTrue("rc diff $dRc", dRc < 1e-6)
    }

    @Test fun mosaicNativeMatchesLinear() {
        // The reference defines no mosaic path; mosaic parity is twinship
        // with the linear merge: same weights, same routing, CFA-gated. At
        // native scale every site must equal its linear channel to float
        // accumulator noise (mosaic narrows per frame, linear sums in
        // double). Both run the reference-verbatim chroma spelling (1.0):
        // the production 2.0 latch guard widens R/B on both paths equally,
        // but twinship pins the shared math, not the default.
        val (ref, moving, _) = mergeFrames()
        val linear = RawSrBayerMerge.merge(ref, moving, scale = RawSrLinearScale.X1,
            chromaSigmaMpy = 1.0)
        val mosaic = MosaicSrReconstructor.reconstruct(
            ref, moving, referenceOnly = false, scale = RawSrMosaicScale.NATIVE,
            chromaSigmaMpy = 1.0)
        assertEquals(rawW, mosaic.width)
        assertEquals(rawH, mosaic.height)
        var d = 0.0
        for (y in 0 until rawH) for (x in 0 until rawW) {
            val siteColor = mosaic.pattern.colorOrdinalAt(x, y)
            val o = (y * rawW + x) * 3 + siteColor
            d = maxOf(d, kotlin.math.abs(mosaic.cfa[y * rawW + x] - linear.rgb[o]).toDouble())
        }
        val fallbackCount = mosaic.fallback.count { it }
        println("mosaic-vs-linear: maxAbs=$d fallbacks=$fallbackCount/${rawW * rawH}")
        assertTrue("mosaic diff $d", d < 2e-6)
        assertTrue("too many fallbacks $fallbackCount", fallbackCount < rawW * rawH / 20)
    }
}
