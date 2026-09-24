// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device pixel parity for the native NEON fallback sampler: same
 * quad-fetch + reciprocal-normalize contract the zero-copy GPU tiers use.
 * Asserts through the real JNI (including symbol linkage), with no camera.
 */
@RunWith(AndroidJUnit4::class)
class VfCpuNeonInstrumentedTest {
    private fun direct(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    @Test fun allBayerPatternsReconstructCanonicalChannels() {
        assumeTrue("vf native library unavailable", VfCpuNeon.available)
        for (cfa in 0..3) {
            val channels = RawPreviewGeometry.channels(cfa)
            val source = direct(128)
            source.position(4)
            val values = intArrayOf(100, 200, 300, 400)
            channels.forEachIndexed { index, channel ->
                source.putShort(4 + (2 + channel / 2) * 20 + (2 + channel % 2) * 4, values[index].toShort())
            }
            val output = direct(4)
            VfCpuNeon.copy(source, 20, 4, 2, 2, 1, 1, 2, channels, FloatArray(4), 510f, output)
            assertEquals(listOf(50, 100, 150, 200), List(4) { output.get().toInt() and 255 })
            assertEquals(4, source.position())
        }
    }

    @Test fun blackWhiteAndClippingUseEachSensorSiteLevel() {
        assumeTrue("vf native library unavailable", VfCpuNeon.available)
        val source = direct(8)
        listOf(10, 40, 65535, 0).forEach { source.putShort(it.toShort()) }
        source.flip()
        val output = direct(4)
        VfCpuNeon.copy(
            source, 4, 2, 0, 0, 1, 1, 2, intArrayOf(0, 1, 2, 3),
            floatArrayOf(10f, 20f, 30f, 40f), 60f, output
        )
        assertEquals(listOf(0, 128, 255, 0), List(4) { output.get().toInt() and 255 })
    }

    @Test fun stridedMultiPixelMatchesScalarReference() {
        assumeTrue("vf native library unavailable", VfCpuNeon.available)
        val width = 5
        val height = 2
        val step = 4
        val left = 2
        val top = 2
        val channels = intArrayOf(1, 0, 3, 2)
        val black = floatArrayOf(10f, 20f, 30f, 40f)
        val white = 1023f
        val lastX = left + (width - 1) * step + 1
        val lastY = top + (height - 1) * step + 1
        val shortsPerRow = lastX + 1 + 4
        val rows = lastY + 1 + 2
        val rnd = java.util.Random(7)
        val shorts = ShortArray(rows * shortsPerRow) { rnd.nextInt(1024).toShort() }
        val source = direct(rows * shortsPerRow * 2)
        shorts.forEach { source.putShort(it) }
        source.flip()
        val output = direct(width * height * 4)
        VfCpuNeon.copy(
            source, shortsPerRow * 2, 2, left, top, width, height, step,
            channels, black, white, output
        )
        // Scalar reference: identical op order, so NEON bytes must match exactly
        // across the 4-wide vector block and the scalar tail.
        val inv = FloatArray(4) { i -> 1f / (white - black[i]).coerceAtLeast(1f) }
        val expected = ByteArray(width * height * 4)
        var o = 0
        for (y in 0 until height) for (x in 0 until width) for (c in 0..3) {
            val ch = channels[c]
            val sx = left + x * step + ch % 2
            val sy = top + y * step + ch / 2
            val code = shorts[sy * shortsPerRow + sx].toInt() and 0xffff
            val n = ((code - black[ch]) * inv[ch]).coerceIn(0f, 1f)
            expected[o++] = (n * 255f + 0.5f).toInt().toByte()
        }
        assertEquals(expected.size, output.remaining())
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i].toInt() and 255, output.get().toInt() and 255)
        }
    }

    @Test fun maxEdge1080RowMatchesScalarReference() {
        assumeTrue("vf native library unavailable", VfCpuNeon.available)
        // 1080-wide packed row proves the raised native bound end to end; the
        // scalar spot-check pins the NEON block across a realistic stride.
        val width = 1080
        val shortsPerRow = 2160
        val shorts = ShortArray(2 * shortsPerRow) { i -> ((i * 7919 + 13) % 1024).toShort() }
        val source = direct(shorts.size * 2)
        shorts.forEach { source.putShort(it) }
        source.flip()
        val output = direct(width * 4)
        VfCpuNeon.copy(
            source, shortsPerRow * 2, 2, 0, 0, width, 1, 2,
            intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, output
        )
        assertEquals(width * 4, output.remaining())
        val bytes = ByteArray(output.remaining())
        output.get(bytes)
        // Same op order as the native sampler (reciprocal multiply, not
        // division) so the reference cannot drift by 1 ulp.
        val inv = 1f / 1023f
        for (x in intArrayOf(0, 1, 2, 3, 4, 539, 1077, 1078, 1079)) {
            for (c in 0..3) {
                val sx = x * 2 + c % 2
                val sy = c / 2
                val code = shorts[sy * shortsPerRow + sx].toInt() and 0xffff
                val n = ((code - 0f) * inv).coerceIn(0f, 1f)
                assertEquals(
                    "x=$x c=$c", (n * 255f + 0.5f).toInt(),
                    bytes[(x * 4 + c)].toInt() and 255
                )
            }
        }
    }

    @Test fun tallFrameThreadsRowBandsDeterministically() {
        assumeTrue("vf native library unavailable", VfCpuNeon.available)
        // 9 rows over 4 bands split 2/2/2/3: pins the uneven tail band and
        // proves threaded dispatch writes bit-identical bytes.
        val width = 7
        val height = 9
        val step = 4
        val channels = intArrayOf(1, 0, 3, 2)
        val black = floatArrayOf(10f, 20f, 30f, 40f)
        val white = 1023f
        val shortsPerRow = 32
        val rows = 36
        val rnd = java.util.Random(21)
        val shorts = ShortArray(rows * shortsPerRow) { rnd.nextInt(1024).toShort() }
        val source = direct(rows * shortsPerRow * 2)
        shorts.forEach { source.putShort(it) }
        source.flip()
        val output = direct(width * height * 4)
        VfCpuNeon.copy(
            source, shortsPerRow * 2, 2, 0, 0, width, height, step,
            channels, black, white, output
        )
        val inv = FloatArray(4) { i -> 1f / (white - black[i]).coerceAtLeast(1f) }
        assertEquals(width * height * 4, output.remaining())
        for (y in 0 until height) for (x in 0 until width) for (c in 0..3) {
            val ch = channels[c]
            val sx = x * step + ch % 2
            val sy = y * step + ch / 2
            val code = shorts[sy * shortsPerRow + sx].toInt() and 0xffff
            val n = ((code - black[ch]) * inv[ch]).coerceIn(0f, 1f)
            assertEquals(
                "x=$x y=$y c=$c", (n * 255f + 0.5f).toInt(),
                output.get().toInt() and 255
            )
        }
    }

    @Test fun nativeRejectsBadArguments() {
        assumeTrue("vf native library unavailable", VfCpuNeon.available)
        val src = direct(64)
        val dst = direct(64)
        assertEquals(
            VfCpuNeon.BAD_ARGUMENT,
            VfCpuNeon.copyNative(
                src, 0, 8, 2, 1, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, dst, 0
            )
        )
        assertEquals(
            VfCpuNeon.OK,
            VfCpuNeon.copyNative(
                src, 0, 8, 2, 0, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, dst, 0
            )
        )
    }
}
