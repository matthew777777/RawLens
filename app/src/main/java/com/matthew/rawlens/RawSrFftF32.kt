// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Float32 transcription of [RawSrFft] (same mixed-radix decimation in
 * time, same factor order, same per-output summation order, same
 * row-then-column 2D order): the tight oracle for the GPU FFT grey pass,
 * which runs the identical arithmetic in GLSL float. Twiddle tables are
 * the CPU double tables rounded to float (same angles, same order).
 *
 * GPU-vs-[RawSrFftF32] agreement bounds the transcription (pinned tight
 * by VkFftGreyParityTest); [RawSrFftF32]-vs-[RawSrFft] agreement bounds
 * the precision step. Deterministic and thread-safe like the double
 * original. Mirrored byte-identical in app/ and tools/sr-vulkan/.
 */
object RawSrFftF32 {
    private data class Tables(val forward: FloatArray, val inverse: FloatArray)

    private val cache = ConcurrentHashMap<Int, Tables>()

    private fun tables(size: Int): Tables {
        require(size >= 1) { "FFT size must be positive" }
        return cache.computeIfAbsent(size) { n ->
            val forward = FloatArray(n * 2)
            val inverse = FloatArray(n * 2)
            for (k in 0 until n) {
                val angle = 2.0 * PI * k / n
                forward[k * 2] = cos(angle).toFloat()
                forward[k * 2 + 1] = (-sin(angle)).toFloat()
                inverse[k * 2] = cos(angle).toFloat()
                inverse[k * 2 + 1] = sin(angle).toFloat()
            }
            Tables(forward, inverse)
        }
    }

    /**
     * In-place 1D complex DFT of [re]/[im] ([size] elements). Set
     * [inverse] for the backward transform (scales by 1/[size]).
     */
    fun fft1d(re: FloatArray, im: FloatArray, size: Int, inverse: Boolean) {
        require(re.size >= size && im.size >= size) { "Buffers too small for FFT size $size" }
        if (size <= 1) return // 1-point DFT is identity (scale 1/1)
        val tmpRe = FloatArray(size)
        val tmpIm = FloatArray(size)
        dit(re, im, 0, 1, tmpRe, tmpIm, 0, size, inverse)
        tmpRe.copyInto(re, 0, 0, size)
        tmpIm.copyInto(im, 0, 0, size)
        if (inverse) {
            val scale = (1.0 / size).toFloat()
            for (i in 0 until size) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    /**
     * Decimation-in-time: DFT of size [n] from strided [src] into [dst].
     * X[k1 + n1*k2] over n = n2*n1 + n2 with n1*n2 = n.
     */
    private fun dit(
        srcRe: FloatArray, srcIm: FloatArray, srcOff: Int, srcStride: Int,
        dstRe: FloatArray, dstIm: FloatArray, dstOff: Int,
        n: Int, inverse: Boolean
    ) {
        if (n == 1) {
            dstRe[dstOff] = srcRe[srcOff]
            dstIm[dstOff] = srcIm[srcOff]
            return
        }
        val p = RawSrFftPlan.smallestFactor(n)
        require(p <= RawSrFft.MAX_RADIX) {
            "FFT size $n has prime factor $p above ${RawSrFft.MAX_RADIX}; no camera size needs this"
        }
        if (p == n) {
            naive(srcRe, srcIm, srcOff, srcStride, dstRe, dstIm, dstOff, n, inverse)
            return
        }
        val n1 = p
        val n2 = n / p
        // Stage 1: n2 DFTs of size n1 on input stride n2.
        // Stage 2: twiddle by W_n^(n2i * k1); stage 3: n1 DFTs of size n2.
        ditStaged(srcRe, srcIm, srcOff, srcStride, dstRe, dstIm, dstOff, n, n1, n2, inverse)
    }

    private fun naive(
        srcRe: FloatArray, srcIm: FloatArray, srcOff: Int, srcStride: Int,
        dstRe: FloatArray, dstIm: FloatArray, dstOff: Int,
        n: Int, inverse: Boolean
    ) {
        val table = if (inverse) tables(n).inverse else tables(n).forward
        for (k in 0 until n) {
            var sumRe = 0f
            var sumIm = 0f
            for (t in 0 until n) {
                val w = table[((t * k) % n) * 2]
                val wim = table[((t * k) % n) * 2 + 1]
                val a = srcRe[srcOff + t * srcStride]
                val b = srcIm[srcOff + t * srcStride]
                sumRe += a * w - b * wim
                sumIm += a * wim + b * w
            }
            dstRe[dstOff + k] = sumRe
            dstIm[dstOff + k] = sumIm
        }
    }

    private fun ditStaged(
        srcRe: FloatArray, srcIm: FloatArray, srcOff: Int, srcStride: Int,
        dstRe: FloatArray, dstIm: FloatArray, dstOff: Int,
        n: Int, n1: Int, n2: Int, inverse: Boolean
    ) {
        val table = if (inverse) tables(n).inverse else tables(n).forward
        // Stage 1+2 output: y[n2i * n1 + k1], twiddled.
        val stage = FloatArray(n * 2)
        val blockRe = FloatArray(n1)
        val blockIm = FloatArray(n1)
        for (n2i in 0 until n2) {
            dit(srcRe, srcIm, srcOff + n2i * srcStride, srcStride * n2,
                blockRe, blockIm, 0, n1, inverse)
            for (k1 in 0 until n1) {
                val w = table[((n2i * k1) % n) * 2]
                val wim = table[((n2i * k1) % n) * 2 + 1]
                val a = blockRe[k1]
                val b = blockIm[k1]
                stage[(n2i * n1 + k1) * 2] = a * w - b * wim
                stage[(n2i * n1 + k1) * 2 + 1] = a * wim + b * w
            }
        }
        // Stage 3: n1 DFTs of size n2 over stride-n1 stage data.
        val colRe = FloatArray(n2)
        val colIm = FloatArray(n2)
        val outRe = FloatArray(n2)
        val outIm = FloatArray(n2)
        for (k1 in 0 until n1) {
            for (n2i in 0 until n2) {
                colRe[n2i] = stage[(n2i * n1 + k1) * 2]
                colIm[n2i] = stage[(n2i * n1 + k1) * 2 + 1]
            }
            dit(colRe, colIm, 0, 1, outRe, outIm, 0, n2, inverse)
            for (k2 in 0 until n2) {
                dstRe[dstOff + k1 + n1 * k2] = outRe[k2]
                dstIm[dstOff + k1 + n1 * k2] = outIm[k2]
            }
        }
    }

    /**
     * In-place 2D complex DFT of the row-major [width]x[height] planes
     * [re]/[im]. Rows then columns; set [inverse] for the backward
     * transform (scales by 1/(width*height)).
     */
    fun fft2d(re: FloatArray, im: FloatArray, width: Int, height: Int, inverse: Boolean) {
        require(re.size >= width * height && im.size >= width * height) {
            "Planes too small for ${width}x$height FFT"
        }
        // Row pass then column pass, each sharded: lines are independent
        // with disjoint reads/writes and shard-local scratch, so any
        // worker count agrees bitwise.
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val lineRe = FloatArray(width)
            val lineIm = FloatArray(width)
            for (y in y0 until y1) {
                val base = y * width
                for (x in 0 until width) {
                    lineRe[x] = re[base + x]
                    lineIm[x] = im[base + x]
                }
                fft1d(lineRe, lineIm, width, inverse)
                for (x in 0 until width) {
                    re[base + x] = lineRe[x]
                    im[base + x] = lineIm[x]
                }
            }
        }
        RawSrWorkers.forEachShard(width) { x0, x1 ->
            val lineRe = FloatArray(height)
            val lineIm = FloatArray(height)
            for (x in x0 until x1) {
                for (y in 0 until height) {
                    lineRe[y] = re[y * width + x]
                    lineIm[y] = im[y * width + x]
                }
                fft1d(lineRe, lineIm, height, inverse)
                for (y in 0 until height) {
                    re[y * width + x] = lineRe[y]
                    im[y * width + x] = lineIm[y]
                }
            }
        }
    }

    /**
     * torch.fft.fftshift (forward) / ifftshift (backward): circular shift
     * by floor(n/2) / (n - floor(n/2)) per axis, in place. Transcribed
     * from [RawSrAlignment] (same axis order, same visit order).
     */
    fun fftShift(re: FloatArray, im: FloatArray, width: Int, height: Int, forward: Boolean) {
        shiftAxis(re, im, width, height, rowWise = true, forward)
        shiftAxis(re, im, width, height, rowWise = false, forward)
    }

    private fun shiftAxis(
        re: FloatArray, im: FloatArray, width: Int, height: Int,
        rowWise: Boolean, forward: Boolean
    ) {
        val n = if (rowWise) width else height
        val shift = if (forward) n / 2 else n - n / 2
        if (shift == 0) return
        if (rowWise) {
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                val tmpRe = FloatArray(width)
                val tmpIm = FloatArray(width)
                for (y in y0 until y1) {
                    val base = y * width
                    for (x in 0 until width) {
                        tmpRe[(x + shift) % width] = re[base + x]
                        tmpIm[(x + shift) % width] = im[base + x]
                    }
                    for (x in 0 until width) {
                        re[base + x] = tmpRe[x]
                        im[base + x] = tmpIm[x]
                    }
                }
            }
        } else {
            RawSrWorkers.forEachShard(width) { x0, x1 ->
                val tmpRe = FloatArray(height)
                val tmpIm = FloatArray(height)
                for (x in x0 until x1) {
                    for (y in 0 until height) {
                        tmpRe[(y + shift) % height] = re[y * width + x]
                        tmpIm[(y + shift) % height] = im[y * width + x]
                    }
                    for (y in 0 until height) {
                        re[y * width + x] = tmpRe[y]
                        im[y * width + x] = tmpIm[y]
                    }
                }
            }
        }
    }

    /**
     * Float transcription of [RawSrAlignment.fftGrey] (same shift/mask/
     * scale sequence): the tight oracle for the GPU grey pass. The flip
     * is applied on load ([RawSrCfaOrientation.toProcessingSpace]
     * semantics: 0 identity, 1 hflip, 2 vflip, 3 rot180), matching the
     * GPU first stage's fused u_flip read.
     */
    fun fftGrey(mosaic: FloatArray, width: Int, height: Int, flip: Int = 0): RawSrGrayImage {
        require(mosaic.size == width * height)
        require(flip in 0..3)
        val n = width * height
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (y in 0 until height) {
            val sy = if (flip == 2 || flip == 3) height - 1 - y else y
            for (x in 0 until width) {
                val sx = if (flip == 1 || flip == 3) width - 1 - x else x
                re[y * width + x] = mosaic[sy * width + sx]
            }
        }
        fft2d(re, im, width, height, false)
        fftShift(re, im, width, height, forward = true)
        val qy = height / 4
        val qx = width / 4
        for (y in 0 until qy) for (x in 0 until width) {
            val i = y * width + x
            re[i] = 0f
            im[i] = 0f
        }
        for (y in height - qy until height) for (x in 0 until width) {
            val i = y * width + x
            re[i] = 0f
            im[i] = 0f
        }
        for (y in 0 until height) for (x in 0 until qx) {
            val i = y * width + x
            re[i] = 0f
            im[i] = 0f
        }
        for (y in 0 until height) for (x in width - qx until width) {
            val i = y * width + x
            re[i] = 0f
            im[i] = 0f
        }
        fftShift(re, im, width, height, forward = false)
        fft2d(re, im, width, height, true)
        return RawSrGrayImage(width, height, re.copyOf())
    }
}
