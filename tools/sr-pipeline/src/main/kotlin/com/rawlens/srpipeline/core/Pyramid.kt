package com.rawlens.srpipeline.core

import kotlin.math.exp

/**
 * Gaussian pyramid helpers matching the reference `downsample`/`upscale`
 * stages.
 *
 * Downsampling blurs with a separable Gaussian of `sigma = factor * 0.5`
 * and `radius = int(2 * factor + 0.5)`, then decimates with floored output
 * dims (`out = in // factor`). Upsampling is bilinear interpolation to the
 * requested size.
 */
object Pyramid {

    /** 1D Gaussian kernel with `sigma = factor * 0.5`. */
    fun gaussianKernel(factor: Int): FloatArray {
        require(factor > 1) { "factor must be > 1 (got $factor)" }
        val radius = (2 * factor + 0.5).toInt()
        val sigma = factor * 0.5
        val kernel = FloatArray(2 * radius + 1) { i ->
            val d = (i - radius).toDouble()
            exp(-0.5 * (d / sigma) * (d / sigma)).toFloat()
        }
        val sum = kernel.sum()
        require(sum > 0f) { "degenerate kernel for factor $factor" }
        return FloatArray(kernel.size) { kernel[it] / sum }
    }

    /** Separable Gaussian blur of a single-channel [height] x [width] frame. */
    fun gaussianBlur(input: FloatArray, height: Int, width: Int, factor: Int): FloatArray {
        require(height > 0 && width > 0) { "height/width must be > 0 (got $height x $width)" }
        require(input.size == height * width) {
            "input.size ${input.size} != height * width ${height * width}"
        }
        val kernel = gaussianKernel(factor)
        val radius = kernel.size / 2
        val tmp = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sx = (x + k - radius).coerceIn(0, width - 1)
                    acc += input[y * width + sx] * kernel[k]
                }
                tmp[y * width + x] = acc
            }
        }
        val out = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sy = (y + k - radius).coerceIn(0, height - 1)
                    acc += tmp[sy * width + x] * kernel[k]
                }
                out[y * width + x] = acc
            }
        }
        return out
    }

    /**
     * Gaussian downsample by integer [factor]. `factor == 1` is a passthrough.
     * Output dims are floored (`height / factor`, `width / factor`).
     */
    fun downsample(input: FloatArray, height: Int, width: Int, factor: Int): Grid {
        require(height > 0 && width > 0) { "height/width must be > 0 (got $height x $width)" }
        require(input.size == height * width) {
            "input.size ${input.size} != height * width ${height * width}"
        }
        require(factor >= 1) { "factor must be >= 1 (got $factor)" }
        if (factor == 1) {
            return Grid(height, width, input.copyOf())
        }
        val blurred = gaussianBlur(input, height, width, factor)
        val outH = height / factor
        val outW = width / factor
        require(outH > 0 && outW > 0) { "factor $factor too large for ${height}x$width" }
        val out = FloatArray(outH * outW)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                out[y * outW + x] = blurred[(y * factor) * width + (x * factor)]
            }
        }
        return Grid(outH, outW, out)
    }

    /** Bilinear upsample to [newHeight] x [newWidth]. */
    fun upsample(input: FloatArray, height: Int, width: Int, newHeight: Int, newWidth: Int): Grid {
        require(height > 0 && width > 0) { "height/width must be > 0 (got $height x $width)" }
        require(input.size == height * width) {
            "input.size ${input.size} != height * width ${height * width}"
        }
        require(newHeight > 0 && newWidth > 0) {
            "newHeight/newWidth must be > 0 (got $newHeight x $newWidth)"
        }
        val out = FloatArray(newHeight * newWidth)
        val scaleY = if (newHeight == 1) 0.0 else (height - 1).toDouble() / (newHeight - 1)
        val scaleX = if (newWidth == 1) 0.0 else (width - 1).toDouble() / (newWidth - 1)
        for (y in 0 until newHeight) {
            val sy = y * scaleY
            val y0 = sy.toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceIn(0, height - 1)
            val fy = (sy - y0).toFloat()
            for (x in 0 until newWidth) {
                val sx = x * scaleX
                val x0 = sx.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceIn(0, width - 1)
                val fx = (sx - x0).toFloat()
                val v00 = input[y0 * width + x0]
                val v01 = input[y0 * width + x1]
                val v10 = input[y1 * width + x0]
                val v11 = input[y1 * width + x1]
                out[y * newWidth + x] =
                    v00 * (1 - fx) * (1 - fy) + v01 * fx * (1 - fy) +
                    v10 * (1 - fx) * fy + v11 * fx * fy
            }
        }
        return Grid(newHeight, newWidth, out)
    }

    /** Multi-channel convenience: downsample every channel of [image]. */
    fun downsample(image: Image, factor: Int): Image {
        if (factor == 1) {
            return image.copy()
        }
        val first = downsample(image.channel(0), image.height, image.width, factor)
        val out = Image.zeros(first.height, first.width, image.channels)
        for (c in 0 until image.channels) {
            val grid = if (c == 0) first else downsample(image.channel(c), image.height, image.width, factor)
            for (y in 0 until first.height) {
                for (x in 0 until first.width) {
                    out[y, x, c] = grid.data[y * first.width + x]
                }
            }
        }
        return out
    }

    /** Multi-channel convenience: upsample every channel of [image]. */
    fun upsample(image: Image, newHeight: Int, newWidth: Int): Image {
        val out = Image.zeros(newHeight, newWidth, image.channels)
        for (c in 0 until image.channels) {
            val grid = upsample(image.channel(c), image.height, image.width, newHeight, newWidth)
            for (y in 0 until newHeight) {
                for (x in 0 until newWidth) {
                    out[y, x, c] = grid.data[y * newWidth + x]
                }
            }
        }
        return out
    }

    /**
     * Coarse-to-fine pyramid: one entry per factor in [factors], where each
     * entry holds the input downsampled by that factor.
     */
    fun buildPyramid(image: Image, factors: IntArray): List<Image> =
        factors.map { downsample(image, it) }

    /** Single-channel frame with its dims. */
    data class Grid(val height: Int, val width: Int, val data: FloatArray)
}
