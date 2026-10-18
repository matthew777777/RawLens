// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring tests for the still-capture highlight stage: FP16 helpers are
 * bit-exact, the toggle truly bypasses, unclipped frames pass through
 * untouched, and a clipped frame comes back reconstructed with alpha
 * intact. Reconstruction math itself is parity-gated in RtHlEndToEndTest;
 * this suite only proves the stage honors its contract.
 */
class HlReconstructionStageTest {
    private fun inputs(white: Float = 66000f) = HlReconstructionStage.Inputs(
        whiteLevel = white,
        blackLevels = floatArrayOf(1024f, 1024f, 1024f, 1024f),
        xyzRow = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    )

    companion object {
        const val WHITE = 66000f
        const val BLACK = 1024f
        /**
         * Test scenes are written in raw units; the tap is
         * range-normalized like the demosaic CFA: (code - black) /
         * (white - black), so sensor white is exactly 1.0.
         */
        fun tap(raw: Float) = (raw - BLACK) / (WHITE - BLACK)
    }

    private fun rgbaFrame(w: Int, h: Int, fill: (x: Int, y: Int) -> FloatArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(w * h * 4 * 2).order(ByteOrder.nativeOrder())
        val s = buf.asShortBuffer()
        for (y in 0 until h) for (x in 0 until w) {
            val px = fill(x, y)
            for (c in 0..3) s.put(HlReconstructionStage.packHalf(px[c]))
        }
        buf.rewind()
        return buf
    }

    private fun channel(out: ByteBuffer, w: Int, h: Int, c: Int): FloatArray {
        val s = out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        return FloatArray(w * h) { i ->
            s.position(i * 4 + c)
            HlReconstructionStage.unpackHalf(s.get().toInt() and 0xffff)
        }
    }

    @Test
    fun halfHelpersAreBitExact() {
        assertEquals(0x3c00.toShort(), HlReconstructionStage.packHalf(1f))
        assertEquals(0x3800.toShort(), HlReconstructionStage.packHalf(0.5f))
        assertEquals(0x0000.toShort(), HlReconstructionStage.packHalf(0f))
        assertEquals(0x7c00.toShort(), HlReconstructionStage.packHalf(Float.POSITIVE_INFINITY))
        assertEquals(0x0001.toShort(), HlReconstructionStage.packHalf(5.9604645e-8f))
        // Overflow of the half range saturates to infinity, never wraps.
        assertEquals(0x7c00.toShort(), HlReconstructionStage.packHalf(100000f))
        assertEquals(1f, HlReconstructionStage.unpackHalf(0x3c00), 0f)
        assertEquals(0f, HlReconstructionStage.unpackHalf(0x0000), 0f)
        assertTrue(HlReconstructionStage.unpackHalf(0x7c00).isInfinite())
        assertTrue(HlReconstructionStage.unpackHalf(0x7e00).isNaN())
        assertEquals(5.9604645e-8f, HlReconstructionStage.unpackHalf(0x0001), 0f)
        // Raw-scale round trip through the clipped range survives packing:
        // binary16 rounding is at most half a quantum (2^-11 relative).
        for (v in floatArrayOf(1024f, 20000f, 45000f, 62700f, 65504f)) {
            val rt = HlReconstructionStage.unpackHalf(
                HlReconstructionStage.packHalf(v).toInt() and 0xffff
            )
            val rel = kotlin.math.abs(rt - v) / v
            assertTrue("round trip of $v drifted $rel relative", rel <= 1f / 2048f)
        }
    }

    @Test
    fun disabledReturnsInputUntouched() {
        val w = 8
        val h = 8
        val clipped = rgbaFrame(w, h) { _, _ -> floatArrayOf(65504f, 65504f, 65504f, 1f) }
        assertSame(clipped, HlReconstructionStage.applyIfEnabled(clipped, w, h, false, inputs()))
    }

    @Test
    fun unclippedFrameSkipsReconstruction() {
        val w = 8
        val h = 8
        val plain = rgbaFrame(w, h) { _, _ ->
            floatArrayOf(tap(20000f), tap(21000f), tap(19000f), 1f)
        }
        assertSame(plain, HlReconstructionStage.applyIfEnabled(plain, w, h, true, inputs()))
    }

    @Test
    fun nearWhiteButUnclippedPassesThroughBitExact() {
        // Regression gate for the tap-domain fix: sensor white is tap 1.0
        // (range-normalized), so a peak at true-raw 62200 is below maxF and
        // must pass through untouched. Scaling the tap by white instead of
        // (white - black) would read it as 63158, flag it clipped, and
        // rewrite it — the gray-mud darkening seen on real photos.
        val w = 64
        val h = 64
        fun scene(x: Int, y: Int): Float {
            val dx = minOf(x, w - 1 - x).toFloat() / (w / 2)
            val dy = minOf(y, h - 1 - y).toFloat() / (h / 2)
            return 60000f + 2200f * minOf(dx, dy)
        }
        val frame = rgbaFrame(w, h) { x, y ->
            val v = scene(x, y)
            floatArrayOf(tap(v), tap(v), tap(v), 1f)
        }
        val before = ShortArray(w * h * 4)
        frame.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(before)
        val out = HlReconstructionStage.applyIfEnabled(frame, w, h, true, inputs())
        assertSame(frame, out)
        val after = ShortArray(w * h * 4)
        out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(after)
        assertTrue("unclipped near-whites were rewritten", before.contentEquals(after))
    }

    @Test
    fun tiledPrestageMatchesUntiled() {
        // Full chain, untiled vs tiled pre-stage, on a 576px scene (forces
        // 2x2 tiles with partial edge tiles and clamped windows): the final
        // reconstructed pixels must match. Intermediate directional maps
        // are deliberately NOT compared cell-wise — hilite ratios in
        // near-empty cells amplify sub-ULP plane differences into
        // thousands of ULP without changing any output pixel.
        // Reports the measured divergence — the gate below encodes it.
        val w = 576
        val h = 576
        fun scene(x: Int, y: Int): Float {
            val base = ((y * 131 + x * 137 + 7) % 1009).toFloat() / 1009f * 40000f
            val ring = x in 180 until 396 && y in 180 until 396
            val blob = x in 200 until 376 && y in 200 until 376
            var v = base
            if (ring) v = 45000f + ((y * 17 + x * 29) % 500).toFloat()
            if (blob) v = 65000f
            return v
        }
        fun fresh() = Triple(
            Array(h) { y -> FloatArray(w) { x -> scene(x, y) } },
            Array(h) { y -> FloatArray(w) { x -> scene(x, y) * 0.98f } },
            Array(h) { y -> FloatArray(w) { x -> scene(x, y) * 1.01f } }
        )
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(66000f, 66000f, 66000f),
            floatArrayOf(64976f, 64976f, 64976f)
        )
        val xyz = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val gamma = RtGuidedFilter.gammaTable()
        val guided = RtGuidedFilter.guidedParams(2)
        val (r1, g1, b1) = fresh()
        val roi = RtHighlightMap.findRoi(r1, g1, b1, t.maxF)!!
        val snapped = HlReconstructionStage.snapRoi(roi, w, h)!!
        val acc = RtHighlightMap.accumulateAndGate(r1, g1, b1, t.thresh, t.maxF, snapped)
        val hil = RtHighlightMap.downsample(acc.full)
        val mapsU = RtHighlightMap.directionalExtend(hil, hil[0][0].size - 1, hil[0].size - 1)
        HlVulkanRecon.reconstructCpu(r1, g1, b1, t, mapsU, snapped, xyz, gamma, guided)
        val (r2, g2, b2) = fresh()
        val mapsT = HlTiledPrestage.run(r2, g2, b2, t.thresh, t.maxF, snapped)
        // Tiled maps must carry true dims (never the 8x8 corner).
        assertEquals((snapped.width - snapped.width % 4) / 4, mapsT.hfw)
        assertEquals((snapped.height - snapped.height % 4) / 4, mapsT.hfh)
        HlVulkanRecon.reconstructCpu(r2, g2, b2, t, mapsT, snapped, xyz, gamma, guided)
        fun ulp(a: Float, b: Float): Long {
            val ua = a.toBits().toLong() and 0xffffffffL
            val ub = b.toBits().toLong() and 0xffffffffL
            fun tr(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            return kotlin.math.abs(tr(ua) - tr(ub))
        }
        var worstUlp = 0L
        var diffCount = 0
        for (y in 0 until h) for (x in 0 until w) {
            val d = maxOf(ulp(r1[y][x], r2[y][x]), ulp(g1[y][x], g2[y][x]), ulp(b1[y][x], b2[y][x]))
            if (d > worstUlp) worstUlp = d
            if (d > 0) diffCount++
        }
        // Calibrated 2026-10-17: 0 ulp over all 331776 pixels; the bound
        // leaves room for hipass-sum ordering on other scenes.
        println("tiled-vs-untiled finals worstUlp=$worstUlp diffPixels=$diffCount")
        assertTrue("tiled finals diverged: worstUlp=$worstUlp over $diffCount pixels", worstUlp <= 4L)
    }

    @Test
    fun vulkanFailureFallsBackToCpu() {
        // A throwing session factory must not fail the photo: the Vulkan
        // arm is attempted first (provider present), then the exact CPU
        // twin still repairs the blob in place.
        // Sensor-realistic width (multiple of 32); the blob spans nearly
        // the full frame so the tiled pre-stage does the heavy lifting.
        val w = 1120
        val h = 1120
        val frame = rgbaFrame(w, h) { x, y ->
            val v = if (x in 100 until 1000 && y in 100 until 1000) 65000f
            else ((y * 131 + x * 137 + 7) % 1009).toFloat() / 1009f * 40000f
            floatArrayOf(tap(v), tap(v * 0.98f), tap(v * 1.01f), 1f)
        }
        val noGpu: () -> VulkanHub.GpuContext? = { throw RuntimeException("no gpu here") }
        val out = HlReconstructionStage.applyIfEnabled(frame, w, h, true, inputs(), noGpu)
        assertSame(frame, out)
        val blobPacked = HlReconstructionStage.unpackHalf(
            HlReconstructionStage.packHalf(tap(65000f)).toInt() and 0xffff
        )
        val red = channel(out, w, h, 0)
        var changed = 0
        for (y in 500 until 600) for (x in 500 until 600) {
            if (red[y * w + x] != blobPacked) changed++
        }
        assertTrue("CPU fallback did not reconstruct", changed > 0)
    }

    @Test
    fun clippedFrameComesBackReconstructedWithAlphaIntact() {
        // Same scene shape as RtHlEndToEndTest: noisy base, bright ring,
        // blown blob — guarantees gradients for propagation to work with.
        val w = 32
        val h = 32
        fun scene(x: Int, y: Int): Float {
            val base = ((y * 131 + x * 137 + 7) % 1009).toFloat() / 1009f * 40000f
            val ring = x in 10 until 22 && y in 10 until 22
            val blob = x in 12 until 20 && y in 12 until 20
            var v = base
            if (ring) v = 45000f + ((y * 17 + x * 29) % 500).toFloat()
            if (blob) v = 65000f
            return v
        }
        val frame = rgbaFrame(w, h) { x, y ->
            val v = scene(x, y)
            // Tap carries black-subtracted, range-normalized values; the
            // channel ratios ride on the raw scene (ratios, not offsets).
            floatArrayOf(tap(v), tap(v * 0.98f), tap(v * 1.01f), 1f)
        }
        // In-place contract: same buffer object back, content repaired.
        val out = HlReconstructionStage.applyIfEnabled(frame, w, h, true, inputs())
        assertSame(frame, out)
        val red = channel(out, w, h, 0)
        val alpha = channel(out, w, h, 3)
        for (a in alpha) assertEquals(1f, a, 0f)
        // The blown blob must move: reconstruction rewrites clipped pixels.
        // Compare against the packed blob tap value: the normalized blob
        // is not exactly representable in binary16, so unpack(pack(v)) is
        // the baseline.
        val blobPacked = HlReconstructionStage.unpackHalf(
            HlReconstructionStage.packHalf(tap(65000f)).toInt() and 0xffff
        )
        var changed = 0
        for (y in 12 until 20) for (x in 12 until 20) {
            if (red[y * w + x] != blobPacked) changed++
        }
        assertTrue("blown pixels were not reconstructed", changed > 0)
        // Reconstruction never invents non-finite pixels.
        for (v in red) assertTrue("non-finite output $v", v.isFinite())
    }

    @Test
    fun pixelsOutsideRoiAreBitPreserved() {
        // The ROI is the blob plus RT's 256px blur border: on a frame much
        // larger than the border, the far corner sits outside the ROI and
        // must come back bit-identical.
        val w = 600
        val h = 600
        val frame = rgbaFrame(w, h) { x, y ->
            val v = if (x in 12 until 20 && y in 12 until 20) 65000f
            else ((y * 131 + x * 137 + 7) % 1009).toFloat() / 1009f * 40000f
            floatArrayOf(tap(v), tap(v * 0.98f), tap(v * 1.01f), 1f)
        }
        // Snapshot first: the stage packs in place, overwriting the input.
        val idx = ((h - 1) * w + (w - 1)) * 4
        val before = ShortArray(4) { c ->
            frame.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(idx + c)
        }
        val out = HlReconstructionStage.applyIfEnabled(frame, w, h, true, inputs())
        assertSame(frame, out)
        val outS = out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        for (c in 0..3) assertEquals(before[c], outS.get(idx + c))
    }

    @Test
    fun nonFiniteTapPixelsComeBackFinite() {
        val w = 32
        val h = 32
        val frame = rgbaFrame(w, h) { x, y ->
            val v = ((y * 131 + x * 137 + 7) % 1009).toFloat() / 1009f * 40000f
            floatArrayOf(tap(v), tap(v * 0.98f), tap(v * 1.01f), 1f)
        }
        val s = frame.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        // +Inf blue (65000*1.01 overflows half) and a NaN red.
        s.put((10 * w + 10) * 4 + 2, HlReconstructionStage.packHalf(Float.POSITIVE_INFINITY))
        s.put(
            (20 * w + 20) * 4,
            (HlReconstructionStage.packHalf(Float.NaN).toInt() and 0xffff).toShort()
        )
        val out = HlReconstructionStage.applyIfEnabled(frame, w, h, true, inputs())
        val o = out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        while (o.hasRemaining()) {
            val v = HlReconstructionStage.unpackHalf(o.get().toInt() and 0xffff)
            assertTrue("non-finite output $v", v.isFinite())
        }
    }

    @Test
    fun snapRoiExpandsWidthTo16WithoutDroppingClippedExtent() {
        // Interior ROI: width 276 -> 288, clipped extent still covered.
        val snapped = HlReconstructionStage.snapRoi(HlRoi(0, 0, 275, 275), 600, 600)!!
        assertEquals(0, snapped.minX)
        assertEquals(287, snapped.maxX)
        assertEquals(0, snapped.width % 32)
        // Right-edge ROI steals context from the left instead of clipping.
        val edge = HlReconstructionStage.snapRoi(HlRoi(2700, 0, 2999, 99), 3000, 3000)!!
        assertEquals(2999, edge.maxX)
        assertEquals(0, edge.width % 32)
        assertTrue(edge.minX <= 2700)
        // Odd height grows by one row.
        val odd = HlReconstructionStage.snapRoi(HlRoi(0, 0, 15, 98), 600, 600)!!
        assertEquals(0, odd.height % 2)
        // Origin off the 4-grid pulls down; height rounds to %4.
        val align = HlReconstructionStage.snapRoi(HlRoi(0, 2, 31, 99), 600, 600)!!
        assertEquals(0, align.minY)
        assertEquals(0, align.minY % 4)
        assertEquals(0, align.height % 4)
        // Full-frame ROI on a width-%-32 frame (the 4080-wide sensor
        // case): never refuses — keeps the full width via the scalar tail.
        val full = HlReconstructionStage.snapRoi(HlRoi(0, 0, 4079, 3059), 4080, 3060)!!
        assertEquals(0, full.minX)
        assertEquals(4079, full.maxX)
        assertEquals(0, full.height % 2)
    }

    @Test
    fun parallelWorkersMatchSequentialBitForBit() {
        // Workers must be invisible: same scene sequentially and on a pool
        // produces identical output shorts (disjoint bands/tiles, fixed
        // reduce order). The wide blob forces a 2x1 tile layout so tile
        // tasks actually run in parallel.
        val w = 640
        val h = 600
        fun build() = rgbaFrame(w, h) { x, y ->
            val v = if (x in 250 until 350 && y in 12 until 20) 65000f
            else ((y * 131 + x * 137 + 7) % 1009).toFloat() / 1009f * 40000f
            floatArrayOf(tap(v), tap(v * 0.98f), tap(v * 1.01f), 1f)
        }
        val seq = HlReconstructionStage.applyIfEnabled(build(), w, h, true, inputs())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val par = HlReconstructionStage.applyIfEnabled(build(), w, h, true, inputs(), null, pool)
            val s = seq.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            val p = par.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            assertEquals(s.remaining(), p.remaining())
            while (s.hasRemaining()) assertEquals(s.get(), p.get())
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun sliceMapsMatchesDirectIndexing() {
        // Sliced tile maps must equal the full maps at the offset cells,
        // in both layouts (y-major dir, x-major dir0/dir4).
        val hfw = 36
        val hfh = 30
        val maps = HlMaps(
            hilite = Array(4) { m -> Array(hfh + 1) { y -> FloatArray(hfw + 1) { x -> (m * 100000 + y * 1000 + x).toFloat() } } },
            dir = Array(8) { c -> Array(hfh) { y -> FloatArray(hfw) { x -> (c * 100000 + y * 1000 + x).toFloat() } } },
            dir0 = Array(4) { c -> Array(hfw) { x -> FloatArray(hfh) { y -> (c * 100000 + x * 1000 + y).toFloat() } } },
            dir4 = Array(4) { c -> Array(hfw) { x -> FloatArray(hfh) { y -> (c * 100000 + x * 500 + y).toFloat() } } },
            hfw = hfw,
            hfh = hfh
        )
        val s = HlTiledPrestage.sliceMaps(maps, 8, 6, 16, 12)
        assertEquals(16, s.hfw)
        assertEquals(12, s.hfh)
        for (c in 0 until 8) for (y in 0 until 12) for (x in 0 until 16) {
            assertEquals(maps.dir[c][6 + y][8 + x], s.dir[c][y][x])
        }
        for (c in 0 until 4) for (x in 0 until 16) for (y in 0 until 12) {
            assertEquals(maps.dir0[c][8 + x][6 + y], s.dir0[c][x][y])
            assertEquals(maps.dir4[c][8 + x][6 + y], s.dir4[c][x][y])
        }
    }

    @Test
    fun gpuTilesPartitionRoiWithEvenWindows() {
        // GTILE-tiles over a full-frame ROI: interiors partition the ROI,
        // every window stays in-frame with even dims for the plan.
        val roi = HlRoi(0, 0, 4079, 3059)
        val tiles = HlTiledPrestage.tiles(roi, HlTiledPrestage.GTILE, HlTiledPrestage.GHALO)
        assertTrue(tiles.size == 6)
        var covered = 0
        for ((window, interior) in tiles) {
            assertTrue(window.minX >= 0 && window.maxX < 4080)
            assertTrue(window.minY >= 0 && window.maxY < 3060)
            assertEquals(0, window.width % 2)
            assertEquals(0, window.height % 2)
            assertTrue(interior.minX >= roi.minX && interior.maxX <= roi.maxX)
            covered += interior.width * interior.height
        }
        assertEquals(4080 * 3060, covered)
    }

    @Test
    fun badInputsFallBackToInput() {
        val w = 8
        val h = 8
        val frame = rgbaFrame(w, h) { _, _ -> floatArrayOf(65504f, 65504f, 65504f, 1f) }
        assertSame(
            frame,
            HlReconstructionStage.applyIfEnabled(frame, w, h, true, inputs(white = 0f))
        )
        val odd = rgbaFrame(7, h) { _, _ -> floatArrayOf(1000f, 1000f, 1000f, 1f) }
        assertSame(odd, HlReconstructionStage.applyIfEnabled(odd, 7, h, true, inputs()))
    }
}
