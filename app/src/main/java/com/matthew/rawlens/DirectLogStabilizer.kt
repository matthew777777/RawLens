// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.ImageWriter
import android.os.Build
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Post-record stabilization pass: staged OG MP4 + warp sidecar ->
 * stabilized MP4. HW decode (ByteBuffer) -> host upload -> Vulkan warp
 * ([VfStab]) -> dumb copy into HW HEVC P010 input -> re-encode -> mux,
 * with the audio track copied through unmodified.
 *
 * Runs on a background thread (the stop path owns it); progress reports
 * per warped frame. Never throws: every failure lands in [Report.error]
 * and the caller keeps the OG file (fail-closed). A partial [output] is
 * always deleted on failure.
 *
 * Frame mapping is by PTS, not ordinal: decoded PTS on the take's CFR
 * grid gives the sidecar frame index ([frameIndexForPts]), so any
 * decoder reorder is transparent. Video encodes first, audio copies
 * after (each track monotonic; the file stays valid, interleaving
 * merely unoptimized).
 */
class DirectLogStabilizer(private val appContext: Context) {
    data class Report(
        val framesDecoded: Int,
        val framesWarped: Int,
        val framesDegraded: Int,
        val audioSamplesCopied: Int,
        val elapsedMs: Long,
        val outputBytes: Long,
        /** Null on success; short reason otherwise (caller keeps OG). */
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    fun stabilize(
        source: File,
        sidecarFile: File,
        output: File,
        profile: DirectLogProfile,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Report {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            return stabilizeOrThrow(source, sidecarFile, output, profile, onProgress, t0)
        } catch (e: Exception) {
            Log.w(TAG, "stab pass failed: ${e.message}")
            try {
                output.delete()
            } catch (_: Exception) {
            }
            return Report(0, 0, 0, 0, android.os.SystemClock.elapsedRealtime() - t0, 0L, short(e))
        }
    }

    private fun stabilizeOrThrow(
        source: File,
        sidecarFile: File,
        output: File,
        profile: DirectLogProfile,
        onProgress: (done: Int, total: Int) -> Unit,
        t0: Long,
    ): Report {
        require(source.isFile && source.length() > 0) { "missing source MP4" }
        require(VfVulkan.available) { "Vulkan bridge unavailable" }
        val take = StabSidecar.parse(sidecarFile.readText()) ?: error("unreadable sidecar")
        val plan = StabTrajectory.plan(take) ?: error("unplannable trajectory")
        val w = take.header.encodeW
        val h = take.header.encodeH
        val fps = take.header.fps

        // Vulkan warp stage (device persists from the take; init is idempotent).
        val devSpv = appContext.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        VfVulkan.setPipelineCachePathNative(
            VulkanPipelineCache.pathFor(appContext.cacheDir, VulkanPipelineCache.HOST_VF))
        check(VfVulkan.initNative(devSpv) == VfVulkan.OK) { "vulkan init failed" }
        val warpSpv = appContext.assets.open("shaders/vf/vf_stabwarp.spv").use { it.readBytes() }
        val initRc = VfStab.initStabNative(warpSpv, w, h)
        check(initRc == VfVulkan.OK) { "stab init: ${VfStab.describe(initRc)}" }
        val staging = VfEglImport.createP010(w, h) ?: error("P010 staging failed")
        val stagingStrides = VfEglImport.describeP010(staging) ?: error("P010 describe failed")
        val srcYStrideB = stagingStrides[0]
        val srcUvStrideB = stagingStrides[1]

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var writer: ImageWriter? = null
        var writerSurface: Surface? = null
        var sdrEgl: EglPusher? = null
        var sdrRgb8: HardwareBufferRef? = null
        var sdrProgram = 0
        var sdrTexture = 0
        var sdrQuad: java.nio.FloatBuffer? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(source.absolutePath)
            val videoTrack = selectTrack(extractor, "video/") ?: error("no video track")
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: error("no video mime")
            check(srcW == w && srcH == h) { "size drift ${srcW}x$srcH vs ${w}x$h" }
            val audioTrack = selectTrack(extractor, "audio/")
            val audioFormat = audioTrack?.let { extractor.getTrackFormat(it) }

            val decoderName = LogVideoProbe.preferredHwDecoder(mime, w, h)
            decoder = if (decoderName != null) MediaCodec.createByCodecName(decoderName)
            else MediaCodec.createDecoderByType(mime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            // Warp re-encode mirrors the take's input modes: direct P010
            // images where advertised, else P010 through an ImageWriter
            // over the codec Surface (API 33+).
            val bitrate = LogVideoProbe.clampedBitrate(
                LogVideoProbe.BITRATE, LogVideoProbe.maxBitrateMbps
            )
            val target = profile.targetColors()
                .copy(width = w, height = h, fps = fps, bitrate = bitrate)
            val directName = LogVideoProbe.preferredP010EncoderAt(w, h, fps)
            val surfaceName =
                if (LogVideoProbe.writerPathAvailable()) LogVideoProbe.preferredHwEncoderAt(w, h, fps)
                else null
            val tenBitMode = LogVideoProbe.rungMode(directName, surfaceName)
            // 8-bit SDR fallback (BT709 sources only): Main-profile
            // Surface encoder + convert + EGL present, after the 10-bit
            // modes. The warp is single-threaded, so one convert buffer
            // and one EGL context serve the whole pass.
            val sdrMode = if (profile in listOf(DirectLogProfile.BT709, DirectLogProfile.ACES) &&
                LogVideoProbe.preferredMain8EncoderAt(w, h, fps) != null
            ) {
                LogVideoProbe.EncoderInputMode.SDR_EGL
            } else {
                null
            }
            val mode = tenBitMode ?: sdrMode ?: error(
                "no HW HEVC Main10 encoder for ${w}x${h}@${fps}fps" +
                    if (LogVideoProbe.supportsMain8At(w, h, fps))
                        "; 8-bit SDR encoder present but this warp needs 10-bit (${profile.name})"
                    else ""
            )
            val encoderName = when (mode) {
                LogVideoProbe.EncoderInputMode.DIRECT_P010 -> requireNotNull(directName)
                LogVideoProbe.EncoderInputMode.IMAGE_WRITER -> requireNotNull(surfaceName)
                LogVideoProbe.EncoderInputMode.SDR_EGL ->
                    requireNotNull(LogVideoProbe.preferredMain8EncoderAt(w, h, fps))
            }
            encoder = MediaCodec.createByCodecName(encoderName)
            if (mode == LogVideoProbe.EncoderInputMode.DIRECT_P010) {
                encoder.configure(
                    LogVideoProbe.toMediaFormat(
                        target, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                    ),
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            } else if (mode == LogVideoProbe.EncoderInputMode.IMAGE_WRITER) {
                encoder.configure(
                    LogVideoProbe.toMediaFormat(
                        target, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    ),
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                // Lint-visible API-33 guard: writerPathAvailable above
                // already excludes older releases, so this never fires.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    throw IllegalStateException("ImageWriter P010 needs API 33+")
                }
                val surface = encoder.createInputSurface()
                writer = ImageWriter.Builder(surface)
                    .setImageFormat(ImageFormat.YCBCR_P010)
                    .setMaxImages(LogVideoProbe.WRITER_MAX_IMAGES)
                    .setUsage(LogVideoProbe.WRITER_P010_USAGE)
                    .build()
                writerSurface = surface
            } else {
                encoder.configure(
                    LogVideoProbe.toMediaFormat(
                        target.copy(
                            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                        ),
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    ),
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                val surface = encoder.createInputSurface()
                try {
                    sdrEgl = EglPusher(surface)
                    if (sdrEgl?.makeCurrent() != true) error("egl makeCurrent failed")
                    sdrProgram = GlBlit.buildProgram()
                    sdrTexture = GlBlit.genTexture()
                    sdrQuad = GlBlit.quadBuffer()
                    sdrRgb8 = HardwareBufferRef.createRgba8(w, h)
                    // Grade chain first (idempotent): the twin/set
                    // allocator below draws from the grade layouts, same
                    // order as the record path.
                    val gradespv = appContext.assets.open("shaders/vf/vf_loggrade.spv")
                        .use { it.readBytes() }
                    check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) {
                        "grade init failed"
                    }
                    val yuvspv = appContext.assets.open("shaders/vf/vf_gradeyuv.spv")
                        .use { it.readBytes() }
                    check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) {
                        "grade-yuv init failed"
                    }
                    val rgb8spv = appContext.assets.open("shaders/vf/vf_p010torgb8.spv")
                        .use { it.readBytes() }
                    check(VfRgb8Convert.initRgb8Native(rgb8spv) == VfVulkan.OK) {
                        "rgb8 convert init failed"
                    }
                    writerSurface = surface
                } catch (e: Exception) {
                    try { sdrRgb8?.close() } catch (_: Exception) {}
                    sdrRgb8 = null
                    sdrProgram = 0; sdrTexture = 0; sdrQuad = null
                    try { sdrEgl?.release() } catch (_: Exception) {}
                    sdrEgl = null
                    try { surface.release() } catch (_: Exception) {}
                    try { encoder.release() } catch (_: Exception) {}
                    throw e
                }
            }
            encoder.start()
            Log.i(TAG, "stab encoder=$encoderName input=$mode")

            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val pass = VideoPass(
                extractor, decoder, encoder, muxer, staging, plan, w, h, fps,
                srcYStrideB, srcUvStrideB, videoTrack, audioFormat, onProgress,
                writer, sdrEgl, sdrRgb8?.buffer, sdrProgram, sdrTexture, sdrQuad
            )
            val warped = pass.run()
            val audioCopied = copyAudioTrack(source, audioTrack, audioFormat, muxer, pass)
            muxer.stop()
            val bytes = output.length()
            check(bytes > 0) { "empty output" }
            Log.i(
                TAG, "stab done decoded=${warped.decoded} warped=${warped.warped} " +
                    "degraded=${warped.degraded} audio=$audioCopied bytes=$bytes"
            )
            return Report(
                warped.decoded, warped.warped, warped.degraded, audioCopied,
                android.os.SystemClock.elapsedRealtime() - t0, bytes
            )
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            // 8-bit EGL teardown on this (single GL) thread: deletes under
            // the still-current context, then release the context+surface.
            try {
                if (sdrEgl != null) {
                    try {
                        if (sdrTexture != 0) android.opengl.GLES20.glDeleteTextures(
                            1, intArrayOf(sdrTexture), 0
                        )
                    } catch (_: Exception) {}
                    try {
                        if (sdrProgram != 0) android.opengl.GLES20.glDeleteProgram(sdrProgram)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            sdrProgram = 0; sdrTexture = 0; sdrQuad = null
            try { sdrRgb8?.close() } catch (_: Exception) {}
            sdrRgb8 = null
            try { sdrEgl?.release() } catch (_: Exception) {}
            sdrEgl = null
            try { writerSurface?.release() } catch (_: Exception) {}
            writerSurface = null
            try { muxer?.release() } catch (_: Exception) {}
            try { staging.close() } catch (_: Exception) {}
            try { VfStab.resetStabNative() } catch (_: Exception) {}
        }
    }

    private data class Warped(val decoded: Int, val warped: Int, val degraded: Int)

    /**
     * Single-thread video transcode: feed the decoder, warp each output
     * image on Vulkan, copy into the encoder, drain the encoder into the
     * muxer. The muxer starts once the encoder format arrives (audio
     * track added alongside when present).
     */
    private class VideoPass(
        val extractor: MediaExtractor,
        val decoder: MediaCodec,
        val encoder: MediaCodec,
        val muxer: MediaMuxer,
        val staging: android.hardware.HardwareBuffer,
        val plan: StabTrajectory.Plan,
        val w: Int,
        val h: Int,
        val fps: Int,
        val srcYStrideB: Int,
        val srcUvStrideB: Int,
        val videoTrack: Int,
        val audioFormat: MediaFormat?,
        val onProgress: (done: Int, total: Int) -> Unit,
        /** Non-null on the ImageWriter path (Surface-only encoders). */
        val writer: ImageWriter? = null,
        /** Non-null on the 8-bit SDR path (single convert buffer + EGL). */
        val sdrEgl: EglPusher? = null,
        val sdrRgb8: android.hardware.HardwareBuffer? = null,
        val sdrProgram: Int = 0,
        val sdrTexture: Int = 0,
        val sdrQuad: java.nio.FloatBuffer? = null,
    ) {
        var videoMuxTrack = -1
        var audioMuxTrack = -1
        var muxerStarted = false
        var decoded = 0
        var warped = 0
        var degraded = 0
        // Encoder input discovery (record-path order: capacity BEFORE the
        // first image — getInputBuffer after getInputImage orphans C2).
        var p010Size = 0
        var yStrideB = 0
        var uvStrideB = 0
        var vStrideB = 0
        var uvPxB = 0
        var vPxB = 0
        // Writer-path input layout (discovered from the first writer image).
        var wyStrideB = 0
        var wuvStrideB = 0
        var wvStrideB = 0
        var wuvPxB = 0
        var wvPxB = 0

        private var decEosIn = false
        private var decEosOut = false
        private var encEosIn = false
        private var encEosOut = false

        fun run(): Warped {
            extractor.selectTrack(videoTrack)
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var stallIters = 0
            while (!encEosOut) {
                var progress = false
                if (!decEosIn) progress = feedDecoder() || progress
                if (!decEosOut) progress = drainDecoder(decInfo) || progress
                else if (!encEosIn) {
                    if (queueEncoderEos()) {
                        encEosIn = true
                        progress = true
                    }
                }
                progress = drainEncoder(encInfo) { encEosOut = true } || progress
                if (progress) stallIters = 0
                else if (++stallIters > MAX_STALL_ITERS) error("codec stall")
            }
            return Warped(decoded, warped, degraded)
        }

        private fun feedDecoder(): Boolean {
            val inIdx = try {
                decoder.dequeueInputBuffer(1_000)
            } catch (_: Exception) {
                return false
            }
            if (inIdx < 0) return false
            val buf = try {
                decoder.getInputBuffer(inIdx)
            } catch (_: Exception) {
                null
            }
            if (buf == null) {
                try {
                    decoder.queueInputBuffer(inIdx, 0, 0, 0, 0)
                } catch (_: Exception) {
                }
                return true
            }
            val sample = extractor.readSampleData(buf, 0)
            if (sample < 0) {
                try {
                    decoder.queueInputBuffer(
                        inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } catch (_: Exception) {
                }
                decEosIn = true
                return true
            }
            val pts = extractor.sampleTime
            val flags = extractor.sampleFlags
            extractor.advance()
            try {
                decoder.queueInputBuffer(inIdx, 0, sample, pts, flags)
            } catch (_: Exception) {
                return false
            }
            return true
        }

        private fun drainDecoder(info: MediaCodec.BufferInfo): Boolean {
            val outIdx = try {
                decoder.dequeueOutputBuffer(info, 1_000)
            } catch (_: Exception) {
                return false
            }
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return true
                outIdx < 0 -> return false
            }
            try {
                // EOS may ride the last frame: warp first, then latch.
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    decEosOut = true
                    if (info.size <= 0) return true
                } else if (info.size <= 0) {
                    return true
                }
                decoded++
                val image = try {
                    decoder.getOutputImage(outIdx)
                } catch (_: Exception) {
                    null
                }
                if (image == null) {
                    degraded++
                    return true
                }
                try {
                    warpFrame(image, info.presentationTimeUs)
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
                return true
            } finally {
                try { decoder.releaseOutputBuffer(outIdx, false) } catch (_: Exception) {}
            }
        }

        private fun warpFrame(image: android.media.Image, ptsUs: Long) {
            val idx = frameIndexForPts(ptsUs, fps, plan.frameCount)
            val planes = image.planes
            if (planes.size < 3) {
                degraded++
                return
            }
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val tenBit = isTenBitOutput(yPlane.pixelStride)
            val upRc = VfStab.stabUploadNative(
                yPlane.buffer, uPlane.buffer, vPlane.buffer,
                yPlane.rowStride, uPlane.rowStride, vPlane.rowStride,
                uPlane.pixelStride, vPlane.pixelStride, w, h, tenBit
            )
            if (upRc != VfVulkan.OK) {
                Log.w(TAG, "stab upload failed (${VfStab.describe(upRc)}); frame degraded")
                degraded++
                return
            }
            val subRc = VfStab.stabSubmitNative(
                staging, intArrayOf(w, h, srcYStrideB, srcUvStrideB),
                plan.matrixFor(idx)
            )
            if (subRc != VfVulkan.OK) {
                Log.w(TAG, "stab submit failed (${VfStab.describe(subRc)}); frame degraded")
                degraded++
                return
            }
            if (!queueWarpedToEncoder(ptsUs)) {
                degraded++
                return
            }
            warped++
            try {
                onProgress(warped, plan.frameCount)
            } catch (_: Exception) {
            }
        }

        private fun queueWarpedToEncoder(ptsUs: Long): Boolean {
            val se = sdrEgl
            if (se != null) {
                val dst = sdrRgb8 ?: return false
                val cfd = VfRgb8Convert.rgb8SubmitNative(
                    staging, dst,
                    intArrayOf(w, h), intArrayOf(w, h),
                    intArrayOf(srcYStrideB, srcUvStrideB, w, h),
                    (warped % 3 + 3) % 3
                )
                if (cfd < 0) return false
                try {
                    if (!waitSyncFd(cfd)) return false
                    val image = VfEglImport.createEGLImage(dst)
                    if (image == 0L) return false
                    try {
                        if (VfEglImport.bindEGLImageToTexture2D(image, sdrTexture) !=
                            android.opengl.GLES20.GL_NO_ERROR
                        ) {
                            return false
                        }
                        android.opengl.GLES20.glViewport(0, 0, w, h)
                        android.opengl.GLES20.glUseProgram(sdrProgram)
                        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                        android.opengl.GLES20.glBindTexture(
                            android.opengl.GLES20.GL_TEXTURE_2D, sdrTexture
                        )
                        android.opengl.GLES20.glUniform1i(
                            android.opengl.GLES20.glGetUniformLocation(sdrProgram, "uTex"), 0
                        )
                        sdrQuad?.let { GlBlit.drawQuad(sdrProgram, it) }
                        android.opengl.GLES20.glFinish()
                    } finally {
                        VfEglImport.destroyEGLImage(image)
                    }
                    se.setPresentationTime(ptsUs * 1_000L)
                    se.swap()
                    return true
                } catch (_: Exception) {
                    return false
                } finally {
                    try { VfEglImport.closeSyncFd(cfd) } catch (_: Exception) {}
                }
            }
            val wr = writer
            if (wr != null) {
                val img = try {
                    wr.dequeueInputImage()
                } catch (_: Exception) {
                    return false
                }
                try {
                    if (wyStrideB == 0) {
                        val planes = img.planes
                        if (planes.size < 3) {
                            try { img.close() } catch (_: Exception) {}
                            return false
                        }
                        wyStrideB = planes[0].rowStride
                        wuvStrideB = planes[1].rowStride
                        wvStrideB = planes[2].rowStride
                        wuvPxB = planes[1].pixelStride
                        wvPxB = planes[2].pixelStride
                        if (wyStrideB <= 0 || wuvStrideB <= 0 || wvStrideB <= 0) {
                            wyStrideB = 0
                            try { img.close() } catch (_: Exception) {}
                            return false
                        }
                    }
                    val rc = VfEglImport.copyP010ToCodec(
                        staging,
                        img.planes[0].buffer, wyStrideB,
                        img.planes[1].buffer, wuvStrideB, wuvPxB,
                        img.planes[2].buffer, wvStrideB, wvPxB,
                        w, h, srcYStrideB, srcUvStrideB
                    )
                    if (rc != 0) {
                        try { img.close() } catch (_: Exception) {}
                        return false
                    }
                    img.timestamp = ptsUs * 1_000L
                    try {
                        wr.queueInputImage(img)
                    } catch (_: Exception) {
                        try { img.close() } catch (_: Exception) {}
                        return false
                    }
                    return true
                } catch (_: Exception) {
                    try { img.close() } catch (_: Exception) {}
                    return false
                }
            }
            val inIdx = try {
                encoder.dequeueInputBuffer(50_000)
            } catch (_: Exception) {
                -1
            }
            if (inIdx < 0) return false
            if (p010Size == 0) {
                val cap = try { encoder.getInputBuffer(inIdx)?.capacity() ?: 0 } catch (_: Exception) { 0 }
                if (cap <= 0) return false
                p010Size = cap
            }
            val inImage = try {
                encoder.getInputImage(inIdx)
            } catch (_: Exception) {
                null
            } ?: return false
            try {
                if (yStrideB == 0) {
                    val planes = inImage.planes
                    if (planes.size < 3) return false
                    yStrideB = planes[0].rowStride
                    uvStrideB = planes[1].rowStride
                    vStrideB = planes[2].rowStride
                    uvPxB = planes[1].pixelStride
                    vPxB = planes[2].pixelStride
                    if (yStrideB <= 0 || uvStrideB <= 0 || vStrideB <= 0) {
                        yStrideB = 0
                        return false
                    }
                }
                val rc = VfEglImport.copyP010ToCodec(
                    staging,
                    inImage.planes[0].buffer, yStrideB,
                    inImage.planes[1].buffer, uvStrideB, uvPxB,
                    inImage.planes[2].buffer, vStrideB, vPxB,
                    w, h, srcYStrideB, srcUvStrideB
                )
                if (rc != 0) return false
                try {
                    encoder.queueInputBuffer(inIdx, 0, p010Size, ptsUs, 0)
                } catch (_: Exception) {
                    return false
                }
                return true
            } finally {
                try { inImage.close() } catch (_: Exception) {}
            }
        }

        private fun waitSyncFd(fd: Int): Boolean {
            if (VfEglImport.pollSyncFd(fd)) return true
            val deadline = System.currentTimeMillis() + 100
            while (System.currentTimeMillis() < deadline) {
                if (VfEglImport.pollSyncFd(fd)) return true
                Thread.sleep(2)
            }
            return false
        }

        private fun queueEncoderEos(): Boolean {
            if (writer != null || sdrEgl != null) {
                return try {
                    encoder.signalEndOfInputStream()
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val inIdx = try {
                encoder.dequeueInputBuffer(50_000)
            } catch (_: Exception) {
                -1
            }
            if (inIdx < 0) return false
            return try {
                encoder.queueInputBuffer(
                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun drainEncoder(info: MediaCodec.BufferInfo, onEos: () -> Unit): Boolean {
            val outIdx = try {
                encoder.dequeueOutputBuffer(info, 1_000)
            } catch (_: Exception) {
                return false
            }
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    try {
                        videoMuxTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) {
                            audioMuxTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    } catch (e: Exception) {
                        error("muxer start failed: ${short(e)}")
                    }
                    return true
                }
                outIdx < 0 -> return false
            }
            try {
                // Codec-config carries the csd (already in the track
                // format): never mux it as a sample.
                if (info.size > 0 && muxerStarted && videoMuxTrack >= 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    val buf = try {
                        encoder.getOutputBuffer(outIdx)
                    } catch (_: Exception) {
                        null
                    }
                    if (buf != null) {
                        muxer.writeSampleData(videoMuxTrack, buf, info)
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    onEos()
                }
                return true
            } finally {
                try { encoder.releaseOutputBuffer(outIdx, false) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Second extractor pass: copies the audio track samples verbatim
     * (same PTS) into the started muxer. Video already wrote; each track
     * stays monotonic, so the file is valid.
     */
    private fun copyAudioTrack(
        source: File,
        audioTrack: Int?,
        audioFormat: MediaFormat?,
        muxer: MediaMuxer,
        pass: VideoPass,
    ): Int {
        if (audioTrack == null || audioFormat == null) return 0
        if (!pass.muxerStarted || pass.audioMuxTrack < 0) return 0
        var copied = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            extractor.selectTrack(audioTrack)
            val buf = ByteBuffer.allocateDirect(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val sample = extractor.readSampleData(buf, 0)
                if (sample < 0) break
                info.set(0, sample, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(pass.audioMuxTrack, buf, info)
                copied++
                extractor.advance()
            }
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return copied
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = try {
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            } catch (_: Exception) {
                null
            }
            if (mime != null && mime.startsWith(prefix)) return i
        }
        return null
    }

    companion object {
        private const val TAG = "DirectLogStab"
        /**
         * Stall bound: loop iterations with zero codec progress before the
         * pass aborts (each iteration waits ≤3ms, so ~90s of wedge).
         */
        private const val MAX_STALL_ITERS = 30_000

        /**
         * Sidecar frame index for a decoded [ptsUs] on the take's CFR grid
         * (frame i sits at i * 1e6 / [fps]), clamped to the table. Pure —
         * unit-tested.
         */
        fun frameIndexForPts(ptsUs: Long, fps: Int, frameCount: Int): Int {
            require(fps > 0) { "fps must be positive" }
            require(frameCount > 0) { "frameCount must be positive" }
            if (ptsUs <= 0) return 0
            val idx = ((ptsUs * fps + 500_000L) / 1_000_000L).toInt()
            return idx.coerceIn(0, frameCount - 1)
        }

        /**
         * Decoder output depth from the luma pixel stride (flexible YUV:
         * 2-byte samples are P010, 1-byte are 8-bit). Pure — unit-tested.
         */
        fun isTenBitOutput(yPixelStride: Int): Boolean = yPixelStride == 2

        private fun short(e: Exception): String =
            "${e.javaClass.simpleName}: ${e.message}".take(160)
    }
}
