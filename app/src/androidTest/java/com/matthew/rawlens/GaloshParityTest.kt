package com.matthew.rawlens

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 2 parity vehicle: runs the ported GALOSH Vulkan pipeline on a host
 * fixture and publishes the float32 output for the tools/galosh_parity.py
 * PSNR gate (>= 69 dB vs the upstream CPU reference).
 *
 * Fixture (pushed by the harness, not committed):
 *   /data/local/tmp/galosh_parity_in.bin  (W*H float32 LE, [0,1] Bayer)
 *   /data/local/tmp/galosh_parity_in.txt  ("W H strength luma chroma alpha sigma")
 * Output: Download/RawLens/galosh_parity_out.bin (MediaStore, adb-pullable).
 */
class GaloshParityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) = Log.i("GALOSHPARITY", s)

    @Test
    fun denoiseFixtureToDownloads() {
        val sidecar = File("/data/local/tmp/galosh_parity_in.txt")
        val inputFile = File("/data/local/tmp/galosh_parity_in.bin")
        assertTrue("missing fixture: push with tools/galosh_parity.py gen + adb push " +
            "(need ${sidecar.absolutePath} and ${inputFile.absolutePath})",
            sidecar.exists() && inputFile.exists())
        val p = sidecar.readText().trim().split(Regex("\\s+"))
        val w = p[0].toInt()
        val h = p[1].toInt()
        val strength = p[2].toFloat()
        val luma = p[3].toFloat()
        val chroma = p[4].toFloat()
        val alpha = p[5].toFloat()
        val sigma = p[6].toFloat()
        log("fixture ${w}x$h strength=$strength luma=$luma chroma=$chroma alpha=$alpha sigma=$sigma")

        val handle = GaloshVulkan.init(context.assets)
        GaloshVulkan.setDump(handle, true)
        lateinit var input: java.nio.FloatBuffer
        lateinit var output: java.nio.FloatBuffer
        try {
            val bytes = inputFile.readBytes()
            assertTrue("short fixture: ${bytes.size} != ${w * h * 4}", bytes.size == w * h * 4)
            input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().apply { put(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()); rewind() }
            output = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val gpuMs = GaloshVulkan.denoise(handle, input, output, w, h,
                strength, luma, chroma, alpha, sigma)
            log("denoise ok: gpuMs=%.1f".format(gpuMs))
            log("p0 alpha=%.6f sigma_sq=%.8f".format(
                GaloshVulkan.lastAlpha(handle), GaloshVulkan.lastSigmaSq(handle)))
            val ndump = GaloshVulkan.dumpCount(handle)
            log("dumps=$ndump")
            for (i in 0 until ndump) {
                val name = GaloshVulkan.dumpName(handle, i)
                val data = GaloshVulkan.dumpData(handle, i)
                val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.asFloatBuffer().put(data)
                val bytes = ByteArray(data.size * 4)
                bb.rewind()
                bb.get(bytes)
                publishDownload("galosh_dump_$name.bin", "application/octet-stream", bytes)
            }
        } finally {
            GaloshVulkan.release(handle)
        }

        // Stats + publish. Output must be finite and different from input.
        output.rewind()
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var mse = 0.0
        var n = 0
        input.rewind()
        while (output.hasRemaining()) {
            val o = output.get()
            val i = input.get()
            assertTrue("non-finite output at $n", o.isFinite())
            min = minOf(min, o)
            max = maxOf(max, o)
            sum += o
            val d = (o - i).toDouble()
            mse += d * d
            n++
        }
        mse /= n
        log("output n=$n min=%.5f max=%.5f mean=%.5f mseVsInput=%.6e".format(min, max, sum / n, mse))
        assertTrue("output identical to input (mse=$mse)", mse > 1e-12)

        output.rewind()
        val outBytes = ByteArray(n * 4)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(output)
        publishDownload("galosh_parity_out.bin", "application/octet-stream", outBytes)
        input.rewind()
        val inBytes = ByteArray(n * 4)
        ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(input)
        publishDownload("galosh_parity_in_echo.bin", "application/octet-stream", inBytes)
        log("PASS denoiseFixtureToDownloads")
    }

    @Test
    fun denoiseFixtureFastup() {
        val sidecar = File("/data/local/tmp/galosh_parity_in.txt")
        val inputFile = File("/data/local/tmp/galosh_parity_in.bin")
        assertTrue("missing fixture", sidecar.exists() && inputFile.exists())
        val p = sidecar.readText().trim().split(Regex("\\s+"))
        val w = p[0].toInt()
        val h = p[1].toInt()
        val handle = GaloshVulkan.init(context.assets)
        try {
            val bytes = inputFile.readBytes()
            val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().apply { put(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()); rewind() }
            val output = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val gpuMs = GaloshVulkan.denoise(handle, input, output, w, h,
                p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(),
                8, true)
            log("fastup denoise ok: gpuMs=%.1f".format(gpuMs))
            output.rewind()
            val outBytes = ByteArray(bytes.size)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(output)
            publishDownload("galosh_fastup_out.bin", "application/octet-stream", outBytes)
        } finally {
            GaloshVulkan.release(handle)
        }
        log("PASS denoiseFixtureFastup")
    }

    private fun publishDownload(displayName: String, mime: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(displayName))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RawLens")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("could not create Downloads entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw AssertionError("could not open Downloads stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
