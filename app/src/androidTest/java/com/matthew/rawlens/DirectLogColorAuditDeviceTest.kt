// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Numeric pixels through shipping SPIR-V/JNI, plus opt-in real codec/crop checks. */
@RunWith(AndroidJUnit4::class)
class DirectLogColorAuditDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String) = context.assets.open("shaders/vf/$name.spv").use { it.readBytes() }
    private fun await(fd: Int) {
        assertTrue("GPU submit failed: $fd", fd >= 0)
        try {
            val end = SystemClock.elapsedRealtime() + 5000
            while (!VfEglImport.pollSyncFd(fd) && SystemClock.elapsedRealtime() < end)
                SystemClock.sleep(1)
            assertTrue("GPU fence timeout", VfEglImport.pollSyncFd(fd))
        } finally { VfEglImport.closeSyncFd(fd) }
    }

    /** A sync-fd can signal before Vulkan retires the descriptor-set fence. */
    private fun awaitSubmit(submit: () -> Int) {
        val deadline = SystemClock.elapsedRealtime() + 1000
        var fd = submit()
        while (fd == -VfVulkan.BUSY && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1)
            fd = submit()
        }
        await(fd)
    }

    @Test fun knownColorsThroughEveryRecordShader() = checkKnownColors(null)

    @Test fun recordedLutKnownColorsThroughEveryRecordShader() {
        val path = InstrumentationRegistry.getArguments().getString("recordLutPath")
        assumeTrue("Supply external cube with -e recordLutPath", path != null)
        checkKnownColors(File(requireNotNull(path)).reader().use { CubeLut.parse(it) })
    }

    @Test fun recordedLutCustomDomainAndSignedOutput() {
        val text = buildString {
            append("LUT_3D_SIZE 16\nDOMAIN_MIN -0.1 0.1 0.2\nDOMAIN_MAX 1.1 1.2 1.4\n")
            repeat(16) { b -> repeat(16) { g -> repeat(16) { r ->
                append("${r/15f-.1f} ${g/15f+.1f} ${b/15f}\n")
            } } }
        }
        checkKnownColors(CubeLut.parse(java.io.StringReader(text)))
    }

    private fun checkKnownColors(cube: CubeLut?) {
        val intensity = InstrumentationRegistry.getArguments().getString("recordLutIntensity", "1").toFloat()
        fun gradeAsset(name: String) = asset(name + if (cube != null) "_cube" else "")
        assertTrue(VfVulkan.available && VfEglImport.available)
        val colors = listOf(
            floatArrayOf(0f,0f,0f), floatArrayOf(0.001f,0.001f,0.001f),
            floatArrayOf(0.018f,0.018f,0.018f), floatArrayOf(0.18f,0.18f,0.18f),
            floatArrayOf(1f,1f,1f), floatArrayOf(1f,0f,0f),
            floatArrayOf(0f,1f,0f), floatArrayOf(0f,0f,1f),
            floatArrayOf(1f,1f,0f), floatArrayOf(0f,1f,1f), floatArrayOf(1f,0f,1f),
            floatArrayOf(0.6f,0.3f,0.2f), floatArrayOf(-0.1f,0.3f,0.2f),
            floatArrayOf(-0.1f,0.01f,0.02f), floatArrayOf(-0.01f,0.005f,0.006f))
        val report = StringBuilder()
        val n = 64
        for (variant in listOf("split", "mhc", "mhc_f16", "rcd", "rcd_f16")) {
            assertEquals(0, VfVulkan.initNative(asset("vf_superpixel")))
            assertEquals(0, VfLogGrade.recordCubeUploadNative(cube?.rgbaHalfBits(), cube?.size ?: 0, cube?.gpuParameters(intensity)))
            if (variant.endsWith("_f16") && !VfVulkan.supportsF16MathNative()) {
                report.append("$variant unavailable\n")
                VfVulkan.resetNative()
                continue
            }
            var cfa: HardwareBuffer? = null
            var rgb: HardwareBuffer? = null
            var scratch: HardwareBuffer? = null
            var p010: HardwareBuffer? = null
            try {
                assertEquals(0, VfVulkan.initF16Native(asset("vf_superpixel_f16")))
                assertEquals(0, VfLogGrade.initGradeNative(asset("vf_loggrade")))
                assertEquals(0, VfLogGrade.initGradeYuvNative(gradeAsset("vf_gradeyuv")))
                assertEquals(0, VfLogGrade.gradeLutUploadNative(VfLogGrade.bakeLut(1f)))
                cfa = requireNotNull(VfEglImport.createBlobBayerInput(n,n))
                val data = ByteBuffer.allocateDirect(n*n*2).order(ByteOrder.nativeOrder())
                repeat(n*n) { data.putShort(1023.toShort()) }
                data.rewind()
                assertEquals(0, VfEglImport.blitBytesToBlob(cfa,data,n*n*2))
                fun image() = HardwareBuffer.create(n,n,HardwareBuffer.RGBA_FP16,1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                rgb = image(); scratch = image()
                p010 = requireNotNull(VfEglImport.createP010(n,n))
                val layout = requireNotNull(VfEglImport.describeP010(p010))
                val strides = intArrayOf(layout[0],layout[1],n,n)
                val dims = intArrayOf(n,n)
                val channels = intArrayOf(0,1,2,3)
                val (mi,mf) = VfMhc.packMhc(n,n,0,0,channels,n,FloatArray(4),1023f)
                if (variant == "split") {
                    assertEquals(0, VfMhc.initMhcNative(asset("vf_mhc")))
                    await(VfMhc.mhcSubmitNative(cfa,rgb,mi,mf,0))
                } else if (variant.startsWith("mhc")) {
                    assertEquals(0, VfLogGrade.initFusedYuvNative(gradeAsset("vf_${variant.replace("mhc","mhcyuv")}")))
                } else {
                    assertEquals(0, VfRcd.initRcdNative(asset("vf_rcd")))
                    assertEquals(0, VfRcd.initRcdYuvNative(gradeAsset("vf_${variant.replace("rcd","rcdyuv")}")))
                    for (mode in 0..2) {
                        val (ri,rf) = VfRcd.packRcd(n,n,0,0,channels,n,mode,FloatArray(4),1023f)
                        await(VfRcd.rcdSubmitNative(cfa,rgb,scratch,ri,rf,0))
                    }
                }
                var maxError = 0
                var checks = 0
                for (profile in DirectLogProfile.entries) for (ev in listOf(0f,4f,10f)) for (color in colors) {
                    val matrix = floatArrayOf(color[0],0f,0f,0f,color[1],0f,0f,0f,color[2])
                    val (_,gf) = VfLogGrade.packGrade(dims,floatArrayOf(1f,1f,1f,1f),matrix,ev,false,profile=profile)
                    if (variant == "split") {
                        assertEquals(0, VfLogGrade.gradeYuvBlockingNative(rgb,p010,dims,dims,gf,strides,0,1))
                    } else if (variant.startsWith("mhc")) {
                        awaitSubmit { VfLogGrade.fusedYuvSubmitNative(cfa,p010,mi,mf,dims,gf,strides,0,null,null) }
                    } else {
                        val (ri,rf) = VfRcd.packRcd(n,n,0,0,channels,n,3,FloatArray(4),1023f)
                        awaitSubmit { VfRcd.rcdFusedYuvSubmitNative(cfa,rgb,scratch,p010,ri,rf,dims,dims,gf,strides,0) }
                    }
                    val developed = color.map { it * 2.0.pow(ev.toDouble()).toFloat() }.toFloatArray()
                    var encoded = when(profile) {
                        DirectLogProfile.BT709 -> VfLogGrade.encodePixel709(developed)
                        DirectLogProfile.ACES -> VfLogGrade.encodePixelAces(developed)
                        DirectLogProfile.HLG -> VfLogGrade.encodePixelHlg(developed)
                        DirectLogProfile.SLOG3 -> VfLogGrade.encodePixelSlog3(developed)
                    }
                    if (profile == DirectLogProfile.SLOG3 && cube != null) encoded = cube.apply(encoded,intensity)
                    val kr = if (profile == DirectLogProfile.HLG) 0.2627f else 0.2126f
                    val kb = if (profile == DirectLogProfile.HLG) 0.0593f else 0.0722f
                    val y = kr*encoded[0]+(1-kr-kb)*encoded[1]+kb*encoded[2]
                    val expected = floatArrayOf(y,(encoded[2]-y)/(2*(1-kb))+0.5f,(encoded[0]-y)/(2*(1-kr))+0.5f)
                    val words = requireNotNull(VfEglImport.sampleP010Center(p010,layout[0],layout[1],n,n,32,32))
                    for (i in 0..2) {
                        assertEquals("P010 lower bits",0,words[i] and 63)
                        val actual = words[i] ushr 6
                        val reference = (64 + expected[i].coerceIn(0f,1f)*(if(i==0)876 else 896)).roundToInt()
                        val error = abs(actual-reference)
                        maxError = maxOf(maxError,error)
                        // fp16 arithmetic may move transfer knees by a few codes.
                        assertTrue("$variant $profile ev=$ev color=${color.toList()} ch=$i got=$actual expected=$reference",
                            error <= if(variant.endsWith("_f16")) 4 else 1)
                        checks++
                    }
                }
                val result = "$variant checks=$checks max10BitCodeError=$maxError"
                report.append(result).append('\n'); Log.i(TAG,result)
            } finally {
                // Imports outlive submits only; all calls above explicitly completed.
                VfVulkan.resetNative()
                p010?.close(); scratch?.close(); rgb?.close(); cfa?.close()
            }
        }
        File(context.getExternalFilesDir(null),(if (cube == null) "direct-log-color-audit.txt" else "direct-log-record-lut-audit.txt")).writeText(report.toString())
    }

    @Test fun recordAcesAtEveryResolutionAndCrop() {
        assumeTrue("Opt in with -e rawlensColorAudit true",
            InstrumentationRegistry.getArguments().getString("rawlensColorAudit") == "true")
        recordProfile(DirectLogProfile.ACES)
    }

    @Test fun recordSonyAtEveryResolutionAndCrop() {
        assumeTrue("Opt in with -e rawlensSonyRecord true",
            InstrumentationRegistry.getArguments().getString("rawlensSonyRecord") == "true")
        recordProfile(DirectLogProfile.SLOG3)
    }

    private fun recordProfile(profile: DirectLogProfile) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = manager.cameraIdList.first {
            val c = manager.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                !c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
        }
        val dir = File(context.getExternalFilesDir(null),"direct-log-color-audit").apply { mkdirs() }
        val report = StringBuilder()
        for (w in listOf(1920,2560,3840)) for (crop in
            listOf(VideoCrop.WIDE_16_9,VideoCrop.SCOPE_2_39,VideoCrop.UNIVISIUM_2_00)) {
            val recorder = DirectLogRecorder(manager,camera,context).apply {
                width=w; height=w*9/16; fps=24; audioEnabled=false
                recordingCrop=crop; logProfile=profile
                allowThermalDegrade=false
            }
            val file = File(dir,"${profile.name.lowercase()}_${w}_${crop.name}.mp4")
            try {
                recorder.start(file)
                SystemClock.sleep(2000)
                val stats = recorder.stop()
                assertTrue("No encoded frames at $w/$crop", stats.framesGraded > 0)
                assertEquals("Frame errors",0,stats.frameErrors)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                        .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    val rw = format.getInteger(MediaFormat.KEY_WIDTH)
                    val rh = format.getInteger(MediaFormat.KEY_HEIGHT)
                    assertEquals("Crop/fallback aspect", crop.ratio!!,rw.toDouble()/rh,0.005)
                    assertEquals(MediaFormat.COLOR_STANDARD_BT709,format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                    assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO,format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                    assertEquals(MediaFormat.COLOR_RANGE_LIMITED,format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                    val result = "$w/$crop -> ${rw}x$rh 10bit=${stats.true10Bit} frames=${stats.framesGraded} bytes=${file.length()} format=$format"
                    report.append(result).append('\n'); Log.i(TAG,result)
                } finally { extractor.release() }
            } finally { runCatching { recorder.stop() } }
        }
        File(dir,"${profile.name.lowercase()}-record-report.txt").writeText(report.toString())
    }
    companion object { private const val TAG = "DirectLogColorAudit" }
}
