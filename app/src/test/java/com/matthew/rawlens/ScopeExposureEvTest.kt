// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * The JPEG scope must predict the saved file: adaptive exposure applies as a
 * scene-referred (pre-AgX) gain, while the DNG scope stays unexposed linear.
 * Uniform Bayer frames make the EV shift exact — every vote moves together.
 *
 * Tests drive the characteristics-free sampler cores directly: the static
 * [android.hardware.camera2.CameraCharacteristics] keys are null under plain
 * unit tests (android.jar stubs), so all three `get(null)` stubs would
 * collide and only the framework path stays uncovered here.
 */
class ScopeExposureEvTest {
    private data class Fixture(val image: Image, val plane: Image.Plane)

    private fun uniformFrame(size: Int = 64, code: Int = 256): Fixture {
        val rowStride = size * 2
        val buf = ByteBuffer.allocate(rowStride * size).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until size) for (x in 0 until size) {
            buf.putShort((y * size + x) * 2, code.toShort())
        }
        val image = Mockito.mock(Image::class.java)
        val plane = Mockito.mock(Image.Plane::class.java)
        Mockito.`when`(plane.buffer).thenReturn(buf)
        Mockito.`when`(plane.rowStride).thenReturn(rowStride)
        Mockito.`when`(plane.pixelStride).thenReturn(2)
        Mockito.`when`(image.planes).thenReturn(arrayOf(plane))
        Mockito.`when`(image.width).thenReturn(size)
        Mockito.`when`(image.height).thenReturn(size)
        return Fixture(image, plane)
    }

    private fun peakBin(bins: IntArray): Int {
        var best = 0
        for (i in bins.indices) if (bins[i] > bins[best]) best = i
        return best
    }

    @Test fun scopeEncodeIgnoresGainWithoutLut() {
        assertEquals(0.3, AgxDisplayTransform.scopeEncode(0.3, null, 4.0), 0.0)
    }

    @Test fun scopeEncodeGainMatchesScaledLookup() {
        val lut = AgxDisplayTransform.buildScopeLut(JpegOutputSettings())
        assertEquals(
            AgxDisplayTransform.scopeLutLookup(lut, 0.5).toDouble(),
            AgxDisplayTransform.scopeEncode(0.25, lut, 2.0),
            0.0
        )
        assertEquals(
            AgxDisplayTransform.scopeLutLookup(lut, 0.25).toDouble(),
            AgxDisplayTransform.scopeEncode(0.25, lut),
            0.0
        )
    }

    @Test fun histogramEvShiftsMassUpWithLut() {
        val (image, plane) = uniformFrame()
        val lut = AgxDisplayTransform.buildScopeLut(JpegOutputSettings())
        val base = RawHistogramSampler.sample(image, plane, 0, null, 1023, lut, 0.0)!!
        val lifted = RawHistogramSampler.sample(image, plane, 0, null, 1023, lut, 2.0)!!
        assertTrue(base.agxApplied)
        assertEquals(base.green.sum(), lifted.green.sum())
        assertTrue(
            "peak ${peakBin(base.green)} -> ${peakBin(lifted.green)}",
            peakBin(lifted.green) > peakBin(base.green)
        )
    }

    @Test fun histogramEvIgnoredWithoutLut() {
        // DNG path: no curve, no gain — bit-exact legacy regardless of EV.
        val (image, plane) = uniformFrame()
        val base = RawHistogramSampler.sample(image, plane, 0, null, 1023, null, 0.0)!!
        val exposed = RawHistogramSampler.sample(image, plane, 0, null, 1023, null, 2.0)!!
        assertArrayEquals(base.red, exposed.red)
        assertArrayEquals(base.green, exposed.green)
        assertArrayEquals(base.blue, exposed.blue)
        assertArrayEquals(base.luminance, exposed.luminance)
    }

    @Test fun waveformEvShiftsMassUpWithLut() {
        val (image, plane) = uniformFrame()
        val lut = AgxDisplayTransform.buildScopeLut(JpegOutputSettings())
        val base = RawWaveformSampler.sample(image, plane, 0, null, 1023, lut, 0.0)!!
        val lifted = RawWaveformSampler.sample(image, plane, 0, null, 1023, lut, 2.0)!!
        assertTrue(base.agxApplied)
        assertEquals(base.red.sum(), lifted.red.sum())
        val levels = RawWaveformSampler.LEVELS
        fun meanLevel(wave: RgbWaveform): Double {
            var weighted = 0L
            var total = 0L
            for (c in 0 until wave.columns) for (l in 0 until levels) {
                val votes = wave.red[c * levels + l]
                weighted += votes.toLong() * l
                total += votes
            }
            return weighted.toDouble() / total
        }
        assertTrue(
            "mean ${meanLevel(base)} -> ${meanLevel(lifted)}",
            meanLevel(lifted) > meanLevel(base)
        )
    }

    @Test fun waveformEvIgnoredWithoutLut() {
        val (image, plane) = uniformFrame()
        val base = RawWaveformSampler.sample(image, plane, 0, null, 1023, null, 0.0)!!
        val exposed = RawWaveformSampler.sample(image, plane, 0, null, 1023, null, 2.0)!!
        assertArrayEquals(base.red, exposed.red)
        assertArrayEquals(base.green, exposed.green)
        assertArrayEquals(base.blue, exposed.blue)
    }
}
