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
        // A fully-dead frame (r = 0) accumulates nowhere (reference Alg. 4:
        // `w*r` weights vanish). The reference defines no support overwrite,
        // so no fallback trips: the output equals the reference-only image.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.9f }, r = 0f)
        val withDead = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        assertTrue(withDead.fallback.none { it })
        assertTrue(alone.fallback.none { it })
        assertArrayEquals(alone.cfa, withDead.cfa, 1e-6f)
    }

    @Test fun lowSupportBlendsInsteadOfOverwriting() {
        // Rc = 0.1 with live reference support: the reference defines no
        // support overwrite, so the site resolves through the plain kernel
        // blend ((0.4 + 0.6*0.1)/1.1 ≈ 0.41818), never through a
        // reference-only overwrite (0.4), and no fallback trips.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f })
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.6f }, r = 0.1f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.fallback.none { it })
        for (v in result.cfa) assertEquals(0.4181818f, v, 1e-6f)
    }

    @Test fun motionDiscontinuityMergesWithoutSkip() {
        // Tile columns disagreeing by 5 quads (0 vs +10 raw px). Reference
        // Alg. 4 defines no motion-edge stop: each site merges under its own
        // nearest tile's flow. The step scene (dark left of x = 24, bright
        // right) makes the two tiles disagree in content: tile-0 sites
        // resolve like the reference-only image while tile-1 interior sites
        // pull bright shifted taps and differ — proving the moving frame
        // merged on both sides of the discontinuity instead of skipping.
        val w = 32
        val h = 32
        val qw = w / 2
        val qh = h / 2
        val samples = FloatArray(w * h) { i -> if (i % w < 24) 0.2f else 0.8f }
        fun flowOf(flowAt: (tx: Int, ty: Int) -> Pair<Float, Float>): RawSrAlignmentField {
            val tiles = List(4) { i ->
                val (dx, dy) = flowAt(i % 2, i / 2)
                RawSrTileFlow(0f, 0f, dx, dy, 0f, true)
            }
            return RawSrAlignmentField(qw, qh, 8, 2, 2, tiles)
        }
        fun mframe(flow: RawSrAlignmentField?, r: Float): RawSrBayerMerge.MergeFrame {
            val covariance = RawSrKernelCovariance.MatrixField(
                qw, qh, FloatArray(qw * qh * 4) { i ->
                    if (i % 4 == 0 || i % 4 == 3) 1e6f else 0f
                })
            val robustness = if (flow == null) null else
                RawSrRobustness.FrameRobustness(qw, qh, FloatArray(qw * qh) { r }, IntArray(qw * qh))
            return RawSrBayerMerge.MergeFrame(
                w, h, samples, BayerPattern.RGGB, 0, 0, covariance, flow, robustness)
        }
        val ref = mframe(null, 1f)
        val mov = mframe(flowOf { tx, _ -> if (tx == 0) 0f to 0f else 5f to 0f }, 1f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        var same = 0
        var moved = 0
        for (y in 0 until result.height) for (x in 0 until result.width) {
            val v = result.cfa[y * result.width + x]
            assertTrue("cfa=$v", v.isFinite())
            val baseX = (x + 0.5) / MosaicSrReconstructor.LINEAR_SCALE
            val a = alone.cfa[y * alone.width + x]
            // Tile 0 interior (flow 0): identical computation to
            // reference-only up to float summation order.
            if (baseX in 4.0..12.0) {
                assertEquals("tile-0 site ($x,$y)", a, v, 1e-6f)
                same++
            } else if (baseX in 16.5..20.5) {
                // Tile 1 interior (flow +10 px, in bounds): shifted taps
                // read the bright side while tile 0 would read dark.
                assertTrue("tile-1 site ($x,$y) v=$v a=$a did not move", abs(v - a) > 1e-3f)
                moved++
            }
        }
        assertTrue("no tile-0 sites", same > 0)
        assertTrue("no tile-1 sites", moved > 0)
    }

    @Test fun blindReferenceMergesMovingUncensored() {
        // Reference kernel blind (NaN covariance: interpolation returns null,
        // the reference contributes nothing) with a near-white moving frame
        // carrying one clean 0.5 tap. Reference Alg. 4 defines no tap censor:
        // every finite moving tap merges, so sites resolve to the moving
        // window mean — near-white far from the clean tap, dipping toward it
        // nearby — never to the blind reference level (0.4) or to zero.
        val refSamples = FloatArray(48) { 0.4f }
        val ref = frame(8, 6, BayerPattern.RGGB, refSamples, covariance = Float.NaN)
        val movSamples = FloatArray(48) { 0.999f }
        movSamples[3 * 8 + 4] = 0.5f
        val mov = frame(8, 6, BayerPattern.RGGB, movSamples, r = 0.1f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.fallback.none { it })
        var sum = 0.0
        for (v in result.cfa) {
            assertTrue("cfa=$v", v.isFinite())
            assertTrue("cfa=$v outside moving window range", v >= 0.7f && v <= 1.0f)
            sum += v
        }
        assertTrue("mean=${sum / result.cfa.size} not white-dominated", sum / result.cfa.size > 0.9)
    }

    @Test fun chromaLevelsReproducePerColourEagerAndStreaming() {
        // Reference Alg. 4 defines no highlight neutralization: per-colour
        // levels merge as-is through the same-colour gate. The phase/origin
        // sweep keeps pinning crop-relative routing (green at full white must
        // not leak into red/blue sites), and eager/streaming must agree.
        val dir = java.nio.file.Files.createTempDirectory("mosaic-chroma").toFile()
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
                val mov = frame(8, 6, pattern, samples, left = left, top = top, r = 0f)
                val eager = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
                val stream = MosaicSrReconstructor.reconstructStreaming(
                    geometryOf(ref), sequenceOf(mov), { ref }, tempDir = dir)
                assertArrayEquals(eager.cfa, stream.cfa, 0f)
                for (y in 0 until eager.height) for (x in 0 until eager.width) {
                    val expected = when (eager.pattern.colorAt(x, y)) {
                        CfaColor.RED -> 0.74f
                        CfaColor.GREEN -> 1f
                        CfaColor.BLUE -> 0.30f
                    }
                    assertEquals("$pattern origin=$left,$top site=$x,$y", expected,
                        eager.cfa[y * eager.width + x], 1e-6f)
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

    @Test fun sharpKernelsDivideToZeroEagerAndStreaming() {
        // Absurdly sharp kernels (covariance 1e-12, precision 1e12): every
        // tap weight underflows to zero, so the denominators vanish and
        // every site divides to 0 with the fallback flag set — the reference
        // NaN-at-zero-support blacked downstream. Eager and streaming must
        // agree exactly.
        val ref = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.4f }, covariance = 1e-12f)
        val mov = frame(8, 6, BayerPattern.RGGB, FloatArray(48) { 0.6f }, dx = 0.35f, covariance = 1e-12f)
        val expected = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(expected.fallback.all { it })
        assertTrue(expected.cfa.all { it == 0f })
        assertTrue(expected.weight.all { it == 0f })
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

    @Test fun lowSupportBlendsConflictingStepWithoutOverwrite() {
        // Ghost occlusion with nonzero support: the moving frame shows the
        // inverse step at zero shift (aligned by flow, conflicting in
        // content) with uniform r = 0.3. The reference defines no support
        // overwrite, so every site takes the plain kernel blend of the two
        // scenes — the moving frame visibly contributes (output differs from
        // reference-only) — and no fallback trips.
        val (ref, mov) = inverseStepScene(0.3f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        val alone = MosaicSrReconstructor.reconstruct(ref, emptyList())
        assertTrue(result.fallback.none { it })
        assertTrue(result.rc.values.all { it == 0.3f })
        var differed = 0
        for (i in result.cfa.indices) {
            assertTrue("cfa=${result.cfa[i]}", result.cfa[i].isFinite())
            if (abs(result.cfa[i] - alone.cfa[i]) > 1e-6f) differed++
        }
        assertTrue("moving frame contributed nowhere", differed > 0)
    }

    @Test fun streamingOverwriteMatchesEagerExactly() {
        // Same ghost scene through the memory-bound path: the mapped
        // accumulators must reproduce the identical blend, bitwise.
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
        // Ghost-magnitude pin: the inverse-step scene at r = 0.7 blends
        // through the plain kernel quotient (the reference defines no
        // support overwrite), so step-boundary windows straddling the
        // conflict resolve to kernel-mush intermediates far from either
        // sharp level. Pins that the unprotected blend really ghosts (5%+
        // deviation) — the artifact a robustness gate would have to rescue.
        val (ref, mov) = inverseStepScene(0.7f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(mov))
        assertTrue(result.rc.values.all { it == 0.7f })
        assertTrue(result.fallback.none { it })
        var worst = 0.0
        for (v in result.cfa) {
            worst = maxOf(worst, sharpLevels(0.7f).minOf { abs(it - v.toDouble()) })
        }
        // Threshold keeps margin for kernel evolutions while still
        // pinning a genuine ghost (5%+ deviation from both sharp levels).
        assertTrue("expected kernel ghost mush, worst=$worst", worst > 0.03)
    }

    @Test fun tileBorderFlowUsesNearestTileQuiltStep() {
        // Mirror of the RGB quilt test (flowTransitionBlendsAcrossTileBorder)
        // for the mosaic path: alternating tile columns shift 0 vs 1 quad px
        // (tileSize 4) on a monotonic ramp. Reference Alg. 4 looks the flow
        // up at the nearest tile (`int(lr//tile_size)`), so sites on either
        // side of a tile border resolve to the uniform-flow endpoint of
        // their own tile bitwise — the quilt step. A smoothing lookup would
        // land strictly between the endpoints at border sites instead.
        val w = 32; val h = 24
        fun ramp() = FloatArray(w * h) { i -> (i % w).toFloat() / 40f }
        fun movingWith(flow: RawSrAlignmentField): RawSrBayerMerge.MergeFrame {
            val quadsW = w / 2; val quadsH = h / 2
            // Unit isotropic covariance (like the RGB quilt test's K = 1.0):
            // kernel weights must respond to subpixel shifts, otherwise
            // flat weights round every outcome to float32-identical values.
            return RawSrBayerMerge.MergeFrame(w, h, ramp(), BayerPattern.RGGB, 0, 0,
                RawSrKernelCovariance.MatrixField(
                    quadsW, quadsH, FloatArray(quadsW * quadsH * 4) { i ->
                        if (i % 4 == 0 || i % 4 == 3) 1.0f else 0f
                    }),
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
                w / 2, h / 2, FloatArray(w / 2 * h / 2 * 4) { i ->
                    if (i % 4 == 0 || i % 4 == 3) 1.0f else 0f
                }), null, null)
        val mixed = MosaicSrReconstructor.reconstruct(
            ref, listOf(movingWith(tiledFlow { tx, _ -> if (tx % 2 == 0) 0f else 1f })))
        val outA = MosaicSrReconstructor.reconstruct(
            ref, listOf(movingWith(tiledFlow { _, _ -> 0f })))
        val outB = MosaicSrReconstructor.reconstruct(
            ref, listOf(movingWith(tiledFlow { _, _ -> 1f })))
        assertTrue(mixed.fallback.none { it })
        // Only GREEN sites gate the quilt: their 3x3 windows hold several
        // same-colour taps, so the endpoint values genuinely differ between
        // the two shifts (RED/BLUE windows often hold a single tap, where
        // any method returns an endpoint by sampling physics).
        var leftOfStep = 0
        var rightOfStep = 0
        for (y in 4 until mixed.height - 4) for (x in 4 until mixed.width - 4) {
            if (mixed.pattern.colorAt(x, y) != CfaColor.GREEN) continue
            val quadX = (((x + 0.5) / MosaicSrReconstructor.LINEAR_SCALE) / 2.0).toInt()
            val quadY = (((y + 0.5) / MosaicSrReconstructor.LINEAR_SCALE) / 2.0).toInt()
            if (quadY < 2 || quadX > w / 2 - 3 || quadY > h / 2 - 3) continue
            val v = mixed.cfa[y * mixed.width + x].toDouble()
            val a = outA.cfa[y * outA.width + x].toDouble()
            val b = outB.cfa[y * outB.width + x].toDouble()
            // Tile border between quadX 3 (tile 0, flow 0) and quadX 4
            // (tile 1, flow 1): nearest-tile lookup resolves each side to
            // its own tile's uniform outcome — the identical computation,
            // bitwise. A smoothing lookup would land strictly between.
            if (quadX == 3) {
                assertEquals("left of step ($x,$y)", a, v, 0.0)
                leftOfStep++
            } else if (quadX == 4) {
                assertEquals("right of step ($x,$y)", b, v, 0.0)
                rightOfStep++
            }
        }
        assertTrue("no left-of-step GREEN sites", leftOfStep > 0)
        assertTrue("no right-of-step GREEN sites", rightOfStep > 0)
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
        covariance: Float = 1e6f
    ): RawSrBayerMerge.MergeFrame {
        val quadsW = w / 2; val quadsH = h / 2
        // Wide isotropic covariance (reference Alg. 4: the merge interpolates
        // covariances and inverts per pixel): every same-colour tap in the 3x3
        // window weighs ~1. NaN blinds the kernel (interpolation returns null,
        // the frame contributes nothing); tiny values sharpen it.
        val covarianceField = RawSrKernelCovariance.MatrixField(
            quadsW, quadsH, FloatArray(quadsW * quadsH * 4) { i ->
                if (covariance.isNaN()) Float.NaN else if (i % 4 == 0 || i % 4 == 3) covariance else 0f
            }
        )
        val tiles = List(quadsW * quadsH) { RawSrTileFlow(0f, 0f, dx, dy, 0f, true) }
        val flow = RawSrAlignmentField(quadsW, quadsH, 1, quadsW, quadsH, tiles)
        val robustness = RawSrRobustness.FrameRobustness(
            quadsW, quadsH, FloatArray(quadsW * quadsH) { r }, IntArray(quadsW * quadsH)
        )
        return RawSrBayerMerge.MergeFrame(
            width = w, height = h, samples = samples,
            sensorPattern = pattern.shifted(left, top), sensorLeft = left, sensorTop = top,
            covariance = covarianceField, flow = flow, robustness = robustness
        )
    }

    // ---- narrow-axis clamp ----

    @Test fun clampWidensSharpIsotropicTexel() {
        // σ=0.25 both axes (P=16I) -> σ=0.3 (P=1/0.09) at the default floor.
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(16f, 0f, 0f, 16f))
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        assertEquals((1.0 / (0.3 * 0.3)).toFloat(), out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals((1.0 / (0.3 * 0.3)).toFloat(), out[3], 1e-4f)
    }

    @Test fun clampWidensOnlyNarrowAxisOfEdgeTexel() {
        // Edge-like P=diag(0.89, 50): σx≈1.06 untouched, σy≈0.14 -> 0.3.
        val field = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(0.89f, 0f, 0f, 50f))
        val out = MosaicSrReconstructor.clampMinorAxis(field).values
        assertEquals(0.89f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals((1.0 / (0.3 * 0.3)).toFloat(), out[3], 1e-3f)
    }

    @Test fun clampPreservesOrientationOfRotatedTexel() {
        // Rotated narrow kernel: minor σ 0.2 at 30° -> 0.3 at 30°.
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
        // Minor σ is 0.3: e2ᵀP'e2 = 1/0.09.
        val q = out[0] * e2x * e2x + 2 * out[1] * e2x * e2y + out[3] * e2y * e2y
        assertEquals(1.0 / 0.09, q, 1e-3)
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
        val f = (1.0 / (0.3 * 0.3)).toFloat()
        assertEquals(f, out[0], 1e-4f)
        assertEquals(0f, out[1], 0f)
        assertEquals(0f, out[2], 0f)
        assertEquals(f, out[3], 1e-4f)
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
