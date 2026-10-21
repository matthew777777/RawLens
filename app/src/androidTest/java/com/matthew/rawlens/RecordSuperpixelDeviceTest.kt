// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct-Log binning (superpixel record path) on a REAL mosaic:
 * vf_superpixel_f16 -> vf_gradeyuv (superpixel mode, 1:1, identity grade)
 * -> P010, every Y/U/V code vs the CPU twin [RecordSuperpixel] + BT.709
 * golden, at steps 2 and 4; logs the chain time. Opt-in
 * `-e rawlensRecordSp true`; `-e spSpv <path>` times another SPIR-V
 * (measurement only; the parity gate then reports, not asserts).
 * Mosaic: /data/local/tmp/rawlens_mhc/<name>_<W>x<H>.u16 (GBRG, 64/1023).
 */
class RecordSuperpixelDeviceTest {
    @Test fun binningMatchesCpuTwinOnRealFrame() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e rawlensRecordSp true", args.getString("rawlensRecordSp") == "true")
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val re = Regex("(.+)_(\\d+)x(\\d+)\\.u16")
        val file = File("/data/local/tmp/rawlens_mhc").listFiles()?.firstOrNull { re.matches(it.name) }
        assumeTrue("push a mosaic to /data/local/tmp/rawlens_mhc", file != null)
        val (_, w, h) = re.matchEntire(file!!.name)!!.destructured
        val sw = w.toInt()
        val sh = h.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun asset(name: String) = context.assets.open(name).use { it.readBytes() }
        val override = args.getString("spSpv")
        check(VfVulkan.initNative(asset("shaders/vf/vf_superpixel.spv")) == VfVulkan.OK)
        check(VfVulkan.initF16Native(override?.let { File(it).readBytes() } ?: asset("shaders/vf/vf_superpixel_f16.spv")) == VfVulkan.OK)
        check(VfLogGrade.initGradeNative(asset("shaders/vf/vf_loggrade.spv")) == VfVulkan.OK)
        check(VfLogGrade.initGradeYuvNative(asset("shaders/vf/vf_gradeyuv.spv")) == VfVulkan.OK)
        check(VfLogGrade.gradeLutUploadNative(VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST)) == VfVulkan.OK)
        val bytes = file.readBytes()
        val codes = IntArray(sw * sh) { (bytes[2 * it].toInt() and 0xff) or ((bytes[2 * it + 1].toInt() and 0xff) shl 8) }
        val raw = VfEglImport.createBlobBayerInput(sw, sh)!!
        val src = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        src.flip()
        check(VfEglImport.blitBytesToBlob(raw, src, bytes.size) == 0) { "blit failed" }
        val channels = intArrayOf(2, 0, 3, 1)  // GBRG
        val levels = floatArrayOf(64f, 64f, 64f, 64f)
        val white = 1023f
        try {
            for (step in intArrayOf(2, 4)) {
                // Even frame dims (P010 chroma pairs).
                val fw = (sw / step) and 1.inv()
                val fh = (sh / step) and 1.inv()
                val exp = HardwareBuffer.create(
                    fw, fh, HardwareBuffer.RGBA_FP16, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
                val p010 = VfEglImport.createP010(fw, fh)!!
                try {
                    val lay = VfEglImport.describeP010(p010)!!
                    val (ip, fp) = VfVulkan.packParams(channels, 0, 0, fw, fh, step, sw, levels, white)
                    val (_, gf) = VfLogGrade.packGrade(intArrayOf(fw, fh), floatArrayOf(1f, 1f, 1f, 1f),
                        VfLogGrade.IDENTITY_CCM, 0f, false)
                    var ms = 0f
                    for (rep in 0 until 3) {  // first run warms imports/pipelines
                        val t0 = System.nanoTime()
                        check(VfVulkan.computeSubmitNative(raw, exp, ip, fp, 0, null, null) == VfVulkan.OK)
                        val fd = VfLogGrade.gradeYuvSubmitNative(exp, p010, intArrayOf(fw, fh), intArrayOf(fw, fh),
                            gf, intArrayOf(lay[0], lay[1], fw, fh), 0, 0)
                        check(fd >= 0) { "grade submit $fd" }
                        while (!VfEglImport.pollSyncFd(fd)) Thread.yield()
                        VfEglImport.closeSyncFd(fd)
                        ms = (System.nanoTime() - t0) / 1e6f
                    }
                    val yP = ByteBuffer.allocateDirect(fw * fh * 2).order(ByteOrder.LITTLE_ENDIAN)
                    val uP = ByteBuffer.allocateDirect(fw * fh / 2).order(ByteOrder.LITTLE_ENDIAN)
                    val vP = ByteBuffer.allocateDirect(fw * fh / 2).order(ByteOrder.LITTLE_ENDIAN)
                    check(VfEglImport.copyP010ToCodec(p010, yP, fw * 2, uP, fw, 2, vP, fw, 2, fw, fh, lay[0], lay[1]) == 0)
                    val twin = RecordSuperpixel.develop(codes, sw, channels, 0, 0, fw, fh, step, levels, white)
                    val yuv = FloatArray(fw * fh * 3)
                    for (t in 0 until fw * fh) {
                        val lin = floatArrayOf(twin[t * 4], (twin[t * 4 + 1] + twin[t * 4 + 2]) * 0.5f, twin[t * 4 + 3])
                        val d = VfLogGrade.encodePixel709(lin)
                        val y = 0.2126f * d[0] + 0.7152f * d[1] + 0.0722f * d[2]
                        yuv[t * 3] = y
                        yuv[t * 3 + 1] = (d[2] - y) / (2f * (1f - 0.0722f)) + 0.5f
                        yuv[t * 3 + 2] = (d[0] - y) / (2f * (1f - 0.2126f)) + 0.5f
                    }
                    var within = 0L
                    var total = 0L
                    var worst = 0
                    fun check(gpuWord: Short, ref: Float, lo: Float, span: Float) {
                        val g = (gpuWord.toInt() and 0xffff) ushr 6
                        val r = Math.round(ref.coerceIn(0f, 1f) * span + lo)
                        val d = Math.abs(g - r)
                        if (d <= 8) within++
                        total++
                        worst = maxOf(worst, d)
                    }
                    for (t in 0 until fw * fh) check(yP.getShort(t * 2), yuv[t * 3], 64f, 876f)
                    // Superpixel-mode chroma: the 2x2 output cell's mean (linear
                    // in YUV, so the mean of the converts).
                    for (j in 0 until fh / 2) for (i in 0 until fw / 2) {
                        val a = (2 * j * fw + 2 * i) * 3
                        val b = a + fw * 3
                        val k = j * (fw / 2) + i
                        check(uP.getShort(k * 2), (yuv[a + 1] + yuv[a + 4] + yuv[b + 1] + yuv[b + 4]) * 0.25f, 64f, 896f)
                        check(vP.getShort(k * 2), (yuv[a + 2] + yuv[a + 5] + yuv[b + 2] + yuv[b + 5]) * 0.25f, 64f, 896f)
                    }
                    val frac = within.toDouble() / total
                    val msg = "RECSP step=$step ${fw}x$fh chain=${"%.1f".format(ms)}ms spv=${override ?: "asset"} " +
                        "within8=${"%.5f".format(frac)} worst=$worst"
                    Log.i(TAG, msg)
                    if (override == null) assertTrue(msg, frac > 0.999)
                } finally {
                    exp.close()
                    p010.close()
                }
            }
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val TAG = "RecordSuperpixel"
    }
}
