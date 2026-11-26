// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Half-float RGBA pixels developed for JPEG XL, with the resolved settings.
 * Row 0 is the image top row (same convention as the RGBA8 JPEG readback,
 * which needs no vertical flip).
 *
 * [halfRgba] is native memory ([HdrPlusVulkan.allocBuffer]): 50 MP needs 402 MB,
 * more than the Java heap can hold. [JxlSaver.save] releases it; read it before.
 */
data class DevelopedJxl(
    val halfRgba: ByteBuffer,
    val width: Int,
    val height: Int,
    val settings: JxlOutputSettings
) : java.io.Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) HdrPlusVulkan.freeBuffer(halfRgba)
    }
}

internal class JxlGpuOutputException(message: String) : IllegalStateException(message)

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
        it.readText().replace("// PHOTO_CUBE", PhotoLutGl.glsl).replace("// SIGMOID_FUNCTIONS", CameraToneBalance.glsl + "\n" + SigmoidDisplayTransform.glsl + "\n" + Aces2DisplayTransform.glsl)
    }.replace("binding = 1, rgba8", "binding = 1, rgba16f")

    // Invoked inside Gles31AmazeProcessor.process(), on its persistent writer
    // EGL context. Same per-context ownership discipline as the JPEG side.
    private val aces2 = Aces2Gl()
    private val photoLut = PhotoLutGl()
    private var program = 0
    private var programThread: Thread? = null
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
            "JXL develop needs scene-linear ACEScg, not camera-linear (camera-linear tail missing?)"
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
            photoLut.bind(outputProgram, resolvedLook.photoLut)
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
            GLES31.glUniform1i(location(outputProgram, "u_basic"), resolvedLook.toneMapper.shaderMode)
            aces2.bind(outputProgram, resolvedLook.toneMapper.shaderMode, ToneLook.values(resolvedLook))
            GLES31.glUniform2f(location(outputProgram, "u_camera_tone"), resolvedLook.shadowLift, resolvedLook.highlightRecovery)
            GLES31.glUniform1f(location(outputProgram, "u_agx_purity_boost"), resolvedLook.agxPurityBoost)
            GLES31.glUniform1i(location(outputProgram, "u_agx_punchy"), if (resolvedLook.agxLook == AgxLook.PUNCHY) 1 else 0)
            GLES31.glUniform1f(location(outputProgram, "u_agx_contrast"), resolvedLook.agxContrast)
            GLES31.glUniform1f(location(outputProgram, "u_agx_saturation"), resolvedLook.agxSaturation)
            GLES31.glUniform1f(
                location(outputProgram, "u_agx_hue_preservation"), resolvedLook.agxHuePreservation
            )
            GLES31.glUniform1f(location(outputProgram, "u_agx_shadow_ev"), resolvedLook.effectiveAgxShadowEv)
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
            val dispatchOffset = location(outputProgram, "u_jxl_dispatch_offset")
            for (slice in GpuScheduling.slices(scene.width, scene.height, 8, 8, DISPATCH_BUDGET)) {
                GLES31.glUniform2i(dispatchOffset, slice.x, slice.y)
                GLES31.glDispatchCompute(slice.groupsX, slice.groupsY, 1)
                awaitSlice(slice)
            }
            GLES31.glMemoryBarrier(
                GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT
            )
            checkGl("AgX/JXL dispatch")
            val submittedAt = SystemClock.elapsedRealtime()
            val pixels = readHalfFloat(encodedTexture[0], scene.width, scene.height)
            val samples = pixels.asShortBuffer()
            for (alpha in 3 until samples.limit() step 4) {
                if (samples.get(alpha).toInt() != HALF_ONE) {
                    val pixel = alpha / 4
                    HdrPlusVulkan.freeBuffer(pixels)
                    throw JxlGpuOutputException(
                        "Invalid JXL readback at (${pixel % scene.width}, ${pixel / scene.width})"
                    )
                }
            }
            val completedAt = SystemClock.elapsedRealtime()
            Log.i(
                LOG_TAG,
                "JXL output ${scene.width}x${scene.height} ${resolved.bitDepth.label} " +
                    "${resolved.pixelType.label} ${resolved.hdrTransfer.label} q=${resolved.quality} effort=${resolved.effort} " +
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
        aces2.close()
        photoLut.close()
        if (program != 0) {
            check(programThread == Thread.currentThread()) {
                "JXL output program must be closed by its owning worker thread"
            }
            GLES31.glDeleteProgram(program)
            MemoryLeakDiagnostics.glProgramReleased()
            program = 0
            programThread = null
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
        var pending: ByteBuffer? = null
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "JXL output framebuffer is incomplete"
            }
            val pixels = HdrPlusVulkan.allocBuffer(width.toLong() * height * 8L)
                .also { pending = it }
                .order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 2)
            try {
                for (row in 0 until height step BAND_ROWS) {
                    pixels.position(row * width * 8)
                    GLES30.glReadPixels(
                        0, row, width, minOf(BAND_ROWS, height - row),
                        GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, pixels
                    )
                    val error = GLES20.glGetError()
                    if (error != GLES20.GL_NO_ERROR) {
                        throw JxlGpuOutputException(
                            "JXL half-float readback failed at row $row (GLES 0x${error.toString(16)})"
                        )
                    }
                }
            } finally {
                GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            }
            pixels.rewind()
            pending = null
            return pixels
        } finally {
            pending?.let(HdrPlusVulkan::freeBuffer)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    // Bound each dispatch so the output shader does not monopolize the
    // Adreno queue while the viewfinder and UI are also rendering.
    private fun awaitSlice(slice: GpuScheduling.Slice) {
        val position = "(${slice.x}, ${slice.y})"
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (fence == 0L) throw JxlGpuOutputException("Could not fence JXL output at $position")
        try {
            val deadline = SystemClock.elapsedRealtime() + GPU_TIMEOUT_MS
            var flags = GLES30.GL_SYNC_FLUSH_COMMANDS_BIT
            while (true) {
                when (val status = GLES30.glClientWaitSync(fence, flags, GPU_WAIT_INTERVAL_NS)) {
                    GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> return
                    GLES30.GL_TIMEOUT_EXPIRED -> {
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            throw JxlGpuOutputException("JXL GPU timed out at $position")
                        }
                    }
                    else -> {
                        val error = GLES20.glGetError()
                        throw JxlGpuOutputException(
                            "JXL GPU wait failed at $position (status=$status, GLES 0x${error.toString(16)})"
                        )
                    }
                }
                flags = 0
            }
        } finally {
            GLES30.glDeleteSync(fence)
        }
    }

    private fun compile(body: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        check(shader != 0) { "Could not allocate AgX/JXL shader" }
        GLES31.glShaderSource(shader, "#version 310 es\n#define RAWLENS_JXL\n$body")
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
        val thread = Thread.currentThread()
        val previousThread = programThread
        check(previousThread == null || previousThread == thread) {
            "JXL output program must stay on AMaZE's owning worker thread"
        }
        if (program == 0) {
            program = compile(shaderSource)
            programThread = thread
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

    private companion object {
        const val BAND_ROWS = 256
        const val DISPATCH_BUDGET = 131_072
        const val GPU_WAIT_INTERVAL_NS = 10_000_000L
        const val GPU_TIMEOUT_MS = 10_000L
        const val HALF_ONE = 0x3c00
        const val SHADER = "shaders/display/agx_srgb8.glsl"
        const val LOG_TAG = "RawLensDevelop"
    }
}
