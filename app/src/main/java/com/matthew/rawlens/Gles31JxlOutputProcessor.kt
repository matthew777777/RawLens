// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Half-float RGBA pixels developed for JPEG XL, with the resolved settings.
 * Row 0 is the image top row (same convention as the RGBA8 JPEG readback,
 * which needs no vertical flip).
 */
data class DevelopedJxl(
    val halfRgba: ByteBuffer,
    val width: Int,
    val height: Int,
    val settings: JxlOutputSettings
)

/**
 * Develops an unclamped scene-linear ACEScg texture into half-float
 * display-referred RGBA, preserving up to 16-bit precision for the JPEG XL
 * encoder. Runs the same AgX shader as the 8-bit JPEG path
 * (`agx_srgb8.glsl`), retargeted at an RGBA16F output image so quantization
 * to 8/10/12/16-bit (or half/float passthrough) happens in [JxlPixelPack]
 * instead of on the GPU.
 */
class Gles31JxlOutputProcessor(context: Context) {
    private val shaderSource = context.applicationContext.assets.open(SHADER).bufferedReader().use {
        it.readText()
    }.replace("binding = 1, rgba8", "binding = 1, rgba16f")

    // Invoked inside Gles31AmazeProcessor.process(), on its persistent writer
    // EGL context. Same per-context ownership discipline as the JPEG side.
    private var program = 0
    private var programThreadId: Long? = null
    private var framebuffer = 0

    /**
     * @param look shared AgX develop look (same contract as the JPEG path:
     * purity, contrast, saturation, hue, shadow/highlight range, gamut,
     * shoulder). Callers pass the HDR-capped look for bracket merges,
     * exactly like [Gles31JpegOutputProcessor].
     * @param jxl container options (darktable parity); its Display-P3 flag
     * owns the JXL color space tag.
     */
    fun process(
        scene: AmazeGpuOutput,
        look: JpegOutputSettings = JpegOutputSettings(),
        jxl: JxlOutputSettings = JxlOutputSettings()
    ): DevelopedJxl {
        val startedAt = SystemClock.elapsedRealtime()
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F)
        require(scene.colorDomain == AmazeColorDomain.ACESCG) {
            "JXL develop needs scene-linear ACEScg, not camera-linear (wavelet tail missing?)"
        }
        val resolvedLook = look.resolvedForPlatform()
        val resolved = jxl.resolved()
        val encodedTexture = IntArray(1)
        val dummyGainTexture = IntArray(1)
        val encodedBytes = scene.width.toLong() * scene.height * 8L
        var encodedTracked = false
        GLES31.glGenTextures(1, encodedTexture, 0)
        check(encodedTexture[0] != 0) { "Could not allocate JXL output texture" }
        try {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, encodedTexture[0])
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, scene.width, scene.height)
            MemoryLeakDiagnostics.glTextureAllocated(encodedBytes)
            encodedTracked = true
            // The shader declares a gainmap image binding; the JXL path never
            // writes one (SDR still), so bind a 1x1 dummy to keep the unit valid.
            GLES31.glGenTextures(1, dummyGainTexture, 0)
            check(dummyGainTexture[0] != 0) { "Could not allocate JXL dummy gainmap texture" }
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, dummyGainTexture[0])
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, 1, 1)
            checkGl("JXL texture allocation")
            val allocatedAt = SystemClock.elapsedRealtime()

            val outputProgram = outputProgram()
            GLES31.glUseProgram(outputProgram)
            GLES31.glBindImageTexture(
                0, scene.textureId, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F
            )
            GLES31.glBindImageTexture(
                1, encodedTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F
            )
            GLES31.glBindImageTexture(
                2, dummyGainTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8
            )
            GLES31.glUniform2i(location(outputProgram, "u_size"), scene.width, scene.height)
            GLES31.glUniform2i(location(outputProgram, "u_gainmap_size"), 1, 1)
            GLES31.glUniform1i(location(outputProgram, "u_display_p3"), if (resolved.displayP3) 1 else 0)
            GLES31.glUniform1i(
                location(outputProgram, "u_hdr_pq"),
                if (resolved.hdrTransfer == JxlHdrTransfer.PQ) 1 else 0
            )
            // Shared AgX look: identical uniforms to the JPEG path so the
            // JXL still matches the JPEG look at 8-bit-equivalent settings.
            GLES31.glUniform1f(location(outputProgram, "u_agx_purity_boost"), resolvedLook.agxPurityBoost)
            GLES31.glUniform1f(location(outputProgram, "u_agx_contrast"), resolvedLook.agxContrast)
            GLES31.glUniform1f(location(outputProgram, "u_agx_saturation"), resolvedLook.agxSaturation)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_hue_preservation"), resolvedLook.agxHuePreservation
            )
            GLES31.glUniform1f(location(outputProgram, "u_agx_shadow_ev"), resolvedLook.agxShadowEv)
            GLES31.glUniform1f(location(outputProgram, "u_agx_highlight_ev"), resolvedLook.agxHighlightEv)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_gamut_compression"), resolvedLook.agxGamutCompression
            )
            GLES31.glUniform1f(
                location(outputProgram, "u_highlight_shoulder"), resolvedLook.highlightShoulder
            )
            GLES31.glUniform1f(location(outputProgram, "u_grain_amount"), 0f)
            GLES31.glUniform1f(location(outputProgram, "u_grain_size"), 0f)
            GLES31.glUniform1ui(location(outputProgram, "u_grain_seed"), System.nanoTime().toInt())
            GLES31.glUniform1i(location(outputProgram, "u_write_gainmap"), 0)
            GLES31.glUniform1i(location(outputProgram, "u_gainmap_only"), 0)
            GLES31.glDispatchCompute(ceilDiv(scene.width, 8), ceilDiv(scene.height, 8), 1)
            GLES31.glMemoryBarrier(
                GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT
            )
            checkGl("AgX/JXL dispatch")
            val submittedAt = SystemClock.elapsedRealtime()
            val pixels = readHalfFloat(encodedTexture[0], scene.width, scene.height)
            val completedAt = SystemClock.elapsedRealtime()
            Log.i(
                LOG_TAG,
                "JXL output ${scene.width}x${scene.height} ${resolved.bitDepth.label} " +
                    "${resolved.pixelType.label} q=${resolved.quality} effort=${resolved.effort} " +
                    "tier=${resolved.decodingSpeed}: allocate=${allocatedAt - startedAt}ms " +
                    "submit=${submittedAt - allocatedAt}ms read=${completedAt - submittedAt}ms " +
                    "total=${completedAt - startedAt}ms"
            )
            MemoryLeakDiagnostics.sample("jxl-frame-complete")
            return DevelopedJxl(pixels, scene.width, scene.height, resolved)
        } finally {
            GLES31.glDeleteTextures(1, encodedTexture, 0)
            if (encodedTracked) MemoryLeakDiagnostics.glTextureReleased(encodedBytes)
            if (dummyGainTexture[0] != 0) GLES31.glDeleteTextures(1, dummyGainTexture, 0)
            MemoryLeakDiagnostics.sample("jxl-temporaries-released")
        }
    }

    /** Must be called while AMaZE's persistent EGL context is current on its worker thread. */
    fun close() {
        if (program != 0) {
            check(programThreadId == Thread.currentThread().id) {
                "JXL output program must be closed by its owning worker thread"
            }
            GLES31.glDeleteProgram(program)
            MemoryLeakDiagnostics.glProgramReleased()
            program = 0
            programThreadId = null
        }
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            MemoryLeakDiagnostics.glFramebufferReleased()
            framebuffer = 0
        }
    }

    private fun readHalfFloat(texture: Int, width: Int, height: Int): ByteBuffer {
        if (framebuffer == 0) {
            framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            check(framebuffer != 0) { "Could not allocate JXL readback framebuffer" }
            MemoryLeakDiagnostics.glFramebufferAllocated()
        }
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "JXL output framebuffer is incomplete"
            }
            val pixels = ByteBuffer
                .allocateDirect(width * height * 8)
                .order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 2)
            try {
                GLES30.glReadPixels(
                    0, 0, width, height,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, pixels
                )
            } finally {
                GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            }
            checkGl("JXL half-float readback")
            pixels.rewind()
            return pixels
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun compile(body: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        check(shader != 0) { "Could not allocate AgX/JXL shader" }
        GLES31.glShaderSource(shader, "#version 310 es\n$body")
        GLES31.glCompileShader(shader)
        val status = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            error("AgX/JXL shader compilation failed: $log")
        }
        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)
        GLES31.glDeleteShader(shader)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(program)
            GLES31.glDeleteProgram(program)
            error("AgX/JXL program link failed: $log")
        }
        MemoryLeakDiagnostics.glProgramAllocated()
        return program
    }

    private fun outputProgram(): Int {
        val threadId = Thread.currentThread().id
        val previousThreadId = programThreadId
        check(previousThreadId == null || previousThreadId == threadId) {
            "JXL output program must stay on AMaZE's owning worker thread"
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
        const val LOG_TAG = "RawLensDevelop"
    }
}
