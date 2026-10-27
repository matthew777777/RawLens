// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class MergedHighlightsDeviceTest {
    private fun input(masked: Boolean): UnpackedRawCfa {
        val w=48; val h=48
        val cfa=UnpackedRawCfa(w,h,BayerPattern.RGGB,FloatArray(w*h) { i ->
            when(BayerPattern.RGGB.colorAt(i%w,i/w)) {
                CfaColor.RED -> 1f
                CfaColor.GREEN -> 4f
                CfaColor.BLUE -> 4f
            }
        },RawCrop(0,0,w,h))
        return if(!masked) cfa else cfa.copy(highlightClipping=CfaHighlightClipping.from(w,h) { i ->
            i==24*w+24
        })
    }
    @Test fun hdrGpuChangesOnlyMergeMarkedPixelsAndMatchesCpuBits() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cfa=input(true)
        val expected=OpposedHighlights.cpu(cfa)
        VulkanHub.openVulkan10().use { vk ->
            val actual=OpposedHighlightsVulkan.run(vk,context.assets,cfa).cfa
            for(i in actual.values.indices) assertEquals(expected.values[i].toRawBits(),actual.values[i].toRawBits())
            assertTrue(actual.values[24*48+24]>1f)
            for(i in cfa.values.indices) if(i!=24*48+24)
                assertEquals(cfa.values[i].toRawBits(),actual.values[i].toRawBits())
        }
    }
    @Test fun hdrPlusAndHdrJpegJxlMatchExplicitReconstructionWithoutChangingDngInput() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val developer=RawDevelopmentCoordinator(context)
        val look=JpegOutputSettings(hlReconstruction=true,highlightShoulder=0f)
        try {
            for(hdr in listOf(false,true)) {
                val cfa=input(hdr)
                val original=cfa.values.copyOf()
                val settings=if(hdr) RawDevelopmentSettings(hdrAdaptiveTuning=HdrAdaptiveTuning()) else RawDevelopmentSettings()
                val adaptive=resolveMergedAdaptive(cfa,settings)
                val directSettings=RawDevelopmentSettings(exposureEv=settings.exposureEv+(adaptive?.correctionEv ?: 0.0))
                val expectedCfa=OpposedHighlights.cpu(cfa).let { recovered ->
                    recovered.copy(highlightClipping=CfaHighlightClipping.from(recovered.width,recovered.height) { false })
                }
                val actual=developer.developMergedJpeg(cfa,metadata(),settings,look)
                val expected=developer.developCfaJpeg(expectedCfa,metadata(),directSettings,look)
                try {
                    val actualPixels=IntArray(48*48); val expectedPixels=IntArray(48*48)
                    actual.bitmap.getPixels(actualPixels,0,48,0,0,48,48)
                    expected.bitmap.getPixels(expectedPixels,0,48,0,0,48,48)
                    assertArrayEquals("JPEG HDR=$hdr",expectedPixels,actualPixels)
                } finally { actual.bitmap.recycle(); expected.bitmap.recycle() }
                val actualJxl=developer.developMergedJxl(cfa,metadata(),settings,look)
                val expectedJxl=developer.developCfaJxl(expectedCfa,metadata(),directSettings,look)
                val a=ByteArray(actualJxl.halfRgba.remaining()).also { actualJxl.halfRgba.duplicate().get(it) }
                val e=ByteArray(expectedJxl.halfRgba.remaining()).also { expectedJxl.halfRgba.duplicate().get(it) }
                assertArrayEquals("JXL HDR=$hdr",e,a)
                assertArrayEquals("Shared DNG input HDR=$hdr",original,cfa.values,0f)
            }
        } finally { developer.close() }
    }
    private fun metadata(neutral: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = 48, imageHeight = 48,
        imageCrop = IntRectSnapshot(0, 0, 48, 48), rawPlaneCount = 1, rawPlaneRowStride = 96,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 48 to 48,
        activeArray = IntRectSnapshot(0, 0, 48, 48), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 48, 48), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(neutral), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = ImmutableDoubleValues(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)), forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )

}
