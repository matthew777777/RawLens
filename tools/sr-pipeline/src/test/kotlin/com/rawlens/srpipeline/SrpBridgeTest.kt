package com.rawlens.srpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.rawlens.srpipeline.parity.toFlow
import com.rawlens.srpipeline.parity.toImage2D
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Executes the real JNI entries (not stubs) against the host build of the
 * native library on MoltenVK.
 *
 * Opt-in: runs only when `-Dsrpipeline.lib=<libsrpipeline.dylib>` and
 * `-Dsrpipeline.spv=<merge_accumulate.spv>` are set (local desktop proof);
 * otherwise the tests skip, so CI without a Vulkan loader stays green.
 * Skips as well when context creation fails (no compute device).
 */
class SrpBridgeTest {

    private val lib = System.getProperty("srpipeline.lib", "")
    private val spv = System.getProperty("srpipeline.spv", "")

    private fun assumeNative(): Pair<Long, ByteArray> {
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv", spv.isNotEmpty() && File(spv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device: ${SrpBridge.nativeDeviceInfo(handle)}", handle != 0L)
        return handle to File(spv).readBytes()
    }

    @Test
    fun `device info names a real gpu`() {
        val (handle, _) = assumeNative()
        try {
            val info = SrpBridge.nativeDeviceInfo(handle)
            println("srp device: $info")
            assertTrue("expected a device name, got: $info", info.startsWith("name="))
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `merge accumulate matches cpu through jni`() {
        val (handle, spirv) = assumeNative()
        try {
            // Non-multiple of the 64-wide workgroup: exercises the tail guard.
            val count = 100
            val input = FloatArray(count) { (it % 17) * 0.5f - 2f }
            val inBuf = SrpBridge.directFloats(count)
            inBuf.asFloatBuffer().put(input)
            inBuf.rewind()
            val accBuf = SrpBridge.directFloats(count) // zeroed
            val rc1 = SrpBridge.mergeAccumulate(handle, inBuf, accBuf, spirv, count, 10, 10, 2f)
            assertEquals("first dispatch status", 0, rc1)
            inBuf.rewind()
            val rc2 = SrpBridge.mergeAccumulate(handle, inBuf, accBuf, spirv, count, 10, 10, 1f)
            assertEquals("second dispatch status", 0, rc2)
            accBuf.rewind()
            val got = FloatArray(count)
            accBuf.asFloatBuffer().get(got)
            var maxAbs = 0f
            for (i in 0 until count) {
                val want = 2f * input[i] + 1f * input[i]
                maxAbs = maxOf(maxAbs, kotlin.math.abs(got[i] - want))
            }
            println("jni merge maxabs=$maxAbs")
            assertEquals(0f, maxAbs, 1e-6f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `write dng emits a readable tiff through jni`() {
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        SrpBridge.loadPath(lib)
        val w = 16
        val h = 12
        val rgb = SrpBridge.directFloats(w * h * 3)
        val fb = rgb.asFloatBuffer()
        for (y in 0 until h) for (x in 0 until w) {
            fb.put(x.toFloat() / (w - 1))
            fb.put(y.toFloat() / (h - 1))
            fb.put(0.5f)
        }
        rgb.rewind()
        val out = File.createTempFile("srp_jni", ".dng")
        try {
            val rc = SrpBridge.writeDng(out.absolutePath, w, h, rgb, "sr-pipeline", "jni-proof")
            assertEquals("write status", 0, rc)
            val bytes = out.readBytes()
            assertTrue("file too small: ${bytes.size}", bytes.size > w * h * 3 * 2)
            val magic = String(bytes.sliceArray(0..3), Charsets.US_ASCII)
            assertTrue("expected little-endian TIFF magic, got: $magic",
                bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte())
            println("jni dng: ${bytes.size} bytes, magic=$magic")
        } finally {
            out.delete()
        }
    }

    @Test
    fun `write dng orientation roundtrips through jni`() {
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        SrpBridge.loadPath(lib)
        val w = 8
        val h = 6
        val rgb = SrpBridge.directFloats(w * h * 3)
        val fb = rgb.asFloatBuffer()
        for (y in 0 until h) for (x in 0 until w) {
            fb.put(x.toFloat() / (w - 1))
            fb.put(y.toFloat() / (h - 1))
            fb.put(0.5f)
        }
        rgb.rewind()
        val out = File.createTempFile("srp_jni_ori", ".dng")
        try {
            val rc = SrpBridge.writeDng(out.absolutePath, w, h, rgb,
                "sr-pipeline", "jni-proof", orientation = 6)
            assertEquals("write status", 0, rc)
            assertEquals("orientation roundtrip", 6,
                SrpBridge.readDngOrientation(out.absolutePath))
        } finally {
            out.delete()
        }
        val plain = File.createTempFile("srp_jni_ori0", ".dng")
        try {
            rgb.rewind()
            val rc = SrpBridge.writeDng(plain.absolutePath, w, h, rgb, null, null)
            assertEquals("write status", 0, rc)
            assertEquals("absent orientation reads 0", 0,
                SrpBridge.readDngOrientation(plain.absolutePath))
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `gat and decimate match port gpu bytes through jni`() {
        val gatSpv = System.getProperty("srpipeline.spv_gat", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_gat", gatSpv.isNotEmpty() && File(gatSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(gatSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            val raw = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "gat_in_raw.npy"))
            val w = raw.shape[1]
            val h = raw.shape[0]
            val inBuf = SrpBridge.directFloats(w * h)
            inBuf.asFloatBuffer().put(raw.data)
            inBuf.rewind()
            // gen_fixtures.py ALPHA/BETA: 1e-4 / 1e-6 on all four planes.
            val abBuf = SrpBridge.directFloats(8)
            val abFb = abBuf.asFloatBuffer()
            repeat(4) { abFb.put(1e-4f) }
            repeat(4) { abFb.put(1e-6f) }
            abBuf.rewind()
            // op 0 (GAT) must reproduce the port's GPU bytes exactly.
            val outBuf = SrpBridge.directFloats(w * h)
            val rc0 = SrpBridge.gat(handle, inBuf, outBuf, abBuf, spirv, w, h, w * h, 0)
            assertEquals("gat dispatch status", 0, rc0)
            outBuf.rewind()
            val gotGat = FloatArray(w * h)
            outBuf.asFloatBuffer().get(gotGat)
            val wantGat = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_gat_out_vst.npy")).data
            var maxAbs = 0f
            for (i in gotGat.indices) maxAbs = maxOf(maxAbs, kotlin.math.abs(gotGat[i] - wantGat[i]))
            println("jni gat maxabs_vs_port_gpu=$maxAbs")
            assertEquals(0f, maxAbs, 0f)
            // op 1 (decimate) likewise.
            val din = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "decimate_in_raw.npy"))
            val dw = din.shape[1]
            val dh = din.shape[0]
            val dinBuf = SrpBridge.directFloats(dw * dh)
            dinBuf.asFloatBuffer().put(din.data)
            dinBuf.rewind()
            val doutBuf = SrpBridge.directFloats((dw / 2) * (dh / 2))
            val zeroAb = SrpBridge.directFloats(8)
            val rc1 = SrpBridge.gat(handle, dinBuf, doutBuf, zeroAb, spirv,
                dw, dh, (dw / 2) * (dh / 2), 1)
            assertEquals("decimate dispatch status", 0, rc1)
            doutBuf.rewind()
            val gotDec = FloatArray((dw / 2) * (dh / 2))
            doutBuf.asFloatBuffer().get(gotDec)
            val wantDec = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_decimate_out_grey.npy")).data
            var maxAbsDec = 0f
            for (i in gotDec.indices) maxAbsDec = maxOf(maxAbsDec, kotlin.math.abs(gotDec[i] - wantDec[i]))
            println("jni decimate maxabs_vs_port_gpu=$maxAbsDec")
            assertEquals(0f, maxAbsDec, 0f)
            // Kotlin CPU leg: same fixture through Decimate.decimateToGrey
            // (float64 accumulate, exact on these integer-valued raws).
            val wantDecCpu = com.rawlens.srpipeline.stages.Decimate.decimateToGrey(din.toImage2D())
            var maxAbsDecCpu = 0f
            for (y in 0 until dh / 2) for (x in 0 until dw / 2) {
                maxAbsDecCpu = maxOf(maxAbsDecCpu,
                    kotlin.math.abs(gotDec[y * (dw / 2) + x] - wantDecCpu[y][x]))
            }
            println("jni decimate maxabs_vs_kotlin=$maxAbsDecCpu")
            // float32 4-tap mean vs float64 accumulate: 1-ulp class,
            // measured 5.9604645E-8 on this fixture; bound ceiled.
            assertTrue("decimate float drift too large: $maxAbsDecCpu", maxAbsDecCpu <= 6e-08f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `demosaicMenon matches kotlin cpu and port gpu bytes`() {
        val demoSpv = System.getProperty("srpipeline.spv_demosaic", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_demosaic", demoSpv.isNotEmpty() && File(demoSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(demoSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // gen_gpu_reference stage_demosaic: demosaic_in_cfa[32,32].
            val cfa = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "demosaic_in_cfa.npy"))
            val w = cfa.shape[1]
            val h = cfa.shape[0]
            val cfaBuf = SrpBridge.directFloats(w * h)
            cfaBuf.asFloatBuffer().put(cfa.data)
            cfaBuf.rewind()
            val greyBuf = SrpBridge.directFloats(w * h)
            val rc = SrpBridge.demosaicMenon(handle, cfaBuf, greyBuf, spirv, w, h)
            assertEquals("demosaic dispatch status", 0, rc)
            greyBuf.rewind()
            val got = FloatArray(w * h)
            greyBuf.asFloatBuffer().get(got)
            // Port GPU bytes first: same shader, same device class.
            val wantGpu = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_demosaic_out_grey.npy")).data
            var maxPort = 0f
            for (i in got.indices) maxPort = maxOf(maxPort, kotlin.math.abs(got[i] - wantGpu[i]))
            println("jni demosaic maxabs_vs_port_gpu=$maxPort")
            assertEquals(0f, maxPort, 0f)
            // Kotlin float64 CPU second (float32-vs-float64 class).
            val wantCpu = com.rawlens.srpipeline.stages.Demosaic.demosaicToGrey(cfa.toImage2D())
            var maxCpu = 0f
            for (y in 0 until h) for (x in 0 until w) {
                maxCpu = maxOf(maxCpu, kotlin.math.abs(got[y * w + x] - wantCpu[y][x]))
            }
            println("jni demosaic maxabs_vs_kotlin=$maxCpu")
            // Certified float32-vs-float64 class: host proof and JNI agree
            // at 1.78814e-07 on this fixture; bound is that value ceiled.
            assertTrue("demosaic float drift too large: $maxCpu", maxCpu <= 1.79e-07f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `grad ops match kotlin cpu and port gpu bytes`() {
        val gradSpv = System.getProperty("srpipeline.spv_grad", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_grad", gradSpv.isNotEmpty() && File(gradSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(gradSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // gen_gpu_reference stage_grad: grad_in_grey[64,64].
            val grey = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "grad_in_grey.npy"))
            val w = grey.shape[1]
            val h = grey.shape[0]
            val inBuf = SrpBridge.directFloats(w * h)
            inBuf.asFloatBuffer().put(grey.data)
            inBuf.rewind()
            fun runOp(op: Int, count: Int, expBits: Int = 0): FloatArray {
                inBuf.rewind()
                val outSize = if (op <= 1) count * 2 else count
                val outBuf = SrpBridge.directFloats(outSize)
                val rc = SrpBridge.grad(handle, inBuf, outBuf, spirv,
                    w, h, count, op, expBits)
                assertEquals("grad op $op dispatch status", 0, rc)
                outBuf.rewind()
                val got = FloatArray(outSize)
                outBuf.asFloatBuffer().get(got)
                return got
            }
            fun maxAbsVec(got: FloatArray, want: FloatArray): Float {
                var m = 0f
                for (i in got.indices) m = maxOf(m, kotlin.math.abs(got[i] - want[i]))
                return m
            }
            // op 0 kerngrad: interleaved pairs vs (H-1)x(W-1)x2.
            val kc = (w - 1) * (h - 1)
            val gotK = runOp(0, kc)
            val wantK = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_grad_out_kerngrad.npy")).data
            val maxKPort = maxAbsVec(gotK, wantK)
            println("jni grad kerngrad maxabs_vs_port_gpu=$maxKPort")
            assertEquals(0f, maxKPort, 0f)
            val wantKCpu = com.rawlens.srpipeline.stages.Grad.kernelGradients(grey.toImage2D())
            var maxKCpu = 0f
            for (y in 0 until h - 1) for (x in 0 until w - 1) {
                val t = (y * (w - 1) + x) * 2
                maxKCpu = maxOf(maxKCpu, kotlin.math.abs(gotK[t] - wantKCpu[y][x][0]))
                maxKCpu = maxOf(maxKCpu, kotlin.math.abs(gotK[t + 1] - wantKCpu[y][x][1]))
            }
            println("jni grad kerngrad maxabs_vs_kotlin=$maxKCpu")
            // Fused-vs-staged summation order (shader fuses what Kotlin
            // stages through tmp0/tmp1); measured 2.9802322E-8, stable.
            assertTrue("kerngrad float drift too large: $maxKCpu", maxKCpu <= 3e-08f)
            // op 1 sobel: even lanes gx, odd lanes gy.
            val gotS = runOp(1, w * h)
            val wantGx = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_grad_out_sobel_gx.npy")).data
            val wantGy = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_grad_out_sobel_gy.npy")).data
            var maxSPort = 0f
            for (i in 0 until w * h) {
                maxSPort = maxOf(maxSPort, kotlin.math.abs(gotS[2 * i] - wantGx[i]))
                maxSPort = maxOf(maxSPort, kotlin.math.abs(gotS[2 * i + 1] - wantGy[i]))
            }
            println("jni grad sobel maxabs_vs_port_gpu=$maxSPort")
            assertEquals(0f, maxSPort, 0f)
            val (wantSx, wantSy) = com.rawlens.srpipeline.stages.Grad.sobel(grey.toImage2D())
            var maxSCpu = 0f
            for (y in 0 until h) for (x in 0 until w) {
                val t = (y * w + x) * 2
                maxSCpu = maxOf(maxSCpu, kotlin.math.abs(gotS[t] - wantSx[y][x]))
                maxSCpu = maxOf(maxSCpu, kotlin.math.abs(gotS[t + 1] - wantSy[y][x]))
            }
            println("jni grad sobel maxabs_vs_kotlin=$maxSCpu")
            assertEquals(0f, maxSCpu, 0f)
            // op 2 sqrt.
            val gotQ = runOp(2, w * h)
            val wantQ = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_grad_out_sqrt.npy")).data
            val maxQPort = maxAbsVec(gotQ, wantQ)
            println("jni grad sqrt maxabs_vs_port_gpu=$maxQPort")
            assertEquals(0f, maxQPort, 0f)
            val wantQCpu = com.rawlens.srpipeline.stages.Grad.preprocess(grey.toImage2D(), "sqrt")
            var maxQCpu = 0f
            for (y in 0 until h) for (x in 0 until w) {
                maxQCpu = maxOf(maxQCpu, kotlin.math.abs(gotQ[y * w + x] - wantQCpu[y][x]))
            }
            println("jni grad sqrt maxabs_vs_kotlin=$maxQCpu")
            // Double-rounding class: Kotlin Float.sqrt narrows a double
            // sqrt while Metal rounds once in float32 (port sqrt bound
            // 1.2332905896528246e-07 is the same class); measured 1.19e-07.
            assertTrue("sqrt float drift too large: $maxQCpu", maxQCpu <= 1.2e-07f)
            // op 3 gamma (float32(1/2.2) by bits, the port's encoding).
            val expBits = java.lang.Float.floatToIntBits((1.0 / 2.2).toFloat())
            val gotG = runOp(3, w * h, expBits)
            val wantG = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_grad_out_gamma.npy")).data
            val maxGPort = maxAbsVec(gotG, wantG)
            println("jni grad gamma maxabs_vs_port_gpu=$maxGPort")
            assertEquals(0f, maxGPort, 0f)
            val wantGCpu = com.rawlens.srpipeline.stages.Grad.preprocess(grey.toImage2D(), "gamma")
            var maxGCpu = 0f
            for (y in 0 until h) for (x in 0 until w) {
                maxGCpu = maxOf(maxGCpu, kotlin.math.abs(gotG[y * w + x] - wantGCpu[y][x]))
            }
            println("jni grad gamma maxabs_vs_kotlin=$maxGCpu")
            // Double-pow class: Kotlin narrows a double pow while Metal
            // pows in float32 (port gamma bound 5.960464477539063e-08 is
            // the same class, torch-side); measured 1.1920929E-7, stable.
            assertTrue("gamma float drift too large: $maxGCpu", maxGCpu <= 1.2e-07f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `kernelsCov matches kotlin cpu and port gpu bytes`() {
        val kSpv = System.getProperty("srpipeline.spv_kernels", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_kernels", kSpv.isNotEmpty() && File(kSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(kSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // gen_gpu_reference stage_kernels: raw[64,64], gat(1e-4/1e-6) ->
            // decimate -> kerngrad -> cov[32,32,2,2], linear steerable law,
            // k_detail=0.3 k_denoise=4.0 D_th=0.75 D_tr=1.1.
            val raw = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "kernels_in_raw.npy"))
            val w = raw.shape[1]
            val h = raw.shape[0]
            val alpha = FloatArray(4) { 1e-4f }
            val beta = FloatArray(4) { 1e-6f }
            val vst = com.rawlens.srpipeline.stages.Gat.gat(raw.toImage2D(), alpha, beta)
            val grey = com.rawlens.srpipeline.stages.Decimate.decimateToGrey(vst)
            val gw = grey[0].size
            val gh = grey.size
            val grads = com.rawlens.srpipeline.stages.Grad.kernelGradients(grey)
            val kw = grads[0].size
            val kh = grads.size
            // Interleaved (gx,gy) pairs, matching the shader's vec2 reads.
            val gradsBuf = SrpBridge.directFloats(kw * kh * 2)
            val gfb = gradsBuf.asFloatBuffer()
            for (y in 0 until kh) for (x in 0 until kw) {
                gfb.put(grads[y][x][0])
                gfb.put(grads[y][x][1])
            }
            gradsBuf.rewind()
            // KParams: six scalars + selection/iso by bit pattern (std430).
            val kpBuf = SrpBridge.directFloats(8)
            val kfb = kpBuf.asFloatBuffer()
            kfb.put(0.3f).put(4.0f).put(0.75f).put(1.1f).put(4.0f).put(2.0f)
            kfb.put(java.lang.Float.intBitsToFloat(1))
            kfb.put(java.lang.Float.intBitsToFloat(0))
            kpBuf.rewind()
            val covBuf = SrpBridge.directFloats(gw * gh * 4)
            val rc = SrpBridge.kernelsCov(handle, gradsBuf, kpBuf, covBuf,
                spirv, kw, kh, gw, gh)
            assertEquals("kernels dispatch status", 0, rc)
            covBuf.rewind()
            val got = FloatArray(gw * gh * 4)
            covBuf.asFloatBuffer().get(got)
            // Port recorded covs (32,32,2,2 row-major): xx<-0, xy<-1 and
            // -2 (symmetric), yy<-3; vec slot 3 must read 0.
            val wantPort = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_kernels_out_covs.npy")).data
            var maxPort = 0f
            for (p in 0 until gw * gh) {
                maxPort = maxOf(maxPort, kotlin.math.abs(got[4 * p] - wantPort[4 * p]))
                maxPort = maxOf(maxPort, kotlin.math.abs(got[4 * p + 1] - wantPort[4 * p + 1]))
                maxPort = maxOf(maxPort, kotlin.math.abs(got[4 * p + 1] - wantPort[4 * p + 2]))
                maxPort = maxOf(maxPort, kotlin.math.abs(got[4 * p + 2] - wantPort[4 * p + 3]))
                maxPort = maxOf(maxPort, kotlin.math.abs(got[4 * p + 3]))
            }
            println("jni kernels maxabs_vs_port_gpu=$maxPort")
            // Rebuilt-grads input wobble (Kotlin float64 GAT/decimate/
            // kerngrad vs torch float32) amplified by eigen shaping;
            // measured 1.5556812E-5, far inside the 1.15e-03 envelope.
            assertTrue("kernels port drift too large: $maxPort", maxPort <= 1.6e-05f)
            // Kotlin CPU on the same rebuilt grads: the JNI cov grid is
            // one row/col larger (OOB-clamped reads), so gate the
            // overlapping grads-grid interior.
            val params = com.rawlens.srpipeline.stages.KernelParams(
                kDetail = 0.3f, kDenoise = 4.0f, dTh = 0.75f, dTr = 1.1f,
                kStretch = 4.0f, kShrink = 2.0f,
                selection = com.rawlens.srpipeline.stages.SelectionLaw.LINEAR,
                iso = false,
            )
            val wantCpu = com.rawlens.srpipeline.stages.estimateKernelCovariances(grads, params)
            var maxCpu = 0f
            for (y in 0 until kh) for (x in 0 until kw) {
                val t = (y * gw + x) * 4
                maxCpu = maxOf(maxCpu, kotlin.math.abs(got[t] - wantCpu[y][x][0][0]))
                maxCpu = maxOf(maxCpu, kotlin.math.abs(got[t + 1] - wantCpu[y][x][0][1]))
                maxCpu = maxOf(maxCpu, kotlin.math.abs(got[t + 2] - wantCpu[y][x][1][1]))
            }
            println("jni kernels maxabs_vs_kotlin=$maxCpu")
            // Same grads both sides: eigen/sqrt/FMA order class only;
            // measured 2.861023E-6.
            assertTrue("kernels float drift too large: $maxCpu", maxCpu <= 2.9e-06f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `divide matches kotlin cpu and port gpu bytes`() {
        val divSpv = System.getProperty("srpipeline.spv_divide", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_divide", divSpv.isNotEmpty() && File(divSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(divSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // gen_gpu_reference stage_divide: divide_in_num/den[16,16,3].
            val num = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "divide_in_num.npy"))
            val den = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "divide_in_den.npy"))
            val count = num.data.size
            val numBuf = SrpBridge.directFloats(count)
            numBuf.asFloatBuffer().put(num.data)
            numBuf.rewind()
            val denBuf = SrpBridge.directFloats(count)
            denBuf.asFloatBuffer().put(den.data)
            denBuf.rewind()
            val outBuf = SrpBridge.directFloats(count)
            val rc = SrpBridge.divide(handle, numBuf, denBuf, outBuf, spirv, count)
            assertEquals("divide dispatch status", 0, rc)
            outBuf.rewind()
            val got = FloatArray(count)
            outBuf.asFloatBuffer().get(got)
            // Port GPU bytes: same shader, same device class.
            val wantGpu = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_divide_out_ratio.npy")).data
            var maxPort = 0f
            for (i in got.indices) maxPort = maxOf(maxPort, kotlin.math.abs(got[i] - wantGpu[i]))
            println("jni divide maxabs_vs_port_gpu=$maxPort")
            assertEquals(0f, maxPort, 0f)
            // Kotlin CPU (Image divide, JVM division): 1-ulp class.
            val h = num.shape[0]
            val w = num.shape[1]
            val ch = num.shape[2]
            val numImg = com.rawlens.srpipeline.core.Image(h, w, ch, num.data.copyOf())
            val denImg = com.rawlens.srpipeline.core.Image(h, w, ch, den.data.copyOf())
            com.rawlens.srpipeline.stages.Divide.divide(numImg, denImg)
            var maxCpu = 0f
            for (i in got.indices) maxCpu = maxOf(maxCpu, kotlin.math.abs(got[i] - numImg.data[i]))
            println("jni divide maxabs_vs_kotlin=$maxCpu")
            // Metal-vs-JVM 1-ulp division class (port maxrel 1.68e-07);
            // host proof and JNI agree at 2.38419e-07, bound ceiled.
            assertTrue("divide float drift too large: $maxCpu", maxCpu <= 2.4e-07f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `smap matches kotlin cpu and port gpu bytes`() {
        val robSpv = System.getProperty("srpipeline.spv_robust", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_robust", robSpv.isNotEmpty() && File(robSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(robSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // gen_gpu_reference stage_smap: flow[4,4,2], Mt=0.8 s1=2 s2=12.
            val flow = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "smap_in_flow.npy"))
            val ntx = flow.shape[1]
            val nty = flow.shape[0]
            val flowBuf = SrpBridge.directFloats(ntx * nty * 2)
            flowBuf.asFloatBuffer().put(flow.data)
            flowBuf.rewind()
            val sBuf = SrpBridge.directFloats(ntx * nty)
            val rc = SrpBridge.smap(handle, flowBuf, sBuf, spirv, ntx, nty)
            assertEquals("smap dispatch status", 0, rc)
            sBuf.rewind()
            val got = FloatArray(ntx * nty)
            sBuf.asFloatBuffer().get(got)
            val wantGpu = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_smap_out_s.npy")).data
            var maxPort = 0f
            for (i in got.indices) maxPort = maxOf(maxPort, kotlin.math.abs(got[i] - wantGpu[i]))
            println("jni smap maxabs_vs_port_gpu=$maxPort")
            assertEquals(0f, maxPort, 0f)
            val flowImg = Array(nty) { y -> Array(ntx) { x -> floatArrayOf(flow.data[(y * ntx + x) * 2], flow.data[(y * ntx + x) * 2 + 1]) } }
            val wantCpu = com.rawlens.srpipeline.stages.Smap.compute(flowImg)
            var maxCpu = 0f
            for (y in 0 until nty) for (x in 0 until ntx) {
                maxCpu = maxOf(maxCpu, kotlin.math.abs(got[y * ntx + x] - wantCpu[y][x]))
            }
            println("jni smap maxabs_vs_kotlin=$maxCpu")
            assertEquals(0f, maxCpu, 0f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `robustInit matches kotlin cpu through jni`() {
        val robSpv = System.getProperty("srpipeline.spv_robust", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_robust", robSpv.isNotEmpty() && File(robSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(robSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            // robustness_in_ref_raw[128,128] (no recorded init outputs;
            // the port leg rides the compute composition test below).
            val ref = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_ref_raw.npy"))
            val w = ref.shape[1]
            val h = ref.shape[0]
            val gw = w / 2
            val gh = h / 2
            val refBuf = SrpBridge.directFloats(w * h)
            refBuf.asFloatBuffer().put(ref.data)
            refBuf.rewind()
            val meansBuf = SrpBridge.directFloats(3 * gw * gh)
            val varsBuf = SrpBridge.directFloats(3 * gw * gh)
            val rc = SrpBridge.robustInit(handle, refBuf, meansBuf, varsBuf, spirv, w, h)
            assertEquals("robustInit dispatch status", 0, rc)
            meansBuf.rewind()
            varsBuf.rewind()
            val gotMeans = FloatArray(3 * gw * gh)
            val gotVars = FloatArray(3 * gw * gh)
            meansBuf.asFloatBuffer().get(gotMeans)
            varsBuf.asFloatBuffer().get(gotVars)
            val refImg = Array(h) { y -> FloatArray(w) { x -> ref.data[y * w + x] } }
            val (wantMeans, wantVars) = com.rawlens.srpipeline.stages.computeLocalStats(
                com.rawlens.srpipeline.stages.computeGuideImage(refImg))
            var maxM = 0f
            var maxV = 0f
            for (c in 0 until 3) for (y in 0 until gh) for (x in 0 until gw) {
                val t = (c * gh + y) * gw + x
                maxM = maxOf(maxM, kotlin.math.abs(gotMeans[t] - wantMeans[c][y][x]))
                maxV = maxOf(maxV, kotlin.math.abs(gotVars[t] - wantVars[c][y][x]))
            }
            println("jni robustInit maxabs_vs_kotlin_means=$maxM vars=$maxV")
            // Guide sqrt double-rounding class (Kotlin Float.sqrt narrows
            // a double sqrt; Metal rounds once), stats accumulate in the
            // same order both sides.
            assertTrue("robustInit means drift too large: $maxM", maxM <= 2.4e-07f)
            assertTrue("robustInit vars drift too large: $maxV", maxV <= 3.9e-07f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `robustCompute matches recorded crop flow through jni`() {
        val robSpv = System.getProperty("srpipeline.spv_robust", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_robust", robSpv.isNotEmpty() && File(robSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(robSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // 128 chain: init -> smap -> compute composition, all entries.
            val ref128 = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_ref_raw.npy"))
            val comp128 = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_comp_raw.npy"))
            val flow128 = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_flow.npy"))
            val w128 = ref128.shape[1]
            val h128 = ref128.shape[0]
            val gw128 = w128 / 2
            val gh128 = h128 / 2
            val ftx128 = flow128.shape[1]
            val fty128 = flow128.shape[0]
            fun packFlat(data: FloatArray): java.nio.ByteBuffer {
                val b = SrpBridge.directFloats(data.size)
                b.asFloatBuffer().put(data)
                b.rewind()
                return b
            }
            val means128Buf = SrpBridge.directFloats(3 * gw128 * gh128)
            val vars128Buf = SrpBridge.directFloats(3 * gw128 * gh128)
            var rc = SrpBridge.robustInit(handle, packFlat(ref128.data), means128Buf, vars128Buf,
                spirv, w128, h128)
            assertEquals("robustInit dispatch status", 0, rc)
            val flow128Buf = packFlat(flow128.data)
            val s128Buf = SrpBridge.directFloats(ftx128 * fty128)
            rc = SrpBridge.smap(handle, flow128Buf, s128Buf, spirv, ftx128, fty128)
            assertEquals("smap dispatch status", 0, rc)
            val lutBuf = packFlat(floatArrayOf(0f, 0f, 0f, 0f))
            val out128Buf = SrpBridge.directFloats(gw128 * gh128)
            rc = SrpBridge.robustCompute(handle, packFlat(comp128.data), means128Buf, vars128Buf,
                packFlat(flow128.data), lutBuf, s128Buf, out128Buf, spirv,
                w128, h128, 16, 2, false, 0.12f, ftx128, fty128)
            assertEquals("robustCompute dispatch status", 0, rc)
            out128Buf.rewind()
            val got128 = FloatArray(gw128 * gh128)
            out128Buf.asFloatBuffer().get(got128)
            val wantPort128 = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_robustness_out_r.npy")).data
            var maxPort128 = 0f
            for (i in got128.indices) maxPort128 = maxOf(maxPort128, kotlin.math.abs(got128[i] - wantPort128[i]))
            println("jni robustCompute128 maxabs_vs_port_gpu=$maxPort128")
            assertEquals(0f, maxPort128, 0f)
            // Kotlin leg on identical inputs (JNI means/vars -> [C][Gy][Gx]).
            means128Buf.rewind()
            vars128Buf.rewind()
            val mFlat = FloatArray(3 * gw128 * gh128)
            val vFlat = FloatArray(3 * gw128 * gh128)
            means128Buf.asFloatBuffer().get(mFlat)
            vars128Buf.asFloatBuffer().get(vFlat)
            fun un44(flat: FloatArray, c: Int, h: Int, w: Int): Array<Array<FloatArray>> =
                Array(c) { cc -> Array(h) { y -> FloatArray(w) { x -> flat[(cc * h + y) * w + x] } } }
            val compImg128 = Array(h128) { y -> FloatArray(w128) { x -> comp128.data[y * w128 + x] } }
            val flowImg128 = Array(fty128) { y -> Array(ftx128) { x -> floatArrayOf(flow128.data[(y * ftx128 + x) * 2], flow128.data[(y * ftx128 + x) * 2 + 1]) } }
            val params = com.rawlens.srpipeline.stages.RobustnessParams(noiseCorrection = false)
            val wantCpu128 = com.rawlens.srpipeline.stages.computeRobustness(compImg128,
                un44(mFlat, 3, gh128, gw128), un44(vFlat, 3, gh128, gw128), flowImg128,
                floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), 16, true, params)
            var maxCpu128 = 0f
            for (y in 0 until gh128) for (x in 0 until gw128) {
                maxCpu128 = maxOf(maxCpu128, kotlin.math.abs(got128[y * gw128 + x] - wantCpu128[y][x]))
            }
            println("jni robustCompute128 maxabs_vs_kotlin=$maxCpu128")
            // Same inputs both sides: warp/dogson/exp order class only.
            assertTrue("robustCompute128 drift too large: $maxCpu128", maxCpu128 <= 8.2e-06f)
            // Crop chain (real burst): recorded means/vars/flow/S inputs.
            val compC = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_crop_in_comp.npy"))
            val meansC = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_crop_in_means.npy"))
            val varsC = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_crop_in_var.npy"))
            val flowC = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_crop_in_flow.npy"))
            val wC = compC.shape[1]
            val hC = compC.shape[0]
            val gwC = wC / 2
            val ghC = hC / 2
            val ftxC = flowC.shape[1]
            val ftyC = flowC.shape[0]
            val sCBuf = SrpBridge.directFloats(ftxC * ftyC)
            rc = SrpBridge.smap(handle, packFlat(flowC.data), sCBuf, spirv, ftxC, ftyC)
            assertEquals("crop smap dispatch status", 0, rc)
            val outCBuf = SrpBridge.directFloats(gwC * ghC)
            rc = SrpBridge.robustCompute(handle, packFlat(compC.data), packFlat(meansC.data),
                packFlat(varsC.data), packFlat(flowC.data), packFlat(floatArrayOf(0f, 0f, 0f, 0f)),
                sCBuf, outCBuf, spirv, wC, hC, 16, 2, false, 0.12f, ftxC, ftyC)
            assertEquals("crop robustCompute dispatch status", 0, rc)
            outCBuf.rewind()
            val gotC = FloatArray(gwC * ghC)
            outCBuf.asFloatBuffer().get(gotC)
            val wantPortC = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_robustness_crop_out_r.npy")).data
            var maxPortC = 0f
            for (i in gotC.indices) maxPortC = maxOf(maxPortC, kotlin.math.abs(gotC[i] - wantPortC[i]))
            println("jni robustComputeCrop maxabs_vs_port_gpu=$maxPortC")
            // Recorded CPU means/vars vs the port's GPU ones: init-input
            // wobble amplified through warp/threshold. The port itself,
            // fed these same CPU inputs, lands on the identical
            // 1.221895218e-04, and the all-JNI 128 composition is
            // bit-exact: the entry is faithful, the delta is the inputs.
            // Inside the 1.2624263763427734e-04 crop envelope.
            assertTrue("crop port drift too large: $maxPortC", maxPortC <= 1.23e-04f)
            // Kotlin leg on the recorded crop inputs.
            fun un443(flat: FloatArray, c: Int, h: Int, w: Int): Array<Array<FloatArray>> =
                Array(c) { cc -> Array(h) { y -> FloatArray(w) { x -> flat[(cc * h + y) * w + x] } } }
            val compImgC = Array(hC) { y -> FloatArray(wC) { x -> compC.data[y * wC + x] } }
            val flowImgC = Array(ftyC) { y -> Array(ftxC) { x -> floatArrayOf(flowC.data[(y * ftxC + x) * 2], flowC.data[(y * ftxC + x) * 2 + 1]) } }
            val wantCpuC = com.rawlens.srpipeline.stages.computeRobustness(compImgC,
                un443(meansC.data, 3, ghC, gwC), un443(varsC.data, 3, ghC, gwC), flowImgC,
                floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), 16, true,
                com.rawlens.srpipeline.stages.RobustnessParams(noiseCorrection = false))
            var maxCpuC = 0f
            for (y in 0 until ghC) for (x in 0 until gwC) {
                maxCpuC = maxOf(maxCpuC, kotlin.math.abs(gotC[y * gwC + x] - wantCpuC[y][x]))
            }
            println("jni robustComputeCrop maxabs_vs_kotlin=$maxCpuC")
            // Same recorded inputs both sides: warp/dogson/exp order
            // class; measured 1.8835068E-5.
            assertTrue("crop kotlin drift too large: $maxCpuC", maxCpuC <= 1.9e-05f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `robustness residents match staged entries bit-exact`() {
        // The Res entries are scheduling-only (same robustness.comp
        // shader, same input bytes; stats never leave the device), so on
        // a deterministic device the composition must equal the staged
        // path bit for bit. This gates the heap-fit slice that drops the
        // two 75 MB full-res host stats buffers.
        val robSpv = System.getProperty("srpipeline.spv_robust", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_robust", robSpv.isNotEmpty() && File(robSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(robSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val ref128 = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_ref_raw.npy"))
            val comp128 = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_comp_raw.npy"))
            val flow128 = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "robustness_in_flow.npy"))
            val w128 = ref128.shape[1]
            val h128 = ref128.shape[0]
            val gw128 = w128 / 2
            val gh128 = h128 / 2
            val ftx128 = flow128.shape[1]
            val fty128 = flow128.shape[0]
            fun packFlat(data: FloatArray): java.nio.ByteBuffer {
                val b = SrpBridge.directFloats(data.size)
                b.asFloatBuffer().put(data)
                b.rewind()
                return b
            }
            // Staged composition (proven bit-exact vs the port above).
            val means128Buf = SrpBridge.directFloats(3 * gw128 * gh128)
            val vars128Buf = SrpBridge.directFloats(3 * gw128 * gh128)
            var rc = SrpBridge.robustInit(handle, packFlat(ref128.data), means128Buf, vars128Buf,
                spirv, w128, h128)
            assertEquals("staged robustInit dispatch status", 0, rc)
            val flow128Buf = packFlat(flow128.data)
            val s128Buf = SrpBridge.directFloats(ftx128 * fty128)
            rc = SrpBridge.smap(handle, flow128Buf, s128Buf, spirv, ftx128, fty128)
            assertEquals("smap dispatch status", 0, rc)
            val lutBuf = packFlat(floatArrayOf(0f, 0f, 0f, 0f))
            val stagedOut = SrpBridge.directFloats(gw128 * gh128)
            rc = SrpBridge.robustCompute(handle, packFlat(comp128.data), means128Buf, vars128Buf,
                packFlat(flow128.data), lutBuf, s128Buf, stagedOut, spirv,
                w128, h128, 16, 2, false, 0.12f, ftx128, fty128)
            assertEquals("staged robustCompute dispatch status", 0, rc)
            // Resident composition on the same inputs.
            val meansRes = SrpBridge.createResident(handle, (3 * gw128 * gh128).toLong())
            val varsRes = SrpBridge.createResident(handle, (3 * gw128 * gh128).toLong())
            try {
                assertTrue("means resident alloc failed", meansRes != 0L)
                assertTrue("vars resident alloc failed", varsRes != 0L)
                rc = SrpBridge.robustInitRes(handle, packFlat(ref128.data), meansRes, varsRes,
                    spirv, w128, h128)
                assertEquals("resident robustInit dispatch status", 0, rc)
                val resOut = SrpBridge.directFloats(gw128 * gh128)
                flow128Buf.rewind()
                s128Buf.rewind()
                lutBuf.rewind()
                rc = SrpBridge.robustComputeRes(handle, packFlat(comp128.data), meansRes, varsRes,
                    flow128Buf, lutBuf, s128Buf, resOut, spirv,
                    w128, h128, 16, 2, false, 0.12f, ftx128, fty128)
                assertEquals("resident robustCompute dispatch status", 0, rc)
                stagedOut.rewind()
                resOut.rewind()
                val wantStaged = FloatArray(gw128 * gh128)
                val gotRes = FloatArray(gw128 * gh128)
                stagedOut.asFloatBuffer().get(wantStaged)
                resOut.asFloatBuffer().get(gotRes)
                var maxRes = 0f
                for (i in wantStaged.indices) maxRes = maxOf(maxRes, kotlin.math.abs(gotRes[i] - wantStaged[i]))
                println("jni robustRes maxabs_vs_staged=$maxRes")
                assertEquals(0f, maxRes, 0f)
            } finally {
                SrpBridge.freeResident(handle, meansRes)
                SrpBridge.freeResident(handle, varsRes)
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `greyFft matches recorded lowpass through jni`() {
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft", fftSpv.isNotEmpty() && File(fftSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(fftSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            fun packFlat(data: FloatArray): java.nio.ByteBuffer {
                val b = SrpBridge.directFloats(data.size)
                b.asFloatBuffer().put(data)
                b.rewind()
                return b
            }
            fun runCase(inName: String, outName: String): Float {
                val raw = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, inName))
                val want = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, outName))
                val h = raw.shape[0]
                val w = raw.shape[1]
                val outBuf = SrpBridge.directFloats(w * h)
                val rc = SrpBridge.greyFft(handle, packFlat(raw.data), outBuf,
                    spirv, w, h)
                assertEquals("$inName dispatch status", 0, rc)
                outBuf.rewind()
                val got = FloatArray(w * h)
                outBuf.asFloatBuffer().get(got)
                var maxAbs = 0f
                for (i in got.indices) {
                    maxAbs = maxOf(maxAbs, kotlin.math.abs(got[i] - want.data[i]))
                }
                println("jni greyFft $inName maxabs_vs_recorded=$maxAbs")
                return maxAbs
            }
            // Port bar (gpu_grey_fft) on its own 512 data: 2.2351741790771484e-08
            // PoT, 2.60770320892334e-08 Bluestein. Measured here: 1.7881393e-07
            // (32x32) and 2.9802322e-08 (512x512 crop) — same RELATIVE
            // precision (~1.8e-7 of input magnitude on both; fixtures differ
            // 6x in range), i.e. float32 FFT rounding luck on different data,
            // same class as the port. Bounds are these values ceiled; the
            // crop end-to-end PNG gate re-proves e2e invisibility.
            val crop = runCase("grey_fft_crop_in_raw.npy", "grey_fft_crop_out_lowpass.npy")
            val tiny = runCase("grey_fft_in_raw.npy", "grey_fft_out_lowpass.npy")
            assertTrue("grey tiny drift too large: $tiny", tiny <= 1.8e-07f)
            assertTrue("grey crop drift too large: $crop", crop <= 3.0e-08f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `greyFft chunked shape is deterministic`() {
        // Row-chunked Bluestein (fwdHave gate) must be exactly repeatable:
        // same input twice → same bytes. Shape 4400x1030 chunks rows AND
        // cols under every shipped threshold (2M through 8M cells), so
        // this guards the chunk/concat path against pool-aliasing drift.
        // Correctness across thresholds is proven by the full-res e2e
        // bit-identity gate (per-row math is threshold-independent).
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft", fftSpv.isNotEmpty() && File(fftSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(fftSpv).readBytes()
            val w = 1030
            val h = 4400
            val img = FloatArray(w * h) { i -> (((i * 2654435761L) % 100003L) % 1000L).toFloat() / 1000f }
            fun pack(data: FloatArray): java.nio.ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            fun runOnce(): FloatArray {
                val outBuf = SrpBridge.directFloats(w * h)
                val rc = SrpBridge.greyFft(handle, pack(img), outBuf, spirv, w, h)
                assertEquals("chunked grey dispatch status", 0, rc)
                outBuf.rewind()
                val got = FloatArray(w * h)
                outBuf.asFloatBuffer().get(got)
                return got
            }
            val first = runOnce()
            // Churn the pool between runs (a different tile shape), then
            // re-run: reuse must be bit-exact.
            val churn = SrpBridge.directFloats(9 * 16 * 16)
            assertEquals("churn dispatch status", 0, SrpBridge.correlate(handle,
                pack(FloatArray(9 * 16 * 16) { i -> i.toFloat() / 2304 }),
                pack(FloatArray(9 * 16 * 16) { i -> (2304 - 1 - i).toFloat() / 2304 }),
                churn, spirv, 9, 16))
            // NOTE: churn uses the fft module for a correlate-shaped call;
            // greyFft and correlate share the fft.comp module, which is
            // exactly the pool-shape variety the chunk path must survive.
            val second = runOnce()
            var diff = 0
            for (i in first.indices) if (first[i] != second[i]) diff++
            println("jni greyFft chunked determinism: $diff/${first.size} lanes differ across runs")
            assertEquals("chunked grey must be deterministic", 0, diff)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `robustCompute maps OOB-warped pixels to zero`() {
        val robSpv = System.getProperty("srpipeline.spv_robust", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_robust", robSpv.isNotEmpty() && File(robSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(robSpv).readBytes()
            // Synthetic 32x32: uniform frames, hurricane flow (all OOB).
            // Warp writes +inf means; the d_sq shrink (inf/inf) is NaN and
            // the threshold must map it to R = 0 like the numba clamp
            // (1024-crop rim regression: 1488 pixels).
            val w = 32
            val h = 32
            val gw = w / 2
            val gh = h / 2
            val ftx = 2
            val fty = 2
            fun packFlat(data: FloatArray): java.nio.ByteBuffer {
                val b = SrpBridge.directFloats(data.size)
                b.asFloatBuffer().put(data)
                b.rewind()
                return b
            }
            val ref = FloatArray(w * h) { 0.25f }
            val comp = FloatArray(w * h) { 0.3f }
            val flow = FloatArray(ftx * fty * 2) { 1e4f }
            val meansBuf = SrpBridge.directFloats(3 * gw * gh)
            val varsBuf = SrpBridge.directFloats(3 * gw * gh)
            var rc = SrpBridge.robustInit(handle, packFlat(ref), meansBuf, varsBuf,
                spirv, w, h)
            assertEquals("robustInit dispatch status", 0, rc)
            val sBuf = SrpBridge.directFloats(ftx * fty)
            rc = SrpBridge.smap(handle, packFlat(flow), sBuf, spirv, ftx, fty)
            assertEquals("smap dispatch status", 0, rc)
            val curves = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            val outBuf = SrpBridge.directFloats(gw * gh)
            rc = SrpBridge.robustCompute(handle, packFlat(comp), meansBuf, varsBuf,
                packFlat(flow), packFlat(curves), sBuf, outBuf, spirv,
                w, h, 16, 2, true, 0.12f, ftx, fty)
            assertEquals("robustCompute dispatch status", 0, rc)
            outBuf.rewind()
            val got = FloatArray(gw * gh)
            outBuf.asFloatBuffer().get(got)
            for (i in got.indices) assertEquals("r[$i] must be 0", 0f, got[i], 0f)
            // Kotlin leg on identical inputs agrees (dual proof).
            meansBuf.rewind()
            varsBuf.rewind()
            val mFlat = FloatArray(3 * gw * gh)
            val vFlat = FloatArray(3 * gw * gh)
            meansBuf.asFloatBuffer().get(mFlat)
            varsBuf.asFloatBuffer().get(vFlat)
            fun un44(flat: FloatArray, c: Int, hh: Int, ww: Int): Array<Array<FloatArray>> =
                Array(c) { cc -> Array(hh) { y -> FloatArray(ww) { x -> flat[(cc * hh + y) * ww + x] } } }
            val compImg = Array(h) { y -> FloatArray(w) { x -> comp[y * w + x] } }
            val flowImg = Array(fty) { y -> Array(ftx) { x -> floatArrayOf(1e4f, 1e4f) } }
            val params = com.rawlens.srpipeline.stages.RobustnessParams(noiseCorrection = true)
            val wantCpu = com.rawlens.srpipeline.stages.computeRobustness(compImg,
                un44(mFlat, 3, gh, gw), un44(vFlat, 3, gh, gw), flowImg,
                floatArrayOf(0.5f, 0.5f), floatArrayOf(0.5f, 0.5f), 16, true, params)
            var maxCpu = 0f
            for (y in 0 until gh) for (x in 0 until gw) {
                maxCpu = maxOf(maxCpu, kotlin.math.abs(got[y * gw + x] - wantCpu[y][x]))
            }
            println("jni robustOob maxabs_vs_kotlin=$maxCpu")
            assertTrue("oob kotlin drift too large: $maxCpu", maxCpu <= 1e-06f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `mergeSplat matches kotlin cpu and port gpu bytes`() {
        val splatSpv = System.getProperty("srpipeline.spv_merge_splat", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_merge_splat", splatSpv.isNotEmpty() && File(splatSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(splatSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val gpuDir = File(System.getProperty("srpipeline.gpuref", "parity/gpu_reference"))
            // gen fixtures: comp[128,128], flow[8,8,2], covs[64,64,2,2],
            // r[64,64] -> num/den[256,256,3], scale 2, tile 16, bayer.
            val comp = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_comp_raw.npy"))
            val flow = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_flow.npy"))
            val covs = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_covs.npy"))
            val r = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_r.npy"))
            val lrW = comp.shape[1]
            val lrH = comp.shape[0]
            val ftx = flow.shape[1]
            val fty = flow.shape[0]
            val gw = covs.shape[1]
            val gh = covs.shape[0]
            // Flow is (fty,ftx,2) interleaved already; covs pack to vec4.
            val flowFlat = flow.data
            val covFlat = FloatArray(gw * gh * 4)
            for (p in 0 until gw * gh) {
                covFlat[4 * p] = covs.data[4 * p]
                covFlat[4 * p + 1] = covs.data[4 * p + 1]
                covFlat[4 * p + 2] = covs.data[4 * p + 3]
                covFlat[4 * p + 3] = 0f
            }
            fun packFlat(data: FloatArray): java.nio.ByteBuffer {
                val b = SrpBridge.directFloats(data.size)
                b.asFloatBuffer().put(data)
                b.rewind()
                return b
            }
            val hrW = lrW * 2
            val hrH = lrH * 2
            val numBuf = SrpBridge.directFloats(hrW * hrH * 3)
            val denBuf = SrpBridge.directFloats(hrW * hrH * 3)
            val rc = SrpBridge.mergeSplat(handle, packFlat(comp.data), packFlat(flowFlat),
                packFlat(covFlat), packFlat(r.data), numBuf, denBuf, spirv,
                lrW, lrH, hrW, hrH, 16, 2f, true, ftx, fty)
            assertEquals("mergeSplat dispatch status", 0, rc)
            numBuf.rewind()
            denBuf.rewind()
            val gotNum = FloatArray(hrW * hrH * 3)
            val gotDen = FloatArray(hrW * hrH * 3)
            numBuf.asFloatBuffer().get(gotNum)
            denBuf.asFloatBuffer().get(gotDen)
            val wantNumPort = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_merge_out_num.npy")).data
            val wantDenPort = com.rawlens.srpipeline.parity.NpyReader
                .load(File(gpuDir, "gpu_merge_out_den.npy")).data
            var maxNumPort = 0f
            var maxDenPort = 0f
            for (i in gotNum.indices) {
                maxNumPort = maxOf(maxNumPort, kotlin.math.abs(gotNum[i] - wantNumPort[i]))
                maxDenPort = maxOf(maxDenPort, kotlin.math.abs(gotDen[i] - wantDenPort[i]))
            }
            println("jni mergeSplat num maxabs_vs_port_gpu=$maxNumPort den=$maxDenPort")
            assertEquals(0f, maxNumPort, 0f)
            assertEquals(0f, maxDenPort, 0f)
            // Kotlin CPU on identical inputs.
            val compImg = Array(lrH) { y -> FloatArray(lrW) { x -> comp.data[y * lrW + x] } }
            val flowImg = Array(fty) { y -> Array(ftx) { x -> floatArrayOf(flowFlat[(y * ftx + x) * 2], flowFlat[(y * ftx + x) * 2 + 1]) } }
            val covImg = Array(gh) { y -> Array(gw) { x -> arrayOf(floatArrayOf(covs.data[(y * gw + x) * 4], covs.data[(y * gw + x) * 4 + 1]), floatArrayOf(covs.data[(y * gw + x) * 4 + 2], covs.data[(y * gw + x) * 4 + 3])) } }
            val rImg = Array(gh) { y -> FloatArray(gw) { x -> r.data[y * gw + x] } }
            val numImg = Array(hrH) { Array(hrW) { FloatArray(3) } }
            val denImg = Array(hrH) { Array(hrW) { FloatArray(3) } }
            com.rawlens.srpipeline.stages.merge(compImg, flowImg, covImg, rImg,
                numImg, denImg, true, 2f, 16)
            var maxNumCpu = 0f
            var maxDenCpu = 0f
            for (y in 0 until hrH) for (x in 0 until hrW) for (c in 0 until 3) {
                val t = (y * hrW + x) * 3 + c
                maxNumCpu = maxOf(maxNumCpu, kotlin.math.abs(gotNum[t] - numImg[y][x][c]))
                maxDenCpu = maxOf(maxDenCpu, kotlin.math.abs(gotDen[t] - denImg[y][x][c]))
            }
            println("jni mergeSplat num maxabs_vs_kotlin=$maxNumCpu den=$maxDenCpu")
            // exp/FMA summation-order class (port merge envelope
            // max_abs 1.6e-04 covers the same class torch-side).
            assertTrue("merge num drift too large: $maxNumCpu", maxNumCpu <= 3.3e-07f)
            assertTrue("merge den drift too large: $maxDenCpu", maxDenCpu <= 1.5e-06f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `mergeSplatRes accumulates bit-identically across frames`() {
        // Resident path (device num/den, no per-frame round-trip) must
        // reproduce the staged path EXACTLY: same dispatches, same order,
        // same zeros — so comparison is bit equality, not a bound.
        val splatSpv = System.getProperty("srpipeline.spv_merge_splat", "")
        val divSpv = System.getProperty("srpipeline.spv_divide", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_merge_splat", splatSpv.isNotEmpty() && File(splatSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_divide", divSpv.isNotEmpty() && File(divSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(splatSpv).readBytes()
            val divSpirv = File(divSpv).readBytes()
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            // Same fixtures as the staged proof: comp[128,128],
            // flow[8,8,2], covs[64,64,2,2], r[64,64], scale 2, tile 16.
            val comp = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_comp_raw.npy"))
            val flow = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_flow.npy"))
            val covs = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_covs.npy"))
            val r = com.rawlens.srpipeline.parity.NpyReader.load(File(fixDir, "merge_in_r.npy"))
            val lrW = comp.shape[1]
            val lrH = comp.shape[0]
            val ftx = flow.shape[1]
            val fty = flow.shape[0]
            val gw = covs.shape[1]
            val gh = covs.shape[0]
            val flowFlat = flow.data
            val covFlat = FloatArray(gw * gh * 4)
            for (p in 0 until gw * gh) {
                covFlat[4 * p] = covs.data[4 * p]
                covFlat[4 * p + 1] = covs.data[4 * p + 1]
                covFlat[4 * p + 2] = covs.data[4 * p + 3]
                covFlat[4 * p + 3] = 0f
            }
            fun packFlat(data: FloatArray): java.nio.ByteBuffer {
                val b = SrpBridge.directFloats(data.size)
                b.asFloatBuffer().put(data)
                b.rewind()
                return b
            }
            val hrW = lrW * 2
            val hrH = lrH * 2
            val total = hrW * hrH * 3
            val compBuf = packFlat(comp.data)
            val flowBuf = packFlat(flowFlat)
            val covBuf = packFlat(covFlat)
            val rBuf = packFlat(r.data)
            // Staged: two identical frames accumulate into zeroed bufs.
            val numBuf = SrpBridge.directFloats(total)
            val denBuf = SrpBridge.directFloats(total)
            repeat(2) {
                val rc = SrpBridge.mergeSplat(handle, compBuf, flowBuf,
                    covBuf, rBuf, numBuf, denBuf, spirv,
                    lrW, lrH, hrW, hrH, 16, 2f, true, ftx, fty)
                assertEquals("staged mergeSplat status", 0, rc)
                numBuf.rewind()
                denBuf.rewind()
            }
            numBuf.rewind()
            denBuf.rewind()
            val wantNum = FloatArray(total)
            val wantDen = FloatArray(total)
            numBuf.asFloatBuffer().get(wantNum)
            denBuf.asFloatBuffer().get(wantDen)
            // Resident: same two frames into device accumulators.
            val numRes = SrpBridge.createResident(handle, total.toLong())
            assertTrue("resident num alloc failed", numRes != 0L)
            val denRes = SrpBridge.createResident(handle, total.toLong())
            assertTrue("resident den alloc failed", denRes != 0L)
            try {
                repeat(2) {
                    val rc = SrpBridge.mergeSplatRes(handle, compBuf, flowBuf,
                        covBuf, rBuf, numRes, denRes, spirv,
                        lrW, lrH, hrW, hrH, 16, 2f, true, ftx, fty)
                    assertEquals("resident mergeSplat status", 0, rc)
                }
                val resNumBuf = SrpBridge.directFloats(total)
                val resDenBuf = SrpBridge.directFloats(total)
                assertEquals("download num status", 0,
                    SrpBridge.downloadResident(handle, numRes, resNumBuf, total.toLong()))
                assertEquals("download den status", 0,
                    SrpBridge.downloadResident(handle, denRes, resDenBuf, total.toLong()))
                resNumBuf.rewind()
                resDenBuf.rewind()
                val gotNum = FloatArray(total)
                val gotDen = FloatArray(total)
                resNumBuf.asFloatBuffer().get(gotNum)
                resDenBuf.asFloatBuffer().get(gotDen)
                var numDiff = 0
                var denDiff = 0
                for (i in 0 until total) {
                    if (gotNum[i] != wantNum[i]) numDiff++
                    if (gotDen[i] != wantDen[i]) denDiff++
                }
                println("jni mergeSplatRes num lanes differ=$numDiff/$total den=$denDiff/$total")
                assertEquals("resident num must be bit-identical", 0, numDiff)
                assertEquals("resident den must be bit-identical", 0, denDiff)
                // divideRes on the residents vs staged divide: exact too.
                val outStaged = SrpBridge.directFloats(total)
                assertEquals("staged divide status", 0,
                    SrpBridge.divide(handle, numBuf, denBuf, outStaged, divSpirv, total))
                assertEquals("resident divide status", 0,
                    SrpBridge.divideRes(handle, numRes, denRes, divSpirv, total))
                val outRes = SrpBridge.directFloats(total)
                assertEquals("resident divide download", 0,
                    SrpBridge.downloadResident(handle, numRes, outRes, total.toLong()))
                outStaged.rewind()
                outRes.rewind()
                val wantOut = FloatArray(total)
                val gotOut = FloatArray(total)
                outStaged.asFloatBuffer().get(wantOut)
                outRes.asFloatBuffer().get(gotOut)
                var outDiff = 0
                var outNanBits = 0
                var outMax = 0f
                for (i in 0 until total) {
                    val a = gotOut[i]
                    val b = wantOut[i]
                    if (a != b) {
                        // NaN != NaN is true by IEEE: uncovered pixels
                        // divide 0/0 on both paths. NaN payloads must
                        // still match bit-for-bit (same op, same GPU).
                        if (a.isNaN() && b.isNaN()) {
                            if (java.lang.Float.floatToRawIntBits(a) !=
                                java.lang.Float.floatToRawIntBits(b)) outNanBits++
                        } else {
                            outDiff++
                            outMax = maxOf(outMax, kotlin.math.abs(a - b))
                        }
                    }
                }
                println("jni divideRes lanes differ=$outDiff/$total maxabs=$outMax nanPayloadDiff=$outNanBits")
                assertEquals("resident divide must be bit-identical", 0, outDiff)
                assertEquals("resident divide NaN payloads must match", 0, outNanBits)
                // Lifecycle: unknown handles fail, double-free fails.
                assertTrue("free unknown must fail",
                    SrpBridge.freeResident(handle, 0x7FFFFFFFFFFFFFFFL) != 0)
                assertTrue("download unknown must fail",
                    SrpBridge.downloadResident(handle, 0x7FFFFFFFFFFFFFFFL,
                        outRes, total.toLong()) != 0)
            } finally {
                assertEquals("free num status", 0, SrpBridge.freeResident(handle, numRes))
                assertEquals("free den status", 0, SrpBridge.freeResident(handle, denRes))
                assertTrue("double-free must fail",
                    SrpBridge.freeResident(handle, numRes) != 0)
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `align l1 matches kotlin cpu through jni`() {
        val alignSpv = System.getProperty("srpipeline.spv_align", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_align", alignSpv.isNotEmpty() && File(alignSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(alignSpv).readBytes()
            // Textured pair: mov is ref rolled by (+2,-1); radius 2 must
            // recover it on interior tiles. OOB reads as 0 on both paths.
            val w = 32
            val h = 32
            val tile = 8
            val radius = 2
            val ntx = w / tile
            val nty = h / tile
            val ref = Array(h) { y -> FloatArray(w) { x -> ((x * 31 + y * 17) % 64).toFloat() / 64f } }
            val mov = Array(h) { y ->
                FloatArray(w) { x ->
                    val sy = y + 1
                    val sx = x - 2
                    if (sy in 0 until h && sx in 0 until w) ref[sy][sx] else 0f
                }
            }
            fun pack(img: Array<FloatArray>): ByteBuffer {
                val buf = SrpBridge.directFloats(w * h)
                val fb = buf.asFloatBuffer()
                for (y in 0 until h) fb.put(img[y])
                buf.rewind()
                return buf
            }
            val flowBuf = SrpBridge.directFloats(ntx * nty * 2) // zero init
            val rc = SrpBridge.alignL1(handle, pack(ref), pack(mov), flowBuf, spirv,
                w, h, ntx * nty, tile, radius, ntx)
            assertEquals("alignL1 dispatch status", 0, rc)
            flowBuf.rewind()
            val gotFlat = FloatArray(ntx * nty * 2)
            flowBuf.asFloatBuffer().get(gotFlat)
            // Kotlin oracle on the same inputs.
            val wantFlow = Array(nty) { Array(ntx) { FloatArray(2) } }
            com.rawlens.srpipeline.stages.l1LocalSearch(ref, mov, radius, wantFlow, tile)
            var maxAbs = 0f
            var nMoved = 0
            for (ty in 0 until nty) for (tx in 0 until ntx) {
                for (c in 0..1) {
                    val got = gotFlat[(ty * ntx + tx) * 2 + c]
                    maxAbs = maxOf(maxAbs, kotlin.math.abs(got - wantFlow[ty][tx][c]))
                }
                if (wantFlow[ty][tx][0] != 0f || wantFlow[ty][tx][1] != 0f) nMoved++
            }
            println("jni alignL1 maxabs_vs_kotlin=$maxAbs movedTiles=$nMoved")
            assertTrue("expected shifted tiles to move, moved=$nMoved", nMoved > 0)
            assertEquals(0f, maxAbs, 0f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `corr extract and argmin through jni`() {
        val corrSpv = System.getProperty("srpipeline.spv_corr", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_corr", corrSpv.isNotEmpty() && File(corrSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(corrSpv).readBytes()
            val w = 20
            val h = 20
            val tile = 4
            val radius = 2
            val ntx = w / tile
            val nty = h / tile
            val tc = ntx * nty
            val s = tile + 2 * radius
            val c = 2 * radius + 1
            val mov = Array(h) { y -> FloatArray(w) { x -> ((x * 13 + y * 29) % 37).toFloat() / 37f } }
            fun packFloats(data: FloatArray): ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            val movBuf = SrpBridge.directFloats(w * h)
            val movFb = movBuf.asFloatBuffer()
            for (y in 0 until h) movFb.put(mov[y])
            movBuf.rewind()
            val flowBuf = SrpBridge.directFloats(tc * 2) // zero init
            val patchBuf = SrpBridge.directFloats(tc * s * s)
            val rc0 = SrpBridge.corrExtract(handle, movBuf, flowBuf, patchBuf,
                spirv, h, w, tc, tile, radius, ntx)
            assertEquals("extract dispatch status", 0, rc0)
            patchBuf.rewind()
            val gotPatches = FloatArray(tc * s * s)
            patchBuf.asFloatBuffer().get(gotPatches)
            // Kotlin oracle on the same inputs.
            val zeroFlow = Array(nty) { Array(ntx) { FloatArray(2) } }
            val wantPatches = com.rawlens.srpipeline.stages.extractFlowPatches(mov, zeroFlow, tile, radius)
            var maxAbs = 0f
            for (ty in 0 until nty) for (tx in 0 until ntx) for (oy in 0 until s) for (ox in 0 until s) {
                val got = gotPatches[(ty * ntx + tx) * s * s + oy * s + ox]
                maxAbs = maxOf(maxAbs, kotlin.math.abs(got - wantPatches[ty][tx][oy][ox]))
            }
            println("jni corrExtract maxabs_vs_kotlin=$maxAbs")
            assertEquals(0f, maxAbs, 0f)
            // Argmin with a planted peak: zero patches (flat energy) plus a
            // single correlation peak per tile at (dx=1, dy=-1) must win.
            val patchCount = tc * s * s
            val corrCount = tc * c * c
            val corrs = FloatArray(corrCount) { i ->
                val local = i % (c * c)
                if (local == 1 * c + 3) 10f else 0f
            }
            val dflowBuf = SrpBridge.directFloats(tc * 2)
            val rc1 = SrpBridge.corrArgmin(handle, packFloats(FloatArray(patchCount)),
                packFloats(corrs), dflowBuf, spirv, tc, tile, radius, ntx, patchCount, corrCount)
            assertEquals("argmin dispatch status", 0, rc1)
            dflowBuf.rewind()
            val gotFlow = FloatArray(tc * 2)
            dflowBuf.asFloatBuffer().get(gotFlow)
            for (t in 0 until tc) {
                assertEquals("tile $t dx", 1f, gotFlow[t * 2], 0f)
                assertEquals("tile $t dy", -1f, gotFlow[t * 2 + 1], 0f)
            }
            println("jni corrArgmin peak recovery exact on $tc tiles")
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `corrCrop matches host cropCorrs bit-exactly`() {
        // Op 2 is a pure permute (no arithmetic): device bytes must equal
        // the host cropCorrs output EXACTLY, not within a bound.
        val corrSpv = System.getProperty("srpipeline.spv_corr", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_corr", corrSpv.isNotEmpty() && File(corrSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(corrSpv).readBytes()
            fun packFloats(data: FloatArray): java.nio.ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            // Two shapes: even crop (s=24,c=9) and odd crop (s=20,c=5,
            // crop=(20-1-5)/2=7 exercises the truncation + offset).
            for ((tile, radius, ntx) in listOf(Triple(16, 4, 8), Triple(16, 2, 6))) {
                val tc = ntx * (ntx / 2)
                val s = tile + 2 * radius
                val c = 2 * radius + 1
                val full = FloatArray(tc * s * s) { i ->
                    ((i * 2654435761u.toInt() % 100003) % 1000).toFloat() / 1000f
                }
                val want = com.rawlens.srpipeline.stages.AlignGpu.cropCorrs(full, tc, s, c)
                val cropBuf = SrpBridge.directFloats(tc * c * c)
                val dummy = SrpBridge.directFloats(1)
                val rc = SrpBridge.corrCrop(handle, packFloats(full), cropBuf,
                    dummy, spirv, tc, tile, radius, ntx)
                assertEquals("crop dispatch status", 0, rc)
                cropBuf.rewind()
                val got = FloatArray(tc * c * c)
                cropBuf.asFloatBuffer().get(got)
                var diff = 0
                for (i in got.indices) {
                    if (got[i] != want[i]) diff++
                }
                println("jni corrCrop s=$s c=$c lanes differ=$diff/${got.size}")
                assertEquals("device crop must be bit-identical", 0, diff)
            }
            // Validation legs: zero tileCount and undersized crop buffer.
            try {
                SrpBridge.corrCrop(handle, packFloats(FloatArray(64)),
                    SrpBridge.directFloats(9), SrpBridge.directFloats(1),
                    spirv, 0, 4, 2, 2)
                fail("zero tileCount must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
            try {
                SrpBridge.corrCrop(handle, packFloats(FloatArray(2 * 8 * 8)),
                    SrpBridge.directFloats(8), SrpBridge.directFloats(1),
                    spirv, 2, 4, 2, 2)
                fail("undersized crop buffer must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `correlate matches jtransforms oracle through jni`() {
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft", fftSpv.isNotEmpty() && File(fftSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(fftSpv).readBytes()
            // s=8 exercises the PoT loop, s=12 the Bluestein path. mov is
            // ref rolled circularly by (+2,-1), giving a sharp peak.
            for (s in listOf(8, 12)) {
                val tc = 2
                val n = tc * s * s
                val ref = FloatArray(n) { i ->
                    val t = i / (s * s)
                    val y = (i / s) % s
                    val x = i % s
                    (((x + 3) * 17 + (y + 5 * t) * 29) % 43).toFloat() / 43f
                }
                val mov = FloatArray(n) { i ->
                    val t = i / (s * s)
                    val y = (i / s) % s
                    val x = i % s
                    ref[t * s * s + ((y - 2 + s) % s) * s + ((x + 1 + s) % s)]
                }
                fun pack(data: FloatArray): ByteBuffer {
                    val buf = SrpBridge.directFloats(data.size)
                    buf.asFloatBuffer().put(data)
                    buf.rewind()
                    return buf
                }
                val corrBuf = SrpBridge.directFloats(n)
                val rc = SrpBridge.correlate(handle, pack(ref), pack(mov),
                    corrBuf, spirv, tc, s)
                assertEquals("correlate s=$s dispatch status", 0, rc)
                corrBuf.rewind()
                val got = FloatArray(n)
                corrBuf.asFloatBuffer().get(got)
                // Independent double-precision oracle (JTransforms).
                var maxAbs = 0f
                var agree = 0
                for (t in 0 until tc) {
                    val r = ref.sliceArray(t * s * s until (t + 1) * s * s)
                    val m = mov.sliceArray(t * s * s until (t + 1) * s * s)
                    val fr = com.rawlens.srpipeline.core.Fft.forward2D(r, s, s)
                    val fm = com.rawlens.srpipeline.core.Fft.forward2D(m, s, s)
                    val pr = FloatArray(s * s)
                    val pi = FloatArray(s * s)
                    for (i in 0 until s * s) {
                        pr[i] = fm.real[i] * fr.real[i] + fm.imag[i] * fr.imag[i]
                        pi[i] = fm.imag[i] * fr.real[i] - fm.real[i] * fr.imag[i]
                    }
                    val back = com.rawlens.srpipeline.core.Fft.inverse2D(
                        com.rawlens.srpipeline.core.ComplexSpectrum(s, s, pr, pi))
                    val want = com.rawlens.srpipeline.core.Shifts.fftShift(back, s, s)
                    var bestGot = 0
                    var bestWant = 0
                    for (i in 0 until s * s) {
                        val g = got[t * s * s + i]
                        maxAbs = maxOf(maxAbs, kotlin.math.abs(g - want[i]))
                        if (g > got[t * s * s + bestGot]) bestGot = i
                        if (want[i] > want[bestWant]) bestWant = i
                    }
                    if (bestGot == bestWant) agree++
                }
                println("jni correlate s=$s maxabs_vs_jtransforms=$maxAbs peakAgree=$agree/$tc")
                assertEquals("peak agreement s=$s", tc, agree)
                assertTrue("maxabs s=$s too large: $maxAbs", maxAbs <= 1e-4f)
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `correlate reuse on one context is bit-exact`() {
        // Pool regression: the buffer pool reuses device memory across
        // entries on one Context. Without explicit host sync, downloads of
        // reused buffers returned stale pre-dispatch contents on MoltenVK
        // (deterministic 93%-pixel e2e corruption). Two identical runs on
        // one Context must agree bit-exactly; interleaved shapes churn
        // the pool between them.
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft", fftSpv.isNotEmpty() && File(fftSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(fftSpv).readBytes()
            fun pack(data: FloatArray): ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            // Proven trigger shape (e2e align tiles): s=24 Bluestein at
            // tc=961 needs multi-MB pooled buffers, where reused-allocation
            // map staleness manifested. Small shapes never caught it.
            val s = 24
            val tc = 961
            val n = tc * s * s
            val ref = FloatArray(n) { i -> (((i * 17) % 43).toFloat() / 43f) }
            val mov = FloatArray(n) { i -> ref[(i + 7) % n] }
            fun runOnce(): FloatArray {
                val corrBuf = SrpBridge.directFloats(n)
                val rc = SrpBridge.correlate(handle, pack(ref), pack(mov),
                    corrBuf, spirv, tc, s)
                assertEquals("reuse correlate dispatch status", 0, rc)
                corrBuf.rewind()
                val got = FloatArray(n)
                corrBuf.asFloatBuffer().get(got)
                return got
            }
            val first = runOnce()
            // Churn the pool with the e2e's other tile shape between runs.
            val cn2 = 9 * 16 * 16
            val other = SrpBridge.directFloats(cn2)
            assertEquals("churn dispatch status", 0, SrpBridge.correlate(handle,
                pack(FloatArray(cn2) { i -> i.toFloat() / cn2 }),
                pack(FloatArray(cn2) { i -> (cn2 - 1 - i).toFloat() / cn2 }),
                other, spirv, 9, 16))
            val second = runOnce()
            var diff = 0
            for (i in 0 until n) if (first[i] != second[i]) diff++
            println("jni correlate reuse: $diff/$n lanes differ across runs")
            assertEquals("pooled reuse must be bit-exact, $diff/$n differ", 0, diff)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `correlatePinned matches staged correlate bit-exactly`() {
        // The pinned path (one ref-forward per level, mov-forward per
        // frame, no stacking) must reproduce the staged path EXACTLY:
        // same per-row FFT math, same order downstream — so comparison
        // is bit equality, not a bound. Covers the PoT path (s=32), the
        // Bluestein path (s=20/24), and a multi-chunk remainder (4097).
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        val corrSpv = System.getProperty("srpipeline.spv_corr", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft", fftSpv.isNotEmpty() && File(fftSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_corr", corrSpv.isNotEmpty() && File(corrSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val fft = File(fftSpv).readBytes()
            val corr = File(corrSpv).readBytes()
            fun pack(data: FloatArray): java.nio.ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            for ((tc, s) in listOf(Pair(100, 20), Pair(256, 32), Pair(4097, 24))) {
                val n = tc * s * s
                val ref = FloatArray(n) { i -> (((i * 2654435761L) % 100003L) % 1000L).toFloat() / 1000f }
                val mov = FloatArray(n) { i -> ref[(i * 7 + 13) % n] }
                // Staged reference.
                val wantBuf = SrpBridge.directFloats(n)
                assertEquals("staged correlate status", 0, SrpBridge.correlate(
                    handle, pack(ref), pack(mov), wantBuf, fft, tc, s))
                wantBuf.rewind()
                val want = FloatArray(n)
                wantBuf.asFloatBuffer().get(want)
                // Pinned: one pin, then two identical frames (reuse across
                // frames is the point — both must match).
                val specRes = SrpBridge.createResident(handle, (2L * n))
                assertTrue("pin resident alloc failed", specRes != 0L)
                try {
                    assertEquals("pinRef status", 0, SrpBridge.correlatePinRef(
                        handle, pack(ref), specRes, fft, corr, tc, s))
                    for (rep in 0 until 2) {
                        val gotBuf = SrpBridge.directFloats(n)
                        assertEquals("pinned correlate status", 0,
                            SrpBridge.correlatePinned(handle, pack(mov),
                                specRes, gotBuf, fft, corr, tc, s))
                        gotBuf.rewind()
                        val got = FloatArray(n)
                        gotBuf.asFloatBuffer().get(got)
                        var diff = 0
                        var maxAbs = 0f
                        for (i in 0 until n) {
                            if (got[i] != want[i]) {
                                diff++
                                maxAbs = maxOf(maxAbs,
                                    kotlin.math.abs(got[i] - want[i]))
                            }
                        }
                        println("jni correlatePinned tc=$tc s=$s rep=$rep lanes differ=$diff/$n maxabs=$maxAbs")
                        assertEquals("pinned must be bit-identical", 0, diff)
                    }
                    // Wrong-size resident must fail, not clobber.
                    val smallRes = SrpBridge.createResident(handle, 16L)
                    assertTrue("small resident alloc failed", smallRes != 0L)
                    try {
                        assertTrue("pinned with small resident must fail",
                            SrpBridge.correlatePinned(handle, pack(mov),
                                smallRes, SrpBridge.directFloats(n),
                                fft, corr, tc, s) != 0)
                    } finally {
                        assertEquals("free small status", 0,
                            SrpBridge.freeResident(handle, smallRes))
                    }
                } finally {
                    assertEquals("free spec status", 0,
                        SrpBridge.freeResident(handle, specRes))
                }
            }
            // Validation legs: null resident handles throw before JNI.
            try {
                SrpBridge.correlatePinRef(handle, pack(FloatArray(64)), 0L,
                    fft, corr, 1, 8)
                fail("null pin resident must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
            try {
                SrpBridge.correlatePinned(handle, pack(FloatArray(64)), 0L,
                    SrpBridge.directFloats(64), fft, corr, 1, 8)
                fail("null pinned resident must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `ica hessian and refine through jni`() {
        val icaSpv = System.getProperty("srpipeline.spv_ica", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_ica", icaSpv.isNotEmpty() && File(icaSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(icaSpv).readBytes()
            val w = 32
            val h = 32
            val tile = 8 // tree-reduction path
            val ntx = w / tile
            val nty = h / tile
            val tc = ntx * nty
            fun pack(data: FloatArray): ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            fun packImg(img: Array<FloatArray>): ByteBuffer {
                val buf = SrpBridge.directFloats(w * h)
                val fb = buf.asFloatBuffer()
                for (y in 0 until h) fb.put(img[y])
                buf.rewind()
                return buf
            }
            val gradx = Array(h) { y -> FloatArray(w) { x -> ((x * 7 + y * 3) % 19).toFloat() / 19f - 0.5f } }
            val grady = Array(h) { y -> FloatArray(w) { x -> ((x * 5 + y * 11) % 17).toFloat() / 17f - 0.5f } }
            val hessBuf = SrpBridge.directFloats(tc * 4)
            val rc0 = SrpBridge.icaHessian(handle, packImg(gradx), packImg(grady),
                hessBuf, spirv, w, h, tc, tile, ntx)
            assertEquals("hessian dispatch status", 0, rc0)
            hessBuf.rewind()
            val gotHess = FloatArray(tc * 4)
            hessBuf.asFloatBuffer().get(gotHess)
            val wantHess = com.rawlens.srpipeline.stages.computeHessian(gradx, grady, tile)
            var maxAbs = 0f
            for (ty in 0 until nty) for (tx in 0 until ntx) {
                val t = (ty * ntx + tx) * 4
                maxAbs = maxOf(maxAbs, kotlin.math.abs(gotHess[t] - wantHess[ty][tx][0][0]))
                maxAbs = maxOf(maxAbs, kotlin.math.abs(gotHess[t + 1] - wantHess[ty][tx][0][1]))
                maxAbs = maxOf(maxAbs, kotlin.math.abs(gotHess[t + 2] - wantHess[ty][tx][1][1]))
                assertEquals("w slot must stay 0", 0f, gotHess[t + 3], 0f)
            }
            println("jni icaHessian maxabs_vs_kotlin=$maxAbs")
            // FMA-contraction class: measured 4.77e-07 is ~0.5 ulp at the
            // O(1..10) Hessian magnitudes of this texture (Kotlin sums in a
            // different order than the shader's FMA chain).
            assertTrue("hessian maxabs too large: $maxAbs", maxAbs <= 1e-6f)
            // Refine on a shifted pair through the Kotlin oracle.
            val ref = Array(h) { y -> FloatArray(w) { x -> ((x * 13 + y * 29) % 37).toFloat() / 37f } }
            val mov = Array(h) { y ->
                FloatArray(w) { x ->
                    val sx = x - 1
                    if (sx in 0 until w) ref[y][sx] else 0f
                }
            }
            val hess2211 = com.rawlens.srpipeline.stages.computeHessian(gradx, grady, tile)
            val hessPack = FloatArray(tc * 4)
            for (ty in 0 until nty) for (tx in 0 until ntx) {
                val t = (ty * ntx + tx) * 4
                hessPack[t] = hess2211[ty][tx][0][0]
                hessPack[t + 1] = hess2211[ty][tx][0][1]
                hessPack[t + 2] = hess2211[ty][tx][1][1]
            }
            val flow = Array(nty) { Array(ntx) { FloatArray(2) } }
            val flowBuf = SrpBridge.directFloats(tc * 2)
            val rc1 = SrpBridge.icaRefine(handle, packImg(ref), packImg(mov),
                packImg(gradx), packImg(grady), pack(hessPack), flowBuf,
                spirv, w, h, tc, tile, 3, 4f, ntx)
            assertEquals("refine dispatch status", 0, rc1)
            flowBuf.rewind()
            val gotFlow = FloatArray(tc * 2)
            flowBuf.asFloatBuffer().get(gotFlow)
            com.rawlens.srpipeline.stages.Ica.alignLvl(ref, gradx, grady, hess2211,
                mov, flow, tile, nIter = 3, clip = true, searchRadius = 4)
            var maxAbsFlow = 0f
            for (ty in 0 until nty) for (tx in 0 until ntx) {
                maxAbsFlow = maxOf(maxAbsFlow,
                    kotlin.math.abs(gotFlow[(ty * ntx + tx) * 2] - flow[ty][tx][0]))
                maxAbsFlow = maxOf(maxAbsFlow,
                    kotlin.math.abs(gotFlow[(ty * ntx + tx) * 2 + 1] - flow[ty][tx][1]))
            }
            println("jni icaRefine maxabs_vs_kotlin=$maxAbsFlow")
            assertTrue("refine maxabs too large: $maxAbsFlow", maxAbsFlow <= 1.3e-05f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `alignL1Res matches staged bit-exactly`() {
        // Resident ref (uploaded once) must reproduce the staged path
        // EXACTLY: same dispatches, same bytes — bit equality, not a
        // bound. Also proves the uploadResident primitive round-trips.
        val alignSpv = System.getProperty("srpipeline.spv_align", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_align", alignSpv.isNotEmpty() && File(alignSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(alignSpv).readBytes()
            fun packImg(img: Array<FloatArray>): java.nio.ByteBuffer {
                val buf = SrpBridge.directFloats(img.size * img[0].size)
                val fb = buf.asFloatBuffer()
                for (y in img.indices) fb.put(img[y])
                buf.rewind()
                return buf
            }
            val w = 32
            val h = 32
            val tile = 8
            val radius = 2
            val ntx = w / tile
            val nty = h / tile
            val tc = ntx * nty
            val ref = Array(h) { y -> FloatArray(w) { x -> ((x * 13 + y * 29) % 37).toFloat() / 37f } }
            val mov = Array(h) { y -> FloatArray(w) { x -> ref[y][(x + 2) % w] } }
            val flowFlat = FloatArray(tc * 2)
            // Staged reference.
            val wantBuf = SrpBridge.directFloats(tc * 2)
            wantBuf.asFloatBuffer().put(flowFlat)
            wantBuf.rewind()
            assertEquals("staged L1 status", 0, SrpBridge.alignL1(handle,
                packImg(ref), packImg(mov), wantBuf, spirv,
                w, h, tc, tile, radius, ntx))
            wantBuf.rewind()
            val want = FloatArray(tc * 2)
            wantBuf.asFloatBuffer().get(want)
            // Resident: upload ref once, run twice (reuse across frames).
            val refFlat = FloatArray(w * h) { i -> ref[i / w][i % w] }
            val refRes = SrpBridge.createResident(handle, refFlat.size.toLong())
            assertTrue("ref resident alloc failed", refRes != 0L)
            try {
                assertEquals("uploadResident status", 0, SrpBridge.uploadResident(
                    handle, refRes, packImg(ref), refFlat.size.toLong()))
                // Round-trip: download must return the uploaded bytes.
                val rtBuf = SrpBridge.directFloats(refFlat.size)
                assertEquals("downloadResident status", 0,
                    SrpBridge.downloadResident(handle, refRes, rtBuf, refFlat.size.toLong()))
                rtBuf.rewind()
                val rt = FloatArray(refFlat.size)
                rtBuf.asFloatBuffer().get(rt)
                var rtDiff = 0
                for (i in refFlat.indices) if (rt[i] != refFlat[i]) rtDiff++
                println("jni uploadResident round-trip lanes differ=$rtDiff/${refFlat.size}")
                assertEquals("upload round-trip must be bit-identical", 0, rtDiff)
                repeat(2) {
                    val gotBuf = SrpBridge.directFloats(tc * 2)
                    gotBuf.asFloatBuffer().put(flowFlat)
                    gotBuf.rewind()
                    assertEquals("resident L1 status", 0, SrpBridge.alignL1Res(
                        handle, refRes, packImg(mov), gotBuf, spirv,
                        w, h, tc, tile, radius, ntx))
                    gotBuf.rewind()
                    val got = FloatArray(tc * 2)
                    gotBuf.asFloatBuffer().get(got)
                    var diff = 0
                    for (i in got.indices) if (got[i] != want[i]) diff++
                    println("jni alignL1Res lanes differ=$diff/${got.size}")
                    assertEquals("resident L1 must be bit-identical", 0, diff)
                }
                // Unknown resident must fail, not dispatch.
                assertTrue("L1Res with unknown resident must fail",
                    SrpBridge.alignL1Res(handle, 0x7FFFFFFFFFFFFFFFL,
                        packImg(mov), SrpBridge.directFloats(tc * 2), spirv,
                        w, h, tc, tile, radius, ntx) != 0)
            } finally {
                assertEquals("free ref status", 0, SrpBridge.freeResident(handle, refRes))
            }
            // Validation legs: null resident throws before JNI.
            try {
                SrpBridge.alignL1Res(handle, 0L, packImg(mov),
                    SrpBridge.directFloats(tc * 2), spirv,
                    w, h, tc, tile, radius, ntx)
                fail("null L1 resident must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
            try {
                SrpBridge.uploadResident(handle, 0L, packImg(ref), refFlat.size.toLong())
                fail("null upload resident must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `icaRefineRes matches staged bit-exactly`() {
        // Four resident statics (ref/grads/hess, uploaded once) must
        // reproduce the staged refine EXACTLY — same dispatches, same
        // bytes. (The float-vs-Kotlin envelope is a different, wider
        // gate; staged-vs-resident is bit equality.)
        val icaSpv = System.getProperty("srpipeline.spv_ica", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_ica", icaSpv.isNotEmpty() && File(icaSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirv = File(icaSpv).readBytes()
            fun packImg(img: Array<FloatArray>): java.nio.ByteBuffer {
                val buf = SrpBridge.directFloats(img.size * img[0].size)
                val fb = buf.asFloatBuffer()
                for (y in img.indices) fb.put(img[y])
                buf.rewind()
                return buf
            }
            fun pack(data: FloatArray): java.nio.ByteBuffer {
                val buf = SrpBridge.directFloats(data.size)
                buf.asFloatBuffer().put(data)
                buf.rewind()
                return buf
            }
            val w = 32
            val h = 32
            val tile = 8
            val ntx = w / tile
            val nty = h / tile
            val tc = ntx * nty
            val ref = Array(h) { y -> FloatArray(w) { x -> ((x * 13 + y * 29) % 37).toFloat() / 37f } }
            val mov = Array(h) { y ->
                FloatArray(w) { x ->
                    val sx = x - 1
                    if (sx in 0 until w) ref[y][sx] else 0f
                }
            }
            val gradx = Array(h) { y -> FloatArray(w) { x -> ((x * 7 + y * 3) % 19).toFloat() / 19f - 0.5f } }
            val grady = Array(h) { y -> FloatArray(w) { x -> ((x * 5 + y * 11) % 17).toFloat() / 17f - 0.5f } }
            val hessPack = FloatArray(tc * 4) { i -> ((i * 31) % 23).toFloat() / 23f }
            // Staged reference.
            val wantBuf = SrpBridge.directFloats(tc * 2)
            assertEquals("staged refine status", 0, SrpBridge.icaRefine(handle,
                packImg(ref), packImg(mov), packImg(gradx), packImg(grady),
                pack(hessPack), wantBuf, spirv, w, h, tc, tile, 3, 4f, ntx))
            wantBuf.rewind()
            val want = FloatArray(tc * 2)
            wantBuf.asFloatBuffer().get(want)
            // Residents: upload statics once, run twice.
            fun pin(data: FloatArray): Long {
                val res = SrpBridge.createResident(handle, data.size.toLong())
                assertTrue("static resident alloc failed", res != 0L)
                assertEquals("uploadResident status", 0,
                    SrpBridge.uploadResident(handle, res, pack(data), data.size.toLong()))
                return res
            }
            val refRes = pin(FloatArray(w * h) { i -> ref[i / w][i % w] })
            val gxRes = pin(FloatArray(w * h) { i -> gradx[i / w][i % w] })
            val gyRes = pin(FloatArray(w * h) { i -> grady[i / w][i % w] })
            val hessRes = pin(hessPack)
            try {
                repeat(2) {
                    val gotBuf = SrpBridge.directFloats(tc * 2)
                    assertEquals("resident refine status", 0,
                        SrpBridge.icaRefineRes(handle, refRes, packImg(mov),
                            gxRes, gyRes, hessRes, gotBuf, spirv,
                            w, h, tc, tile, 3, 4f, ntx))
                    gotBuf.rewind()
                    val got = FloatArray(tc * 2)
                    gotBuf.asFloatBuffer().get(got)
                    var diff = 0
                    var maxAbs = 0f
                    for (i in got.indices) {
                        if (got[i] != want[i]) {
                            diff++
                            maxAbs = maxOf(maxAbs, kotlin.math.abs(got[i] - want[i]))
                        }
                    }
                    println("jni icaRefineRes lanes differ=$diff/${got.size} maxabs=$maxAbs")
                    assertEquals("resident refine must be bit-identical", 0, diff)
                }
            } finally {
                assertEquals("free ref status", 0, SrpBridge.freeResident(handle, refRes))
                assertEquals("free gx status", 0, SrpBridge.freeResident(handle, gxRes))
                assertEquals("free gy status", 0, SrpBridge.freeResident(handle, gyRes))
                assertEquals("free hess status", 0, SrpBridge.freeResident(handle, hessRes))
            }
            // Validation legs: null statics throw before JNI.
            try {
                SrpBridge.icaRefineRes(handle, 0L, packImg(mov), gxRes,
                    gyRes, hessRes, SrpBridge.directFloats(tc * 2), spirv,
                    w, h, tc, tile, 3, 4f, ntx)
                fail("null ICA resident must throw")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `gpu align loop matches recorded crop flow`() {
        val alignSpv = System.getProperty("srpipeline.spv_align", "")
        val corrSpv = System.getProperty("srpipeline.spv_corr", "")
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        val icaSpv = System.getProperty("srpipeline.spv_ica", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_align",
            alignSpv.isNotEmpty() && File(alignSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_corr",
            corrSpv.isNotEmpty() && File(corrSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft",
            fftSpv.isNotEmpty() && File(fftSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_ica",
            icaSpv.isNotEmpty() && File(icaSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirvs = com.rawlens.srpipeline.stages.AlignGpu.Spirvs(
                File(alignSpv).readBytes(), File(corrSpv).readBytes(),
                File(fftSpv).readBytes(), File(icaSpv).readBytes())
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            // Same config as the ParityHarness align_crop case
            // (parity_config(512)).
            val config = com.rawlens.srpipeline.stages.AlignLoopConfig(
                factors = listOf(1, 2, 4, 4),
                tileSizes = listOf(16, 16, 16, 8),
                searchRadii = listOf(1, 4, 4, 4),
                metrics = listOf(
                    com.rawlens.srpipeline.stages.BlockMetric.L1,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                ),
            )
            val reader = com.rawlens.srpipeline.parity.NpyReader
            val ref = reader.load(File(fixDir, "align_crop_in_ref_grey.npy")).toImage2D()
            val moving = reader.load(File(fixDir, "align_crop_in_mov_grey.npy")).toImage2D()
            val want = reader.load(File(fixDir, "align_crop_out_flow.npy")).toFlow()
            val init = com.rawlens.srpipeline.stages.AlignGpu.initGpu(
                ref, config, handle, spirvs.ica)
            val got = com.rawlens.srpipeline.stages.AlignGpu.alignLevelsGpu(
                init, moving, config, handle, spirvs)
            var maxAbs = 0f
            for (ty in want.indices) for (tx in want[ty].indices) for (c in 0..1) {
                maxAbs = maxOf(maxAbs,
                    kotlin.math.abs(got[ty][tx][c] - want[ty][tx][c]))
            }
            println("gpu align loop maxabs_vs_recorded=${maxAbs}")
            // Certified align_crop loop envelope: the reference Vulkan port
            // measures 1.153349876e-05 here at full precision (the old
            // 1.15e-05 literal was that value rounded to 3 sig figs, which
            // not even the port satisfies). Bound is the port value ceiled
            // to 4 sig figs, never weakened.
            assertTrue("loop maxabs too large: $maxAbs", maxAbs <= 1.154e-05f)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `pinned align loop matches recorded crop flow`() {
        // Same recorded-flow gate as `gpu align loop matches recorded
        // crop flow`, but through the pinned path (pinLevelResidents +
        // correlatePinned/alignL1Res/icaRefineRes). Pinning is proven
        // bit-exact at the entry
        // level, so the loop must land inside the SAME envelope — this
        // test proves the wiring (which levels pin, resident plumbing).
        val alignSpv = System.getProperty("srpipeline.spv_align", "")
        val corrSpv = System.getProperty("srpipeline.spv_corr", "")
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        val icaSpv = System.getProperty("srpipeline.spv_ica", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_align",
            alignSpv.isNotEmpty() && File(alignSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_corr",
            corrSpv.isNotEmpty() && File(corrSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft",
            fftSpv.isNotEmpty() && File(fftSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_ica",
            icaSpv.isNotEmpty() && File(icaSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirvs = com.rawlens.srpipeline.stages.AlignGpu.Spirvs(
                File(alignSpv).readBytes(), File(corrSpv).readBytes(),
                File(fftSpv).readBytes(), File(icaSpv).readBytes())
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val config = com.rawlens.srpipeline.stages.AlignLoopConfig(
                factors = listOf(1, 2, 4, 4),
                tileSizes = listOf(16, 16, 16, 8),
                searchRadii = listOf(1, 4, 4, 4),
                metrics = listOf(
                    com.rawlens.srpipeline.stages.BlockMetric.L1,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                ),
            )
            val reader = com.rawlens.srpipeline.parity.NpyReader
            val ref = reader.load(File(fixDir, "align_crop_in_ref_grey.npy")).toImage2D()
            val moving = reader.load(File(fixDir, "align_crop_in_mov_grey.npy")).toImage2D()
            val want = reader.load(File(fixDir, "align_crop_out_flow.npy")).toFlow()
            val init = com.rawlens.srpipeline.stages.AlignGpu.initGpu(
                ref, config, handle, spirvs.ica)
            val pinned = com.rawlens.srpipeline.stages.AlignGpu.pinLevelResidents(
                init, config, handle, spirvs)
            try {
                // Three L2 levels pin spectra; every level pins statics.
                val specCount = pinned.levels.count { it.refSpecRes != 0L }
                val staticCount = pinned.levels.count {
                    it.refRes != 0L && it.gradXRes != 0L &&
                        it.gradYRes != 0L && it.hessRes != 0L
                }
                println("pinned align levels spectra=$specCount/${pinned.levels.size} statics=$staticCount/${pinned.levels.size}")
                assertEquals("three L2 levels must pin spectra", 3, specCount)
                assertEquals("all levels must pin statics", 4, staticCount)
                val got = com.rawlens.srpipeline.stages.AlignGpu.alignLevelsGpu(
                    pinned, moving, config, handle, spirvs)
                var maxAbs = 0f
                for (ty in want.indices) for (tx in want[ty].indices) for (c in 0..1) {
                    maxAbs = maxOf(maxAbs,
                        kotlin.math.abs(got[ty][tx][c] - want[ty][tx][c]))
                }
                println("pinned align loop maxabs_vs_recorded=${maxAbs}")
                assertTrue("pinned loop maxabs too large: $maxAbs", maxAbs <= 1.154e-05f)
            } finally {
                com.rawlens.srpipeline.stages.AlignGpu.freeLevelSpectra(pinned, handle)
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `level scratch reuse is deterministic`() {
        // Scratch buffers are rewritten per use, but a test must prove
        // it: run the pinned loop twice over the SAME init (all scratch
        // reused on the second pass) and require bit-identical flows.
        // Catches stale-read and aliasing regressions in the reuse.
        val alignSpv = System.getProperty("srpipeline.spv_align", "")
        val corrSpv = System.getProperty("srpipeline.spv_corr", "")
        val fftSpv = System.getProperty("srpipeline.spv_fft", "")
        val icaSpv = System.getProperty("srpipeline.spv_ica", "")
        assumeTrue("native proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_align",
            alignSpv.isNotEmpty() && File(alignSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_corr",
            corrSpv.isNotEmpty() && File(corrSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_fft",
            fftSpv.isNotEmpty() && File(fftSpv).exists())
        assumeTrue("native proof needs -Dsrpipeline.spv_ica",
            icaSpv.isNotEmpty() && File(icaSpv).exists())
        SrpBridge.loadPath(lib)
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            val spirvs = com.rawlens.srpipeline.stages.AlignGpu.Spirvs(
                File(alignSpv).readBytes(), File(corrSpv).readBytes(),
                File(fftSpv).readBytes(), File(icaSpv).readBytes())
            val fixDir = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))
            val config = com.rawlens.srpipeline.stages.AlignLoopConfig(
                factors = listOf(1, 2, 4, 4),
                tileSizes = listOf(16, 16, 16, 8),
                searchRadii = listOf(1, 4, 4, 4),
                metrics = listOf(
                    com.rawlens.srpipeline.stages.BlockMetric.L1,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                    com.rawlens.srpipeline.stages.BlockMetric.L2,
                ),
            )
            val reader = com.rawlens.srpipeline.parity.NpyReader
            val ref = reader.load(File(fixDir, "align_crop_in_ref_grey.npy")).toImage2D()
            val moving = reader.load(File(fixDir, "align_crop_in_mov_grey.npy")).toImage2D()
            val init = com.rawlens.srpipeline.stages.AlignGpu.initGpu(
                ref, config, handle, spirvs.ica)
            val pinned = com.rawlens.srpipeline.stages.AlignGpu.pinLevelResidents(
                init, config, handle, spirvs)
            try {
                fun runOnce(): Array<Array<FloatArray>> =
                    com.rawlens.srpipeline.stages.AlignGpu.alignLevelsGpu(
                        pinned, moving, config, handle, spirvs)
                val first = runOnce()
                val second = runOnce()
                var diff = 0
                for (ty in first.indices) for (tx in first[ty].indices) for (c in 0..1) {
                    if (first[ty][tx][c] != second[ty][tx][c]) diff++
                }
                val n = first.size * first[0].size * 2
                println("jni level scratch reuse: $diff/$n flow lanes differ across runs")
                assertEquals("scratch reuse must be bit-exact", 0, diff)
            } finally {
                com.rawlens.srpipeline.stages.AlignGpu.freeLevelSpectra(pinned, handle)
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun `kotlin validation rejects bad args without jni`() {
        try {
            SrpBridge.mergeAccumulate(0L, SrpBridge.directFloats(4),
                SrpBridge.directFloats(4), ByteArray(4), 1, 1, 1, 1f)
            assertTrue("expected IllegalArgumentException for null handle", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("null context"))
        }
        try {
            SrpBridge.writeDng("", 4, 4, SrpBridge.directFloats(48), null, null)
            assertTrue("expected IllegalArgumentException for empty path", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty path"))
        }
        // Non-direct buffer must be rejected before crossing JNI.
        try {
            val heap = java.nio.ByteBuffer.allocate(48 * 4).order(ByteOrder.nativeOrder())
            SrpBridge.writeDng("/tmp/x.dng", 4, 4, heap, null, null)
            assertTrue("expected IllegalArgumentException for heap buffer", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("direct"))
        }
    }
}
