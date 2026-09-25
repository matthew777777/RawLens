// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * P0 spike bridge to the vendored MediaCinemaRAW encoder (GPL-3.0-only, see
 * NOTICE.md). Thin validated wrapper in the [VfCpuNeon] style: geometry is
 * checked in Kotlin so contract unit tests don't need the .so; the timed
 * pack/encode calls run on direct buffers with positions advanced by callers.
 *
 * This is spike scaffolding, not the final P1 API: [ContainerWriter] (file
 * muxing) is intentionally not exposed yet — the spike measures per-frame
 * pack + encode + storage separately to decide CPU vs Vulkan for the pack
 * stage.
 */
internal object CinemaRawSpike {
    val available: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("rawLensCinemaRaw")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
        available = loaded
    }

    /** Packed RAW10 row stride for a given width (width must be % 4 == 0). */
    fun packedStride(width: Int): Int {
        require(width > 0 && width % 4 == 0) { "RAW10 width must be % 4 == 0, got $width" }
        return width / 4 * 5
    }

    fun packRaw10(
        source: ByteBuffer, srcRowStride: Int, width: Int, height: Int,
        destination: ByteBuffer
    ) {
        require(width > 0 && height > 0 && width % 4 == 0) { "Bad extent ${width}x$height" }
        require(srcRowStride >= width * 2) { "srcRowStride $srcRowStride < ${width * 2}" }
        require(source.isDirect && destination.isDirect) { "CinemaRawSpike needs direct buffers" }
        require(source.remaining() >= (height - 1).toLong() * srcRowStride + width * 2) {
            "source holds ${source.remaining()}, needs ${(height - 1).toLong() * srcRowStride + width * 2}"
        }
        val needOut = packedStride(width).toLong() * height
        require(destination.remaining().toLong() >= needOut) {
            "destination holds ${destination.remaining()}, needs $needOut"
        }
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val code = try {
            packRaw10Native(
                source, source.position(), srcRowStride, width, height,
                destination, destination.position()
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw packer missing", e)
        }
        if (code != 0) throw IllegalStateException("packRaw10 failed: code=$code")
        destination.position(destination.position() + needOut.toInt())
    }

    /**
     * Encode one frame; returns the payload byte count. [size] is the input
     * plane byte count, [stride] its row stride. Mirrors the encoder's
     * geometry contract (even width, height % 4 == 0, even cropTop).
     */
    fun encode(
        source: ByteBuffer, size: Int, width: Int, height: Int, stride: Int,
        raw10: Boolean, cropTop: Int, cropHeight: Int, bin: Boolean,
        destination: ByteBuffer
    ): Int {
        require(width > 0 && height > 0 && width % 2 == 0) { "Bad extent ${width}x$height" }
        require(height % 4 == 0) { "Encoder requires height % 4 == 0, got $height" }
        require(cropTop >= 0 && cropTop % 2 == 0) { "cropTop must be even, got $cropTop" }
        require(cropHeight > 0 && cropTop + cropHeight <= height) {
            "Bad crop top=$cropTop height=$cropHeight for h=$height"
        }
        require(source.isDirect && destination.isDirect) { "CinemaRawSpike needs direct buffers" }
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val dstOffset = destination.position()
        val capacity = destination.remaining()
        val written = try {
            encodeNative(
                source, source.position(), size, width, height, stride,
                raw10, cropTop, cropHeight, bin, destination, dstOffset, capacity
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw encoder missing", e)
        }
        if (written < 0) throw IllegalStateException("encode failed: code=$written")
        destination.position(dstOffset + written)
        return written
    }

    external fun packRaw10Native(
        src: ByteBuffer, srcOffset: Int, srcStride: Int, w: Int, h: Int,
        dst: ByteBuffer, dstOffset: Int
    ): Int

    external fun encodeNative(
        src: ByteBuffer, srcOffset: Int, size: Int, w: Int, h: Int,
        stride: Int, raw10: Boolean, cropTop: Int, cropHeight: Int,
        bin: Boolean, dst: ByteBuffer, dstOffset: Int, dstCapacity: Int
    ): Int
}
