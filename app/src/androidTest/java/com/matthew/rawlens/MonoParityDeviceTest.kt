// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * GPU B&W (mono_detail / mono_clean) vs an independent CPU reference on a
 * 12 MP synthetic RGGB scene (neutral ramp, saturated patches, fine edges,
 * shot noise), plus checkerboard and packed-plane checks and median timings.
 */
@RunWith(AndroidJUnit4::class)
class MonoParityDeviceTest {
    private val w = 4000
    private val h = 3000
    private val balance = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val neutral = floatArrayOf(0.5f, 1.0f, 0.625f)

    private fun scene(): UnpackedRawCfa {
        val rnd = java.util.Random(7)
        val v = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // Scene radiance per channel (balanced units), then un-balance.
            var r: Float; var g: Float; var b: Float
            when {
                x < w / 4 -> { val l = 0.02f + 0.6f * y / h; r = l; g = l; b = l } // neutral ramp
                x < w / 2 -> { r = 0.5f; g = 0.08f; b = 0.03f }                     // saturated red
                x < 3 * w / 4 -> { val l = if ((x / 3 + y / 3) % 2 == 0) 0.6f else 0.1f; r = l; g = l; b = l } // fine edges
                else -> { r = 0.05f; g = 0.2f; b = 0.45f }                           // saturated blue
            }
            val c = BayerPattern.RGGB.colorAt(x, y).ordinal
            val bal = floatArrayOf(r, g, b)[c]
            val noisy = bal + rnd.nextGaussian().toFloat() * sqrt(1e-4f * bal + 1e-6f)
            v[y * w + x] = noisy / balance[c]
        }
        return UnpackedRawCfa(w, h, BayerPattern.RGGB, v, RawCrop(0, 0, w, h))
    }

    private fun ref(cfa: UnpackedRawCfa, clean: Boolean, a: Float, b: Float): FloatArray {
        val v = cfa.values
        val clip = OpposedHighlights.CLIP * balance[1]
        fun bal(x: Int, y: Int) = minOf(v[y * w + x] * balance[BayerPattern.RGGB.colorAt(x, y).ordinal], clip)
        if (clean) return FloatArray((w / 2) * (h / 2)) {
            val ox = it % (w / 2); val oy = it / (w / 2)
            0.25f * (bal(2 * ox, 2 * oy) + bal(2 * ox + 1, 2 * oy) + bal(2 * ox, 2 * oy + 1) + bal(2 * ox + 1, 2 * oy + 1))
        }
        val wt = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val out = FloatArray(w * h)
        for (y in 2 until h - 3) for (x in 2 until w - 3) {
            val cls = DoubleArray(4) // ee, oe, eo, oo
            for (dy in -2..2) for (dx in -2..2) {
                val k = (if (dx and 1 != 0) 1 else 0) + (if (dy and 1 != 0) 2 else 0)
                cls[k] += wt[dx + 2] * wt[dy + 2] * bal(x + dx, y + dy).toDouble()
            }
            for (k in 0..3) cls[k] /= 64.0
            val yy = cls.sum() / 4.0
            val own = max(cls[0], 0.0)
            val sigma = sqrt(max(a * own + b, 1e-12))
            val c = bal(x, y)
            out[y * w + x] = if (c >= clip) max(yy, c.toDouble()).toFloat()
            else (yy * (max(c.toDouble(), 0.0) + sigma) / (own + sigma)).toFloat()
        }
        return out
    }

    @Test
    fun gpuMatchesReferenceAndTimes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cfa = scene()
        VulkanHub.openVulkan10().use { vk ->
            MonoVulkan(vk, ctx.assets).use { mono ->
                for (mode in listOf(MonoMode.DETAIL, MonoMode.CLEAN)) {
                    val frame = mono.run(cfa, mode, balance, neutral, wantMono = true, wantPacked = true)
                    try {
                        val r = ref(cfa, mode == MonoMode.CLEAN, frame.noise.a, frame.noise.b)
                        val gpu = requireNotNull(frame.mono)
                        val ow = frame.width
                        var maxRel = 0.0; var n = 0
                        val border = if (mode == MonoMode.CLEAN) 0 else 3
                        for (y in border until frame.height - border) for (x in border until ow - border) {
                            val e = abs(gpu[y * ow + x] - r[y * ow + x]) / max(abs(r[y * ow + x]), 1e-3f)
                            if (e > maxRel) maxRel = e.toDouble(); n++
                        }
                        // Packed halves must equal mono * neutral (half precision).
                        val p = requireNotNull(frame.packed).duplicate().order(ByteOrder.nativeOrder())
                        var maxHalf = 0.0
                        for (i in 0 until frame.width * frame.height step 997) {
                            val rg = p.getInt(i * 8); val ba = p.getInt(i * 8 + 4)
                            val R = android.util.Half.toFloat((rg and 0xffff).toShort())
                            val G = android.util.Half.toFloat((rg ushr 16).toShort())
                            maxHalf = max(maxHalf, (abs(R - gpu[i] * neutral[0]) / max(gpu[i] * neutral[0], 1e-3f)).toDouble())
                            maxHalf = max(maxHalf, (abs(G - gpu[i] * neutral[1]) / max(gpu[i] * neutral[1], 1e-3f)).toDouble())
                            assertTrue(android.util.Half.toFloat((ba ushr 16).toShort()) == 1f)
                        }
                        // Checkerboard on the saturated red patch: 2x2 phase means must agree.
                        val ph = DoubleArray(4); val cnt = IntArray(4)
                        val sx = if (mode == MonoMode.CLEAN) 2 else 1
                        for (y in 500 / sx until 2500 / sx) for (x in 1100 / sx until 1900 / sx) {
                            val k = (x and 1) + 2 * (y and 1); ph[k] += gpu[y * ow + x]; cnt[k]++
                        }
                        val means = ph.indices.map { ph[it] / cnt[it] }
                        val checker = (means.max() - means.min()) / means.average()
                        val times = (0 until 5).map {
                            mono.run(cfa, mode, balance, neutral, wantMono = false, wantPacked = true)
                                .also { f -> f.close() }.gpuMs
                        }.sorted()
                        android.util.Log.i("MonoParity", "$mode ${frame.width}x${frame.height} n=$n maxRel=$maxRel " +
                            "packedHalfRel=$maxHalf redChecker=$checker noise a=${frame.noise.a} b=${frame.noise.b} " +
                            "detail/noise=${frame.noise.detailEnergy / frame.noise.noiseEnergy} " +
                            "gpu+io median=${times[2]}ms min=${times[0]}ms")
                        assertTrue("maxRel $maxRel", maxRel < 1e-4)
                        assertTrue("half $maxHalf", maxHalf < 1e-3)
                        assertTrue("checker $checker", checker < 0.02)
                    } finally { frame.close() }
                }
            }
        }
    }
}
