// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.SystemClock
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Developed SDR base plus, when [settings.ultraHdr] resolved on, the RAWR-style
 * wide-range gain-map bitmap (quarter resolution, RGB codes normalized by the
 * resolved gainMapMin/MaxLog2). The file writer dual-encodes both and assembles
 * the JPEG_R container natively; no platform Gainmap object is involved.
 */
data class DevelopedJpeg(val bitmap: Bitmap, val settings: JpegOutputSettings, val gainmap: Bitmap? = null)

/** Converts an unclamped scene-linear ACEScg texture into a color-tagged JPEG Bitmap. */
class Gles31JpegOutputProcessor(context: Context) {
    private val cleanup = RapidRawDevelop(context.applicationContext)
    private val shaderSource = context.applicationContext.assets.open(SHADER).bufferedReader().use {
        it.readText().replace("// PHOTO_CUBE", PhotoLutGl.glsl).replace("// SIGMOID_FUNCTIONS", CameraToneBalance.glsl + "\n" + SigmoidDisplayTransform.glsl + "\n" + Aces2DisplayTransform.glsl)
    }
    // This processor is invoked inside Gles31AmazeProcessor.process(), on its persistent writer
    // EGL context. Compile once per context instead of once per saved JPEG.
    private val aces2 = Aces2Gl()
    private val photoLut = PhotoLutGl()
    private var program = 0
    private val cleanupPrograms = IntArray(4)
    private var programThread: Thread? = null
    private var framebuffer = 0

    /** Completes readback only; AgX, gamut mapping, encoding and gain-map generation were fused. */
    fun processEncoded(
        encoded: AmazeGpuOutput,
        settings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        require(encoded.internalFormat == AmazeTextureFormat.RGBA8)
        val startedAt = SystemClock.elapsedRealtime()
        val resolved = settings.resolvedForPlatform()
        val colorSpace = ColorSpace.get(
            if (resolved.displayP3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB
        )
        val base = readBitmap(encoded.textureId, encoded.width, encoded.height, colorSpace)
        val baseReadyAt = SystemClock.elapsedRealtime()
        val gainmap = if (resolved.ultraHdr) {
            check(encoded.gainmapTextureId != 0) { "Fused Ultra HDR output has no gain-map texture" }
            readBitmap(
                encoded.gainmapTextureId,
                ceilDiv(encoded.width, GAINMAP_DOWNSCALE),
                ceilDiv(encoded.height, GAINMAP_DOWNSCALE),
                colorSpace
            ).bitmap
        } else null
        val completedAt = SystemClock.elapsedRealtime()
        Log.i(
            LOG_TAG,
            "JPEG fused output ${encoded.width}x${encoded.height}: " +
                "baseRead=${base.readPixelsMs}ms baseBitmap=${base.bitmapCopyMs}ms " +
                "gain+attach=${completedAt - baseReadyAt}ms total=${completedAt - startedAt}ms"
        )
        MemoryLeakDiagnostics.sample("jpeg-frame-complete")
        return DevelopedJpeg(base.bitmap, resolved, gainmap)
    }

    fun process(scene: AmazeGpuOutput, settings: JpegOutputSettings = JpegOutputSettings()): DevelopedJpeg =
        if (settings.chromaCleanup.rapidRawActive) {
            cleanup.withPrepared(scene, settings.chromaCleanup.rapidRawAmounts) { prepared -> processCleaned(scene, settings, prepared) }
        } else processCleaned(scene, settings)

    private fun processCleaned(scene: AmazeGpuOutput, settings: JpegOutputSettings,
                               prepared: RapidRawDevelop.Prepared? = null): DevelopedJpeg {
        val startedAt = SystemClock.elapsedRealtime()
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F)
        require(scene.colorDomain == AmazeColorDomain.ACESCG) {
            "JPEG develop needs scene-linear ACEScg, not camera-linear (wavelet tail missing?)"
        }
        val resolved = settings.resolvedForPlatform()
        val encodedTexture = IntArray(1)
        val gainTexture = IntArray(1)
        val encodedBytes = scene.width.toLong() * scene.height * 4L
        var encodedTracked = false
        var gainTracked = false
        var gainBytes = 0L
        GLES31.glGenTextures(1, encodedTexture, 0)
        check(encodedTexture[0] != 0) { "Could not allocate encoded output texture" }
        try {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, encodedTexture[0])
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, scene.width, scene.height)
            MemoryLeakDiagnostics.glTextureAllocated(encodedBytes)
            encodedTracked = true
            if (resolved.ultraHdr) {
                val gainWidth = ceilDiv(scene.width, GAINMAP_DOWNSCALE)
                val gainHeight = ceilDiv(scene.height, GAINMAP_DOWNSCALE)
                GLES31.glGenTextures(1, gainTexture, 0)
                check(gainTexture[0] != 0) { "Could not allocate Ultra HDR gainmap texture" }
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, gainTexture[0])
                GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
                GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
                GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, gainWidth, gainHeight)
                gainBytes = gainWidth.toLong() * gainHeight * 4L
                MemoryLeakDiagnostics.glTextureAllocated(gainBytes)
                gainTracked = true
            }
            checkGl("encoded texture allocation")
            val allocatedAt = SystemClock.elapsedRealtime()

            val outputProgram = outputProgram(prepared != null, resolved.toneMapper, resolved.ultraHdr)
            GLES31.glUseProgram(outputProgram)
            photoLut.bind(outputProgram, resolved.photoLut)
            if (prepared != null) cleanup.bindPrepared(outputProgram, prepared)
            GLES31.glBindImageTexture(
                0, scene.textureId, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F
            )
            GLES31.glBindImageTexture(
                1, encodedTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8
            )
            GLES31.glBindImageTexture(
                2, gainTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8
            )
            GLES31.glUniform2i(location(outputProgram, "u_size"), scene.width, scene.height)
            GLES31.glUniform2i(
                location(outputProgram, "u_gainmap_size"),
                ceilDiv(scene.width, GAINMAP_DOWNSCALE), ceilDiv(scene.height, GAINMAP_DOWNSCALE)
            )
            GLES31.glUniform1i(location(outputProgram, "u_display_p3"), if (resolved.displayP3) 1 else 0)
            GLES31.glUniform1i(GLES31.glGetUniformLocation(outputProgram, "u_basic"), resolved.toneMapper.shaderMode)
            aces2.bind(outputProgram, resolved.toneMapper.shaderMode, ToneLook.values(resolved))
            GLES31.glUniform2f(location(outputProgram, "u_camera_tone"), resolved.shadowLift, resolved.highlightRecovery)
            GLES31.glUniform1f(location(outputProgram, "u_agx_purity_boost"), resolved.agxPurityBoost)
            GLES31.glUniform1i(location(outputProgram, "u_agx_punchy"), if (resolved.agxLook == AgxLook.PUNCHY) 1 else 0)
            GLES31.glUniform1f(location(outputProgram, "u_agx_contrast"), resolved.agxContrast)
            GLES31.glUniform1f(location(outputProgram, "u_agx_saturation"), resolved.agxSaturation)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_hue_preservation"), resolved.agxHuePreservation
            )
            GLES31.glUniform1f(location(outputProgram, "u_agx_shadow_ev"), resolved.effectiveAgxShadowEv)
            GLES31.glUniform1f(location(outputProgram, "u_agx_highlight_ev"), resolved.agxHighlightEv)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_gamut_compression"), resolved.agxGamutCompression
            )
            GLES31.glUniform1f(
                location(outputProgram, "u_highlight_shoulder"), resolved.highlightShoulder
            )
            GLES31.glUniform1f(location(outputProgram, "u_grain_amount"), 0f)
            GLES31.glUniform1f(location(outputProgram, "u_grain_size"), 0f)
            GLES31.glUniform1ui(location(outputProgram, "u_grain_seed"), System.nanoTime().toInt())
            GLES31.glUniform1i(location(outputProgram, "u_write_gainmap"), if (prepared != null && resolved.ultraHdr) 1 else 0)
            GLES31.glUniform1i(location(outputProgram, "u_gainmap_only"), 0)
            bindUltraHdrParams(outputProgram, resolved)
            if (prepared != null) cleanup.dispatchSlices(outputProgram, scene.width, scene.height)
            else GLES31.glDispatchCompute(ceilDiv(scene.width, 8), ceilDiv(scene.height, 8), 1)
            if (resolved.ultraHdr && prepared == null) {
                GLES31.glUniform1i(location(outputProgram, "u_write_gainmap"), 1)
                GLES31.glUniform1i(location(outputProgram, "u_gainmap_only"), 1)
                GLES31.glDispatchCompute(
                    ceilDiv(scene.width, GAINMAP_DOWNSCALE * 8),
                    ceilDiv(scene.height, GAINMAP_DOWNSCALE * 8), 1
                )
            }
            GLES31.glMemoryBarrier(
                GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT
            )
            checkGl("AgX/output dispatch")
            val submittedAt = SystemClock.elapsedRealtime()
            val colorSpace = ColorSpace.get(
                if (resolved.displayP3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB
            )
            val base = readBitmap(encodedTexture[0], scene.width, scene.height, colorSpace)
            val baseReadyAt = SystemClock.elapsedRealtime()
            val gainmap = if (resolved.ultraHdr) {
                readBitmap(
                    gainTexture[0], ceilDiv(scene.width, GAINMAP_DOWNSCALE),
                    ceilDiv(scene.height, GAINMAP_DOWNSCALE), colorSpace
                ).bitmap
            } else null
            val completedAt = SystemClock.elapsedRealtime()
            Log.i(
                LOG_TAG,
                "JPEG output ${scene.width}x${scene.height}: allocate=${allocatedAt - startedAt}ms " +
                    "submit=${submittedAt - allocatedAt}ms " +
                    "baseRead=${base.readPixelsMs}ms baseBitmap=${base.bitmapCopyMs}ms " +
                    "gain+attach=${completedAt - baseReadyAt}ms total=${completedAt - startedAt}ms"
            )
            MemoryLeakDiagnostics.sample("jpeg-frame-complete")
            return DevelopedJpeg(base.bitmap, resolved, gainmap)
        } finally {
            GLES31.glDeleteTextures(1, encodedTexture, 0)
            if (encodedTracked) MemoryLeakDiagnostics.glTextureReleased(encodedBytes)
            if (gainTexture[0] != 0) GLES31.glDeleteTextures(1, gainTexture, 0)
            if (gainTracked) MemoryLeakDiagnostics.glTextureReleased(gainBytes)
            MemoryLeakDiagnostics.sample("jpeg-temporaries-released")
        }
    }

    /** Must be called while AMaZE's persistent EGL context is current on its worker thread. */
    fun close() {
        aces2.close()
        photoLut.close()
        cleanup.close()
        if (program != 0 || cleanupPrograms.any { it != 0 }) {
            check(programThread == Thread.currentThread()) {
                "JPEG output program must be closed by its owning worker thread"
            }
            for (id in intArrayOf(program, *cleanupPrograms)) if (id != 0) {
                GLES31.glDeleteProgram(id)
                MemoryLeakDiagnostics.glProgramReleased()
            }
            program = 0
            cleanupPrograms.fill(0)
            programThread = null
        }
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            MemoryLeakDiagnostics.glFramebufferReleased()
            framebuffer = 0
        }
    }

    private fun readBitmap(
        texture: Int,
        width: Int,
        height: Int,
        colorSpace: ColorSpace
    ): ReadbackResult {
        if (framebuffer == 0) {
            framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            check(framebuffer != 0) { "Could not allocate output readback framebuffer" }
            MemoryLeakDiagnostics.glFramebufferAllocated()
        }
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Encoded output framebuffer is incomplete"
            }
            val byteCount = Math.multiplyExact(Math.multiplyExact(width, height), 4)
            // ART charges allocateDirect against its Java heap. A 50 MP RGBA
            // readback is ~200 MB while B&W already owns full-frame CFA/mono
            // arrays. Keep this staging allocation native and release it on
            // every path, rather than caching an entire photo between saves.
            val pixels = HdrPlusVulkan.allocBuffer(byteCount.toLong()).order(ByteOrder.nativeOrder())
            try {
                pixels.clear()
                pixels.limit(byteCount)
                GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
                val readStartedAt = SystemClock.elapsedRealtime()
                GLES30.glReadPixels(
                    0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
                )
                checkGl("encoded output readback")
                val readCompletedAt = SystemClock.elapsedRealtime()
                // GLES RGBA readback is accepted directly by this device's ARGB_8888 Bitmap path.
                // Do not apply a second R/B swap here: it turns the calibrated DNG's warm neutrals
                // cyan and visibly exchanges blue and orange subjects.
                pixels.rewind()
                val bitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888, false, colorSpace
                )
                try {
                    bitmap.copyPixelsFromBuffer(pixels)
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
                return ReadbackResult(
                    bitmap,
                    readCompletedAt - readStartedAt,
                    SystemClock.elapsedRealtime() - readCompletedAt
                )
            } finally {
                HdrPlusVulkan.freeBuffer(pixels)
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private data class ReadbackResult(
        val bitmap: Bitmap,
        val readPixelsMs: Long,
        val bitmapCopyMs: Long
    )

    /**
     * Binds the RAWR-parity gain-map encode parameters for both dispatches.
     * Must stay in sync with agx_srgb8.glsl's u_gainmap_* / u_hdr_exposure /
     * u_sat_protect / u_multi_channel declarations.
     */
    private fun bindUltraHdrParams(program: Int, resolved: JpegOutputSettings) {
        GLES31.glUniform1f(location(program, "u_hdr_exposure"), resolved.hdrExposure)
        GLES31.glUniform1f(location(program, "u_gainmap_min_log2"), resolved.gainMapMinLog2)
        GLES31.glUniform1f(location(program, "u_gainmap_max_log2"), resolved.gainMapMaxLog2)
        GLES31.glUniform1f(location(program, "u_gainmap_gamma"), resolved.gainmapGamma)
        GLES31.glUniform1f(location(program, "u_gainmap_offset_sdr"), resolved.gainmapOffsetSdr)
        GLES31.glUniform1f(location(program, "u_gainmap_offset_hdr"), resolved.gainmapOffsetHdr)
        GLES31.glUniform1f(location(program, "u_sat_protect"), resolved.satProtect)
        GLES31.glUniform1i(
            location(program, "u_multi_channel"),
            if (resolved.multiChannelMap) 1 else 0
        )
    }

    private fun compile(body: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        check(shader != 0) { "Could not allocate AgX/output shader" }
        GLES31.glShaderSource(shader, "#version 310 es\n$body")
        GLES31.glCompileShader(shader)
        val status = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            error("AgX/output shader compilation failed: $log")
        }
        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)
        GLES31.glDeleteShader(shader)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(program)
            GLES31.glDeleteProgram(program)
            error("AgX/output shader link failed: $log")
        }
        MemoryLeakDiagnostics.glProgramAllocated()
        return program
    }

    private fun outputProgram(fusedCleanup: Boolean = false, tone: PhotoToneMapper = PhotoToneMapper.BASIC, gainmap: Boolean = false): Int {
        val thread = Thread.currentThread()
        val previousThread = programThread
        check(previousThread == null || previousThread == thread) {
            "JPEG output program must stay on AMaZE's owning worker thread"
        }
        val index = (if(tone == PhotoToneMapper.BASIC) 2 else 0) + (if(gainmap)1 else 0)
        if (fusedCleanup) {
            if (cleanupPrograms[index] == 0) cleanupPrograms[index] = compile(cleanup.fusedSource(shaderSource, tone == PhotoToneMapper.BASIC, gainmap))
        } else {
            if (program == 0) program = compile("#define RAWLENS_STILL_SDR 1\n$shaderSource")
        }
        programThread = thread
        return if (fusedCleanup) cleanupPrograms[index] else program
    }

    private fun location(program: Int, name: String): Int =
        GLES31.glGetUniformLocation(program, name).also {
            // Specialized Basic/AgX programs legitimately eliminate the other curve's controls.
            check(it >= 0 || cleanupPrograms.contains(program)) { "Missing uniform $name" }
        }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation failed with GLES error 0x${error.toString(16)}"
        }
    }

    private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

    private companion object {
        const val SHADER = "shaders/display/agx_srgb8.glsl"
        const val GAINMAP_DOWNSCALE = 4
        const val LOG_TAG = "RawLensDevelop"
    }
}
