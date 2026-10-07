package com.matthew.rawlens

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class RawSrMergeJobTest {
    // ---- decisions: the failure table ----

    @Test fun mergeDecisionIsReferenceFirstWithRejectedCount() {
        // Reference sits mid-list; the merge order must lead with it exactly once.
        val decision = RawSrMergeDecisions.decide(listOf(0, 1, 2), 1, 1, 4)
        assertEquals(RawSrMergeDecisions.Mode.MERGE, decision.mode)
        assertEquals(1, decision.referenceIndex)
        assertEquals(listOf(1, 0, 2), decision.mergeIndices)
        assertEquals(1, decision.rejectedCount)
        assertNull(decision.fallbackReason)
    }

    @Test fun incompatibleFramesRejectAndContinueWhenTwoRemain() {
        // Planner rejected indices 1 (dimensions) and 3 (CFA); the decision
        // merges the surviving pair and reports both rejections.
        val decision = RawSrMergeDecisions.decide(listOf(0, 2), 2, 0, 4)
        assertEquals(RawSrMergeDecisions.Mode.MERGE, decision.mode)
        assertEquals(listOf(0, 2), decision.mergeIndices)
        assertEquals(2, decision.rejectedCount)
    }

    @Test fun fewerThanTwoValidFramesFallsBackTruthfully() {
        val none = RawSrMergeDecisions.decide(emptyList(), 0, null, 4)
        assertEquals(RawSrMergeDecisions.Mode.REFERENCE_FALLBACK, none.mode)
        assertEquals(RawSrMergeDecisions.FallbackReason.NO_ELIGIBLE_FRAMES, none.fallbackReason)
        assertEquals(
            "RAW SR FALLBACK • NO ELIGIBLE FRAMES",
            RawSrMergeDecisions.fallbackStatus(4, none.fallbackReason!!)
        )
        val one = RawSrMergeDecisions.decide(listOf(1), 2, 1, 3)
        assertEquals(RawSrMergeDecisions.Mode.REFERENCE_FALLBACK, one.mode)
        assertEquals(RawSrMergeDecisions.FallbackReason.INSUFFICIENT_FRAMES, one.fallbackReason)
        assertEquals(
            "RAW SR ×3 • REFERENCE FALLBACK",
            RawSrMergeDecisions.fallbackStatus(3, one.fallbackReason!!)
        )
    }

    @Test fun mergeStatusNamesCountsAndDngMode() {
        assertEquals(
            "RAW SR ×8 • LINEAR",
            RawSrMergeDecisions.mergeStatus(8, 0, RawSrDngMode.LINEAR_RGB)
        )
        assertEquals(
            "RAW SR ×6 • MOSAIC • REJECTED 2",
            RawSrMergeDecisions.mergeStatus(6, 2, RawSrDngMode.MOSAIC_SR)
        )
    }

    // ---- flow resampling ----

    @Test fun upsampledFlowMatchesQuadGridExactly() {
        // Coarse field on the raw lattice (tileSize 2); each quad samples
        // its center in raw coordinates (2q+1).
        val coarse = RawSrAlignmentField(
            imageWidth = 8, imageHeight = 6, tileSize = 2, columns = 4, rows = 3,
            tiles = List(12) { i -> RawSrTileFlow(0f, 0f, i.toFloat(), -i.toFloat(), 0f, true) }
        )
        val quad = RawSrMergeJob.upsampleFlowToQuads(coarse, 4, 3)
        assertEquals(4, quad.imageWidth)
        assertEquals(3, quad.imageHeight)
        assertEquals(1, quad.tileSize)
        // Quad (3, 2) samples raw (7, 5): ux=3.25, uy=2.25 clamp to tile 11.
        val tile = quad.flowAt(3f, 2f)
        assertEquals(11f, tile.dx, 0f)
        assertEquals(-11f, tile.dy, 0f)
        // Quad (2, 2): ux=2.25 blends tiles 10,11 at 0.75/0.25, uy clamps
        // to row 2: 10*0.75 + 11*0.25 = 10.25.
        assertEquals(10.25f, quad.flowAt(2f, 2f).dx, 0f)
    }

    @Test fun upsampledFlowListContractHoldsOnCompactBacking() {
        // The quad grid is array-backed (no boxed tile per quad); the List
        // contract must still hold exactly: size, centers, values, iteration.
        val coarse = RawSrAlignmentField(
            imageWidth = 8, imageHeight = 6, tileSize = 2, columns = 4, rows = 3,
            tiles = List(12) { i -> RawSrTileFlow(0f, 0f, i.toFloat(), -i.toFloat(), 0.5f, i % 2 == 0) }
        )
        val quad = RawSrMergeJob.upsampleFlowToQuads(coarse, 4, 3)
        assertEquals(12, quad.tiles.size)
        val first = quad.tiles[0]
        assertEquals(0f, first.centerX, 0f)
        assertEquals(0f, first.centerY, 0f)
        // Quad (0,0): ux=uy=0.25 blends tiles 0,1,4,5 at
        // 0.5625/0.1875/0.1875/0.0625 = 1.25. Residual/reliability come
        // from the containing tile (0): 0.5 / true.
        assertEquals(1.25f, first.dx, 0f)
        assertEquals(0.5f, first.residual, 0f)
        assertTrue(first.reliable)
        val last = quad.tiles[11]
        assertEquals(3f, last.centerX, 0f)
        assertEquals(2f, last.centerY, 0f)
        assertEquals(11f, last.dx, 0f)
        assertEquals(-11f, last.dy, 0f)
        assertFalse(last.reliable)
        // Interior blend: quad (2, 1) at ux=2.25/uy=1.25 mixes tiles
        // 6,7,10,11 -> 7.25, where nearest lookup returned tile (2, 1) = 6.
        assertEquals(7.25f, quad.tiles[6].dx, 0f)
        assertEquals(12, quad.tiles.count())
        assertEquals(
            quad.tiles.filter { it.reliable }.size,
            quad.tiles.count { it.reliable }
        )
    }

    // ---- mosaic chain ----

    private val chainConfig = RawSrAlignmentConfig()

    @Test fun mosaicChainKeepsAlignedFramesAndRejectsBadOnes() {
        val ref = input(512, 512, textured = true)
        // Flat field: no reliable tile anywhere, rejected by the chain.
        val flat = input(512, 512, textured = false)
        // Wrong-size frame: align() rejects the dimension mismatch.
        val bad = input(32, 24, textured = true)
        val good = input(512, 512, textured = true)
        val chain = RawSrMergeJob.mosaicChain(listOf(ref, flat, bad, good), 0, chainConfig)
        assertEquals(listOf(3), chain.survivorIndices)
        assertEquals(1, chain.moving.size)
        val result = MosaicSrReconstructor.reconstruct(chain.reference, chain.moving)
        assertEquals(result.width * result.height, result.cfa.size)
        assertTrue(result.cfa.all { it.isFinite() })
        assertTrue(result.rc.values.any { it > 0f })
    }

    @Test fun mosaicChainAndStreamDefaultToTuningTileSize() {
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        // Fixture profile and brightness put SNR at the 30 clip: 16 raw px.
        val chain = RawSrMergeJob.mosaicChain(listOf(ref, good), 0)
        assertEquals(16, chain.tuning.rawTileSize)
        assertEquals(16, chain.alignmentTileSize)
        assertEquals(listOf(1), chain.survivorIndices)
        val stream = RawSrMergeJob.mosaicStream(listOf(ref, good), 0)
        assertEquals(16, stream.alignmentTileSize)
        assertEquals(chain.tuning, stream.tuning)
        // Explicit configs still win over tuning (low-SNR tuning would
        // resolve tile 64; the explicit 16 runs the chain instead).
        val explicit = RawSrMergeJob.mosaicChain(
            listOf(ref, good), 0, RawSrAlignmentConfig(tileSize = 16),
            tuningOverride = RawSrTuning.forSnr(6.0))
        assertEquals(16, explicit.alignmentTileSize)
    }

    @Test fun mosaicChainAndStreamHonorTuningOverride() {
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        val override = RawSrTuning.decoupledSharp()
        val chain = RawSrMergeJob.mosaicChain(
            listOf(ref, good), 0, null, tuningOverride = override)
        assertEquals(override, chain.tuning)
        // Null config derives the tile size from the override, not the scan.
        assertEquals(override.rawTileSize, chain.alignmentTileSize)
        assertEquals(16, chain.alignmentTileSize)
        assertEquals(listOf(1), chain.survivorIndices)
        val stream = RawSrMergeJob.mosaicStream(
            listOf(ref, good), 0, null, tuningOverride = override)
        assertEquals(override, stream.tuning)
        assertEquals(16, stream.alignmentTileSize)
    }

    @Test fun chainAndStreamCarryAutoSigmaRatioWhenEnabled() {
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        val saved = RawSrKernelNetAniso.enabled
        RawSrKernelNetAniso.enabled = true
        try {
            val chain = RawSrMergeJob.mosaicChain(listOf(ref, good), 0)
            assertNotNull(chain.noiseSigmaRatio)
            val stream = RawSrMergeJob.mosaicStream(listOf(ref, good), 0)
            assertNotNull(stream.noiseSigmaRatio)
            assertEquals(chain.noiseSigmaRatio!!, stream.noiseSigmaRatio!!, 0f)
        } finally {
            RawSrKernelNetAniso.enabled = saved
        }
    }

    @Test fun chainAndStreamOmitAutoSigmaRatioWhenDisabled() {
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        val saved = RawSrKernelNetAniso.enabled
        RawSrKernelNetAniso.enabled = false
        try {
            // Analytic opt-out (--no-kernelnet): no measurement, no ratio.
            val chain = RawSrMergeJob.mosaicChain(listOf(ref, good), 0)
            assertNull(chain.noiseSigmaRatio)
            val stream = RawSrMergeJob.mosaicStream(listOf(ref, good), 0)
            assertNull(stream.noiseSigmaRatio)
        } finally {
            RawSrKernelNetAniso.enabled = saved
        }
    }

    @Test fun streamFallsBackToAnalyticWithoutModel() {
        // No ncnn model in unit tests: enabled-but-not-ready must stay
        // exactly analytic (silent fallback), never crash, never half-swap.
        val saved = RawSrKernelNetAniso.enabled
        RawSrKernelNetAniso.enabled = true
        try {
            val ref = input(512, 512, textured = true)
            val good = input(512, 512, textured = true)
            val on = RawSrMergeJob.mosaicStream(listOf(ref, good), 0)
            val onFrames = on.frames.toList()
            val onRef = on.referenceFrame()
            RawSrKernelNetAniso.enabled = false
            val off = RawSrMergeJob.mosaicStream(listOf(ref, good), 0)
            val offFrames = off.frames.toList()
            val offRef = off.referenceFrame()
            assertEquals(offFrames.size, onFrames.size)
            onFrames.zip(offFrames).forEachIndexed { i, (a, b) ->
                assertArrayEquals("moving $i covariance",
                    b.covariance.values, a.covariance.values, 0f)
            }
            assertArrayEquals("reference covariance",
                offRef.covariance.values, onRef.covariance.values, 0f)
        } finally {
            RawSrKernelNetAniso.enabled = saved
        }
    }

    @Test fun streamSwapsEveryFrameThroughTestSeam() {
        // The seam stands in for model inference (unavailable in unit
        // tests): every yielded moving frame plus the reference must pass
        // through the swap with the stream's measured auto-sigma ratio.
        val saved = RawSrKernelNetAniso.enabled
        val savedSeam = RawSrMergeJob.kernelNetSwapForTest
        RawSrKernelNetAniso.enabled = true
        val ratios = mutableListOf<Float?>()
        try {
            RawSrMergeJob.kernelNetSwapForTest = { frame, _, ratio ->
                ratios.add(ratio)
                val cov = frame.covariance
                frame.copy(covariance = RawSrKernelCovariance.MatrixField(
                    cov.width, cov.height, FloatArray(cov.values.size) { -1f }))
            }
            val ref = input(512, 512, textured = true)
            val good = input(512, 512, textured = true)
            val stream = RawSrMergeJob.mosaicStream(listOf(ref, good), 0)
            val frames = stream.frames.toList()
            val refFrame = stream.referenceFrame()
            assertEquals(frames.size + 1, ratios.size)
            ratios.forEach { assertEquals(stream.noiseSigmaRatio, it) }
            (frames + refFrame).forEach {
                assertTrue(it.covariance.values.all { v -> v == -1f })
            }
        } finally {
            RawSrMergeJob.kernelNetSwapForTest = savedSeam
            RawSrKernelNetAniso.enabled = saved
        }
    }

    @Test fun mosaicChainWithZeroLutMatchesAnalytic() {
        // A zero LUT is an exact no-op: the chain must produce bit-identical
        // robustness fields with and without it.
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        val zero = RawSrNoiseLut.Lut(
            bins = 4, trials = 8, seed = 0,
            alpha = DoubleArray(4), beta = DoubleArray(4),
            sigmaSq = FloatArray(4), dSq = FloatArray(4),
            sigmaSem = FloatArray(4), dSem = FloatArray(4),
            counts = LongArray(4) { 2 })
        val analytic = RawSrMergeJob.mosaicChain(listOf(ref, good), 0, chainConfig)
        val withLut = RawSrMergeJob.mosaicChain(listOf(ref, good), 0, chainConfig, noiseLut = zero)
        assertEquals(analytic.survivorIndices, withLut.survivorIndices)
        assertEquals(analytic.moving.size, withLut.moving.size)
        for (i in analytic.moving.indices) {
            assertArrayEquals(
                analytic.moving[i].robustness!!.r, withLut.moving[i].robustness!!.r, 0f)
        }
    }

    @Test fun mosaicChainThrowsWhenNothingSurvives() {
        val ref = input(512, 512, textured = true)
        val bad = input(32, 24, textured = true)
        assertThrows(MergeUnavailableException::class.java) {
            RawSrMergeJob.mosaicChain(listOf(ref, bad), 0, chainConfig)
        }
    }

    @Test fun mosaicChainReportsRejectionsWithReasons() {
        val ref = input(512, 512, textured = true)
        val flat = input(512, 512, textured = false)
        val good = input(512, 512, textured = true)
        val seen = ArrayList<RawSrMergeJob.RejectedFrame>()
        val chain = RawSrMergeJob.mosaicChain(listOf(ref, flat, good), 0, chainConfig,
            onRejected = { seen.add(it) })
        assertEquals(listOf(2), chain.survivorIndices)
        assertEquals(1, chain.rejections.size)
        assertEquals(1, chain.rejections.single().index)
        assertTrue(chain.rejections.single().reason.isNotEmpty())
        assertEquals(chain.rejections, seen)
    }

    @Test fun strictSupportPolicyRejectsAndNamesDetail() {
        // Mean-R floor of 1.0 is unreachable (R clamps below 1 - t), so every
        // moving frame rejects and the exception names the gate per frame.
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        val strict = RawSrFrameRejection.Policy(minMeanRobustness = 1.0)
        try {
            RawSrMergeJob.mosaicChain(listOf(ref, good), 0, chainConfig, rejectionPolicy = strict)
            fail("expected MergeUnavailableException")
        } catch (failure: MergeUnavailableException) {
            assertTrue(failure.message ?: "", (failure.message ?: "").contains("low-mean-r"))
        }
        // Default policy keeps the same pair (proves the gate, not the fixture).
        val chain = RawSrMergeJob.mosaicChain(listOf(ref, good), 0, chainConfig)
        assertEquals(listOf(1), chain.survivorIndices)
    }

    @Test fun mosaicStreamForwardsRejectionsToCallback() {
        val ref = input(512, 512, textured = true)
        val flat = input(512, 512, textured = false)
        val good = input(512, 512, textured = true)
        val seen = ArrayList<RawSrMergeJob.RejectedFrame>()
        val stream = RawSrMergeJob.mosaicStream(listOf(ref, flat, good), 0, chainConfig,
            onRejected = { seen.add(it) })
        assertEquals(1, stream.frames.toList().size)
        assertEquals(1, seen.size)
        assertEquals(1, seen.single().index)
    }

    @Test fun mosaicChainPropagatesCancellation() {
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        assertThrows(CancellationException::class.java) {
            RawSrMergeJob.mosaicChain(listOf(ref, good), 0, chainConfig, isCancelled = { true })
        }
    }

    @Test fun mosaicChainPropagatesOutOfMemoryInsteadOfRejecting() {
        // Heap pressure must surface as OOM (the caller falls back with an
        // OOM message), never as a silent frame rejection that ends in the
        // misleading "kept no moving frame after alignment".
        val ref = input(512, 512, textured = true)
        val packed = mock(RawSrPackedFrame::class.java)
        `when`(packed.uploadInput()).thenThrow(OutOfMemoryError("test OOM"))
        val oom = RawSrMergeJob.MosaicInput(packed, metadataFor(512, 512))
        assertThrows(OutOfMemoryError::class.java) {
            RawSrMergeJob.mosaicChain(listOf(ref, oom), 0, chainConfig)
        }
    }

    @Test fun streamingMeanSupportMatchesEagerRc() {
        // The streaming path must expose the same mean support the eager
        // path accumulates: both fold identical per-quad robustness in the
        // same order, so the merged noise model agrees bitwise.
        val ref = input(512, 512, textured = true)
        val good = input(512, 512, textured = true)
        val inputs = listOf(ref, good)
        val chain = RawSrMergeJob.mosaicChain(inputs, 0, chainConfig)
        val eager = MosaicSrReconstructor.reconstruct(chain.reference, chain.moving)
        val expected = RawSrMergedNoise.meanSupport(eager.rc.values)
        assertTrue("expected fusion gain, got $expected", expected > 1.0)
        val dir = java.nio.file.Files.createTempDirectory("mosaic-mean-support").toFile()
        try {
            val lazy = RawSrMergeJob.mosaicStream(inputs, 0, chainConfig)
            val actual = MosaicSrReconstructor.reconstructStreaming(
                lazy.geometry, lazy.frames,
                buildReference = { RawSrMergeJob.buildReferenceFrame(inputs[0]) },
                tempDir = dir
            )
            assertEquals(expected, actual.meanSupport, 0.0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun mosaicStreamMatchesChainSurvivorsAndCfa() {
        // Production path parity: the lazy stream (mosaicStream +
        // reconstructStreaming, as RawCameraController wires them) must keep
        // the same moving frames as the eager chain and reconstruct the
        // bitwise-identical CFA.
        val ref = input(512, 512, textured = true)
        val flat = input(512, 512, textured = false)
        val bad = input(32, 24, textured = true)
        val good = input(512, 512, textured = true)
        val inputs = listOf(ref, flat, bad, good)
        val chain = RawSrMergeJob.mosaicChain(inputs, 0, chainConfig)
        val stream = RawSrMergeJob.mosaicStream(inputs, 0, chainConfig)
        assertEquals(4, stream.selected)
        assertEquals(chain.reference.width, stream.geometry.width)
        assertEquals(chain.reference.height, stream.geometry.height)
        assertEquals(chain.reference.sensorPattern, stream.geometry.pattern)
        val streamed = stream.frames.toList()
        assertEquals(chain.moving.size, streamed.size)
        assertEquals(1, streamed.size)
        assertArrayEquals(chain.moving[0].samples, streamed[0].samples, 0f)
        assertArrayEquals(chain.moving[0].robustness!!.r, streamed[0].robustness!!.r, 0f)
        val expected = MosaicSrReconstructor.reconstruct(chain.reference, chain.moving)
        val dir = java.nio.file.Files.createTempDirectory("mosaic-stream-chain").toFile()
        try {
            // Fresh lazy stream, consumed during reconstruction exactly as the
            // controller wires it (build-during-consume, not a materialized list).
            val lazy = RawSrMergeJob.mosaicStream(inputs, 0, chainConfig)
            val actual = MosaicSrReconstructor.reconstructStreaming(
                lazy.geometry, lazy.frames,
                buildReference = { RawSrMergeJob.buildReferenceFrame(inputs[0]) },
                tempDir = dir
            )
            assertEquals(1, actual.acceptedFrames)
            assertArrayEquals(expected.cfa, actual.cfa, 0f)
            assertTrue(dir.listFiles()?.isEmpty() == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- ownership: immutable owner, exactly-once closure, cancellation ----

    @Test fun cancelledJobClosesOwnerExactlyOnceWithoutRunning() {
        val released = ArrayList<String>()
        var ran = false
        val owner = CloseOnceOwner(listOf("frame")) { released.add(it) }
        val job = OwnedCaptureJob(owner) { ran = true }
        job.cancelBeforeRun()
        assertFalse(ran)
        assertEquals(listOf("frame"), released)
    }

    @Test fun completedJobClosesOwnerExactlyOnce() {
        val released = ArrayList<String>()
        var ran = false
        val owner = CloseOnceOwner(listOf("a", "b")) { released.add(it) }
        OwnedCaptureJob(owner) { ran = true }.run()
        assertTrue(ran)
        assertEquals(listOf("a", "b"), released)
    }

    // ---- MediaStore cleanup paths ----

    @Test fun linearRgbSaverDeletesIncompleteEntryOnWriterFailure() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(resolver.insert(any(), any())).thenReturn(uri)
        `when`(resolver.openOutputStream(eq(uri), eq("w"))).thenReturn(ByteArrayOutputStream())
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("1") // mismatch vs provenance "0" fails AFTER insert
        `when`(metadata.exifOrientation).thenReturn(1)
        val image = MergedLinearRgb(2, 2, FloatArray(12) { 0.5f })
        val provenance = MergeProvenance(
            LinearRgbDngWriter.ALGORITHM_VERSION, 2, 2, 0, 1L, 1, "0", true
        )
        assertThrows(IllegalArgumentException::class.java) {
            LinearRgbDngSaver(context).saveLinearRgb(image, metadata, provenance, 42L)
        }
        verify(resolver).delete(eq(uri), isNull(), isNull())
        verify(resolver, never()).update(eq(uri), any(), isNull(), isNull())
    }

    @Test fun mosaicSaverDeletesIncompleteEntryOnWriterFailure() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(resolver.insert(any(), any())).thenReturn(uri)
        `when`(resolver.openOutputStream(eq(uri), eq("w"))).thenReturn(ByteArrayOutputStream())
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("1")
        `when`(metadata.exifOrientation).thenReturn(1)
        val image = MosaicSrCfa(4, 4, BayerPattern.RGGB, FloatArray(16) { 0.5f })
        val provenance = MosaicSrProvenance(2, 2, 0, 1L, 4, 4, "0", true)
        assertThrows(IllegalArgumentException::class.java) {
            MosaicSrDngSaver(context).saveMosaicSr(image, metadata, provenance, 42L)
        }
        verify(resolver).delete(eq(uri), isNull(), isNull())
    }

    @Test fun linearRgbSaverPublishesOnSuccess() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(resolver.insert(any(), any())).thenReturn(uri)
        `when`(resolver.openOutputStream(eq(uri), eq("w"))).thenReturn(ByteArrayOutputStream())
        `when`(resolver.update(eq(uri), any(), isNull(), isNull())).thenReturn(1)
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        val image = MergedLinearRgb(2, 2, FloatArray(12) { 0.5f })
        val provenance = MergeProvenance(
            LinearRgbDngWriter.ALGORITHM_VERSION, 2, 2, 0, 1L, 1, "0", true
        )
        val name = LinearRgbDngSaver(context).saveLinearRgb(image, metadata, provenance, 42L)
        assertTrue(name.endsWith("_LINEAR.dng"))
        verify(resolver, never()).delete(eq(uri), isNull(), isNull())
    }

    @Test fun linearRgbStripedSaverPublishesOnSuccess() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(resolver.insert(any(), any())).thenReturn(uri)
        `when`(resolver.openOutputStream(eq(uri), eq("w"))).thenReturn(ByteArrayOutputStream())
        `when`(resolver.update(eq(uri), any(), isNull(), isNull())).thenReturn(1)
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        val provenance = MergeProvenance(
            LinearRgbDngWriter.ALGORITHM_VERSION, 2, 2, 0, 1L, 1, "0", true
        )
        val name = LinearRgbDngSaver(context).saveLinearRgbStriped(
            2, 2, metadata, provenance, 42L
        ) { _, rows, band -> band.fill(0.5f, 0, 2 * rows * 3) }
        assertTrue(name.endsWith("_LINEAR.dng"))
        verify(resolver, never()).delete(eq(uri), isNull(), isNull())
    }

    @Test fun linearRgbStripedSaverDeletesIncompleteEntryOnWriterFailure() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(resolver.insert(any(), any())).thenReturn(uri)
        `when`(resolver.openOutputStream(eq(uri), eq("w"))).thenReturn(ByteArrayOutputStream())
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("1") // mismatch vs provenance "0" fails AFTER insert
        `when`(metadata.exifOrientation).thenReturn(1)
        val provenance = MergeProvenance(
            LinearRgbDngWriter.ALGORITHM_VERSION, 2, 2, 0, 1L, 1, "0", true
        )
        assertThrows(IllegalArgumentException::class.java) {
            LinearRgbDngSaver(context).saveLinearRgbStriped(
                2, 2, metadata, provenance, 42L
            ) { _, rows, band -> band.fill(0.5f, 0, 2 * rows * 3) }
        }
        verify(resolver).delete(eq(uri), isNull(), isNull())
        verify(resolver, never()).update(eq(uri), any(), isNull(), isNull())
    }

    // ---- synthetic frame builders ----

    private fun input(w: Int, h: Int, textured: Boolean): RawSrMergeJob.MosaicInput {
        val metadata = metadataFor(w, h)
        // Seeded block texture: constant 8x8-pixel cells from a random
        // coarse grid. Still aperiodic (block matching keeps its unique
        // minimum, LK keeps edge gradients in every tile) with the same mean
        // brightness as white noise — but with no isolated single-tap
        // spikes, so the hot-pixel gate stays silent here. (Per-tap white
        // noise cannot serve as this fixture: ±400-code spikes against the
        // fixture's 0.35-code model are indistinguishable from stuck taps,
        // and any correct single-frame gate fires on them.)
        // Deterministic across runs.
        val random = kotlin.random.Random(0x5D1C5EED)
        val block = 8
        val cw = (w + block - 1) / block
        val ch = (h + block - 1) / block
        val cell = ShortArray(cw * ch) { (100 + random.nextInt(800)).toShort() }
        val codes = ShortArray(w * h) { i ->
            if (!textured) 512.toShort()
            else cell[(i / w / block) * cw + (i % w) / block]
        }
        val buffer = ByteBuffer.allocateDirect(codes.size * 2).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(codes)
        return RawSrMergeJob.MosaicInput(RawSrPackedFrame.fromMetadata(buffer, metadata), metadata)
    }

    private fun metadataFor(w: Int, h: Int) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = w, imageHeight = h,
        imageCrop = IntRectSnapshot(0, 0, w, h), rawPlaneCount = 1, rawPlaneRowStride = w * 2,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = w to h,
        activeArray = IntRectSnapshot(0, 0, w, h), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, w, h), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = null, forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null,
        noiseProfile = ImmutableDoubleValues(doubleArrayOf(2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6)),
        sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )

    // ---- readback firewall ----

    @Test fun sanitizeStripLeavesFiniteStripsBitwiseUntouched() {
        val rgb = floatArrayOf(0f, 0.5f, 1f, 1.25f, -0f, 4.35f)
        val before = rgb.copyOf()
        assertEquals(0, RawSrMergeJob.sanitizeStrip(rgb, 0, 2, 7, 1))
        assertArrayEquals(before, rgb, 0f)
    }

    @Test fun sanitizeStripZeroesNonFiniteAndCountsThem() {
        val rgb = floatArrayOf(
            0.5f, Float.NaN, 1f,
            Float.POSITIVE_INFINITY, 0.25f, Float.NEGATIVE_INFINITY
        )
        assertEquals(3, RawSrMergeJob.sanitizeStrip(rgb, 0, 1, 0, 2))
        assertArrayEquals(floatArrayOf(0.5f, 0f, 1f, 0f, 0.25f, 0f), rgb, 0f)
    }

    @Test fun sanitizeStripHonorsOffsetAndLeavesNeighborsAlone() {
        // Width 2, one row at offset 3: only indices 3..8 scan.
        val rgb = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, 0.5f, Float.NaN, 1f, 0.25f, 0f, 2f, Float.NaN)
        assertEquals(1, RawSrMergeJob.sanitizeStrip(rgb, 3, 2, 0, 1))
        assertTrue(rgb[0].isNaN())
        assertTrue(rgb[9].isNaN())
        assertArrayEquals(floatArrayOf(0.5f, 0f, 1f, 0.25f, 0f, 2f), rgb.copyOfRange(3, 9), 0f)
    }
}
