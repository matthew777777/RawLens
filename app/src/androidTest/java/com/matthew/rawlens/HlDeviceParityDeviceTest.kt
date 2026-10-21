// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Production HL (Vulkan path, 4-worker pool like RawDevelopmentCoordinator)
 * on every `<name>_<W>x<H>.f16` (RGBA half, normalized tap) in
 * /data/local/tmp/rawlens_hl. Writes files/hl-device/<name>.f32 (RGB) for
 * comparison with the RawTherapee oracle (tools/hl_oracle) and between
 * builds; logs wall time. Opt-in: `-e rawlensHlDevice true`.
 */
class HlDeviceParityDeviceTest {
    @Test
    fun reconstructKnownScenes() {
        assumeTrue("Pass -e rawlensHlDevice true",
            InstrumentationRegistry.getArguments().getString("rawlensHlDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.getExternalFilesDir(null), "hl-device").apply { deleteRecursively(); mkdirs() }
        val re = Regex("(.+)_(\\d+)x(\\d+)\\.f16")
        val files = File("/data/local/tmp/rawlens_hl").listFiles()?.filter { re.matches(it.name) }.orEmpty()
        assumeTrue("push scenes to /data/local/tmp/rawlens_hl", files.isNotEmpty())
        // -e hlSliceBudget <px>: measurement only (VkBound.dispatch watchdog slicing).
        InstrumentationRegistry.getArguments().getString("hlSliceBudget")?.let {
            System.setProperty("hub.sliceBudget", it)
        }
        val handle = VulkanHub.open()
        val workers = Executors.newFixedThreadPool(4)
        try {
            for (f in files.sortedBy { it.name }) {
                val (name, w, h) = re.matchEntire(f.name)!!.destructured
                val width = w.toInt(); val height = h.toInt()
                val src = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val camera = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder())
                while (src.hasRemaining()) camera.putShort(src.short)
                camera.flip()
                val t0 = SystemClock.elapsedRealtime()
                val result = HlReconstructionStage.applyIfEnabled(
                    camera, width, height, true,
                    HlReconstructionStage.Inputs(1023f, floatArrayOf(64f, 64f, 64f, 64f), floatArrayOf(0.2126f, 0.7152f, 0.0722f)),
                    gpuProvider = { VulkanHub.GpuContext(handle, context.assets) },
                    workers = workers
                )
                Log.i(TAG, "HLDEV $name ${width}x$height ${SystemClock.elapsedRealtime() - t0} ms")
                val shorts = result.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                val rgb = ByteBuffer.allocate(width * height * 12).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until width * height) {
                    for (c in 0..2) rgb.putFloat(HlReconstructionStage.unpackHalf(shorts.get(i * 4 + c).toInt() and 0xffff))
                }
                File(out, "$name.f32").writeBytes(rgb.array())
            }
        } finally {
            workers.shutdown()
            handle.close()
        }
    }

    private companion object {
        const val TAG = "RawLensHlDevice"
    }
}
