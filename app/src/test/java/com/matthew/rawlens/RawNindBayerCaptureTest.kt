package com.matthew.rawlens

import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class RawNindBayerCaptureTest {
    private fun source(pattern: BayerPattern) = UnpackedRawCfa(4, 4, pattern,
        FloatArray(16) { i -> when (pattern.colorAt(i % 4, i / 4)) {
            CfaColor.RED -> 0.2f
            CfaColor.GREEN -> 0.5f
            CfaColor.BLUE -> 0.8f
        } }, RawCrop(0, 0, 4, 4), 3, 5)

    @Test fun captureUsesBundledBayerWhenTinyIsMissingAndMatchesLearnedGain() {
        val processor = mock(RawNindNcnnProcessor::class.java)
        `when`(processor.waitReady(anyLong())).thenReturn(false)
        `when`(processor.waitBayerReady(anyLong())).thenReturn(true)
        val bytes = ByteBuffer.allocateDirect(16 * 3 * 4).order(ByteOrder.nativeOrder())
        val values = bytes.asFloatBuffer()
        repeat(16) { p ->
            values.put(p * 3, 200_000f)
            values.put(p * 3 + 1, 500_000f)
            values.put(p * 3 + 2, 800_000f)
        }
        // Shifted (w2, h2) vary by pattern — RGGB (2, 2), GRBG (1, 2),
        // GBRG (2, 1), BGGR (1, 1); the oversized uniform buffer satisfies
        // every limit check.
        `when`(processor.runInferenceBayer(any(FloatBuffer::class.java), anyInt(), anyInt())).thenReturn(bytes)
        val denoiser = RawNindDenoiser(processor)
        for (pattern in BayerPattern.entries) {
            val input = source(pattern)
            val result = denoiser.denoise(input, CfaNoiseModel.from(null))!!
            assertEquals(input.pattern, result.pattern)
            assertEquals(input.sensorCropLeft, result.sensorCropLeft)
            assertEquals(input.sensorCropTop, result.sensorCropTop)
            assertArrayEquals(input.values, result.values, 1e-6f)
        }
        verify(processor, times(4)).runInferenceBayer(any(FloatBuffer::class.java), anyInt(), anyInt())
        verify(processor, never()).runInference(any(FloatBuffer::class.java), anyInt(), anyInt())
    }

    @Test fun remosaicSamplesAtSiteAndKeepsMarginsAtSource() {
        // Distinct model value per (pixel, channel) so any off-site sampling
        // fails; margins (outside the R-shifted working grid) keep source.
        val rgb = FloatBuffer.wrap(FloatArray(4 * 4 * 3) { it / 100f })
        for (pattern in BayerPattern.entries) {
            val input = source(pattern)
            val result = RawNindPack.bayerRgbToCfa(rgb, input, 1f)
            val (y0, x0) = RawNindPack.bayerOrigin(pattern)
            val mw = 2 * ((4 - x0) / 2)
            val mh = 2 * ((4 - y0) / 2)
            for (y in 0..3) for (x in 0..3) {
                val expected = if (y in y0 until y0 + mh && x in x0 until x0 + mw) {
                    val wy = y - y0
                    val wx = x - x0
                    // Working grid is RGGB by construction.
                    val c = when ((wy and 1) * 2 + (wx and 1)) {
                        0 -> 0
                        3 -> 2
                        else -> 1
                    }
                    rgb.get((wy * mw + wx) * 3 + c)
                } else {
                    input.values[y * 4 + x]
                }
                assertEquals("$pattern@$x,$y", expected, result.values[y * 4 + x], 1e-6f)
            }
            assertSame(input, RawNindPack.bayerRgbToCfa(rgb, input, 0f))
        }
        val invalid = FloatBuffer.wrap(FloatArray(48) { Float.NaN })
        val input = source(BayerPattern.RGGB)
        assertArrayEquals(input.values, RawNindPack.bayerRgbToCfa(invalid, input, 1f).values, 0f)
    }

    @Test fun gainMatchingRejectsBrokenNetworkOutputAndSupportsNegativeLearnedScale() {
        assertEquals(-0.001, RawNindPack.bayerOutputGain(
            FloatBuffer.wrap(floatArrayOf(-200f, -200f, -200f)), floatArrayOf(0.2f)), 1e-9)
        assertThrows(IllegalArgumentException::class.java) {
            RawNindPack.bayerOutputGain(FloatBuffer.wrap(floatArrayOf(Float.POSITIVE_INFINITY)), floatArrayOf(0.2f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawNindPack.bayerOutputGain(FloatBuffer.wrap(floatArrayOf(0f)), floatArrayOf(0.2f))
        }
    }
}
