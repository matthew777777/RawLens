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

    @Test fun identicalTexturedFramesAreAccepted() {
        val plan=RawSrBurstPlanner.plan(listOf(input(),input()),"0")
        assertTrue(plan.canMerge)
        assertEquals(listOf(0,1),plan.accepted)
        assertTrue(plan.rejected.isEmpty())
    }
}
