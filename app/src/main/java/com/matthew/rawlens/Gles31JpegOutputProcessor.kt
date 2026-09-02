// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.annotation.TargetApi
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DevelopedJpeg(val bitmap: Bitmap, val settings: JpegOutputSettings)

/** Converts an unclamped scene-linear ACEScg texture into a color-tagged JPEG Bitmap. */
class Gles31JpegOutputProcessor(context: Context) {
    private val shaderSource = context.applicationContext.assets.open(SHADER).bufferedReader().use {
        it.readText()
    }
    // This processor is invoked inside Gles31AmazeProcessor.process(), on its persistent writer
    // EGL context. Compile once per context instead of once per saved JPEG.
    private var program = 0
    private var programThreadId: Long? = null
    private var framebuffer = 0
    private var baseReadback: ByteBuffer? = null
    private var gainReadback: ByteBuffer? = null

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
        if (resolved.ultraHdr) {
            check(encoded.gainmapTextureId != 0) { "Fused Ultra HDR output has no gain-map texture" }
            attachUltraHdrGainmap(
                base.bitmap,
                readBitmap(
                    encoded.gainmapTextureId,
                    ceilDiv(encoded.width, GAINMAP_DOWNSCALE),
                    ceilDiv(encoded.height, GAINMAP_DOWNSCALE),
                    colorSpace,
                    gainmap = true
                ).bitmap
            )
        }
        val completedAt = SystemClock.elapsedRealtime()
        Log.i(
            LOG_TAG,
            "JPEG fused output ${encoded.width}x${encoded.height}: " +
                "baseRead=${base.readPixelsMs}ms baseBitmap=${base.bitmapCopyMs}ms " +
                "gain+attach=${completedAt - baseReadyAt}ms total=${completedAt - startedAt}ms"
        )
        return DevelopedJpeg(base.bitmap, resolved)
    }

    fun process(scene: AmazeGpuOutput, settings: JpegOutputSettings = JpegOutputSettings(), denoise: DenoiseSettings = DenoiseSettings()): DevelopedJpeg {
        val startedAt = SystemClock.elapsedRealtime()
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F)
        val resolved = settings.resolvedForPlatform()
        val encodedTexture = IntArray(1)
        val gainTexture = IntArray(1)
        GLES31.glGenTextures(1, encodedTexture, 0)
        check(encodedTexture[0] != 0) { "Could not allocate encoded output texture" }
        try {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, encodedTexture[0])
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, scene.width, scene.height)
            if (resolved.ultraHdr) {
                val gainWidth = ceilDiv(scene.width, GAINMAP_DOWNSCALE)
                val gainHeight = ceilDiv(scene.height, GAINMAP_DOWNSCALE)
                GLES31.glGenTextures(1, gainTexture, 0)
                check(gainTexture[0] != 0) { "Could not allocate Ultra HDR gainmap texture" }
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, gainTexture[0])
                GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
                GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
                GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, gainWidth, gainHeight)
            }
            checkGl("encoded texture allocation")
            val allocatedAt = SystemClock.elapsedRealtime()

            val outputProgram = outputProgram()
            GLES31.glUseProgram(outputProgram)
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
            GLES31.glUniform1f(location(outputProgram, "u_agx_purity_boost"), resolved.agxPurityBoost)
            GLES31.glUniform1i(location(outputProgram, "u_agx_look"), resolved.agxLook.ordinal)
            GLES31.glUniform1f(location(outputProgram, "u_agx_contrast"), resolved.agxContrast)
            GLES31.glUniform1f(location(outputProgram, "u_agx_saturation"), resolved.agxSaturation)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_hue_preservation"), resolved.agxHuePreservation
            )
            GLES31.glUniform1f(location(outputProgram, "u_agx_shadow_ev"), resolved.agxShadowEv)
            GLES31.glUniform1f(location(outputProgram, "u_agx_highlight_ev"), resolved.agxHighlightEv)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_gamut_compression"), resolved.agxGamutCompression
            )
            GLES31.glUniform1f(location(outputProgram, "u_grain_amount"), if (denoise.enabled && denoise.filmGrainEnabled) denoise.filmGrainAmount else 0f)
            GLES31.glUniform1f(location(outputProgram, "u_grain_size"), denoise.filmGrainSize)
            GLES31.glUniform1ui(location(outputProgram, "u_grain_seed"), System.nanoTime().toInt())
            GLES31.glUniform1i(location(outputProgram, "u_write_gainmap"), 0)
            GLES31.glUniform1i(location(outputProgram, "u_gainmap_only"), 0)
            GLES31.glDispatchCompute(ceilDiv(scene.width, 8), ceilDiv(scene.height, 8), 1)
            if (resolved.ultraHdr) {
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
            if (resolved.ultraHdr) {
                attachUltraHdrGainmap(
                    base.bitmap,
                    readBitmap(
                        gainTexture[0], ceilDiv(scene.width, GAINMAP_DOWNSCALE),
                        ceilDiv(scene.height, GAINMAP_DOWNSCALE), colorSpace, gainmap = true
                    ).bitmap
                )
            }
            val completedAt = SystemClock.elapsedRealtime()
            Log.i(
                LOG_TAG,
                "JPEG output ${scene.width}x${scene.height}: allocate=${allocatedAt - startedAt}ms " +
                    "submit=${submittedAt - allocatedAt}ms " +
                    "baseRead=${base.readPixelsMs}ms baseBitmap=${base.bitmapCopyMs}ms " +
                    "gain+attach=${completedAt - baseReadyAt}ms total=${completedAt - startedAt}ms"
            )
            return DevelopedJpeg(base.bitmap, resolved)
        } finally {
            GLES31.glDeleteTextures(1, encodedTexture, 0)
            if (gainTexture[0] != 0) GLES31.glDeleteTextures(1, gainTexture, 0)
        }
    }

    /** Must be called while AMaZE's persistent EGL context is current on its worker thread. */
    fun close() {
        if (program != 0) {
            check(programThreadId == Thread.currentThread().id) {
                "JPEG output program must be closed by its owning worker thread"
            }
            GLES31.glDeleteProgram(program)
            program = 0
            programThreadId = null
        }
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        baseReadback = null
        gainReadback = null
    }

    private fun readBitmap(
        texture: Int,
        width: Int,
        height: Int,
        colorSpace: ColorSpace,
        gainmap: Boolean = false
    ): ReadbackResult {
        if (framebuffer == 0) {
            framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            check(framebuffer != 0) { "Could not allocate output readback framebuffer" }
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
            val byteCount = width * height * 4
            var pixels = if (gainmap) gainReadback else baseReadback
            if (pixels == null || pixels.capacity() < byteCount) {
                pixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
                if (gainmap) gainReadback = pixels else baseReadback = pixels
            }
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
            ).also {
                it.copyPixelsFromBuffer(pixels)
            }
            return ReadbackResult(
                bitmap,
                readCompletedAt - readStartedAt,
                SystemClock.elapsedRealtime() - readCompletedAt
            )
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private data class ReadbackResult(
        val bitmap: Bitmap,
        val readPixelsMs: Long,
        val bitmapCopyMs: Long
    )

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachUltraHdrGainmap(base: Bitmap, contents: Bitmap) {
        val gainmap = Gainmap(contents).apply {
            // D = B * exp(mix(log(1), log(16), G)); see android.graphics.Gainmap.
            setRatioMin(1f, 1f, 1f)
            setRatioMax(16f, 16f, 16f)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(16f)
        }
        base.setGainmap(gainmap)
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
        return program
    }

    private fun outputProgram(): Int {
        val threadId = Thread.currentThread().id
        val previousThreadId = programThreadId
        check(previousThreadId == null || previousThreadId == threadId) {
            "JPEG output program must stay on AMaZE's owning worker thread"
        }
        if (program == 0) {
            program = compile(shaderSource)
            programThreadId = threadId
        }
        return program
    }

    private fun location(program: Int, name: String): Int =
        GLES31.glGetUniformLocation(program, name).also { check(it >= 0) { "Missing uniform $name" } }

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
