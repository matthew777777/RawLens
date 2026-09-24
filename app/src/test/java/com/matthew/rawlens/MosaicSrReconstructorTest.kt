package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs

class MosaicSrReconstructorTest {
    // ---- target grid ----

    @Test fun targetGridDoublesAreaAtSqrt2() {
        val grid = MosaicSrReconstructor.planTarget(4000, 3000)
        assertEquals(5656, grid.width)
        assertEquals(4242, grid.height)
        assertEquals(0, grid.width % 2)
        assertEquals(0, grid.height % 2)
        val areaRatio = grid.width.toDouble() * grid.height / (4000.0 * 3000.0)
        assertTrue("area ratio $areaRatio", areaRatio in 1.9..2.1)
        assertEquals(4000.0 / 3000.0, grid.width.toDouble() / grid.height, 0.01)
        assertEquals(MosaicSrReconstructor.LINEAR_SCALE, grid.scale, 0.0)
    }

    @Test fun targetGridStaysEvenAtSmallSizes() {
        val grid = MosaicSrReconstructor.planTarget(8, 6)
        assertEquals(10, grid.width)
        assertEquals(8, grid.height)
    }

    @Test fun targetGridRejectsBadSource() {
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrReconstructor.planTarget(7, 6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrReconstructor.planTarget(0, 0)
        }
    }

    @Test fun targetPhaseFollowsPatternAndCropOrigin() {
        for (pattern in BayerPattern.entries) {
            assertEquals(pattern, MosaicSrReconstructor.planTarget(8, 6, pattern).pattern)
            // planTarget takes the origin-shifted phase verbatim: an odd
            // crop origin arrives pre-shifted, exactly like production
            // UnpackedRawCfa.pattern, and must not shift again.
            assertEquals(
                pattern.shifted(1, 0),
                MosaicSrReconstructor.planTarget(8, 6, pattern.shifted(1, 0)).pattern
            )
            assertEquals(
                pattern.shifted(0, 1),
                MosaicSrReconstructor.planTarget(8, 6, pattern.shifted(0, 1)).pattern
            )
            val result = MosaicSrReconstructor.reconstruct(
                frame(8, 6, pattern, FloatArray(48) { 0.5f }, left = 1), emptyList()
            )
            assertEquals(pattern.shifted(1, 0), result.pattern)
        }
    }

    // ---- accumulation ----

    @Test fun singleFrameUniformReproducesValue() {
        val result = MosaicSrReconstructor.reconstruct(
            frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.37f }), emptyList()
        )
        assertEquals(10 * 8, result.cfa.size) // one sample per site: never RGB triplets
        for (v in result.cfa) assertEquals(0.37f, v, 1e-6f)
        assertTrue(result.fallback.none { it })
        assertTrue(result.weight.all { it > 0f })
        assertTrue(result.taps.all { it > 0 })
    }

    @Test fun sameColourRoutingHoldsForAllPatterns() {
        for (pattern in BayerPattern.entries) {
            val samples = FloatArray(48) { i ->
                when (pattern.colorAt(i % 8, i / 8)) {
                    CfaColor.RED -> 0.8f
                    CfaColor.GREEN -> 0.5f
                    CfaColor.BLUE -> 0.25f
                }
            }
            val result = MosaicSrReconstructor.reconstruct(frame(8, 6, pattern, samples), emptyList())
            for (y in 0 until result.height) for (x in 0 until result.width) {
                val expected = when (result.pattern.colorAt(x, y)) {
                    CfaColor.RED -> 0.8f
                    CfaColor.GREEN -> 0.5f
                    CfaColor.BLUE -> 0.25f
                }
                assertEquals("$pattern ($x,$y)", expected, result.cfa[y * result.width + x], 1e-6f)
            }
        }
    }

    @Test fun crossColourObservationsNeverLeak() {
        // Red-only source: green/blue target sites must stay exactly zero even
        // though every 3x3 tap window contains bright red samples.
        val samples = FloatArray(48) { i ->
            if (BayerPattern.RGGB.colorAt(i % 8, i / 8) == CfaColor.RED) 0.8f else 0f
        }
        val result = MosaicSrReconstructor.reconstruct(
            frame(8, 6, BayerPattern.RGGB, samples), emptyList()
        )
        for (y in 0 until result.height) for (x in 0 until result.width) {
            val v = result.cfa[y * result.width + x]
            if (result.pattern.colorAt(x, y) == CfaColor.RED) assertEquals(0.8f, v, 1e-6f)
            else assertEquals(0f, v, 0f)
        }
    }

    @Test fun workerCountNeverChangesOutputBits() {
        // Threading contract: row shards run the serial per-pixel code over
        // disjoint rows, so 1 worker and N workers must agree bitwise. Uses
        // textured samples with sub-pixel shift to exercise interpolation,
        // bounds taps and the robustness gate (r < 1).
        var s = 12345L
        fun textured(w: Int, h: Int): FloatArray = FloatArray(w * h) {
            s = (s * 1103515245 + 12345) % 2147483648
            (0.2f + (s % 1000) / 1000f * 0.6f)
        }
        val w = 64; val h = 48
        val ref = frame(w, h, BayerPattern.RGGB, textured(w, h))
        val mov = listOf(
            frame(w, h, BayerPattern.RGGB, textured(w, h), dx = 0.35f, dy = -0.2f, r = 0.7f),
            frame(w, h, BayerPattern.RGGB, textured(w, h), dx = -0.3f, dy = 0.4f, r = 0.9f)
        )
        RawSrWorkers.overrideCount = 1
        val serial = try {
            MosaicSrReconstructor.reconstruct(ref, mov)
        } finally {
            RawSrWorkers.overrideCount = null
        }
        val parallel = MosaicSrReconstructor.reconstruct(ref, mov)
        assertEquals(serial.width, parallel.width)
        assertEquals(serial.height, parallel.height)
        assertArrayEquals(serial.cfa, parallel.cfa, 0.0f)
        assertArrayEquals(serial.weight, parallel.weight, 0.0f)
        assertArrayEquals(serial.taps, parallel.taps)
        assertArrayEquals(serial.oob, parallel.oob)
        assertTrue(serial.fallback.contentEquals(parallel.fallback))
    }

    @Test fun twoFramesAverageWithZeroShift() {
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.6f })
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        for (v in result.cfa) assertEquals(0.5f, v, 1e-5f)
    }

    @Test fun zeroRobustnessFrameContributesNothing() {
        // A fully-dead frame (r = 0) accumulates nowhere, but its quads still
        // trip the support overwrite (rc = 0 < MIN_SUPPORT) and route to the
        // reference-kernel fallback instead of the kernel — the oracle behaves
        // identically. Values agree to float rounding, and the mask records
        // the routing.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.9f }, r = 0f)
        val withDead = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        assertTrue(withDead.fallback.all { it })
        assertTrue(alone.fallback.none { it })
        assertArrayEquals(alone.cfa, withDead.cfa, 1e-6f)
    }

    @Test fun unsupportedSitePrefersReferenceKernelOverNearest() {
        // Rc = 0.1 < MIN_SUPPORT with live reference support: the site must
        // resolve through the smooth reference kernel mean (0.4), not the
        // single-tap burst-nearest blend ((0.4 + 0.6*0.1)/1.1 ≈ 0.418) that
        // reads as jagged/blocky on sparse distant sites.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.6f }, r = 0.1f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.fallback.all { it })
        for (v in result.cfa) assertEquals(0.4f, v, 1e-6f)
    }

    @Test fun motionEdgeSkipsSplatsWithoutOob() {
        // Tile columns disagreeing by 5 quads: every site's tile window
        // straddles the discontinuity, so every moving splat skips with no
        // OOB bump and the output equals the reference-only image exactly.
        val w = 32
        val h = 32
        val qw = w / 2
        val qh = h / 2
        val samples = FloatArray(w * h) { 0.5f }
        fun flowOf(flowAt: (tx: Int, ty: Int) -> Pair<Float, Float>): RawSrAlignmentField {
            val tiles = List(4) { i ->
                val (dx, dy) = flowAt(i % 2, i / 2)
                RawSrTileFlow(0f, 0f, dx, dy, 0f, true)
            }
            return RawSrAlignmentField(qw, qh, 8, 2, 2, tiles)
        }
        fun mframe(flow: RawSrAlignmentField?, r: Float): RawSrBayerMerge.MergeFrame {
            val precision = RawSrKernelCovariance.MatrixField(
                qw, qh, FloatArray(qw * qh * 4) { 1e-6f })
            val robustness = if (flow == null) null else
                RawSrRobustness.FrameRobustness(qw, qh, FloatArray(qw * qh) { r }, IntArray(qw * qh))
            return RawSrBayerMerge.MergeFrame(
                w, h, samples, BayerPattern.RGGB, 0, 0, precision, flow, robustness)
        }
        val ref = mframe(null, 1f)
        val mov = mframe(flowOf { tx, _ -> if (tx == 0) 0f to 0f else 5f to 0f }, 1f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        assertTrue(result.oob.all { it == 0 })
        assertArrayEquals(alone.cfa, result.cfa, 0f)
    }

    @Test fun censoredNearestTapNeverBecomesFallback() {        // Reference kernel blind (NaN precision) with sub-threshold support
        // from a clipped moving frame: sites near the single clean tap must
        // resolve through it (0.409…), far sites through the reference
        // nearest (0.4) — never through a clipped white tap (0.454…).
        val refSamples = FloatArray(48) { 0.4f }
        val ref = frame(8, 6, BayerPattern.RGGB, refSamples, precision = Float.NaN)
        val movSamples = FloatArray(48) { 0.999f }
        movSamples[3 * 8 + 4] = 0.5f
        val mov = frame(8, 6, BayerPattern.RGGB, movSamples, r = 0.1f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        for (v in result.cfa) {
            assertTrue("cfa=$v", v.isFinite())
            assertTrue("cfa=$v below honest floor", v >= 0.39f)
            assertTrue("cfa=$v admits clipped white", v < 0.42f)
        }
    }

    @Test fun clippedChromaIsNeutralForEveryTargetPhaseAndStreamingPath() {
        val neutral = floatArrayOf(0.749634f, 1f, 0.368611f)
        val dir = java.nio.file.Files.createTempDirectory("mosaic-highlights").toFile()
        try {
            for (pattern in BayerPattern.entries) for (left in 0..1) for (top in 0..1) {
                val samples = FloatArray(48) { i ->
                    when (pattern.colorAt(left + i % 8, top + i / 8)) {
                        CfaColor.RED -> 0.74f
                        CfaColor.GREEN -> 1f
                        CfaColor.BLUE -> 0.30f
                    }
                }
                val ref = frame(8, 6, pattern, samples, left = left, top = top)
                    .copy(highlightNeutral = neutral)
                val mov = ref.copy(robustness = frame(8, 6, pattern, samples, r = 0f).robustness)
                val eager = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
                val stream = MosaicSrReconstructor.reconstructStreaming(
                    geometryOf(ref), sequenceOf(mov), { ref }, tempDir = dir)
                assertArrayEquals(eager.cfa, stream.cfa, 0f)
                for (y in 0 until eager.height) for (x in 0 until eager.width) {
                    val c = RawSrHighlights.channel(eager.pattern.colorAt(x, y))
                    assertEquals("$pattern origin=$left,$top site=$x,$y", 1f,
                        eager.cfa[y * eager.width + x] / neutral[c], 1e-6f)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun flowShiftsObservationsInTargetSpace() {
        // Source ramp value=x. The shift is exactly one red period (2 px), so
        // same-colour tap windows keep their shape and parity: interior site
        // means rise by exactly 2 units in the moving contribution. The zero
        // reference dilutes the rise by half, hence the net 1 unit. The ramp
        // stays in physical normalised range (< 0.99): values at/above the
        // censor level are excluded from kernel means by design, so an
        // unphysical 0..7 ramp would measure censoring, not geometry. Kernel
        // weights are value-independent, so the geometry pin is level-
        // invariant.
        fun ramp() = FloatArray(48) { i -> (i % 8).toFloat() * 0.1f }
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48))
        val still = frame(8, 6, BayerPattern.RGGB, ramp())
        val shifted = frame(8, 6, BayerPattern.RGGB, ramp(), dx = 1.0f)
        val plain = MosaicSrReconstructor.reconstruct(ref, listOf(still))
        val moved = MosaicSrReconstructor.reconstruct(ref, listOf(shifted))
        var sum = 0.0; var n = 0
        for (i in moved.cfa.indices) {
            // Windows must stay fully in-bounds on BOTH reconstructions: the
            // +2 px shift pushes right-edge windows out, so the interior stops
            // one column earlier on the right.
            val x = i % moved.width; val y = i / moved.width
            if (x in 2 until moved.width - 4 && y in 2 until moved.height - 2) {
                sum += moved.cfa[i] - plain.cfa[i]; n++
            }
        }
        assertEquals(0.1, sum / n, 0.001)
        // The +2 px shift pushes right-edge sites out of bounds: the OOB
        // counter must be non-zero exactly there, corroborating the direction.
        assertTrue(moved.oob.sum() > 0)
        assertTrue(plain.oob.sum() == 0)
    }

    @Test fun oobFlowFallsBackToReferenceOnly() {
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.3f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.9f }, dx = 100f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.oob.sum() > 0)
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        assertArrayEquals(alone.cfa, result.cfa, 0f)
    }

    @Test fun noSupportFallsBackFlagged() {
        val nan = FloatArray(48) { Float.NaN }
        val result = MosaicSrReconstructor.reconstruct(
            frame(8, 6, BayerPattern.RGGB, nan),
            listOf(frame(8, 6, BayerPattern.RGGB, nan))
        )
        assertTrue(result.fallback.all { it })
        assertTrue(result.cfa.all { it == 0f })
    }

    @Test fun rcAccumulatesOverMovingFrames() {
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.5f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.5f })
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.rc.values.all { it == 1f })
        val refOnly = MosaicSrReconstructor.reconstruct(ref, listOf(mov), referenceOnly = true)
        assertTrue(refOnly.rc.values.all { it == 0f })
    }

    @Test fun cancelAbortsReconstruction() {
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.5f })
        assertThrows(CancellationException::class.java) {
            MosaicSrReconstructor.reconstruct(ref, listOf(ref), isCancelled = { true })
        }
    }

    // ---- helpers (validated-model inputs, synthetic values) ----

    // ---- streaming (memory-bound production path) ----

    @Test fun streamingMatchesListExactly() {
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.6f }, dx = 1f)
        val expected = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val dir = java.nio.file.Files.createTempDirectory("mosaic-stream").toFile()
        try {
            val actual = MosaicSrReconstructor.reconstructStreaming(
                geometryOf(ref), listOf(mov).asSequence(), { ref }, tempDir = dir
            )
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            assertEquals(expected.pattern, actual.pattern)
            assertEquals(1, actual.acceptedFrames)
            // Same math, same order, same double ops: bitwise identical.
            assertArrayEquals(expected.cfa, actual.cfa, 0f)
            assertTrue(dir.listFiles()?.isEmpty() == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun fallbackTakesBurstNearestAverageEagerAndStreaming() {
        // Absurdly sharp kernels (P = 1e12): every tap weight underflows to
        // zero, so the kernel denominators vanish and every site falls back.
        // Nearest needs no kernel: ref 0.4 + moving 0.6 (r = 1) average to
        // 0.5, where reference-only would give 0.4. Eager and streaming must
        // agree exactly.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f }, precision = 1e12f)
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.6f }, dx = 0.35f, precision = 1e12f)
        val expected = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(expected.cfa.all { abs(it - 0.5f) < 1e-6f })
        val dir = java.nio.file.Files.createTempDirectory("mosaic-stream-near").toFile()
        try {
            val actual = MosaicSrReconstructor.reconstructStreaming(
                geometryOf(ref), listOf(mov).asSequence(), { ref }, tempDir = dir
            )
            assertArrayEquals(expected.cfa, actual.cfa, 0f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun lowSupportOverwriteRestoresSharpEdgeAndSetsMask() {
        // Ghost occlusion with nonzero support: the moving frame shows the
        // inverse step at zero shift (aligned by flow, conflicting in
        // content) with uniform r = 0.3, so rc = 0.3 < MIN_SUPPORT while den
        // stays above eps everywhere (the reference contributes). The
        // overwrite routes every site to the reference-kernel fallback —
        // the smooth, honest single-frame mean — never to a kernel-mush
        // blend of the conflicting step, and never to single-tap nearest
        // picks that checker between the two scenes on sparse sites.
        // Pin: unsupported-everywhere output equals the reference-only
        // output exactly (same quotient, same path).
        val (ref, mov) = inverseStepScene(0.3f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        assertTrue(result.fallback.all { it })
        assertTrue(alone.fallback.none { it })
        assertArrayEquals(alone.cfa, result.cfa, 0f)
    }

    @Test fun streamingOverwriteMatchesEagerExactly() {
        // Same ghost scene through the memory-bound path: the mapped rc
        // accumulator must drive the identical overwrite, bitwise.
        val (ref, mov) = inverseStepScene(0.3f)
        val expected = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val dir = java.nio.file.Files.createTempDirectory("mosaic-stream-ov").toFile()
        try {
            val actual = MosaicSrReconstructor.reconstructStreaming(
                geometryOf(ref), listOf(mov).asSequence(), { ref }, tempDir = dir
            )
            assertEquals(1, actual.acceptedFrames)
            assertArrayEquals(expected.cfa, actual.cfa, 0f)
            assertTrue(dir.listFiles()?.isEmpty() == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun supportThresholdABDiscriminatingBandIsGhosty() {
        // Threshold A/B fixture: r = 0.7 sits in the discriminating band
        // [0.5, 1.0) — merged at MIN_SUPPORT 0.5, overwritten at a
        // hypothetical 1.0. (Stacker's own 1.0 is a self-test-harness literal
        // over a per-pixel quantity, not a production threshold over Rc —
        // see merge contract §9 — so the comparison here is against 1.0 as a
        // number, not as Stacker's rule.) Pins what 1.0 would rescue: the
        // kernel mush magnitude in the 0.5 output. (Cost side — extra fallback
        // fraction on real scenes — is measured from device rc textures in
        // the runlog, not here.)
        val (ref, mov) = inverseStepScene(0.7f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.rc.values.all { it == 0.7f })
        assertTrue(result.fallback.none { it })
        var worst = 0.0
        for (v in result.cfa) {
            worst = maxOf(worst, sharpLevels(0.7f).minOf { abs(it - v.toDouble()) })
        }
        // Measured 0.053 on the pre-overwrite code: what MIN_SUPPORT 1.0
        // would rescue. Threshold keeps margin for kernel evolutions while
        // still pinning a genuine ghost (5%+ deviation).
        assertTrue("expected kernel ghost mush, worst=$worst", worst > 0.03)
    }

    @Test fun tileBorderFlowBlendsSmoothlyWithoutQuiltStep() {
        // Mirror of the RGB quilt test (flowTransitionBlendsAcrossTileBorder)
        // for the mosaic path: alternating tile columns shift 0 vs 1 quad px
        // (tileSize 4) on a monotonic ramp kept below the saturation guard.
        // The ramp is linear, so any blended shift lands strictly between
        // the uniform-flow outcomes; nearest-tile lookup returns an endpoint
        // exactly at border sites (the quilt step). BEFORE (flowAt): border
        // sites equal an endpoint; AFTER (flowAtSmooth): strictly interior.
        val w = 32; val h = 24
        fun ramp() = FloatArray(w * h) { i -> (i % w).toFloat() / 40f }
        fun movingWith(flow: RawSrAlignmentField): RawSrBayerMerge.MergeFrame {
            val quadsW = w / 2; val quadsH = h / 2
            // Peaked precision (like the RGB quilt test's K = 1.0): kernel
            // weights must respond to subpixel shifts, otherwise flat
            // weights round every blend to float32-identical endpoints.
            return RawSrBayerMerge.MergeFrame(w, h, ramp(), BayerPattern.RGGB, 0, 0,
                RawSrKernelCovariance.MatrixField(
                    quadsW, quadsH, FloatArray(quadsW * quadsH * 4) { 1.0f }),
                flow, RawSrRobustness.FrameRobustness(
                    quadsW, quadsH, FloatArray(quadsW * quadsH) { 1f }, IntArray(quadsW * quadsH)))
        }
        fun tiledFlow(dxAt: (tx: Int, ty: Int) -> Float): RawSrAlignmentField {
            val tileSize = 4
            val columns = (w / 2 + tileSize - 1) / tileSize
            val rows = (h / 2 + tileSize - 1) / tileSize
            val tiles = List(columns * rows) { i ->
                RawSrTileFlow(0f, 0f, dxAt(i % columns, i / columns), 0f, 0f, true)
            }
            return RawSrAlignmentField(w / 2, h / 2, tileSize, columns, rows, tiles)
        }
        val ref = RawSrBayerMerge.MergeFrame(w, h, ramp(), BayerPattern.RGGB, 0, 0,
            RawSrKernelCovariance.MatrixField(
                w / 2, h / 2, FloatArray(w / 2 * h / 2 * 4) { 1.0f }), null, null)
        val mixed = MosaicSrReconstructor.reconstruct(
            ref, listOf(movingWith(tiledFlow { tx, _ -> if (tx % 2 == 0) 0f else 1f })))
        val outA = MosaicSrReconstructor.reconstruct(
            ref, listOf(movingWith(tiledFlow { _, _ -> 0f })))
        val outB = MosaicSrReconstructor.reconstruct(
            ref, listOf(movingWith(tiledFlow { _, _ -> 1f })))
        assertTrue(mixed.fallback.none { it })
        // Only GREEN sites gate the blend: their 3x3 windows hold several
        // same-colour taps, so a fractional shift rebalances weights
        // continuously. RED/BLUE windows often hold a single tap, where any
        // method returns an endpoint by sampling physics (same reason the
        // RGB quilt test gates one hand-picked GREEN site, not a sweep).
        var clamped = 0
        var blended = 0
        for (y in 4 until mixed.height - 4) for (x in 4 until mixed.width - 4) {
            if (mixed.pattern.colorAt(x, y) != CfaColor.GREEN) continue
            val quadX = (((x + 0.5) / MosaicSrReconstructor.LINEAR_SCALE) / 2.0).toInt()
            val quadY = (((y + 0.5) / MosaicSrReconstructor.LINEAR_SCALE) / 2.0).toInt()
            if (quadY < 2 || quadX > w / 2 - 3 || quadY > h / 2 - 3) continue
            val v = mixed.cfa[y * mixed.width + x].toDouble()
            val a = outA.cfa[y * outA.width + x].toDouble()
            val b = outB.cfa[y * outB.width + x].toDouble()
            if (quadX == 1) {
                // Edge-clamped: corner collapse returns tile 0 exactly, the
                // identical computation to uniform A — bitwise, like the RGB
                // test's Quad 1 gate.
                assertEquals("clamped ($x,$y)", a, v, 0.0)
                clamped++
            } else if (quadX == 2) {
                // Blend zone (0.875/0.125): strictly between the uniforms.
                // Nearest-tile flow returns an endpoint bitwise here.
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                assertTrue("quilt endpoint at ($x,$y) v=$v in [$lo,$hi]",
                    v > lo + 1e-4 && v < hi - 1e-4)
                blended++
            }
        }
        assertTrue("no clamped GREEN sites", clamped > 0)
        assertTrue("no blend-zone GREEN sites", blended > 0)
    }

    @Test fun streamingFallsBackToReferenceWhenNothingSupported() {
        // Poisoned reference (NaN) + fully out-of-bounds moving frame: no tap
        // lands anywhere, so every site takes the reference-only value.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { Float.NaN })
        val oob = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.9f }, dx = 100f)
        val expected = MosaicSrReconstructor.reconstruct(ref, listOf(oob))
        assertTrue(expected.fallback.all { it })
        val dir = java.nio.file.Files.createTempDirectory("mosaic-stream-fb").toFile()
        try {
            val actual = MosaicSrReconstructor.reconstructStreaming(
                geometryOf(ref), listOf(oob).asSequence(), { ref }, tempDir = dir
            )
            assertEquals(1, actual.acceptedFrames)
            assertArrayEquals(expected.cfa, actual.cfa, 0f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun streamingThrowsWhenNothingSurvives() {
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val dir = java.nio.file.Files.createTempDirectory("mosaic-stream-empty").toFile()
        try {
            assertThrows(MergeUnavailableException::class.java) {
                MosaicSrReconstructor.reconstructStreaming(
                    geometryOf(ref), emptySequence(), { ref }, tempDir = dir
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // Ghost-occlusion scene for the support-overwrite tests: the moving
    // frame shows the inverse step at zero shift with uniform robustness r.
    private fun inverseStepScene(r: Float): Pair<RawSrBayerMerge.MergeFrame, RawSrBayerMerge.MergeFrame> {
        val ref = frame(8, 6, BayerPattern.RGGB,
            FloatArray(48) { i -> if (i % 8 < 4) 0.2f else 0.8f })
        val mov = frame(8, 6, BayerPattern.RGGB,
            FloatArray(48) { i -> if (i % 8 < 4) 0.8f else 0.2f }, r = r)
        return ref to mov
    }

    // The two exact nearest-blend levels for the inverse-step scene: both
    // frames pick the same tap geometry at zero shift, so every site blends
    // an inverse pair — a sharp compressed step, never a kernel-mush
    // intermediate. Levels use the float-valued inputs (0.2f/0.8f are not
    // exact decimals); the accumulation order matches the finalizer, so the
    // only slack is the Float32 cfa store (~4e-8 here) — 1e-7 keeps three
    // orders of margin against kernel-mush distances (~0.1).
    private fun sharpLevels(r: Float): DoubleArray {
        val lo = 0.2f.toDouble()
        val hi = 0.8f.toDouble()
        val rd = r.toDouble()
        return doubleArrayOf((rd * hi + lo) / (rd + 1.0), (rd * lo + hi) / (rd + 1.0))
    }

    private fun geometryOf(frame: RawSrBayerMerge.MergeFrame) =
        RawSrMergeJob.ReferenceGeometry(
            frame.width, frame.height, frame.sensorPattern,
            frame.sensorLeft, frame.sensorTop
        )

    private fun frame(
        w: Int, h: Int,
        pattern: BayerPattern,
        samples: FloatArray,
        dx: Float = 0f, dy: Float = 0f, r: Float = 1f,
        left: Int = 0, top: Int = 0,
        precision: Float = 1e-6f
    ): RawSrBayerMerge.MergeFrame {
        val quadsW = w / 2; val quadsH = h / 2
        // Near-zero precision: every same-colour tap in the 3x3 window weighs ~1.
        val precision = RawSrKernelCovariance.MatrixField(
            quadsW, quadsH, FloatArray(quadsW * quadsH * 4) { precision }
        )
        val tiles = List(quadsW * quadsH) { RawSrTileFlow(0f, 0f, dx, dy, 0f, true) }
        val flow = RawSrAlignmentField(quadsW, quadsH, 1, quadsW, quadsH, tiles)
        val robustness = RawSrRobustness.FrameRobustness(
            quadsW, quadsH, FloatArray(quadsW * quadsH) { r }, IntArray(quadsW * quadsH)
        )
        return RawSrBayerMerge.MergeFrame(
            width = w, height = h, samples = samples,
            sensorPattern = pattern.shifted(left, top), sensorLeft = left, sensorTop = top,
            precision = precision, flow = flow, robustness = robustness
        )
    }

    // ---- narrow-axis clamp ----

    @Test fun clampWidensSharpIsotropicTexel() {
        // σ=0.25 both axes (P=16I) -> σ=0.5 (P=4I) at the default floor.
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(16f, 0f, 0f, 16f))
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        assertEquals(4f, out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(4f, out[3], 1e-5f)
    }

    @Test fun clampWidensOnlyNarrowAxisOfEdgeTexel() {
        // Edge-like P=diag(0.89, 50): σx≈1.06 untouched, σy≈0.14 -> 0.5.
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(0.89f, 0f, 0f, 50f))
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        assertEquals(0.89f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(4f, out[3], 1e-4f)
    }

    @Test fun clampPreservesOrientationOfRotatedTexel() {
        // Rotated narrow kernel: minor σ 0.2 at 30° -> 0.5 at 30°.
        val c = kotlin.math.cos(kotlin.math.PI / 6)
        val s = kotlin.math.sin(kotlin.math.PI / 6)
        // Σ = 1.0*e1e1ᵀ + 0.04*e2e2ᵀ with e2 at 30°; P = Σ^-1.
        val e1x = -s; val e1y = c; val e2x = c; val e2y = s
        val t00 = 1.0 * e1x * e1x + 0.04 * e2x * e2x
        val t01 = 1.0 * e1x * e1y + 0.04 * e2x * e2y
        val t11 = 1.0 * e1y * e1y + 0.04 * e2y * e2y
        val det = t00 * t11 - t01 * t01
        val field = RawSrKernelCovariance.MatrixField(
            1, 1, floatArrayOf((t11 / det).toFloat(), (-t01 / det).toFloat(),
                (-t01 / det).toFloat(), (t00 / det).toFloat())
        )
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        // Minor axis still at 30°: P'*e2 is parallel to e2.
        val qx = out[0] * e2x + out[1] * e2y
        val qy = out[2] * e2x + out[3] * e2y
        val cross = abs(qx * e2y - qy * e2x)
        val norm = kotlin.math.sqrt(qx * qx + qy * qy)
        assertEquals(0.0, cross / norm, 1e-5)
        // Minor σ is 0.5: e2ᵀP'e2 = 1/0.25.
        val q = out[0] * e2x * e2x + 2 * out[1] * e2x * e2y + out[3] * e2y * e2y
        assertEquals(4.0, q, 1e-4)
    }

    @Test fun clampLeavesWideTexelAlone() {
        // σ=(1.06, 0.6): roundtrip P->Σ->P' within float tolerance.
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(0.89f, 0f, 0f, 2.78f))
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        assertEquals(0.89f, out[0], 1e-5f)
        assertEquals(2.78f, out[3], 1e-5f)
    }

    @Test fun clampRepairsDegenerateTexel() {
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(1f, 1f, 1f, 1f))
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        assertEquals(4f, out[0], 1e-6f)
        assertEquals(0f, out[1], 0f)
        assertEquals(0f, out[2], 0f)
        assertEquals(4f, out[3], 1e-6f)
    }

    @Test fun clampRejectsNonPositiveFloor() {
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(1f, 0f, 0f, 1f))
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrReconstructor.clampMinorAxis(field, 0.0)
        }
    }

    @Test fun clampInPlaceMatchesAllocatingBitwise() {
        // Allocating clamp delegates to the in-place core: identical inputs
        // must give bitwise-identical outputs across sharp, edge-like,
        // rotated, wide, and degenerate texels.
        val texels = floatArrayOf(
            16f, 0f, 0f, 16f,
            0.89f, 0f, 0f, 50f,
            3f, 1.5f, 1.5f, 8f,
            0.89f, 0f, 0f, 2.78f,
            1f, 1f, 1f, 1f
        )
        val field = RawSrKernelCovariance.MatrixField(5, 1, texels.copyOf())
        val expected = MosaicSrReconstructor.clampMinorAxis(field).values
        val actual = texels.copyOf()
        MosaicSrReconstructor.clampMinorAxisInPlace(actual)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("texel ${i / 4} lane ${i % 4}", expected[i], actual[i], 0f)
        }
    }

    @Test fun clampInPlaceRejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrReconstructor.clampMinorAxisInPlace(floatArrayOf(1f, 0f, 0f, 1f), 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrReconstructor.clampMinorAxisInPlace(FloatArray(6))
        }
    }
}
