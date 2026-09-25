// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Host ncnnMl proof: the shared JNI bridge + upstream ncnn run the real
 * KernelNet model on a synthetic tile. Skips loudly when the host library
 * or staged models are absent (offline/native-less builds keep the
 * analytic fallback).
 */
class NcnnKernelNetHostTest {
    @Test
    fun kernelNetInferenceRunsAndIsDeterministic() {
        assumeTrue("host libncnnMl not built", NcnnLoader.tryLoad())
        val ctx = Context(copyStagedModels())
        val proc = KernelNetNcnnProcessor.start(ctx.applicationContext)
        assertTrue("kernelnet init", proc.waitReady(120_000))
        assertTrue(proc.isReady)

        val w = 128
        val h = 128
        val gray = ByteBuffer.allocateDirect(w * h * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Gentle gradient + checker detail in [0,1].
                val v = (x + y) / 254f * 0.8f + ((x / 8 + y / 8) % 2) * 0.2f
                gray.put(v.coerceIn(0f, 1f))
            }
        }

        val t0 = System.nanoTime()
        val r1 = proc.runInference(gray, w, h, 0.02f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("inference result", r1)
        r1!!
        assertEquals(64, r1.width)
        assertEquals(64, r1.height)
        val p1 = FloatArray(64 * 64 * 3)
        r1.params().get(p1)
        assertTrue("all finite", p1.all { it.isFinite() })
        assertTrue("net produced varying output", p1.min() < p1.max())
        // Result contract (with slack for other GPUs/inputs): s1/s2 are
        // [0,2]-ish kernel sigmas, rho is in [-1,1].
        val s1 = p1.slice(0 until 4096)
        val s2 = p1.slice(4096 until 8192)
        val rho = p1.slice(8192 until 12288)
        assertTrue("s1 range", s1.min() >= -0.05f && s1.max() <= 2.5f)
        assertTrue("s2 range", s2.min() >= -0.05f && s2.max() <= 2.5f)
        assertTrue("rho range", rho.min() >= -1.05f && rho.max() <= 1.05f)
        // Debug hook for cross-backend parity (step 4): -Dncnn.dump=<path>
        // writes the raw s1/s2/rho planes for offline comparison.
        System.getProperty("ncnn.dump")?.let { path ->
            val bytes = ByteBuffer.allocate(p1.size * 4)
                .order(ByteOrder.nativeOrder())
            bytes.asFloatBuffer().put(p1)
            File(path).outputStream().use { it.write(bytes.array()) }
            println("kernelnet host: dumped ${p1.size} floats to $path")
        }
        val digest = p1.fold(0.0) { acc, v -> acc + v }
        println(
            "kernelnet host: 128x128 -> 64x64x3 in ${ms}ms " +
                "s1=[${s1.min()}, ${s1.max()}] " +
                "s2=[${s2.min()}, ${s2.max()}] " +
                "rho=[${rho.min()}, ${rho.max()}] digest=%.6f".format(digest)
        )

        // Same input twice must give bitwise-identical output (same GPU,
        // same tiled path); any drift means uninitialized/stale memory.
        val r2 = proc.runInference(gray, w, h, 0.02f)
        assertNotNull("second inference", r2)
        val p2 = FloatArray(64 * 64 * 3)
        r2!!.params().get(p2)
        assertTrue(
            "bitwise deterministic",
            p1.indices.all { p1[it].toBits() == p2[it].toBits() }
        )
    }

    /** Copy the Gradle-staged models into a temp APK-assets layout. */
    private fun copyStagedModels(): File {
        val root = Files.createTempDirectory("ncnn-assets").toFile()
        val models = File(root, "models").apply { mkdirs() }
        listOf(
            "kernelnet_aniso_v2_2_params.ncnn.param",
            "kernelnet_aniso_v2_2_params.ncnn.bin"
        ).forEach { name ->
            val stream = javaClass.getResourceAsStream("/$name")
            assumeTrue("model $name staged", stream != null)
            stream!!.use { input ->
                File(models, name).outputStream().use { input.copyTo(it) }
            }
        }
        return root
    }
}
