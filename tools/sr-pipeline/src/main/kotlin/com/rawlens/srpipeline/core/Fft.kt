package com.rawlens.srpipeline.core

import org.jtransforms.fft.DoubleFFT_1D
import org.jtransforms.fft.DoubleFFT_2D

/**
 * Forward/inverse Fourier transforms backed by JTransforms.
 *
 * Conventions mirror the CPU reference pipeline (`torch.fft.fft2` /
 * `ifft2` on float32 inputs, complex64 spectra): the forward transform is
 * unscaled and the inverse transform divides by N so that
 * `inverse(forward(x)) == x`.
 */
object Fft {

    /** Unscaled forward 1D DFT of a real signal. */
    fun forward1D(input: FloatArray): ComplexArray {
        require(input.isNotEmpty()) { "input must be non-empty" }
        val n = input.size
        val fft = DoubleFFT_1D(n.toLong())
        val packed = DoubleArray(2 * n)
        for (i in 0 until n) {
            packed[2 * i] = input[i].toDouble()
            packed[2 * i + 1] = 0.0
        }
        fft.complexForward(packed)
        val real = FloatArray(n)
        val imag = FloatArray(n)
        for (i in 0 until n) {
            real[i] = packed[2 * i].toFloat()
            imag[i] = packed[2 * i + 1].toFloat()
        }
        return ComplexArray(n, real, imag)
    }

    /** Scaled inverse 1D DFT: returns the real signal. */
    fun inverse1D(spectrum: ComplexArray): FloatArray {
        val n = spectrum.size
        val fft = DoubleFFT_1D(n.toLong())
        val packed = DoubleArray(2 * n)
        for (i in 0 until n) {
            packed[2 * i] = spectrum.real[i].toDouble()
            packed[2 * i + 1] = spectrum.imag[i].toDouble()
        }
        fft.complexInverse(packed, true)
        return FloatArray(n) { packed[2 * it].toFloat() }
    }

    /** Unscaled forward 2D DFT of a real [height] x [width] frame. */
    fun forward2D(input: FloatArray, height: Int, width: Int): ComplexSpectrum {
        require(height > 0 && width > 0) { "height/width must be > 0 (got $height x $width)" }
        require(input.size == height * width) {
            "input.size ${input.size} != height * width ${height * width}"
        }
        val fft = DoubleFFT_2D(height.toLong(), width.toLong())
        val grid = Array(height) { y ->
            DoubleArray(2 * width) { x2 ->
                if (x2 % 2 == 0) input[y * width + x2 / 2].toDouble() else 0.0
            }
        }
        fft.complexForward(grid)
        val real = FloatArray(height * width)
        val imag = FloatArray(height * width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                real[y * width + x] = grid[y][2 * x].toFloat()
                imag[y * width + x] = grid[y][2 * x + 1].toFloat()
            }
        }
        return ComplexSpectrum(height, width, real, imag)
    }

    /** Scaled inverse 2D DFT: returns the real frame. */
    fun inverse2D(spectrum: ComplexSpectrum): FloatArray {
        val height = spectrum.height
        val width = spectrum.width
        val fft = DoubleFFT_2D(height.toLong(), width.toLong())
        val grid = Array(height) { y ->
            DoubleArray(2 * width) { x2 ->
                val x = x2 / 2
                if (x2 % 2 == 0) spectrum.real[y * width + x].toDouble()
                else spectrum.imag[y * width + x].toDouble()
            }
        }
        fft.complexInverse(grid, true)
        return FloatArray(height * width) { i ->
            grid[i / width][2 * (i % width)].toFloat()
        }
    }

    /**
     * FFT low-pass filter matching the reference `fft_lowpass` stage: forward
     * FFT, shift the DC component to the centre, zero the outer quarters on
     * each axis, shift back, inverse FFT (real part).
     */
    fun lowpass(input: FloatArray, height: Int, width: Int): FloatArray {
        require(height > 0 && width > 0) { "height/width must be > 0 (got $height x $width)" }
        require(input.size == height * width) {
            "input.size ${input.size} != height * width ${height * width}"
        }
        var spectrum = forward2D(input, height, width)
        spectrum = Shifts.fftShift(spectrum)
        val masked = spectrum.copy()
        val hq = height / 4
        val wq = width / 4
        for (y in 0 until height) {
            val zeroRow = y < hq || y >= height - hq
            for (x in 0 until width) {
                if (zeroRow || x < wq || x >= width - wq) {
                    masked.set(y, x, 0f, 0f)
                }
            }
        }
        val unshifted = Shifts.ifftShift(masked)
        return inverse2D(unshifted)
    }
}
