// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap

/**
 * Scopes the DEVELOPED output (the exact pixels a saved JPEG is encoded
 * from), mirroring the [RawHistogramSampler]/[RawWaveformSampler] layouts so
 * live and saved scopes render identically: 64-bin RGB + Rec.709 luma
 * histogram, 96x48 RGB parade waveform. The live RAW scopes sample the
 * mosaic before exposure/white-balance (linear mode matches the DNG; the
 * AgX-LUT mode predicts tone response only), while these bins hold finished
 * display-referred sRGB — so a saved-frame scope matches the saved JPEG the
 * way the linear RAW scope matches the saved DNG.
 */
object DevelopedScopeSampler {
    private const val HISTOGRAM_BINS = 64
    /** Samples per bitmap pass cap; matches the live scopes' ~32k density. */
    private const val TARGET_SAMPLES = 32768

    data class DevelopedScope(val histogram: RgbHistogram, val waveform: RgbWaveform)

    /**
     * Pure histogram core over packed ARGB ints (framework-free for unit
     * tests): same bin formula as the live sampler, [agxApplied] true
     * because developed output is display-referred.
     */
    fun histogramOfArgb(pixels: IntArray): RgbHistogram {
        val red = IntArray(HISTOGRAM_BINS)
        val green = IntArray(HISTOGRAM_BINS)
        val blue = IntArray(HISTOGRAM_BINS)
        val luminance = IntArray(HISTOGRAM_BINS)
        for (pixel in pixels) {
            val r = ((pixel ushr 16) and 0xff) / 255.0
            val g = ((pixel ushr 8) and 0xff) / 255.0
            val b = (pixel and 0xff) / 255.0
            red[binOf(r)]++
            green[binOf(g)]++
            blue[binOf(b)]++
            luminance[binOf(RawHistogramSampler.luminanceOf(r, g, b))]++
        }
        return RgbHistogram(red, green, blue, luminance, agxApplied = true)
    }

    /**
     * Pure waveform core over packed ARGB ints in row-major order
     * ([width]x[height]): same 96x48 per-channel parade cells as the live
     * sampler ([RawWaveformSampler.columnLut]/levelOf).
     */
    fun waveformOfArgb(pixels: IntArray, width: Int, height: Int): RgbWaveform {
        require(pixels.size == width * height)
        val columns = RawWaveformSampler.columnLut(width)
        val red = IntArray(RawWaveformSampler.COLUMNS * RawWaveformSampler.LEVELS)
        val green = IntArray(RawWaveformSampler.COLUMNS * RawWaveformSampler.LEVELS)
        val blue = IntArray(RawWaveformSampler.COLUMNS * RawWaveformSampler.LEVELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val cell = columns[x] * RawWaveformSampler.LEVELS
                red[cell + RawWaveformSampler.levelOf(((pixel ushr 16) and 0xff) / 255.0)]++
                green[cell + RawWaveformSampler.levelOf(((pixel ushr 8) and 0xff) / 255.0)]++
                blue[cell + RawWaveformSampler.levelOf((pixel and 0xff) / 255.0)]++
            }
        }
        return RgbWaveform(
            RawWaveformSampler.COLUMNS, RawWaveformSampler.LEVELS,
            red, green, blue, agxApplied = true
        )
    }

    /**
     * One pass over [bitmap] (stride-sampled to [TARGET_SAMPLES]), building
     * both scopes. Thin Android touchpoint ([Bitmap.getPixels] in reused row
     * strips); binning lives in the pure cores above.
     */
    fun sampleBitmap(bitmap: Bitmap): DevelopedScope? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 2 || height < 2) return null
        val step = maxOf(1, kotlin.math.sqrt(width.toDouble() * height / TARGET_SAMPLES).toInt())
        val row = IntArray(width)
        val red = IntArray(HISTOGRAM_BINS)
        val green = IntArray(HISTOGRAM_BINS)
        val blue = IntArray(HISTOGRAM_BINS)
        val luminance = IntArray(HISTOGRAM_BINS)
        val columns = RawWaveformSampler.columnLut(width)
        val waveRed = IntArray(RawWaveformSampler.COLUMNS * RawWaveformSampler.LEVELS)
        val waveGreen = IntArray(RawWaveformSampler.COLUMNS * RawWaveformSampler.LEVELS)
        val waveBlue = IntArray(RawWaveformSampler.COLUMNS * RawWaveformSampler.LEVELS)
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                val pixel = row[x]
                val r = ((pixel ushr 16) and 0xff) / 255.0
                val g = ((pixel ushr 8) and 0xff) / 255.0
                val b = (pixel and 0xff) / 255.0
                red[binOf(r)]++
                green[binOf(g)]++
                blue[binOf(b)]++
                luminance[binOf(RawHistogramSampler.luminanceOf(r, g, b))]++
                val cell = columns[x] * RawWaveformSampler.LEVELS
                waveRed[cell + RawWaveformSampler.levelOf(r)]++
                waveGreen[cell + RawWaveformSampler.levelOf(g)]++
                waveBlue[cell + RawWaveformSampler.levelOf(b)]++
                x += step
            }
            y += step
        }
        return DevelopedScope(
            RgbHistogram(red, green, blue, luminance, agxApplied = true),
            RgbWaveform(
                RawWaveformSampler.COLUMNS, RawWaveformSampler.LEVELS,
                waveRed, waveGreen, waveBlue, agxApplied = true
            )
        )
    }

    private fun binOf(value: Double): Int =
        (value * (HISTOGRAM_BINS - 1)).toInt().coerceIn(0, HISTOGRAM_BINS - 1)
}
