package com.matthew.rawlens
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
class BranchLinearDngDeviceTest {
 @Test fun renderExternalLinearSample() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(context.getExternalFilesDir(null), "branches").apply { mkdirs() }
  assumeTrue("External linear sample not installed", File(dir,"linear.f32").isFile)
  val bytes=File(dir,"linear.f32").readBytes()
  assertEquals(W*H*12,bytes.size)
  val rgb=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).apply { flip() }
  val coordinator=RawDevelopmentCoordinator(context)
  try {
   for (mode in listOf("guarded")) {
   val cleanup=when(mode) {
    "no-cleanup" -> ChromaCleanup(falseColorSuppression=false,defringeStrength=0f)
    "median-only" -> ChromaCleanup(falseColorSuppression=true,defringeStrength=0f)
    "defringe-only" -> ChromaCleanup(falseColorSuppression=false,defringeStrength=1f)
    else -> ChromaCleanup()
   }
   rgb.rewind()
   val look=JpegOutputSettings(toneMapper=if(mode.startsWith("agx")) PhotoToneMapper.AGX else PhotoToneMapper.BASIC, highlightShoulder=1f, chromaCleanup=cleanup)
   val settings=RawDevelopmentSettings(adaptiveExposureStrength=if(mode.endsWith("zero")) 0f else 1f, adaptiveExposureTuning=AdaptiveExposureTuning.fromOutputSettings(look))
   val ev=if(mode.endsWith("zero")) 0.0 else AdaptiveDevelopmentExposure.analyzeRgb(rgb,W,H).correctionEv
   File(dir,"$mode-transform.txt").writeText(SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata()),ev).toString())
   val developed=coordinator.developMfsrJpeg(rgb,W,H,metadata(),settings,look)
   try { File(dir,"branches-$mode.jpg").outputStream().use { developed.bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) } }
   finally { developed.bitmap.recycle(); developed.gainmap?.recycle() }
  }
  } finally { coordinator.close() }
 }
 @Test fun renderTodayBatch() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(context.getExternalFilesDir(null),"today-linear")
  assumeTrue("External batch fixtures not installed", dir.isDirectory)
  val fixtures=dir.listFiles { f -> f.extension=="json" }!!.sortedBy { it.name }
  assertEquals(8,fixtures.size)
  val coordinator=RawDevelopmentCoordinator(context)
  try { for (fixture in fixtures) {
   val j=org.json.JSONObject(fixture.readText())
   fun values(key:String): DoubleArray? = j.optJSONArray(key)?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
   fun matrix(key:String)=values(key)?.let { v -> dngMatrix(*v) }
   val name=j.getString("name"); val w=j.getInt("width");val h=j.getInt("height")
   val meta=metadata().copy(imageWidth=w,imageHeight=h,neutralColorPoint=ImmutableDoubleValues(values("neutral")!!),
    colorMatrix1=matrix("color1"),colorMatrix2=matrix("color2"),forwardMatrix1=matrix("forward1"),forwardMatrix2=matrix("forward2"),
    cameraCalibration1=matrix("calibration1"),cameraCalibration2=matrix("calibration2"))
   val bytes=File(dir,"$name.f32").readBytes()
   val rgb=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
   val look=JpegOutputSettings(toneMapper=PhotoToneMapper.BASIC,highlightShoulder=1f)
   val settings=RawDevelopmentSettings(adaptiveExposureStrength=1f,adaptiveExposureTuning=AdaptiveExposureTuning.fromOutputSettings(look))
   val developed=coordinator.developMfsrJpeg(rgb,w,h,meta,settings,look)
   try { File(dir,"${name}_BASIC_FIXED.jpg").outputStream().use { developed.bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) } }
   finally { developed.bitmap.recycle();developed.gainmap?.recycle() }
   android.util.Log.i("TodayLinearBatch","Rendered $name ${w}x$h")
  } } finally { coordinator.close() }
 }
    private fun d(vararg v: Double) = ImmutableDoubleValues(v)

    /** DNG tags are row-major; RawFrameMetadata uses Camera2's column-major ordering. */
    private fun dngMatrix(vararg rowMajor: Double): ImmutableDoubleValues {
        require(rowMajor.size == 9)
        return ImmutableDoubleValues(DoubleArray(9) { index ->
            rowMajor[(index % 3) * 3 + index / 3]
        })
    }

    private fun metadata(): RawFrameMetadata {
        val full = IntRectSnapshot(0, 0, W, H)
        return RawFrameMetadata(
            cameraId = "external-linear-dng", timestampNanos = 1L, frameNumber = 1L,
            imageWidth = W, imageHeight = H, imageCrop = full,
            rawPlaneCount = 1, rawPlaneRowStride = W * 2, rawPlanePixelStride = 2,
            exifOrientation = 1, sensorOrientationDegrees = 0,
            sensitivityIso = 50, exposureTimeNanos = 2_068_000L, frameDurationNanos = 33_333_333L,
            rollingShutterSkewNanos = null,
            cfaPattern = BayerPattern.GBRG, rawDevelopmentUnsupportedReason = null,
            blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
            blackLevelSource = BlackLevelSource.STATIC,
            whiteLevel = 65535f, whiteLevelSource = WhiteLevelSource.STATIC,
            pixelArraySize = W to H, activeArray = full, preCorrectionActiveArray = full,
            rawCropRegion = null,
            bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, W, H), "cloud-dng"),
            lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(),
            wbGains = null,
            neutralColorPoint = d(0.506429, 1.0, 0.653061),
            colorCorrectionTransform = null,
            colorMatrix1 = dngMatrix(0.666794, -0.15889, -0.085739, -0.573944, 1.389786, 0.143021, -0.137878, 0.265152, 0.603622),
            colorMatrix2 = dngMatrix(1.531464, -0.469604, -0.215057, -0.476227, 1.445328, 0.006699, -0.071747, 0.238724, 0.232956),
            cameraCalibration1 = dngMatrix(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            cameraCalibration2 = dngMatrix(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            forwardMatrix1 = dngMatrix(0.673141, 0.195038, 0.096024, 0.276184, 0.818207, -0.094406, 0.021652, -0.232452, 1.036011),
            forwardMatrix2 = dngMatrix(0.574493, 0.184006, 0.205704, 0.193817, 0.745377, 0.060791, -0.014496, -0.528687, 1.368408),
            referenceIlluminant1 = 21, // D65
            referenceIlluminant2 = 17, // Standard light A
            noiseProfile = null, sensorPixelMode = null, rawBinningFactorUsed = false,
            activePhysicalCameraId = null
        )
    }

 private companion object { const val W=4080; const val H=3060 }
}
