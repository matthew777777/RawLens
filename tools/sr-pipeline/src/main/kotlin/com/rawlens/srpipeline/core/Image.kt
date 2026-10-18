package com.rawlens.srpipeline.core

/**
 * Dense float32 image in row-major [height, width, channels] layout.
 *
 * Mirrors the CPU reference pipeline convention where every stage works on
 * float32 buffers (2D raw/grey frames are the channels == 1 case).
 */
class Image(
    val height: Int,
    val width: Int,
    val channels: Int,
    val data: FloatArray,
) {
    init {
        require(height > 0) { "height must be > 0 (got $height)" }
        require(width > 0) { "width must be > 0 (got $width)" }
        require(channels > 0) { "channels must be > 0 (got $channels)" }
        require(data.size == height * width * channels) {
            "data.size ${data.size} != height * width * channels ${height * width * channels}"
        }
    }

    operator fun get(y: Int, x: Int, c: Int): Float = data[(y * width + x) * channels + c]

    operator fun set(y: Int, x: Int, c: Int, value: Float) {
        data[(y * width + x) * channels + c] = value
    }

    fun channel(c: Int): FloatArray {
        require(c in 0 until channels) { "channel $c out of range [0, $channels)" }
        val out = FloatArray(height * width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = get(y, x, c)
            }
        }
        return out
    }

    fun copy(): Image = Image(height, width, channels, data.copyOf())

    companion object {
        fun zeros(height: Int, width: Int, channels: Int): Image =
            Image(height, width, channels, FloatArray(height * width * channels))

        fun fromChannel(height: Int, width: Int, channel: FloatArray, channels: Int = 1): Image {
            require(channel.size == height * width) {
                "channel.size ${channel.size} != height * width ${height * width}"
            }
            return Image(height, width, channels, channel.copyOf())
        }

        operator fun invoke(
            height: Int,
            width: Int,
            channels: Int,
            init: (y: Int, x: Int, c: Int) -> Float,
        ): Image {
            val data = FloatArray(height * width * channels)
            val img = Image(height, width, channels, data)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    for (c in 0 until channels) {
                        img[y, x, c] = init(y, x, c)
                    }
                }
            }
            return img
        }
    }
}

/** One-dimensional complex spectrum: parallel real/imag arrays of [size]. */
data class ComplexArray(val size: Int, val real: FloatArray, val imag: FloatArray) {
    init {
        require(size > 0) { "size must be > 0 (got $size)" }
        require(real.size == size && imag.size == size) {
            "real/imag sizes (${real.size}, ${imag.size}) != $size"
        }
    }
}

/**
 * Two-dimensional complex spectrum in row-major [height, width] layout.
 *
 * Produced by [Fft.forward2D][com.rawlens.srpipeline.core.Fft.forward2D] and
 * consumed by the inverse transform; matches the reference pipeline's
 * complex64 FFT buffers inherited from float32 inputs.
 */
data class ComplexSpectrum(val height: Int, val width: Int, val real: FloatArray, val imag: FloatArray) {
    init {
        require(height > 0) { "height must be > 0 (got $height)" }
        require(width > 0) { "width must be > 0 (got $width)" }
        require(real.size == height * width && imag.size == height * width) {
            "real/imag sizes (${real.size}, ${imag.size}) != height * width ${height * width}"
        }
    }

    operator fun get(y: Int, x: Int): Pair<Float, Float> = Pair(real[y * width + x], imag[y * width + x])

    fun set(y: Int, x: Int, re: Float, im: Float) {
        real[y * width + x] = re
        imag[y * width + x] = im
    }

    fun copy(): ComplexSpectrum = ComplexSpectrum(height, width, real.copyOf(), imag.copyOf())
}
