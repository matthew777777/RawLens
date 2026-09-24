// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * Fast native CPU fallback for the RAW viewfinder: one aligned Bayer quad per
 * display texel into canonical R/Gr/Gb/B order, normalized by reciprocal
 * multiply exactly like the zero-copy GPU tiers.
 *
 * The old Kotlin sampler is gone: per-sample `ByteBuffer.getShort` bounds
 * checks cost ~100+ ms per frame. This path runs in
 * `app/src/main/cpp/vf_cpu_neon.cpp` (NEON float32x4 normalize on ARM, scalar
 * on x86 emulators) with no allocation and no per-sample checks — single-digit
 * milliseconds for a 1080px frame.
 *
 * Both buffers must be direct: the camera plane and the viewfinder's
 * `Frame.pixels` already are. Arguments are validated in Kotlin (same contract
 * as the old sampler) so unit tests cover the contract without the native lib;
 * pixel parity is covered by the instrumented test on device.
 */
internal object VfCpuNeon {
    const val OK = 0
    const val BAD_ARGUMENT = 1
    const val OVERFLOW = 2
    const val NOT_DIRECT = 3

    /** Maximum VF long edge, shared with the viewfinder's reusable buffers. */
    const val MAX_EDGE = 1080

    val available: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("rawLensVfEgl")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
        available = loaded
    }

    fun describe(code: Int): String = when (code) {
        OK -> "ok"
        BAD_ARGUMENT -> "bad-argument"
        OVERFLOW -> "overflow"
        NOT_DIRECT -> "not-direct"
        else -> "code-$code"
    }

    /**
     * Copy one aligned Bayer quad per display texel into [destination] as
     * canonical R/Gr/Gb/B bytes. Mirrors the old sampler contract:
     * even [left]/[top]/[step], [step] >= 2, 1..[MAX_EDGE] extents.
     *
     * @throws IllegalArgumentException on bad geometry, short buffers, or
     * non-direct buffers (checked before the native call, so host unit tests
     * exercise the contract without the .so).
     * @throws IllegalStateException when the native library or call fails.
     */
    fun copy(
        source: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int,
        width: Int, height: Int, step: Int, channels: IntArray, black: FloatArray,
        white: Float, destination: ByteBuffer
    ) {
        require(left % 2 == 0 && top % 2 == 0 && step >= 2 && step % 2 == 0) {
            "Unaligned quad geometry left=$left top=$top step=$step"
        }
        require(width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE) {
            "Bad VF extent ${width}x$height"
        }
        require(channels.size == 4 && black.size == 4) { "channels/black must have 4 entries" }
        require(channels.all { it in 0..3 }) { "channels must be sensor sites 0..3" }
        require(rowStride > 0 && pixelStride > 0) { "Bad strides row=$rowStride px=$pixelStride" }
        require(white.isFinite() && white > 0f && black.all { it.isFinite() }) {
            "Bad levels white=$white"
        }
        require(source.isDirect && destination.isDirect) { "VfCpuNeon needs direct buffers" }
        val needOut = width * height * 4
        require(destination.remaining() >= needOut) {
            "destination holds ${destination.remaining()}, needs $needOut"
        }
        val lastX = left.toLong() + (width - 1).toLong() * step + 1
        val lastY = top.toLong() + (height - 1).toLong() * step + 1
        require(lastX >= 0 && lastY >= 0) { "Quad geometry overflows" }
        if (pixelStride == 2 && rowStride % 2 == 0) {
            val shortsPerRow = rowStride / 2
            val needShorts = lastY * shortsPerRow + lastX + 1
            require(source.remaining().toLong() >= needShorts * 2) {
                "source holds ${source.remaining()}, needs ${needShorts * 2}"
            }
        } else {
            val needSrc = lastY * rowStride + lastX * pixelStride + 2
            require(source.remaining().toLong() >= needSrc) {
                "source holds ${source.remaining()}, needs $needSrc"
            }
        }
        if (!available) throw IllegalStateException("vf native library unavailable")
        val srcOffset = source.position()
        val dstOffset = destination.position()
        val code = try {
            copyNative(
                source, srcOffset, rowStride, pixelStride, left, top,
                width, height, step, channels, black, white,
                destination, dstOffset
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("vf native sampler missing", e)
        }
        if (code != OK) throw IllegalStateException("vf native sampler failed: ${describe(code)}")
        destination.position(dstOffset + needOut)
        destination.flip()
    }

    external fun copyNative(
        source: ByteBuffer, srcOffset: Int, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int, step: Int,
        channels: IntArray, black: FloatArray, white: Float,
        destination: ByteBuffer, dstOffset: Int
    ): Int
}
