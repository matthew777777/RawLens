// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.SharedMemory
import java.nio.DoubleBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mixed-radix complex FFT (double precision) for the Jamy-L checkout
 * alignment port: the reference grey path (`compute_grey_images`, FFT
 * method) and its frequency-domain filtering need forward/inverse DFTs at
 * arbitrary camera sizes (e.g. 4000x3000, 4080x3060), which a radix-2 FFT
 * cannot serve without size-changing padding (padding changes the DFT bins
 * and would break parity).
 *
 * Cooley-Tukey decimation-in-time over the smallest prime factor (naive
 * O(p^2) base case for p <= [MAX_RADIX]); twiddle tables are shared per
 * size across rows, columns, and frames. Forward is unscaled, inverse
 * scales by 1/n (torch.fft/numpy convention). Sizes with a prime factor
 * above [MAX_RADIX] are rejected with a clear error (no camera size needs
 * them: 17 and 19 are covered).
 *
 * Deterministic: same twiddle values, same visit order, bitwise-identical
 * repeated runs. Thread-safe (tables are immutable once published).
 */
object RawSrFft {
    /** Largest prime factor served (covers 17/19 in padded camera sizes). */
    const val MAX_RADIX = 19

    private data class Tables(val forward: DoubleArray, val inverse: DoubleArray)

    private val cache = ConcurrentHashMap<Int, Tables>()

    private fun tables(size: Int): Tables {
        require(size >= 1) { "FFT size must be positive" }
        return cache.computeIfAbsent(size) { n ->
            val forward = DoubleArray(n * 2)
            val inverse = DoubleArray(n * 2)
            for (k in 0 until n) {
                val angle = 2.0 * PI * k / n
                forward[k * 2] = cos(angle)
                forward[k * 2 + 1] = -sin(angle)
                inverse[k * 2] = cos(angle)
                inverse[k * 2 + 1] = sin(angle)
            }
            Tables(forward, inverse)
        }
    }

    private fun smallestFactor(n: Int): Int {
        var p = 2
        while (p * p <= n) {
            if (n % p == 0) return p
            p += if (p == 2) 1 else 2
        }
        return n
    }

    /**
     * In-place 1D complex DFT of [re]/[im] ([size] elements). Set [inverse]
     * for the backward transform (scales by 1/[size]).
     */
    fun fft1d(re: DoubleArray, im: DoubleArray, size: Int, inverse: Boolean) {
        require(re.size >= size && im.size >= size) { "Buffers too small for FFT size $size" }
        if (size <= 1) return // 1-point DFT is identity (scale 1/1)
        val tmpRe = DoubleArray(size)
        val tmpIm = DoubleArray(size)
        dit(re, im, 0, 1, tmpRe, tmpIm, 0, size, inverse)
        tmpRe.copyInto(re, 0, 0, size)
        tmpIm.copyInto(im, 0, 0, size)
        if (inverse) {
            val scale = 1.0 / size
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
        srcRe: DoubleArray, srcIm: DoubleArray, srcOff: Int, srcStride: Int,
        dstRe: DoubleArray, dstIm: DoubleArray, dstOff: Int,
        n: Int, inverse: Boolean
    ) {
        if (n == 1) {
            dstRe[dstOff] = srcRe[srcOff]
            dstIm[dstOff] = srcIm[srcOff]
            return
        }
        val p = smallestFactor(n)
        require(p <= MAX_RADIX) {
            "FFT size $n has prime factor $p above $MAX_RADIX; no camera size needs this"
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
        srcRe: DoubleArray, srcIm: DoubleArray, srcOff: Int, srcStride: Int,
        dstRe: DoubleArray, dstIm: DoubleArray, dstOff: Int,
        n: Int, inverse: Boolean
    ) {
        val table = if (inverse) tables(n).inverse else tables(n).forward
        for (k in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
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
        srcRe: DoubleArray, srcIm: DoubleArray, srcOff: Int, srcStride: Int,
        dstRe: DoubleArray, dstIm: DoubleArray, dstOff: Int,
        n: Int, n1: Int, n2: Int, inverse: Boolean
    ) {
        val table = if (inverse) tables(n).inverse else tables(n).forward
        // Stage 1+2 output: y[n2i * n1 + k1], twiddled.
        val stage = DoubleArray(n * 2)
        val blockRe = DoubleArray(n1)
        val blockIm = DoubleArray(n1)
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
        val colRe = DoubleArray(n2)
        val colIm = DoubleArray(n2)
        val outRe = DoubleArray(n2)
        val outIm = DoubleArray(n2)
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
     * A pair of genuinely off-heap complex FFT planes, explicitly freed
     * with [close] (use `use`). One 12MP plane is ~100MB: two heap
     * planes plus the input and output float mosaics peak ~300MB of
     * Dalvik heap, over the 512MB growth limit beside a live camera
     * session (the 2026-10-04 logcats OOMed the 99MB plane alloc twice
     * at a ~485MB footprint, and the linear merge fell back to a
     * single-frame DNG both times).
     *
     * Backed by ashmem ([SharedMemory]), never [DoubleArray] and never
     * [java.nio.ByteBuffer.allocateDirect] on device: ART heap-backs
     * direct buffers in a non-movable store charged against the growth
     * limit, so allocateDirect OOMs exactly like a heap array (proven
     * by the second logcat, which died inside it). Where ashmem is
     * unavailable (JVM unit tests run against android.jar stubs)
     * [allocate] falls back to direct buffers — genuinely off-heap on
     * HotSpot, and on ART reachable only if ashmem itself failed. The
     * desktop host shim backs the same calls with HotSpot
     * allocateDirect, so the contract holds on every platform.
     *
     * The transform reads and writes identical doubles in identical
     * order, so results are bitwise-identical on every backing.
     */
    class FftPlanePair private constructor(
        /** Real plane, [words] doubles. */
        val re: DoubleBuffer,
        /** Imaginary plane, [words] doubles. */
        val im: DoubleBuffer,
        private val maps: List<java.nio.ByteBuffer>,
        private val stores: List<SharedMemory>,
        private val ashmem: Boolean
    ) : java.io.Closeable {
        /** Doubles per plane. */
        val words: Int get() = re.capacity()

        /**
         * Unmaps both mappings and closes both regions. Idempotent and
         * best-effort per step, so one failure cannot leak the other
         * region and a second close is a no-op instead of a
         * double-munmap.
         */
        override fun close() {
            if (closed) return
            closed = true
            // Only ashmem mappings need unmapping: fallback buffers are
            // GC-owned, and unmapping a non-ashmem address would corrupt.
            if (ashmem) maps.forEach { runCatching { SharedMemory.unmap(it) } }
            stores.forEach { runCatching { it.close() } }
        }

        private var closed = false

        companion object {
            /**
             * Allocates two zeroed off-heap planes of [words] doubles:
             * ashmem regions on device, direct buffers where
             * [SharedMemory] is unavailable. A half-allocated pair
             * unwinds what is already open, so a failed second region
             * cannot leak the first.
             */
            fun allocate(words: Int): FftPlanePair {
                require(words >= 0) { "FFT plane size must be non-negative" }
                val bytes = words.toLong() * Double.SIZE_BYTES
                require(bytes <= Int.MAX_VALUE) {
                    "FFT plane of $words doubles exceeds a byte buffer"
                }
                return try {
                    allocateAshmem(words, bytes.toInt())
                } catch (failure: Throwable) {
                    allocateDirect(words)
                }
            }

            private fun allocateAshmem(words: Int, size: Int): FftPlanePair {
                val maps = ArrayList<java.nio.ByteBuffer>(2)
                val stores = ArrayList<SharedMemory>(2)
                try {
                    repeat(2) {
                        // Platform type: android.jar stubs (JVM unit tests)
                        // return null instead of throwing.
                        val store = SharedMemory.create("rawsr-fft", size)
                            ?: throw IllegalStateException("SharedMemory unavailable")
                        stores.add(store)
                        maps.add(store.mapReadWrite())
                    }
                    return FftPlanePair(
                        maps[0].asDoubleBuffer(), maps[1].asDoubleBuffer(),
                        maps, stores, ashmem = true)
                } catch (failure: Throwable) {
                    maps.forEach { runCatching { SharedMemory.unmap(it) } }
                    stores.forEach { runCatching { it.close() } }
                    throw failure
                }
            }

            private fun allocateDirect(words: Int): FftPlanePair {
                // Fallback for environments without ashmem (JVM unit
                // tests): genuinely off-heap on HotSpot; heap-charged on
                // ART, where it only runs if ashmem itself failed.
                val maps = List(2) {
                    java.nio.ByteBuffer.allocateDirect(words * Double.SIZE_BYTES)
                }
                return FftPlanePair(
                    maps[0].asDoubleBuffer(), maps[1].asDoubleBuffer(),
                    maps, emptyList(), ashmem = false)
            }
        }
    }

    /**
     * In-place 2D complex DFT of the row-major [width]x[height] planes
     * [re]/[im]. Rows then columns; set [inverse] for the backward
     * transform (scales by 1/(width*height)).
     *
     * The array overload wraps and delegates here, so both spellings run
     * one implementation and agree bitwise.
     */
    fun fft2d(re: DoubleBuffer, im: DoubleBuffer, width: Int, height: Int, inverse: Boolean) {
        require(re.capacity() >= width * height && im.capacity() >= width * height) {
            "Planes too small for ${width}x$height FFT"
        }
        // Row pass then column pass, each sharded: lines are independent
        // with disjoint reads/writes and shard-local scratch, so any
        // worker count agrees bitwise. The join between passes is the
        // only needed visibility edge.
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val lineRe = DoubleArray(width)
            val lineIm = DoubleArray(width)
            for (y in y0 until y1) {
                val base = y * width
                for (x in 0 until width) {
                    lineRe[x] = re.get(base + x)
                    lineIm[x] = im.get(base + x)
                }
                fft1d(lineRe, lineIm, width, inverse)
                for (x in 0 until width) {
                    re.put(base + x, lineRe[x])
                    im.put(base + x, lineIm[x])
                }
            }
        }
        RawSrWorkers.forEachShard(width) { x0, x1 ->
            val lineRe = DoubleArray(height)
            val lineIm = DoubleArray(height)
            for (x in x0 until x1) {
                for (y in 0 until height) {
                    lineRe[y] = re.get(y * width + x)
                    lineIm[y] = im.get(y * width + x)
                }
                fft1d(lineRe, lineIm, height, inverse)
                for (y in 0 until height) {
                    re.put(y * width + x, lineRe[y])
                    im.put(y * width + x, lineIm[y])
                }
            }
        }
    }

    /**
     * Array spelling of the buffer 2D DFT above; wraps and delegates, so
     * results are bitwise-identical to the off-heap path.
     */
    fun fft2d(re: DoubleArray, im: DoubleArray, width: Int, height: Int, inverse: Boolean) {
        require(re.size >= width * height && im.size >= width * height) {
            "Planes too small for ${width}x$height FFT"
        }
        fft2d(DoubleBuffer.wrap(re), DoubleBuffer.wrap(im), width, height, inverse)
    }
}
