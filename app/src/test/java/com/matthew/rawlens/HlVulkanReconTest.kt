// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durable parity gates for the Vulkan highlight-reconstruction plan
 * ([HlVulkanRecon]): dispatch order and counts, the windowed-average blur
 * twin against the ring reference, and the full GPU-plan chain (executed
 * by the JVM twin) against the [RtHlEndToEndTest] oracle goldens.
 *
 * The on-device gate (same plan through [VkSession] vs these goldens)
 * runs on hardware; everything here must stay green on the JVM.
 */
class HlVulkanReconTest {
    private fun maxUlp(expected: List<String>, actual: Array<FloatArray>): Long {
        val flat = expected.flatMap { it.split(" ") }
        var k = 0
        var worst = 0L
        for (row in actual) for (x in row) {
            val e = flat[k].toLong(16)
            val a = x.toBits().toLong() and 0xffffffffL
            fun t(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            val d = kotlin.math.abs(t(e) - t(a))
            if (d > worst) worst = d
            k++
        }
        assertEquals(flat.size, k)
        return worst
    }

    private fun maxUlpArrays(expected: Array<FloatArray>, actual: Array<FloatArray>): Long {
        var worst = 0L
        for (y in expected.indices) for (x in expected[0].indices) {
            val e = expected[y][x].toBits().toLong() and 0xffffffffL
            val a = actual[y][x].toBits().toLong() and 0xffffffffL
            fun t(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            val d = kotlin.math.abs(t(e) - t(a))
            if (d > worst) worst = d
        }
        return worst
    }

    @Test
    fun planHas68OpsInDispatchOrder() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(66000f, 66000f, 66000f),
            floatArrayOf(63000f, 64000f, 63500f)
        )
        val params = HlVulkanRecon.RunParams(t, floatArrayOf(0.2126f, 0.7152f, 0.0722f), 0.1f, 3, 1)
        val plan = HlVulkanRecon.buildPlan(32, 32, 16, 16, params)
        assertEquals(68, plan.size)
        val modes = plan.map { it.mode }
        assertEquals(HlVulkanRecon.M_GUIDE, modes[0])
        assertEquals(HlVulkanRecon.M_RECONSTRUCT, modes[1])
        assertEquals(HlVulkanRecon.M_RESOLVE, modes[2])
        assertEquals(HlVulkanRecon.M_MERGE, modes.last())
        // Guide statistics run once per radius (4 guided passes, 2 radii).
        assertEquals(2 + 1 + 2 + 1, modes.subList(3, 9).size)
        // Every BLUR carries a clamped radius; COMBINE ops cover all five.
        assertTrue(plan.filter { it.mode == HlVulkanRecon.M_BLUR_H || it.mode == HlVulkanRecon.M_BLUR_V }
            .all { it.radius in 0..6 })
        assertEquals(
            setOf(0, 1, 2, 4),
            plan.filter { it.mode == HlVulkanRecon.M_COMBINE }.map { it.combineOp }.toSet()
        )
    }

    @Test
    fun radiusZeroPlanSkipsIdentityBlurs() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(66000f, 66000f, 66000f),
            floatArrayOf(63000f, 64000f, 63500f)
        )
        // hlbl=1 shape: radius1=4, radius2=0 — every radius-0 blur is an
        // exact identity, so 28 copy dispatches vanish (68 -> 40).
        val params = HlVulkanRecon.RunParams(t, floatArrayOf(0.2126f, 0.7152f, 0.0722f), 0.3f, 4, 0)
        val plan = HlVulkanRecon.buildPlan(32, 32, 16, 16, params)
        assertEquals(40, plan.size)
        assertTrue(plan.none {
            (it.mode == HlVulkanRecon.M_BLUR_H || it.mode == HlVulkanRecon.M_BLUR_V) && it.radius == 0
        })
        assertDefUseValid(plan)
        // The skipped plan still executes end to end with sane outputs.
        val s = stageE2e()
        val result = HlVulkanRecon.reconstructCpu(
            s.red, s.green, s.blue, s.t, s.maps, s.roi, s.xyzcam, s.gamma,
            RtGuidedFilter.guidedParams(1)
        )
        for (row in s.red) for (x in row) assertTrue(x.isFinite())
        for (row in result.mask) for (x in row) assertTrue(x.isFinite())
        for (row in result.guide) for (x in row) assertTrue(x.isFinite() && x >= 0f)
    }

    @Test
    fun fullPlanDefUseValid() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(66000f, 66000f, 66000f),
            floatArrayOf(63000f, 64000f, 63500f)
        )
        val params = HlVulkanRecon.RunParams(t, floatArrayOf(0.2126f, 0.7152f, 0.0722f), 0.1f, 3, 1)
        assertDefUseValid(HlVulkanRecon.buildPlan(32, 32, 16, 16, params))
    }

    /**
     * Structural plumbing gate: every plane an op reads must be staged
     * (host upload) or written by an earlier op. Catches aliasing mistakes
     * in the radius-0 skip: a skipped copy must leave readers pointed at
     * the live source buffer.
     */
    private fun assertDefUseValid(plan: List<HlVulkanRecon.Op>) {
        val H = HlVulkanRecon
        val defined = mutableSetOf(
            H.P_IN_R, H.P_IN_G, H.P_IN_B,
            H.P_D0 + "0", H.P_D0 + "1", H.P_D0 + "2", H.P_D0 + "3",
            H.P_DA + "0", H.P_DA + "1", H.P_DA + "2", H.P_DA + "3",
            H.P_DB + "0", H.P_DB + "1", H.P_DB + "2", H.P_DB + "3",
            H.P_D4 + "0", H.P_D4 + "1", H.P_D4 + "2", H.P_D4 + "3",
            H.P_H_R, H.P_H_G, H.P_H_B
        )
        for (op in plan) {
            val reads = when (op.mode) {
                H.M_BLUR_H, H.M_BLUR_V, H.M_COMBINE ->
                    listOf(op.a, op.b, op.c).filter { it.isNotEmpty() }
                H.M_RECONSTRUCT -> listOf(H.P_IN_R, H.P_IN_G, H.P_IN_B)
                H.M_RESOLVE -> listOf(H.P_BUF_R, H.P_BUF_G, H.P_BUF_B, H.P_MASK_F)
                H.M_GUIDE -> listOf(H.P_H_R, H.P_H_G, H.P_H_B)
                H.M_MERGE -> listOf(
                    H.P_BUF_R, H.P_BUF_G, H.P_BUF_B,
                    H.P_H_R, H.P_H_G, H.P_H_B, H.P_H_MASK
                )
                else -> throw IllegalArgumentException("unknown mode ${op.mode}")
            }
            for (r in reads) assertTrue("op $op reads undefined plane $r", r in defined)
            val writes = when (op.mode) {
                H.M_BLUR_H, H.M_BLUR_V, H.M_COMBINE -> listOf(op.out)
                H.M_RECONSTRUCT -> listOf(H.P_BUF_R, H.P_BUF_G, H.P_BUF_B, H.P_MASK_F)
                H.M_RESOLVE -> listOf(H.P_H_R, H.P_H_G, H.P_H_B, H.P_H_MASK)
                H.M_GUIDE -> listOf(H.P_GUIDE)
                H.M_MERGE -> listOf(H.P_OUT_R, H.P_OUT_G, H.P_OUT_B)
                else -> throw IllegalArgumentException("unknown mode ${op.mode}")
            }
            defined.addAll(writes)
        }
    }

    @Test
    fun boxMeanClampMatchesReference() {
        assertEquals(3, HlVulkanRecon.clampBoxMean(16, 16, 3))
        assertEquals(1, HlVulkanRecon.clampBoxMean(16, 16, 1))
        // lim = (16-1)/2-1 = 6, like boxMeanInPlace.
        assertEquals(6, HlVulkanRecon.clampBoxMean(16, 16, 99))
        assertEquals(0, HlVulkanRecon.clampBoxMean(16, 16, 0))
    }

    @Test
    fun windowedBlurMatchesRingWithinEnvelope() {
        val w = 16
        val h = 16
        val src = Array(h) { y -> FloatArray(w) { x -> ((y * 131 + x * 137 + 7) % 1009).toFloat() } }
        for (radius in listOf(1, 3, 6)) {
            // Ring reference: one call blurs H then V.
            val ring = Array(h) { src[it].copyOf() }
            RtGuidedFilter.boxBlurRing(src, ring, radius, w, h)
            // Twin: windowed H into tmp, windowed V into out.
            val tmp = Array(h) { FloatArray(w) }
            val out = Array(h) { FloatArray(w) }
            HlVulkanRecon.blurCpu(src, tmp, w, h, radius, horizontal = true)
            HlVulkanRecon.blurCpu(tmp, out, w, h, radius, horizontal = false)
            val worst = maxUlpArrays(ring, out)
            assertTrue("radius $radius maxUlp=$worst exceeds 32", worst <= 32L)
        }
    }

    @Test
    fun dirMapInterleaveRoundtrips() {
        // X-major planes (dir0/dir4): image (x, y) holds map cell (x, y).
        val xch = List(4) { c -> Array(3) { x -> FloatArray(2) { y -> (c * 100 + x * 10 + y).toFloat() } } }
        val xf = HlVulkanRecon.interleaveMaps(xch[0], xch[1], xch[2], xch[3], xMajor = true)
        for (x in 0 until 3) for (y in 0 until 2) for (c in 0..3) {
            assertEquals(xch[c][x][y], xf[(y * 3 + x) * 4 + c])
        }
        // Y-major planes (dirA/dirB): image (x, y) holds map cell (y, x).
        val ych = List(4) { c -> Array(2) { y -> FloatArray(3) { x -> (c * 100 + y * 10 + x).toFloat() } } }
        val yf = HlVulkanRecon.interleaveMaps(ych[0], ych[1], ych[2], ych[3], xMajor = false)
        for (x in 0 until 3) for (y in 0 until 2) for (c in 0..3) {
            assertEquals(ych[c][y][x], yf[(y * 3 + x) * 4 + c])
        }
    }

    private data class Staged(
        val red: Array<FloatArray>,
        val green: Array<FloatArray>,
        val blue: Array<FloatArray>,
        val t: RtHlThresholds,
        val maps: HlMaps,
        val roi: HlRoi,
        val xyzcam: FloatArray,
        val gamma: FloatArray,
        val guided: RtGuidedFilter.GuidedParams
    )

    private fun stageE2e(): Staged {
        val e2e = RtHlEndToEndTest()
        val (red, green, blue) = e2e.scene()
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(66000f, 66000f, 66000f),
            floatArrayOf(63000f, 64000f, 63500f)
        )
        val roi = RtHighlightMap.findRoi(red, green, blue, t.maxF)!!
        val acc = RtHighlightMap.accumulateAndGate(red, green, blue, t.thresh, t.maxF, roi)
        val hil = RtHighlightMap.downsample(acc.full)
        val maps = RtHighlightMap.directionalExtend(hil, 8, 8)
        val xyzcam = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val gamma = RtGuidedFilter.gammaTable()
        val guided = RtGuidedFilter.guidedParams(2)
        return Staged(red, green, blue, t, maps, roi, xyzcam, gamma, guided)
    }

    @Test
    fun fullPlanMatchesEndToEndGoldens() {
        val s = stageE2e()
        val red = s.red
        val green = s.green
        val blue = s.blue
        val t = s.t
        val maps = s.maps
        val roi = s.roi
        val xyzcam = s.xyzcam
        val gamma = s.gamma
        val guided = s.guided
        // Reference guide from the staged originals, snapshotted before the
        // twin runs: GUIDE must read pre-correction buffers, so the twin's
        // guide is bit-exact here (any drift is a staging bug).
        val w2 = roi.width / 2
        val h2 = roi.height / 2
        val refR = Array(h2) { FloatArray(w2) }
        val refG = Array(h2) { FloatArray(w2) }
        val refB = Array(h2) { FloatArray(w2) }
        RtGuidedFilter.rescaleNearestInto(red, roi.minX, roi.minY, roi.width, roi.height, refR)
        RtGuidedFilter.rescaleNearestInto(green, roi.minX, roi.minY, roi.width, roi.height, refG)
        RtGuidedFilter.rescaleNearestInto(blue, roi.minX, roi.minY, roi.width, roi.height, refB)
        val result = HlVulkanRecon.reconstructCpu(red, green, blue, t, maps, roi, xyzcam, gamma, guided)
        for (y in 0 until h2) {
            for (x in 0 until w2) {
                assertEquals(
                    "guide bit-exact at ($x,$y)",
                    RtGuidedFilter.lutSample(
                        gamma, RtGuidedFilter.rgbLuminance(refR[y][x], refG[y][x], refB[y][x], xyzcam)
                    ).toBits(),
                    result.guide[y][x].toBits()
                )
            }
        }
        val g = RtHlEndToEndTest.GOLDEN_G
        assertTrue(maxUlp(RtHlEndToEndTest.GOLDEN_R, red) <= PLAN_PIXEL_BOUND)
        assertTrue(maxUlp(g, green) <= PLAN_PIXEL_BOUND)
        assertTrue(maxUlp(RtHlEndToEndTest.GOLDEN_B, blue) <= PLAN_PIXEL_BOUND)
        assertTrue(maxUlp(RtHlEndToEndTest.GOLDEN_M, result.mask) <= PLAN_MASK_BOUND)
        assertTrue(maxUlp(RtHlEndToEndTest.GOLDEN_GD, result.guide) <= PLAN_GUIDE_BOUND)
    }

    /**
     * Writes the device measurement bundle for tools/hlmeasure: plan.json
     * plus one little-endian float32 .bin per staged input and twin output.
     * Directory from -Dhl.measure.dir (default /tmp/hlmeasure/bundle).
     */
    @Test
    fun writeMeasureBundle() {
        val s = stageE2e()
        val b = HlVulkanRecon.measureBundle(
            s.red, s.green, s.blue, s.t, s.maps, s.roi, s.xyzcam, s.gamma, s.guided
        )
        val dir = java.io.File(System.getProperty("hl.measure.dir", "/tmp/hlmeasure/bundle"))
        dir.mkdirs()
        val images = HlVulkanRecon.bundleImages(b.w, b.h, b.w2, b.h2, b.hfw, b.hfh)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"w\": ${b.w}, \"h\": ${b.h}, \"w2\": ${b.w2}, \"h2\": ${b.h2},\n")
        sb.append("  \"hfw\": ${b.hfw}, \"hfh\": ${b.hfh},\n")
        sb.append("  \"whitePt\": ${b.whitePt}, \"clipPt\": ${b.clipPt}, \"blendPt\": ${b.blendPt},\n")
        sb.append("  \"maxF\": [${b.maxF.joinToString()}],\n")
        sb.append("  \"medF\": [${b.medF.joinToString()}],\n")
        sb.append("  \"xyzRow\": [${b.xyzRow.joinToString()}],\n")
        sb.append("  \"images\": {\n")
        sb.append(images.entries.joinToString(",\n") { (k, v) ->
            "    \"$k\": [${v.first}, ${v.second}, ${v.third}]"
        })
        sb.append("\n  },\n  \"ops\": [\n")
        sb.append(b.plan.joinToString(",\n") { op ->
            val roles = HlVulkanRecon.slotRoles(op)
            val eps = if (op.mode == HlVulkanRecon.M_COMBINE) op.eps else HlVulkanRecon.EPSILON
            "    {\"mode\": ${op.mode}, \"w\": ${op.w}, \"h\": ${op.h}, " +
                "\"radius\": ${op.radius}, \"combineOp\": ${op.combineOp}, \"eps\": $eps, " +
                "\"roles\": {${roles.entries.joinToString { (slot, key) -> "\"$slot\": \"$key\"" }}}}"
        })
        sb.append("\n  ]\n}\n")
        java.io.File(dir, "plan.json").writeText(sb.toString())
        fun writeBin(name: String, values: FloatArray) {
            val buf = java.nio.ByteBuffer.allocate(values.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(values)
            java.io.File(dir, "$name.bin").writeBytes(buf.array())
        }
        for ((key, values) in b.inputs) writeBin("in_$key", values)
        val zeros = { n: Int -> FloatArray(n) }
        for ((key, dims) in images) {
            if (key !in b.inputs) writeBin("in_$key", zeros(dims.first * dims.second * dims.third))
        }
        b.twinMerged.forEachIndexed { i, plane -> writeBin("twin_merged_$i", plane) }
        writeBin("twin_mask", b.twinMask.flat())
        writeBin("twin_guide", b.twinGuide.flat())
    }

    private fun Array<FloatArray>.flat(): FloatArray {
        val out = FloatArray(size * this[0].size)
        for (y in indices) this[y].copyInto(out, y * this[0].size)
        return out
    }

    private companion object {
        // Measured maxima on the 32x32 oracle scene: pixels 25, mask 219,
        // guide 4 ULP. Pixel/guide bounds match the RtHlEndToEndTest gates;
        // every bound keeps >=2x headroom over the measurement.
        const val PLAN_PIXEL_BOUND = 64L
        const val PLAN_MASK_BOUND = 512L
        const val PLAN_GUIDE_BOUND = 64L
    }
}
