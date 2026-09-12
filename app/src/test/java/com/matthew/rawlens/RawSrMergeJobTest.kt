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
        val coarse = RawSrAlignmentField(
            imageWidth = 4, imageHeight = 3, tileSize = 2, columns = 2, rows = 2,
            tiles = List(4) { i -> RawSrTileFlow(0f, 0f, i.toFloat(), -i.toFloat(), 0f, true) }
        )
        val quad = RawSrMergeJob.upsampleFlowToQuads(coarse, 4, 3)
        assertEquals(4, quad.imageWidth)
        assertEquals(3, quad.imageHeight)
        assertEquals(1, quad.tileSize)
        // Bilinear: quad (3, 2) blends tile (1, 0)=1 and (1, 1)=3 at fy=0.75.
        val tile = quad.flowAt(3f, 2f)
        assertEquals(2.5f, tile.dx, 0f)
        assertEquals(-2.5f, tile.dy, 0f)
        assertEquals(2.25f, quad.flowAt(2f, 2f).dx, 0f)
    }

    @Test fun upsampledFlowListContractHoldsOnCompactBacking() {
        // The quad grid is array-backed (no boxed tile per quad); the List
        // contract must still hold exactly: size, centers, values, iteration.
        val coarse = RawSrAlignmentField(
            imageWidth = 4, imageHeight = 3, tileSize = 2, columns = 2, rows = 2,
            tiles = List(4) { i -> RawSrTileFlow(0f, 0f, i.toFloat(), -i.toFloat(), 0.5f, i % 2 == 0) }
        )
        val quad = RawSrMergeJob.upsampleFlowToQuads(coarse, 4, 3)
        assertEquals(12, quad.tiles.size)
        val first = quad.tiles[0]
        assertEquals(0f, first.centerX, 0f)
        assertEquals(0f, first.centerY, 0f)
        assertEquals(0f, first.dx, 0f)
        assertEquals(0.5f, first.residual, 0f)
        assertTrue(first.reliable)
        val last = quad.tiles[11]
        assertEquals(3f, last.centerX, 0f)
        assertEquals(2f, last.centerY, 0f)
        // Bilinear (not nearest): quad (3, 2) blends tiles 1 and 3 at fy=0.75.
        assertEquals(2.5f, last.dx, 0f)
        assertEquals(-2.5f, last.dy, 0f)
        assertFalse(last.reliable)
        // Interior blend: quad (2, 1) mixes all four coarse tiles -> 1.25,
        // where nearest lookup returned tile (1, 0) = 1 exactly.
        assertEquals(1.25f, quad.tiles[6].dx, 0f)
        assertEquals(12, quad.tiles.count())
        assertEquals(
            quad.tiles.filter { it.reliable }.size,
            quad.tiles.count { it.reliable }
        )
    }

    // ---- mosaic chain ----

    private val chainConfig = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 3)

    @Test fun mosaicChainKeepsAlignedFramesAndRejectsBadOnes() {
        val ref = input(128, 96, textured = true)
        // Flat field: no reliable tile anywhere, rejected by the chain.
        val flat = input(128, 96, textured = false)
        // Wrong-size frame: align() rejects the dimension mismatch.
        val bad = input(32, 24, textured = true)
        val good = input(128, 96, textured = true)
        val chain = RawSrMergeJob.mosaicChain(listOf(ref, flat, bad, good), 0, chainConfig)
        assertEquals(listOf(3), chain.survivorIndices)
        assertEquals(1, chain.moving.size)
        val result = MosaicSrReconstructor.reconstruct(chain.reference, chain.moving)
        assertEquals(result.width * result.height, result.cfa.size)
        assertTrue(result.cfa.all { it.isFinite() })
        assertTrue(result.rc.values.any { it > 0f })
    }

    @Test fun mosaicChainThrowsWhenNothingSurvives() {
        val ref = input(128, 96, textured = true)
        val bad = input(32, 24, textured = true)
        assertThrows(MergeUnavailableException::class.java) {
            RawSrMergeJob.mosaicChain(listOf(ref, bad), 0, chainConfig)
        }
    }

    @Test fun mosaicChainPropagatesCancellation() {
        val ref = input(128, 96, textured = true)
        val good = input(128, 96, textured = true)
        assertThrows(CancellationException::class.java) {
            RawSrMergeJob.mosaicChain(listOf(ref, good), 0, chainConfig, isCancelled = { true })
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

    // ---- synthetic frame builders ----

    private fun input(w: Int, h: Int, textured: Boolean): RawSrMergeJob.MosaicInput {
        val metadata = metadataFor(w, h)
        // Seeded white noise: aperiodic, so block matching has a unique minimum
        // and LK sees gradients everywhere. Deterministic across runs.
        val random = kotlin.random.Random(0x5D1C5EED)
        val codes = ShortArray(w * h) { i ->
            val v = if (textured) 100 + random.nextInt(800) else 512
            v.toShort()
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
}
