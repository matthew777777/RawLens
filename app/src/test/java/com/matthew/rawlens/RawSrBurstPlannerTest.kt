package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawSrBurstPlannerTest {
    private fun metadata() = RawFrameMetadata(
        cameraId="0", timestampNanos=100, frameNumber=1, imageWidth=32, imageHeight=32,
        imageCrop=IntRectSnapshot(0,0,32,32),rawPlaneCount=1,rawPlaneRowStride=64,rawPlanePixelStride=2,
        exifOrientation=1,sensorOrientationDegrees=0,sensitivityIso=100,exposureTimeNanos=10_000_000,
        frameDurationNanos=null,rollingShutterSkewNanos=0,cfaPattern=BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason=null,blackLevels=ImmutableFloatValues(floatArrayOf(0f,0f,0f,0f)),
        blackLevelSource=BlackLevelSource.STATIC,whiteLevel=4095f,whiteLevelSource=WhiteLevelSource.STATIC,
        pixelArraySize=32 to 32,activeArray=IntRectSnapshot(0,0,32,32),preCorrectionActiveArray=null,
        rawCropRegion=null,bufferGeometry=RawBufferGeometry.Supported(0,0,RawCrop(0,0,32,32),"test"),
        lensShadingAlreadyApplied=true,lensShadingMap=null,hotPixels=emptyList(),wbGains=null,
        neutralColorPoint=ImmutableDoubleValues(doubleArrayOf(1.0,1.0,1.0)),colorCorrectionTransform=null,
        colorMatrix1=ImmutableDoubleValues(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)),
        colorMatrix2=null,cameraCalibration1=null,cameraCalibration2=null,forwardMatrix1=null,forwardMatrix2=null,
        referenceIlluminant1=21,referenceIlluminant2=null,noiseProfile=null,sensorPixelMode=null,
        rawBinningFactorUsed=false,activePhysicalCameraId="wide",afState=2,aeState=2,lensState=0)

    private fun input(m: RawFrameMetadata = metadata(), motion: Float = 0f, saturated: Boolean = false): RawSrBurstPlanner.Input {
        val bytes=ByteBuffer.allocate(2048).order(ByteOrder.nativeOrder())
        repeat(1024) { p -> bytes.putShort((if(saturated) 4095 else 500+((p/32*131+p%32*67)%1800)).toShort()) }
        bytes.flip()
        return RawSrBurstPlanner.Input(m,bytes,motion)
    }

    private fun inputBowl(m: RawFrameMetadata, motion: Float, bump: Boolean): RawSrBurstPlanner.Input {
        // Shared quadratic bowl (any translation misaligns it, so the
        // registration optimum stays interior at zero shift); the bumped
        // twin adds one localized gaussian (3.6x sampled sharpness) whose
        // residual to the plain bowl is ~0.02, far under the 0.12 gate, with
        // no code near the 0.98 saturation rail. Full-frame HF twins are
        // unusable here: pseudo-random texture chance-aligns at the search
        // boundary and the displacement gate correctly rejects it.
        val bytes=ByteBuffer.allocate(2048).order(ByteOrder.nativeOrder())
        repeat(1024) { p ->
            val x=p%32; val y=p/32
            var code=500+(x-16)*(x-16)+(y-16)*(y-16)
            if (bump) code+=(800*kotlin.math.exp(-((x-16)*(x-16)+(y-16)*(y-16))/8.0)).toInt()
            bytes.putShort(code.toShort())
        }
        bytes.flip()
        return RawSrBurstPlanner.Input(m,bytes,motion)
    }

    @Test fun rejectsEveryMetadataCompatibilityMismatch() {
        val b=metadata()
        val cases=listOf(
            b.copy(cameraId="1") to RawSrBurstPlanner.Reason.CAMERA,
            b.copy(activePhysicalCameraId="tele") to RawSrBurstPlanner.Reason.PHYSICAL_CAMERA,
            b.copy(imageWidth=30) to RawSrBurstPlanner.Reason.DIMENSIONS,
            b.copy(imageCrop=IntRectSnapshot(2,0,32,32)) to RawSrBurstPlanner.Reason.CROP,
            b.copy(bufferGeometry=RawBufferGeometry.Supported(2,0,RawCrop(0,0,32,32),"test")) to RawSrBurstPlanner.Reason.GEOMETRY,
            b.copy(rawPlanePixelStride=4) to RawSrBurstPlanner.Reason.PIXEL_STRIDE,
            b.copy(cfaPattern=BayerPattern.BGGR) to RawSrBurstPlanner.Reason.CFA,
            b.copy(whiteLevel=1023f) to RawSrBurstPlanner.Reason.NORMALIZATION,
            b.copy(exposureTimeNanos=11_000_001) to RawSrBurstPlanner.Reason.EXPOSURE,
            b.copy(sensitivityIso=111) to RawSrBurstPlanner.Reason.EXPOSURE,
            b.copy(colorMatrix1=null) to RawSrBurstPlanner.Reason.COLOR,
            b.copy(lensShadingAlreadyApplied=false) to RawSrBurstPlanner.Reason.LENS_SHADING)
        cases.forEach { (m,reason) -> assertTrue(reason.name,RawSrBurstPlanner.incompatible(b,m).contains(reason)) }
        assertTrue(RawSrBurstPlanner.incompatible(b,b.copy(exposureTimeNanos=11_000_000)).isEmpty())
    }

    @Test fun invalidMetadataCannotBecomeReference() {
        val b=metadata()
        listOf(b.copy(blackLevels=null) to RawSrBurstPlanner.Reason.NORMALIZATION,
            b.copy(rawPlaneCount=0) to RawSrBurstPlanner.Reason.PLANE,
            b.copy(cfaPattern=null) to RawSrBurstPlanner.Reason.CFA,
            b.copy(exposureTimeNanos=0) to RawSrBurstPlanner.Reason.EXPOSURE,
            b.copy(colorMatrix1=null) to RawSrBurstPlanner.Reason.COLOR,
            b.copy(lensShadingAlreadyApplied=false) to RawSrBurstPlanner.Reason.LENS_SHADING
        ).forEach { (m,r) -> assertTrue(RawSrBurstPlanner.invalid(m).contains(r)) }
    }

    @Test fun selectsMedianThenStableThenLowestAngularTravel() {
        val frames=listOf(10L,20L,30L).map { input(metadata().copy(timestampNanos=it)) }
        assertEquals(1,RawSrBurstPlanner.plan(frames,"0").reference)
        val stable=frames.toMutableList()
        stable[1]=input(metadata().copy(timestampNanos=20,afState=1))
        assertEquals(0,RawSrBurstPlanner.plan(stable,"0").reference)
        val moving=frames.toMutableList(); moving[1]=input(metadata().copy(timestampNanos=20),1f)
        assertNotEquals(1,RawSrBurstPlanner.plan(moving,"0").reference)
        assertEquals(RawSrBurstPlanner.plan(frames,"0"),RawSrBurstPlanner.plan(frames,"0"))
    }

    @Test fun rejectsSaturationAndTruncationAndPreservesBufferPosition() {
        val good=input(); val before=good.plane.position()
        val result=RawSrBurstPlanner.plan(listOf(good,input(saturated=true)),"0")
        assertFalse(result.canMerge)
        assertEquals(setOf(RawSrBurstPlanner.Reason.SATURATION),result.rejected.single().reasons)
        assertEquals(before,good.plane.position())
        val bad=good.copy(plane=ByteBuffer.allocate(1))
        assertTrue(RawSrBurstPlanner.plan(listOf(good,bad),"0").rejected.single().reasons.contains(RawSrBurstPlanner.Reason.PLANE))
    }

    @Test fun flatRegistrationIsRejectedAndSharperFrameWins() {
        val flat=input()
        for (p in 0 until 1024) flat.plane.putShort(p*2,1000.toShort())
        val plan=RawSrBurstPlanner.plan(listOf(flat,input()),"0")
        assertEquals(1,plan.reference)
        assertTrue(plan.rejected.single().reasons.contains(RawSrBurstPlanner.Reason.REGISTRATION))
        assertFalse(plan.canMerge)
    }

    @Test fun sharpestFirstPrefersSharpestDespiteStabilityAndMotion() {
        val soft=inputBowl(metadata().copy(timestampNanos=10), motion=0f, bump=false)
        // Bumped twin: 3.6x sampled sharpness, but hunting AF (stability
        // penalty) and high gyro travel (motion penalty).
        val sharp=inputBowl(metadata().copy(timestampNanos=20,afState=1), motion=1f, bump=true)
        val mid=inputBowl(metadata().copy(timestampNanos=30), motion=0f, bump=false)
        val frames=listOf(soft,sharp,mid)
        val sharpest=RawSrBurstPlanner.plan(frames,"0",RawSrBurstPlanner.ReferenceMode.SHARPEST_FIRST)
        assertEquals(1,sharpest.reference)
        assertTrue(sharpest.canMerge)
        assertEquals(listOf(0,1,2),sharpest.accepted)
        // Default stays stability-first: the soft stable frame wins, and the
        // soft/mid tie breaks to the earlier timestamp (equal median distance).
        assertEquals(0,RawSrBurstPlanner.plan(frames,"0").reference)
    }

    @Test fun identicalTexturedFramesAreAccepted() {
        val plan=RawSrBurstPlanner.plan(listOf(input(),input()),"0")
        assertTrue(plan.canMerge)
        assertEquals(listOf(0,1),plan.accepted)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test fun overflowCapKeepsReferencePlusLowestTravel() {
        // Nine identical frames (motion ramps with index): the HDR+ N-cap
        // keeps 8 (reference + 7 lowest-travel) and rejects one OVERFLOW.
        val frames=(0 until 9).map { input(metadata().copy(timestampNanos=it.toLong()), motion=it.toFloat()) }
        val plan=RawSrBurstPlanner.plan(frames,"0")
        assertEquals(8,plan.accepted.size)
        assertEquals(1,plan.rejected.size)
        assertEquals(setOf(RawSrBurstPlanner.Reason.OVERFLOW),plan.rejected.single().reasons)
        assertTrue(plan.accepted.contains(plan.reference))
        // Highest-travel frame (index 8) is the one cut.
        assertEquals(8,plan.rejected.single().index)
        assertTrue(plan.canMerge)
    }

    @Test fun overflowCapHonorsPolicyOverride() {
        val frames=(0 until 4).map { input(metadata().copy(timestampNanos=it.toLong())) }
        val plan=RawSrBurstPlanner.plan(frames,"0",
            policy=RawSrFrameRejection.Policy(maxMergeFrames=2))
        assertEquals(2,plan.accepted.size)
        assertEquals(2,plan.rejected.size)
        assertTrue(plan.rejected.all { it.reasons.contains(RawSrBurstPlanner.Reason.OVERFLOW) })
    }

    @Test fun zslSelectionSizeMergesWithoutOverflowCut() {
        // Phone SR path: the ZSL slider owns the merge count, so a 16-frame
        // selection plans with maxMergeFrames=16 and merges all 16 — the
        // default HDR+ N-cap (8) must not silently cut the burst.
        val frames=(0 until 16).map { input(metadata().copy(timestampNanos=it.toLong()), motion=it.toFloat()) }
        val plan=RawSrBurstPlanner.plan(frames,"0",
            policy=RawSrFrameRejection.Policy(maxMergeFrames=frames.size))
        assertEquals(16,plan.accepted.size)
        assertTrue(plan.rejected.isEmpty())
        assertTrue(plan.canMerge)
    }

    private fun inputSoftBowl(m: RawFrameMetadata, motion: Float, factor: Double): RawSrBurstPlanner.Input {
        // Contrast-crushed plain bowl (same quadratic as inputBowl, no bump):
        // sharpness falls by factor² while the centered-bowl SAD optimum
        // stays interior at zero shift (pseudo-random HF twins chance-align
        // at the search boundary instead — see inputBowl).
        val codes=IntArray(1024) { p ->
            val x=p%32; val y=p/32
            500+(x-16)*(x-16)+(y-16)*(y-16)
        }
        val mean=codes.average()
        val bytes=ByteBuffer.allocate(2048).order(ByteOrder.nativeOrder())
        codes.forEach { code -> bytes.putShort((mean+(code-mean)*factor).toInt().toShort()) }
        bytes.flip()
        return RawSrBurstPlanner.Input(m,bytes,motion)
    }

    @Test fun lowContrastTwinRejectsAsUnsharp() {
        // Bumped bowl reference vs 20%-contrast plain twin: the twin's
        // sharpness (~1% of the reference, quadratic in contrast over the
        // 3.6x bump gap) trips UNSHARP while SAD stays under the 0.12
        // registration gate — a pure UNSHARP verdict.
        val ref=inputBowl(metadata().copy(timestampNanos=100), motion=0f, bump=true)
        val soft=inputSoftBowl(metadata().copy(timestampNanos=200), motion=0f, factor=0.2)
        val plan=RawSrBurstPlanner.plan(listOf(ref,soft),"0")
        assertEquals(0,plan.reference)
        assertEquals(setOf(RawSrBurstPlanner.Reason.UNSHARP),plan.rejected.single().reasons)
        assertFalse(plan.canMerge)
    }

    @Test fun planExposesSharpnessAndEvRelative() {
        val a=input(metadata().copy(timestampNanos=10))
        val b=input(metadata().copy(timestampNanos=20))
        val plan=RawSrBurstPlanner.plan(listOf(a,b),"0")
        assertEquals(2,plan.sharpness.size)
        assertTrue(plan.sharpness.all { (it ?: 0.0) > 0.0 })
        assertEquals(2,plan.evRelative.size)
        assertEquals(0.0,plan.evRelative[plan.reference!!]!!,0.0)
        // Constant-exposure burst: all EVs ~0.
        assertTrue(plan.evRelative.all { kotlin.math.abs(it ?: 99.0) < 0.01 })
    }
}
