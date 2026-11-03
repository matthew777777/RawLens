// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Half
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Golden outputs are independently compiled pinned RAWR, with identical half-float inputs. */
class CaptureSharpeningDeviceTest {
    @Test fun nativeBridgeMatchesRawrForEveryPresetAndPattern() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val stage = WaveletDenoiser(context)
        try {
            for ((index, level) in CaptureSharpeningLevel.entries.withIndex()) {
                val name = "preset-${level.iterations}-cfa-$index"
                fun asset(suffix: String) = inst.context.assets.open("capture_sharpening/$name-$suffix.rgba16f").use { it.readBytes() }
                val input = asset("input")
                val pixels = ByteBuffer.allocateDirect(input.size).order(ByteOrder.nativeOrder()).apply { put(input); rewind() }
                val expected = ByteBuffer.wrap(asset("expected")).order(ByteOrder.nativeOrder())
                stage.captureSharpen(pixels, 193, 187, floatArrayOf(1.9f, 1f, 1.45f),
                    BayerPattern.entries[index], CaptureSharpeningSettings(true, level))
                val actualBytes = ByteArray(input.size)
                pixels.duplicate().apply { rewind(); get(actualBytes) }
                File(context.getExternalFilesDir(null), "capsharp-$name.rgba16f").writeBytes(actualBytes)
                var maxError = 0f
                var sumError = 0.0
                var changed = 0
                for (i in 0 until 193*187*4) {
                    val actual = Half.toFloat(pixels.getShort(i*2))
                    val reference = Half.toFloat(expected.getShort(i*2))
                    assertTrue("finite $name at $i", actual.isFinite())
                    if (i % 4 != 3) {
                        val err = abs(actual-reference)
                        maxError = maxOf(maxError, err)
                        sumError += err
                        if (actualBytes[i*2] != input[i*2] || actualBytes[i*2+1] != input[i*2+1]) changed++
                    }
                }
                assertTrue("sharpening changed pixels $name", changed > 100)
                assertTrue("$name max=$maxError mean=${sumError/(193*187*3)}", maxError <= 0.004f && sumError/(193*187*3) < 0.0003)
                Log.i("CaptureSharpeningParity", "$name max=$maxError mean=${sumError/(193*187*3)} changed=$changed")
                // Disable/re-enable cannot mutate the persisted strength, and off is a byte-exact bypass.
                val before = actualBytes.copyOf()
                stage.captureSharpen(pixels, 193, 187, floatArrayOf(1.9f, 1f, 1.45f),
                    BayerPattern.entries[index], CaptureSharpeningSettings(false, level))
                pixels.duplicate().apply { rewind(); get(actualBytes) }
                assertArrayEquals(before, actualBytes)
            }
        } finally { stage.release() }
    }
    @Test fun settingsRoundTripAndDevelopedPhotoPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("capture-sharpening-test", 0)
        prefs.edit().clear().commit()
        assertEquals(CaptureSharpeningSettings(), CaptureSharpeningSettings.read(prefs))
        prefs.edit().putBoolean(CaptureSharpeningSettings.ENABLED_KEY, false)
            .putString(CaptureSharpeningSettings.LEVEL_KEY, "Sharpest").commit()
        assertEquals(CaptureSharpeningSettings(false, CaptureSharpeningLevel.Sharpest), MergeScreenSettings.denoise(prefs).captureSharpening)
        prefs.edit().putBoolean(CaptureSharpeningSettings.ENABLED_KEY, true).commit()
        assertEquals(CaptureSharpeningLevel.Sharpest, CaptureSharpeningSettings.read(prefs).level)
        prefs.edit().clear().commit()

        val w=128; val h=128
        val pattern=BayerPattern.GRBG
        val values=FloatArray(w*h) { i ->
            val x=i%w; val y=i/w
            val detail=if ((x/3+y/5)%2==0) 0.13f else 0.38f
            detail * floatArrayOf(0.8f,1f,0.65f)[pattern.colorOrdinalAt(x,y)]
        }
        val cfa=UnpackedRawCfa(w,h,pattern,values,RawCrop(0,0,w,h))
        val m=metadata().copy(imageWidth=w,imageHeight=h,imageCrop=IntRectSnapshot(0,0,w,h),
            cfaPattern=pattern,pixelArraySize=w to h,activeArray=IntRectSnapshot(0,0,w,h),
            bufferGeometry=RawBufferGeometry.Supported(0,0,RawCrop(0,0,w,h),"test"))
        val coordinator=RawDevelopmentCoordinator(context)
        val look=JpegOutputSettings(hlReconstruction=false,ultraHdr=false,displayP3=false)
        try {
            for (enabled in listOf(false,true)) {
                val settings=RawDevelopmentSettings(denoise=DenoiseSettings(captureSharpening=CaptureSharpeningSettings(enabled)))
                val jpeg=coordinator.developCfaJpeg(cfa,m,settings,look)
                assertEquals(w,jpeg.bitmap.width);jpeg.bitmap.recycle()
                val merged=coordinator.developMergedJpeg(cfa,m,settings,look)
                assertEquals(h,merged.bitmap.height);merged.bitmap.recycle()
                val jxl=coordinator.developCfaJxl(cfa,m,settings,look)
                assertEquals(w*h*8,jxl.halfRgba.capacity())
                val mergedJxl=coordinator.developMergedJxl(cfa,m,settings,look)
                assertEquals(w*h*8,mergedJxl.halfRgba.capacity())
                val rgb=ByteBuffer.allocateDirect(w*h*3*4).order(ByteOrder.nativeOrder())
                for (i in 0 until w*h) for (c in 0..2) rgb.putFloat(values[i]*(0.8f+c*0.1f))
                rgb.rewind()
                val sr=coordinator.developMfsrJpeg(rgb,w,h,m,settings,look)
                assertEquals(w,sr.bitmap.width);sr.bitmap.recycle()
                rgb.rewind()
                assertEquals(w*h*8,coordinator.developMfsrJxl(rgb,w,h,m,settings,look).halfRgba.capacity())
                Gles31AmazeProcessor.EglComputeContext().use {
                    val textures=IntArray(2)
                    android.opengl.GLES31.glGenTextures(2,textures,0)
                    fun upload(id: Int, format: Int, channels: Int, tw: Int, th: Int) {
                        val data=ByteBuffer.allocateDirect(tw*th*channels*4).order(ByteOrder.nativeOrder())
                        repeat(tw*th*channels) { data.putFloat(if(channels==1) 4f else 0.2f+(it%7)*0.01f) }
                        data.rewind()
                        android.opengl.GLES31.glBindTexture(android.opengl.GLES31.GL_TEXTURE_2D,id)
                        android.opengl.GLES31.glTexStorage2D(android.opengl.GLES31.GL_TEXTURE_2D,1,format,tw,th)
                        android.opengl.GLES31.glTexSubImage2D(android.opengl.GLES31.GL_TEXTURE_2D,0,0,0,tw,th,
                            if(channels==1) android.opengl.GLES31.GL_RED else android.opengl.GLES31.GL_RGBA,
                            android.opengl.GLES31.GL_FLOAT,data)
                    }
                    upload(textures[0],android.opengl.GLES30.GL_RGBA32F,4,w,h)
                    upload(textures[1],android.opengl.GLES30.GL_R32F,1,w/2,h/2)
                    val owner=android.opengl.EGL14.eglGetCurrentContext()
                    val live=RawDevelopmentCoordinator(context)
                    try {
                        val result=live.developMergedTextureJpeg(MergedTextureJpegInput(textures[0],w,h,textures[1],4,m),settings,look)
                        assertEquals(w,result.bitmap.width);result.bitmap.recycle()
                        assertEquals("merged context restored",owner,android.opengl.EGL14.eglGetCurrentContext())
                    } finally {
                        live.close()
                        it.makeCurrent()
                        android.opengl.GLES31.glDeleteTextures(2,textures,0)
                    }
                }
            }
        } finally { coordinator.close() }
    }

    private fun metadata(neutral: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = 16, imageHeight = 16,
        imageCrop = IntRectSnapshot(0, 0, 16, 16), rawPlaneCount = 1, rawPlaneRowStride = 32,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 16 to 16,
        activeArray = IntRectSnapshot(0, 0, 16, 16), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 16, 16), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = ImmutableFloatValues(floatArrayOf(1f, 1f, 1f, 1f)),
        neutralColorPoint = ImmutableDoubleValues(neutral), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = ImmutableDoubleValues(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)), forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )


}
