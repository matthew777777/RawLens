// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * Writer handle to the vendored MediaCinemaRAW container (GPL-3.0-only,
 * see NOTICE.md). Thin wrapper in the [VfCpuNeon]/[CinemaRawSpike] style.
 *
 * Threading: the native handle is confined to the recorder's single
 * committer thread (all [writeFrame]/[writeAudio]/[writeGyro]/[writeAccel]/
 * [close] calls); [encodeFrame] runs on any number of worker threads
 * (stateless: thread_local scratch in native). [encodeFrame] feeds the HAL's
 * unpacked RAW16 plane straight to the type-7 encoder — per the P0 verdict,
 * packed-RAW10 input is never used (scalar encoder path, 4x slower).
 */
internal object CinemaRawWriter {
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

    fun open(path: String, containerMetadataJson: String): Long {
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val handle = try {
            containerOpen(path, containerMetadataJson)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw writer missing", e)
        }
        if (handle == 0L) throw IllegalStateException("ContainerWriter failed to open $path")
        return handle
    }

    /**
     * Encode one RAW16 frame into [destination] (position advanced by the
     * payload size). Worker-thread safe. Returns the payload byte count;
     * negative native codes become exceptions (-3 = destination too small).
     */
    fun encodeFrame(
        source: ByteBuffer, size: Int, width: Int, height: Int, stride: Int,
        cropTop: Int, cropHeight: Int, destination: ByteBuffer
    ): Int {
        checkFrameGeometry(width, height, cropTop, cropHeight)
        require(source.isDirect && destination.isDirect) {
            "CinemaRawWriter needs direct buffers"
        }
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val dstOffset = destination.position()
        val written = try {
            containerEncodeFrame(
                source, source.position(), size, width, height, stride,
                cropTop, cropHeight, destination, dstOffset, destination.remaining()
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw encoder missing", e)
        }
        if (written < 0) throw IllegalStateException("encodeFrame failed: code=$written")
        destination.position(dstOffset + written)
        return written
    }

    /**
     * Commit one encoded payload to the container. Committer-thread only:
     * [timestampNs] must be strictly increasing across calls on a handle
     * (the recorder's reorder buffer guarantees capture order).
     */
    fun writeFrame(
        handle: Long, payload: ByteBuffer, bytes: Int, timestampNs: Long, frameJson: String
    ) {
        require(bytes > 0) { "Empty payload" }
        require(payload.isDirect) { "CinemaRawWriter needs a direct buffer" }
        require(payload.remaining() >= bytes) {
            "Payload holds ${payload.remaining()}, needs $bytes"
        }
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val written = try {
            containerWriteFrame(
                handle, payload, payload.position(), bytes, timestampNs, frameJson
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw writer missing", e)
        }
        if (written < 0) throw IllegalStateException("writeFrame failed: code=$written")
    }

    private fun checkFrameGeometry(width: Int, height: Int, cropTop: Int, cropHeight: Int) {
        require(width > 0 && height > 0 && width % 2 == 0) { "Bad extent ${width}x$height" }
        require(height % 4 == 0) { "Encoder requires height % 4 == 0, got $height" }
        require(cropTop >= 0 && cropTop % 2 == 0) { "cropTop must be even, got $cropTop" }
        require(cropHeight > 0 && cropTop + cropHeight <= height) {
            "Bad crop top=$cropTop height=$cropHeight for h=$height"
        }
    }

    fun close(handle: Long) {
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val code = try {
            containerClose(handle)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw writer missing", e)
        }
        if (code != 0) throw IllegalStateException("containerClose failed: code=$code")
    }

    /**
     * Append one PCM16 chunk with [channels] interleaved channels (1 or 2).
     * [samples] holds `frames * channels` little-endian int16 samples;
     * [timestampNs] is the first frame's time on the video frame timeline
     * (recording-relative ns). The container is channel-agnostic — it
     * stores the int16 array verbatim; players interpret channels via
     * `extraData.audioChannels`. Returns payload bytes written.
     */
    fun writeAudio(
        handle: Long, samples: ByteBuffer, frames: Int, channels: Int, timestampNs: Long
    ): Int {
        require(frames > 0) { "Empty audio chunk" }
        require(channels == 1 || channels == 2) { "Bad channel count $channels" }
        require(samples.isDirect) { "CinemaRawWriter needs a direct buffer" }
        require(samples.remaining() >= frames * channels * 2) {
            "Audio buffer holds ${samples.remaining()}, needs ${frames * channels * 2}"
        }
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val written = try {
            containerWriteAudio(
                handle, samples, samples.position(), frames * channels, timestampNs
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw writer missing", e)
        }
        if (written < 0) throw IllegalStateException("writeAudio failed: code=$written")
        return written
    }

    /**
     * Append motion samples: [count] samples, [timestampsNs] length [count],
     * [axes] length `count * 3` (x/y/z triplets). The native writer buffers
     * and coalesces them — at most one chunk per sensor per committed frame
     * plus a trailing chunk at close — because motioncam-decoder rejects
     * files with more motion chunks than frames + 1. Gyro axes are rad/s in
     * the camera frame (map with [GyroCameraFrameMapper] before calling);
     * accel axes are m/s^2 including gravity, platform convention.
     * Timestamps are boot-time ns on the video frame timeline, ascending.
     * Returns samples written.
     */
    fun writeGyro(handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int): Int =
        writeMotion(handle, timestampsNs, axes, count, gyro = true)

    fun writeAccel(handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int): Int =
        writeMotion(handle, timestampsNs, axes, count, gyro = false)

    private fun writeMotion(
        handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int, gyro: Boolean
    ): Int {
        require(count > 0) { "Empty motion chunk" }
        require(timestampsNs.size >= count) { "timestamps hold ${timestampsNs.size}, need $count" }
        require(axes.size >= count * 3) { "axes hold ${axes.size}, need ${count * 3}" }
        if (!available) throw IllegalStateException("cinemaraw native library unavailable")
        val written = try {
            if (gyro) containerWriteGyro(handle, timestampsNs, axes, count)
            else containerWriteAccel(handle, timestampsNs, axes, count)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("cinemaraw writer missing", e)
        }
        if (written < 0) throw IllegalStateException("writeMotion failed: code=$written")
        return written
    }

    external fun containerOpen(path: String, metadataJson: String): Long

    external fun containerEncodeFrame(
        src: ByteBuffer, srcOffset: Int, size: Int,
        w: Int, h: Int, stride: Int, cropTop: Int, cropHeight: Int,
        dst: ByteBuffer, dstOffset: Int, dstCapacity: Int
    ): Int

    external fun containerWriteFrame(
        handle: Long, payload: ByteBuffer, payloadOffset: Int, payloadBytes: Int,
        timestampNs: Long, frameJson: String
    ): Int

    external fun containerWriteAudio(
        handle: Long, samples: ByteBuffer, offset: Int, totalShorts: Int,
        timestampNs: Long
    ): Int

    external fun containerWriteGyro(
        handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int
    ): Int

    external fun containerWriteAccel(
        handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int
    ): Int

    external fun containerClose(handle: Long): Int
}
