// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Probe for Direct-Log GPU path, step 1: HEVC 10-bit 4K30 75 Mbps BT.709.
 *
 * No camera, no Vulkan yet — answers only: does this device's hardware
 * encoder accept `video/hevc + Main10 + 3840x2160@30 + COLOR_FormatSurface`
 * with SDR BT.709 signalling, and can we push frames through its input
 * Surface (the exact surface the GPU stage will render into later)?
 *
 * Pure part ([Target]) is unit-tested on JVM. Device part ([probe])
 * must run on-device (MediaCodecList + EGL + encoder).
 */
object LogVideoProbe {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val WIDTH = 3840
    const val HEIGHT = 2160
    const val FPS = 30
    const val BITRATE = 75_000_000
    const val I_FRAME_INTERVAL_S = 1

    data class Target(
        val mime: String = MIME,
        val width: Int = WIDTH,
        val height: Int = HEIGHT,
        val fps: Int = FPS,
        val bitrate: Int = BITRATE,
        val profile: Int = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        // MediaFormat SDR BT.709 signalling (API 29+):
        // STANDARD_BT709 / TRANSFER_SDR_VIDEO / RANGE_LIMITED.
        val colorStandard: Int = MediaFormat.COLOR_STANDARD_BT709,
        val colorTransfer: Int = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        val colorRange: Int = MediaFormat.COLOR_RANGE_LIMITED,
    )

    data class EncoderCandidate(
        val name: String,
        val isHardware: Boolean,
        val supportsMain10: Boolean,
        val supportsSizeRate: Boolean,
        val supportsSurface: Boolean,
    )

    data class ProbeReport(
        val target: Target = Target(),
        val candidates: List<EncoderCandidate> = emptyList(),
        val selected: String? = null,
        val configured: Boolean = false,
        val framesSubmitted: Int = 0,
        val outputBytes: Long = 0L,
        val outputBuffers: Int = 0,
        val error: String? = null,
    )

    data class ForceAttempt(
        val codecName: String,
        val profile: Int,
        val profileLabel: String,
        val advertisedProfiles: List<Int> = emptyList(),
        val configureOk: Boolean = false,
        val framesSubmitted: Int = 0,
        val outputBytes: Long = 0L,
        val outputBuffers: Int = 0,
        /** Encoder-reported output profile (null until INFO_OUTPUT_FORMAT_CHANGED). */
        val outputProfile: Int? = null,
        /** Compact output-format dump: profile/width/height/cs/xfer/range. */
        val outputFormat: String? = null,
        /** Short outcome: configure exception or drain note. Null on full success. */
        val error: String? = null,
    )

    data class SustainedReport(
        val codecName: String? = null,
        val profile: Int = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        val secondsRequested: Int = 10,
        val framesSubmitted: Int = 0,
        val outputBuffers: Int = 0,
        val outputBytes: Long = 0L,
        val outputProfile: Int? = null,
        val outputFormat: String? = null,
        val elapsedMs: Long = 0L,
        /** Submit-side fps including EGL swap backpressure. */
        val submitFps: Float = 0f,
        val error: String? = null,
    )

    /** Pure PTS helper — frame i at [fps] starts at i * 1e9 / fps ns. Unit-tested. */
    fun framePtsNs(frameIndex: Int, fps: Int = FPS): Long =
        frameIndex * (1_000_000_000L / fps)

    data class ForceReport(
        val candidates: List<EncoderCandidate> = emptyList(),
        val attempts: List<ForceAttempt> = emptyList(),
        val error: String? = null,
    )

    /** Pure label helper — unit-tested. */
    fun profileLabel(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "MainStill"
        else -> "0x${profile.toString(16)}"
    }

    fun target(): Target = Target()

    /**
     * JVM-testable view of the exact keys [toMediaFormat] sets.
     * MediaFormat itself is unmockable on JVM (createVideoFormat returns
     * null under isReturnDefaultValues), so unit tests assert this map.
     */
    fun formatKeys(t: Target = Target()): Map<String, Int> = buildMap {
        put(MediaFormat.KEY_WIDTH, t.width)
        put(MediaFormat.KEY_HEIGHT, t.height)
        put(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        put(MediaFormat.KEY_BIT_RATE, t.bitrate)
        put(MediaFormat.KEY_FRAME_RATE, t.fps)
        put(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        put(MediaFormat.KEY_PROFILE, t.profile)
        put(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        put(MediaFormat.KEY_COLOR_STANDARD, t.colorStandard)
        put(MediaFormat.KEY_COLOR_TRANSFER, t.colorTransfer)
        put(MediaFormat.KEY_COLOR_RANGE, t.colorRange)
    }

    /** Thin wrapper — device only. All keys set here are asserted in unit test via [formatKeys]. */
    fun toMediaFormat(
        t: Target = Target(),
        colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
    ): MediaFormat =
        MediaFormat.createVideoFormat(t.mime, t.width, t.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, t.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, t.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PROFILE, t.profile)
            // CBR: stable encode ms + steady file growth (VBR's quality
            // hunting shows up as jumpy per-frame cost). GOP is 1s = 30
            // frames at the record rate.
            if (Build.VERSION.SDK_INT >= 21) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, t.colorStandard)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, t.colorTransfer)
                setInteger(MediaFormat.KEY_COLOR_RANGE, t.colorRange)
            }
        }

    /**
     * Full device probe: enumerate HEVC encoders, pick hardware Main10 +
     * 3840x2160@30 + Surface, configure, render [testFrames] solid frames
     * via EGL into the encoder input Surface, drain output.
     *
     * Never throws — all failures are captured in [ProbeReport.error].
     * Caller owns no resources on return (codec + EGL + Surface released).
     */
    fun probe(testFrames: Int = 30): ProbeReport {
        val t = Target()
        return try {
            val infos = queryHevcEncoders(t)
            val sel = infos.firstOrNull { it.isHardware && it.supportsMain10 && it.supportsSizeRate && it.supportsSurface }
                ?: infos.firstOrNull { it.supportsMain10 && it.supportsSizeRate && it.supportsSurface }
            if (sel == null) {
                return ProbeReport(
                    candidates = infos,
                    error = "no HEVC encoder with Main10+3840x2160@30+Surface"
                )
            }
            val format = toMediaFormat(t)
            val codec = MediaCodec.createByCodecName(sel.name)
            var surface: Surface? = null
            var egl: EglPusher? = null
            var frames = 0
            var bytes = 0L
            var outBufs = 0
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                egl = EglPusher(surface)
                codec.start()
                // Push solid frames with monotonically increasing PTS (33.33ms).
                var ptsNs = 0L
                val frameIntervalNs = 1_000_000_000L / t.fps
                repeat(testFrames) { i ->
                    // Vary clear color slightly so encoders can't collapse to zero-byte stream.
                    val f = i / testFrames.toFloat()
                    egl.render(f * 0.08f, f * 0.10f, f * 0.12f)
                    egl.setPresentationTime(ptsNs)
                    egl.swap()
                    ptsNs += frameIntervalNs
                    frames++
                }
                codec.signalEndOfInputStream()
                val info = MediaCodec.BufferInfo()
                val deadlineMs = System.currentTimeMillis() + 10_000
                var eos = false
                while (!eos && System.currentTimeMillis() < deadlineMs) {
                    val idx = codec.dequeueOutputBuffer(info, 2000)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (frames < testFrames + 60 && outBufs == 0) continue else break
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        idx >= 0 -> {
                            if (info.size > 0) {
                                bytes += info.size
                                outBufs++
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                            codec.releaseOutputBuffer(idx, false)
                        }
                    }
                }
                ProbeReport(
                    candidates = infos,
                    selected = sel.name,
                    configured = true,
                    framesSubmitted = frames,
                    outputBytes = bytes,
                    outputBuffers = outBufs,
                    error = if (outBufs == 0) "configured but no output buffers" else null
                )
            } finally {
                try { egl?.release() } catch (_: Exception) {}
                try { surface?.release() } catch (_: Exception) {}
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "log probe failed: ${e.message}")
            ProbeReport(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Force mode: ignore the advertised profile list and attempt Main10
     * configure directly by codec name (what MotionCam-style native code
     * does with `AMediaCodec_createCodecByName`). Some MTK firmware
     * accepts Main10 encode despite `dumpsys` listing Main only; most
     * reject with CodecException at configure.
     *
     * Tries, per hardware HEVC encoder with 4K30+Surface size support:
     * Main10, then Main10HDR10. Never throws — each attempt captures
     * its own outcome. Codecs are fully released between attempts.
     */
    fun probeForceMain10(testFrames: Int = 15): ForceReport {
        val base = Target()
        val profiles = listOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        )
        return try {
            val infos = queryHevcEncoders(base)
            val targets = infos
                .filter { it.isHardware && it.supportsSizeRate && it.supportsSurface }
                .take(3)
            if (targets.isEmpty()) {
                return ForceReport(
                    candidates = infos,
                    error = "no HW HEVC encoder with 3840x2160@30+Surface at all"
                )
            }
            val attempts = mutableListOf<ForceAttempt>()
            for (enc in targets) {
                val advertised = advertisedProfiles(enc.name, base.mime)
                for (p in profiles) {
                    attempts.add(
                        attemptEncode(enc.name, base.copy(profile = p), advertised, testFrames)
                    )
                    // Stop at first fully working 10-bit path to save time.
                    if (attempts.last().configureOk && attempts.last().outputBytes > 0) break
                }
                if (attempts.any { it.configureOk && it.outputBytes > 0 }) break
            }
            ForceReport(candidates = infos, attempts = attempts)
        } catch (e: Exception) {
            Log.w(TAG, "force probe failed: ${e.message}")
            ForceReport(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun advertisedProfiles(codecName: String, mime: String): List<Int> {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val info = list.codecInfos.firstOrNull { it.name == codecName } ?: return emptyList()
            info.getCapabilitiesForType(mime)?.profileLevels?.map { it.profile } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun attemptEncode(
        codecName: String,
        target: Target,
        advertised: List<Int>,
        testFrames: Int,
    ): ForceAttempt {
        val label = profileLabel(target.profile)
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var egl: EglPusher? = null
        return try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(toMediaFormat(target), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            egl = EglPusher(surface)
            codec.start()
            var ptsNs = 0L
            val stepNs = 1_000_000_000L / target.fps
            var frames = 0
            repeat(testFrames) { i ->
                val f = i / testFrames.toFloat()
                egl.render(0.02f + f * 0.08f, 0.03f + f * 0.10f, 0.04f + f * 0.12f)
                egl.setPresentationTime(ptsNs)
                egl.swap()
                ptsNs += stepNs
                frames++
            }
            codec.signalEndOfInputStream()
            val info = MediaCodec.BufferInfo()
            val deadlineMs = System.currentTimeMillis() + 10_000
            var bytes = 0L
            var outBufs = 0
            var eos = false
            var outProfile: Int? = null
            var outFormatDump: String? = null
            while (!eos && System.currentTimeMillis() < deadlineMs) {
                val idx = codec.dequeueOutputBuffer(info, 2000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (outBufs > 0) break else continue
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            val fmt = codec.outputFormat
                            outProfile = runCatching {
                                fmt.getInteger(MediaFormat.KEY_PROFILE)
                            }.getOrNull()
                            outFormatDump = dumpFormat(fmt)
                        } catch (_: Exception) {}
                    }
                    idx >= 0 -> {
                        if (info.size > 0) {
                            bytes += info.size
                            outBufs++
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        codec.releaseOutputBuffer(idx, false)
                    }
                }
            }
            ForceAttempt(
                codecName = codecName,
                profile = target.profile,
                profileLabel = label,
                advertisedProfiles = advertised,
                configureOk = true,
                framesSubmitted = frames,
                outputBytes = bytes,
                outputBuffers = outBufs,
                outputProfile = outProfile,
                outputFormat = outFormatDump,
                error = when {
                    outBufs == 0 -> "configured+started but no output"
                    outProfile != null && outProfile != target.profile ->
                        "output profile ${profileLabel(outProfile)} != requested $label"
                    else -> null
                }
            )
        } catch (e: Exception) {
            ForceAttempt(
                codecName = codecName,
                profile = target.profile,
                profileLabel = label,
                advertisedProfiles = advertised,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            try { egl?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Sustained soak: paced 30fps submit for [seconds] on the first HW
     * encoder that force-configures Main10 (or [codecName] when given),
     * draining concurrently. Measures submit-side fps including EGL swap
     * backpressure — the number that decides 4K30 viability.
     */
    fun probeSustained(seconds: Int = 10, codecName: String? = null): SustainedReport {
        val target = Target()
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var egl: EglPusher? = null
        return try {
            val infos = queryHevcEncoders(target)
            val name = codecName
                ?: infos.firstOrNull { it.isHardware && it.supportsSizeRate && it.supportsSurface }?.name
                ?: return SustainedReport(error = "no HW HEVC 4K30 Surface encoder")
            codec = MediaCodec.createByCodecName(name)
            codec.configure(toMediaFormat(target), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            egl = EglPusher(surface)
            codec.start()
            val totalFrames = seconds * target.fps
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            val bufs = java.util.concurrent.atomic.AtomicInteger(0)
            val outProfile = java.util.concurrent.atomic.AtomicReference<Int?>(null)
            val outDump = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val drainError = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val draining = java.util.concurrent.atomic.AtomicBoolean(true)
            val drainInfo = MediaCodec.BufferInfo()
            val nonLocalCodec = codec
            val drainThread = Thread {
                try {
                    while (draining.get()) {
                        val idx = nonLocalCodec.dequeueOutputBuffer(drainInfo, 2000)
                        when {
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> try {
                                val fmt = nonLocalCodec.outputFormat
                                outProfile.set(runCatching { fmt.getInteger(MediaFormat.KEY_PROFILE) }.getOrNull())
                                outDump.set(dumpFormat(fmt))
                            } catch (_: Exception) {}
                            idx >= 0 -> {
                                if (drainInfo.size > 0) {
                                    bytes.addAndGet(drainInfo.size.toLong())
                                    bufs.incrementAndGet()
                                }
                                val eos = drainInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                nonLocalCodec.releaseOutputBuffer(idx, false)
                                if (eos) break
                            }
                        }
                    }
                } catch (e: Exception) {
                    drainError.set("${e.javaClass.simpleName}: ${e.message}")
                }
            }.apply { start() }
            val t0 = android.os.SystemClock.elapsedRealtime()
            var submitted = 0
            val stepNs = 1_000_000_000L / target.fps
            val startNs = System.nanoTime()
            repeat(totalFrames) { i ->
                val f = (i % target.fps) / target.fps.toFloat()
                egl.render(0.02f + f * 0.08f, 0.03f + f * 0.10f, 0.04f + f * 0.12f)
                egl.setPresentationTime(framePtsNs(i))
                egl.swap()
                submitted++
                // Pace to wall-clock 30fps: sleep until next frame deadline.
                val deadlineNs = startNs + (i + 1L) * stepNs
                val nowNs = System.nanoTime()
                if (deadlineNs > nowNs) {
                    val sleepMs = (deadlineNs - nowNs) / 1_000_000L
                    if (sleepMs > 0) try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
                }
                if (i % target.fps == 0) {
                    Log.i(TAG, "SPIKE sustain t=${i / target.fps}s submitted=$submitted outBufs=${bufs.get()}")
                }
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            codec.signalEndOfInputStream()
            // Drain tail up to 8s for EOS.
            val tailEnd = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < tailEnd) {
                if (bufs.get() >= submitted && submitted > 0) break
                Thread.sleep(100)
                // EOS arrives via drain thread; exit when it stops making progress
                // and the queue looks drained is handled by timeout.
                if (!drainThread.isAlive) break
            }
            draining.set(false)
            try { drainThread.join(5_000) } catch (_: InterruptedException) {}
            SustainedReport(
                codecName = name,
                secondsRequested = seconds,
                framesSubmitted = submitted,
                outputBuffers = bufs.get(),
                outputBytes = bytes.get(),
                outputProfile = outProfile.get(),
                outputFormat = outDump.get(),
                elapsedMs = elapsed,
                submitFps = if (elapsed > 0) submitted * 1000f / elapsed else 0f,
                error = drainError.get() ?: when {
                    outProfile.get() != null && outProfile.get() != target.profile ->
                        "output profile ${profileLabel(outProfile.get()!!)} != Main10"
                    bufs.get() == 0 -> "no output in $seconds s"
                    else -> null
                }
            )
        } catch (e: Exception) {
            SustainedReport(error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { egl?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    /** First HW HEVC encoder with 4K30+Surface size support, or null. Device-only. */
    fun preferredHwEncoder(): String? = try {
        queryHevcEncoders(Target())
            .firstOrNull { it.isHardware && it.supportsSizeRate && it.supportsSurface }?.name
    } catch (_: Exception) {
        null
    }

    data class DecoderCandidate(
        val name: String,
        val isHardware: Boolean,
        val supportsSizeRate: Boolean,
    )

    /**
     * First HW decoder for [mime] at [width]x[height], or null. Device-only.
     * ByteBuffer output needs no color-format check (universal); callers
     * fall back to `MediaCodec.createDecoderByType` on null.
     */
    fun preferredHwDecoder(mime: String, width: Int, height: Int): String? = try {
        queryDecoders(mime, width, height)
            .firstOrNull { it.isHardware && it.supportsSizeRate }?.name
    } catch (_: Exception) {
        null
    }

    private fun queryDecoders(mime: String, width: Int, height: Int): List<DecoderCandidate> {
        val out = mutableListOf<DecoderCandidate>()
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val types = try { info.supportedTypes } catch (_: Exception) { continue }
            if (!types.contains(mime)) continue
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (_: Exception) { continue }
            val sizeRate = try {
                caps.videoCapabilities?.isSizeSupported(width, height) == true
            } catch (_: Exception) { false }
            val hw = if (Build.VERSION.SDK_INT >= 29) {
                try { info.isHardwareAccelerated } catch (_: Exception) { true }
            } else true
            out.add(DecoderCandidate(info.name, hw, sizeRate))
        }
        return out.sortedWith(compareBy({ !it.isHardware }, { it.name }))
    }

    private fun dumpFormat(fmt: MediaFormat): String {
        fun gi(k: String) = runCatching { fmt.getInteger(k).toString() }.getOrNull() ?: "?"
        return "profile=${runCatching { profileLabel(fmt.getInteger(MediaFormat.KEY_PROFILE)) }.getOrNull() ?: "?"} " +
            "${gi(MediaFormat.KEY_WIDTH)}x${gi(MediaFormat.KEY_HEIGHT)} " +
            "cs=${gi(MediaFormat.KEY_COLOR_STANDARD)} xfer=${gi(MediaFormat.KEY_COLOR_TRANSFER)} " +
            "range=${gi(MediaFormat.KEY_COLOR_RANGE)}"
    }

    private fun queryHevcEncoders(t: Target): List<EncoderCandidate> {
        val out = mutableListOf<EncoderCandidate>()
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val types = try { info.supportedTypes } catch (_: Exception) { continue }
            if (!types.contains(t.mime)) continue
            val caps = try {
                info.getCapabilitiesForType(t.mime)
            } catch (_: Exception) { continue }
            val profileLevels = caps.profileLevels ?: emptyArray()
            val main10 = profileLevels.any { it.profile == t.profile }
            val sizeRate = try {
                caps.videoCapabilities?.isSizeSupported(t.width, t.height) == true &&
                    caps.videoCapabilities?.areSizeAndRateSupported(t.width, t.height, t.fps.toDouble()) == true
            } catch (_: Exception) { false }
            val surface = caps.colorFormats?.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            ) == true
            val hw = if (Build.VERSION.SDK_INT >= 29) {
                try { info.isHardwareAccelerated } catch (_: Exception) { true }
            } else true
            out.add(EncoderCandidate(info.name, hw, main10, sizeRate, surface))
        }
        return out.sortedWith(compareBy({ !it.isHardware }, { it.name }))
    }

    private const val TAG = "LogVideoProbe"
}
