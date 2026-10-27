// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * Writer for Cinematic RAW Studio (.CRAW) takes: lossless RAW video and
 * burst containers (encoder: third_party/craw, Apache-2.0; JNI:
 * cpp/craw_writer_jni.cpp, library `rawLensCraw`).
 *
 * Threading: the handle is confined to the recorder's single committer
 * thread (all [writeFrame]/[writeAudio]/[writeGyro]/[writeAccel]/[close]
 * calls); [encodeFrame] runs on any number of worker threads.
 */
internal object CrawWriter {
    /** True when the native encoder loaded (false on host JVM tests). */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("rawLensCraw")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun open(path: String, containerMetadataJson: String): Long {
        if (!available) throw IllegalStateException("CRAW writer unavailable")
        val handle = try {
            containerOpen(path, containerMetadataJson)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRAW writer missing", e)
        }
        if (handle == 0L) throw IllegalStateException("ContainerWriter failed to open $path")
        return handle
    }

    /**
     * Encode one RAW16 frame into [destination] (position advanced by the
     * payload size). Worker-thread safe. Returns the payload byte count;
     * negative native codes become exceptions (-3 = destination too small).
     * With [bin], the frame is binned to half resolution: each output sample is
     * the rounded mean of the four same-colour samples of its 4x4 block (lossy
     * by nature); [cfa] is the Camera2 color-filter arrangement (0..3).
     * [bitDepth] comes from SENSOR_INFO_WHITE_LEVEL ([bitDepthFor]); a sample
     * above it falls back to 16-bit coding, so encoding stays lossless.
     */
    fun encodeFrame(
        source: ByteBuffer, size: Int, width: Int, height: Int, stride: Int,
        cropTop: Int, cropHeight: Int, destination: ByteBuffer,
        bin: Boolean, cfa: Int, bitDepth: Int = 16
    ): Int {
        checkFrameGeometry(width, height, cropTop, cropHeight, bin, cfa)
        require(source.isDirect && destination.isDirect) {
            "CrawWriter needs direct buffers"
        }
        if (!available) throw IllegalStateException("CRAW writer unavailable")
        val dstOffset = destination.position()
        val written = try {
            containerEncodeFrame(
                source, source.position(), size, width, height, stride,
                cropTop, cropHeight, bin, cfa, bitDepth.coerceIn(1, 16),
                destination, dstOffset, destination.remaining()
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRAW encoder missing", e)
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
        require(payload.isDirect) { "CrawWriter needs a direct buffer" }
        require(payload.remaining() >= bytes) {
            "Payload holds ${payload.remaining()}, needs $bytes"
        }
        if (!available) throw IllegalStateException("CRAW writer unavailable")
        val written = try {
            containerWriteFrame(
                handle, payload, payload.position(), bytes, timestampNs, frameJson
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRAW writer missing", e)
        }
        if (written < 0) throw IllegalStateException("writeFrame failed: code=$written")
    }

    /** Bits needed for samples up to [whiteLevel] (Camera2 SENSOR_INFO_WHITE_LEVEL). */
    fun bitDepthFor(whiteLevel: Int?): Int =
        if (whiteLevel == null || whiteLevel <= 0) 16 else (32 - Integer.numberOfLeadingZeros(whiteLevel)).coerceIn(1, 16)

    private fun checkFrameGeometry(
        width: Int, height: Int, cropTop: Int, cropHeight: Int, bin: Boolean, cfa: Int
    ) {
        require(width > 0 && height > 0 && width % 2 == 0) { "Bad extent ${width}x$height" }
        require(height % 4 == 0) { "Encoder requires height % 4 == 0, got $height" }
        require(cropTop >= 0 && cropTop % 2 == 0) { "cropTop must be even, got $cropTop" }
        require(cropHeight > 0 && cropTop + cropHeight <= height) {
            "Bad crop top=$cropTop height=$cropHeight for h=$height"
        }
        require(cfa in 0..3) { "Bad CFA arrangement $cfa" }
        if (bin) {
            require(width % 4 == 0) { "Binning requires width % 4 == 0, got $width" }
            require(cropHeight % 8 == 0) {
                "Binning requires cropHeight % 8 == 0, got $cropHeight"
            }
        }
    }

    fun close(handle: Long) {
        if (!available) throw IllegalStateException("CRAW writer unavailable")
        val code = try {
            containerClose(handle)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRAW writer missing", e)
        }
        if (code != 0) throw IllegalStateException("containerClose failed: code=$code")
    }

    /**
     * Append one audio chunk with [channels] interleaved channels (1 or 2) at
     * [sampleRate]: little-endian float32 (AUDI format F32) when [float], else
     * int16 (S16). [samples] holds `frames * channels` samples; [timestampNs]
     * is the first frame's time on the video frame timeline (recording-
     * relative ns). Returns bytes written.
     */
    fun writeAudio(
        handle: Long, samples: ByteBuffer, frames: Int, channels: Int, timestampNs: Long,
        sampleRate: Int = AudioPcmRecorder.SAMPLE_RATE, float: Boolean = false
    ): Int {
        require(frames > 0) { "Empty audio chunk" }
        require(channels == 1 || channels == 2) { "Bad channel count $channels" }
        require(samples.isDirect) { "CrawWriter needs a direct buffer" }
        val need = frames * channels * (if (float) 4 else 2)
        require(samples.remaining() >= need) {
            "Audio buffer holds ${samples.remaining()}, needs $need"
        }
        if (!available) throw IllegalStateException("CRAW writer unavailable")
        val written = try {
            containerWriteAudio(
                handle, samples, samples.position(), frames, channels, sampleRate, float, timestampNs
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRAW writer missing", e)
        }
        if (written < 0) throw IllegalStateException("writeAudio failed: code=$written")
        return written
    }

    /**
     * Append motion samples: [count] samples, [timestampsNs] length [count],
     * [axes] length `count * 3` (x/y/z triplets). The writer may buffer
     * and coalesce them (at most one chunk per sensor per committed frame
     * plus a trailing chunk at close). Gyro axes are rad/s in
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
        if (!available) throw IllegalStateException("CRAW writer unavailable")
        val written = try {
            if (gyro) containerWriteGyro(handle, timestampsNs, axes, count)
            else containerWriteAccel(handle, timestampsNs, axes, count)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRAW writer missing", e)
        }
        if (written < 0) throw IllegalStateException("writeMotion failed: code=$written")
        return written
    }

    external fun containerOpen(path: String, metadataJson: String): Long

    external fun containerEncodeFrame(
        src: ByteBuffer, srcOffset: Int, size: Int,
        w: Int, h: Int, stride: Int, cropTop: Int, cropHeight: Int,
        bin: Boolean, cfa: Int, bitDepth: Int,
        dst: ByteBuffer, dstOffset: Int, dstCapacity: Int
    ): Int

    external fun containerWriteFrame(
        handle: Long, payload: ByteBuffer, payloadOffset: Int, payloadBytes: Int,
        timestampNs: Long, frameJson: String
    ): Int

    external fun containerWriteAudio(
        handle: Long, samples: ByteBuffer, offset: Int, frames: Int, channels: Int,
        sampleRate: Int, float: Boolean, timestampNs: Long
    ): Int

    external fun containerWriteGyro(
        handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int
    ): Int

    external fun containerWriteAccel(
        handle: Long, timestampsNs: LongArray, axes: FloatArray, count: Int
    ): Int

    external fun containerClose(handle: Long): Int
}
