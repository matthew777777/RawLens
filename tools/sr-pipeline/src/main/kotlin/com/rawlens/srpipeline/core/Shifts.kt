package com.rawlens.srpipeline.core

/**
 * `fftshift`/`ifftshift` helpers (NumPy semantics).
 *
 * `fftShift` moves the DC component to the centre with a per-axis roll of
 * `(n + 1) / 2`; `ifftShift` is its inverse with a roll of `n / 2`. The two
 * coincide for even extents and differ for odd ones.
 */
object Shifts {

    fun fftShift(input: FloatArray, height: Int, width: Int): FloatArray {
        checkGrid(input.size, height, width)
        return roll2D(input, height, width, (height + 1) / 2, (width + 1) / 2)
    }

    fun ifftShift(input: FloatArray, height: Int, width: Int): FloatArray {
        checkGrid(input.size, height, width)
        return roll2D(input, height, width, height / 2, width / 2)
    }

    fun fftShift(spectrum: ComplexSpectrum): ComplexSpectrum {
        val real = fftShift(spectrum.real, spectrum.height, spectrum.width)
        val imag = fftShift(spectrum.imag, spectrum.height, spectrum.width)
        return ComplexSpectrum(spectrum.height, spectrum.width, real, imag)
    }

    fun ifftShift(spectrum: ComplexSpectrum): ComplexSpectrum {
        val real = ifftShift(spectrum.real, spectrum.height, spectrum.width)
        val imag = ifftShift(spectrum.imag, spectrum.height, spectrum.width)
        return ComplexSpectrum(spectrum.height, spectrum.width, real, imag)
    }

    fun fftShift(input: FloatArray): FloatArray {
        require(input.isNotEmpty()) { "input must be non-empty" }
        return roll1D(input, (input.size + 1) / 2)
    }

    fun ifftShift(input: FloatArray): FloatArray {
        require(input.isNotEmpty()) { "input must be non-empty" }
        return roll1D(input, input.size / 2)
    }

    private fun checkGrid(size: Int, height: Int, width: Int) {
        require(height > 0 && width > 0) { "height/width must be > 0 (got $height x $width)" }
        require(size == height * width) { "input.size $size != height * width ${height * width}" }
    }

    private fun roll1D(input: FloatArray, shift: Int): FloatArray {
        val n = input.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[(i + shift) % n] = input[i]
        }
        return out
    }

    private fun roll2D(input: FloatArray, height: Int, width: Int, shiftY: Int, shiftX: Int): FloatArray {
        val out = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ny = (y + shiftY) % height
                val nx = (x + shiftX) % width
                out[ny * width + nx] = input[y * width + x]
            }
        }
        return out
    }
}
