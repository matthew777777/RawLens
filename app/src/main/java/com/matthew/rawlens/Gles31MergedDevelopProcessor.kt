// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Prompt 5A input: the live merged linear camera-RGB texture with reference
 * metadata and the effective-frame-count (accumulated robustness Rc) texture.
 *
 * - [mergedTextureId] is RGBA32F at [width]x[height], holding merged camera
 *   RGB with reference fallback already applied. Bayer data must never reach
 *   this entry point: the signature accepts only texture IDs, so passing CFA
 *   through is a type error, and no AMaZE/Bayer symbol is referenced.
 * - [effectiveCountTextureId] is R32F at exactly width/2 x height/2; quad
 *   (x, y) covers the merged 2x2 block. Rc is the accumulated per-frame
 *   robustness, so rc / acceptedFrames is the pixel confidence in [0, 1].
 * - [crop] selects the developed region in merged pixels (default: full frame).
 */
data class MergedTextureJpegInput(
    val mergedTextureId: Int,
    val width: Int,
    val height: Int,
    val effectiveCountTextureId: Int,
    val acceptedFrames: Int,
    val referenceMetadata: RawFrameMetadata,
    val crop: RawCrop = RawCrop(0, 0, width, height)
) {
    init {
        require(mergedTextureId != 0 && effectiveCountTextureId != 0) {
            "Merged develop input textures must be live GPU textures"
        }
        require(width > 0 && height > 0) { "Merged frame must have positive dimensions" }
        require(acceptedFrames >= 1) { "Accepted frame count must be at least 1" }
        require(crop.left >= 0 && crop.top >= 0 && crop.width > 0 && crop.height > 0 &&
            crop.left + crop.width <= width && crop.top + crop.height <= height) {
            "Develop crop $crop is outside the $width x $height merged frame"
        }
    }
}

/**
 * Pure-CPU mirror of `shaders/merged/develop.glsl`, in Double precision.
 * The GPU test asserts device output against this within fp16 readback
 * tolerance. Denoise weights replicate the shader exactly so the blend is a
 * separately unit-testable function.
 */
object MergedDevelopWeights {
    /** Pixel confidence from accumulated robustness; invalid Rc means no support. */
    fun confidence(rc: Float, acceptedFrames: Int): Double {
        require(acceptedFrames >= 1)
        if (!rc.isFinite() || rc <= 0f) return 0.0
        return min(rc.toDouble() / acceptedFrames, 1.0)
    }

    /**
     * Spatial blend ceiling from user strength. Merged frames are already
     * temporally averaged, so the 3x3 finishing pass saturates at 1.0:
     * strength above 1 clamps rather than overshooting the neighborhood mean.
     */
    fun blendMax(strength: Float): Double {
        require(strength.isFinite() && strength >= 0f)
        return min(strength.toDouble(), 1.0)
    }

    fun blend(rc: Float, acceptedFrames: Int, strength: Float): Double =
        blendMax(strength) * (1.0 - confidence(rc, acceptedFrames))

    /**
     * One developed output pixel. [block] is the raw 3x3 merged camera-RGB
     * neighborhood in row-major order with replicated edges (negatives
     * allowed); only the center and the neighborhood mean are clamped, exactly
     * like the shader. [matrix] is the row-major mathematical
     * camera-to-ACEScg matrix.
     */
    fun developPixel(
        block: DoubleArray,
        rc: Float,
        acceptedFrames: Int,
        denoiseEnabled: Boolean,
        strength: Float,
        matrix: DoubleArray,
        cameraWhiteNormalized: DoubleArray
    ): DoubleArray {
        require(block.size == 27 && matrix.size == 9 && cameraWhiteNormalized.size == 3)
        val cx = max(block[12], 0.0); val cy = max(block[13], 0.0); val cz = max(block[14], 0.0)
        var r = cx; var g = cy; var b = cz
        if (denoiseEnabled) {
            val k = blend(rc, acceptedFrames, strength)
            var ar = 4.0 * cx; var ag = 4.0 * cy; var ab = 4.0 * cz
            for (i in intArrayOf(1, 3, 5, 7)) {
                ar += 2.0 * block[i * 3]; ag += 2.0 * block[i * 3 + 1]; ab += 2.0 * block[i * 3 + 2]
            }
            for (i in intArrayOf(0, 2, 6, 8)) {
                ar += block[i * 3]; ag += block[i * 3 + 1]; ab += block[i * 3 + 2]
            }
            val mr = max(ar / 16.0, 0.0); val mg = max(ag / 16.0, 0.0); val mb = max(ab / 16.0, 0.0)
            r = cx + (mr - cx) * k
            g = cy + (mg - cy) * k
            b = cz + (mb - cz) * k
        }
        // smoothstep(0.70, 0.99) per channel, then the peak channel drives the
        // mix toward neutral white — the same order as amaze/final.glsl.
        fun ss(x: Double): Double {
            val t = ((x - 0.70) / (0.99 - 0.70)).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
        val wb = max(ss(r), max(ss(g), ss(b)))
        r += (cameraWhiteNormalized[0] - r) * wb
        g += (cameraWhiteNormalized[1] - g) * wb
        b += (cameraWhiteNormalized[2] - b) * wb
        // out = M * in, M mathematical row-major.
        return doubleArrayOf(
            matrix[0] * r + matrix[1] * g + matrix[2] * b,
            matrix[3] * r + matrix[4] * g + matrix[5] * b,
            matrix[6] * r + matrix[7] * g + matrix[8] * b
        )
    }
}

/**
 * Prompt 5A developer: merged camera RGB straight to scene-linear working
 * space, with no demosaic and no RAW preprocessing anywhere in the path.
 *
 * Threading mirrors [Gles31JpegOutputProcessor]: exactly one instance per EGL
 * context, used only on the thread that owns that context — in production the
 * merge thread, inside `VkRawSrProcessor.processPacked.consume` (through the
 * coordinator's image bridge) while the input textures are live. Textures are
 * context-local, so this processor must never run on the AMaZE context (or
 * vice versa).
 */
class Gles31MergedDevelopProcessor(context: Context) {
    private val shaderSource = context.applicationContext.assets.open(SHADER).bufferedReader().use {
        it.readText()
    }
    private var program = 0
    private var programThreadId: Long? = null

    /** Develops the merged frame; the RGBA16F output is live only inside [consume]. */
    fun <T> develop(
        input: MergedTextureJpegInput,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        consume: (AmazeGpuOutput) -> T
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        input.referenceMetadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        validateTextures(input)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(input.referenceMetadata),
            settings.exposureEv
        )
        // Wavelet spatial denoise retired with the AI-only DenoiseSettings migration;
        // the merged path stays denoise-free until the oil-painting fix re-tunes it.
        val denoiseOn = false
        val blendMax = 0f
        val outTexture = IntArray(1)
        GLES31.glGenTextures(1, outTexture, 0)
        check(outTexture[0] != 0) { "Could not allocate merged develop output texture" }
        val outBytes = input.crop.width.toLong() * input.crop.height * 8L
        MemoryLeakDiagnostics.glTextureAllocated(outBytes)
        try {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, outTexture[0])
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexStorage2D(
                GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, input.crop.width, input.crop.height
            )
            checkGl("merged develop output allocation")
            val prog = developProgram()
            GLES31.glUseProgram(prog)
            GLES31.glBindImageTexture(
                0, input.mergedTextureId, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA32F
            )
            GLES31.glBindImageTexture(
                1, input.effectiveCountTextureId, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F
            )
            GLES31.glBindImageTexture(
                2, outTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F
            )
            GLES31.glUniform2i(location(prog, "u_crop_offset"), input.crop.left, input.crop.top)
            GLES31.glUniform2i(location(prog, "u_crop_size"), input.crop.width, input.crop.height)
            GLES31.glUniform2i(location(prog, "u_merged_size"), input.width, input.height)
            GLES31.glUniformMatrix3fv(
                location(prog, "u_camera_to_acescg"), 1, false, transform.glslColumnMajorMatrix(), 0
            )
            GLES31.glUniform3fv(
                location(prog, "u_camera_white_normalized"), 1, transform.glslCameraWhiteNormalized(), 0
            )
            GLES31.glUniform1f(location(prog, "u_denoise_blend_max"), blendMax)
            GLES31.glUniform1f(location(prog, "u_rc_normalizer"), input.acceptedFrames.toFloat())
            GLES31.glUniform1i(location(prog, "u_denoise_enabled"), if (denoiseOn) 1 else 0)
            GLES31.glDispatchCompute(
                (input.crop.width + 7) / 8, (input.crop.height + 7) / 8, 1
            )
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            checkGl("merged develop dispatch")
            val result = consume(
                AmazeGpuOutput(outTexture[0], input.crop.width, input.crop.height, AmazeTextureFormat.RGBA16F)
            )
            Log.i(
                LOG_TAG,
                "Merged develop ${input.crop.width}x${input.crop.height} " +
                    "(crop ${input.crop} of ${input.width}x${input.height}): " +
                    "denoise=$denoiseOn policy=${transform.cameraToXyzPolicy} " +
                    "total=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            MemoryLeakDiagnostics.sample("merged-develop-complete")
            return result
        } finally {
            GLES31.glDeleteTextures(1, outTexture, 0)
            MemoryLeakDiagnostics.glTextureReleased(outBytes)
            MemoryLeakDiagnostics.sample("merged-develop-released")
        }
    }

    /** Must be called by the owning thread while its EGL context is current. */
    fun close() {
        if (program != 0) {
            check(programThreadId == Thread.currentThread().id) {
                "Merged develop program must be closed by its owning thread"
            }
            GLES31.glDeleteProgram(program)
            MemoryLeakDiagnostics.glProgramReleased()
            program = 0
            programThreadId = null
        }
    }

    private fun validateTextures(input: MergedTextureJpegInput) {
        val dims = IntArray(1)
        fun query(texture: Int, pname: Int): Int {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture)
            GLES31.glGetTexLevelParameteriv(GLES31.GL_TEXTURE_2D, 0, pname, dims, 0)
            checkGl("merged develop texture query")
            return dims[0]
        }
        check(query(input.mergedTextureId, GLES31.GL_TEXTURE_WIDTH) == input.width &&
            query(input.mergedTextureId, GLES31.GL_TEXTURE_HEIGHT) == input.height &&
            query(input.mergedTextureId, GLES31.GL_TEXTURE_INTERNAL_FORMAT) == GLES30.GL_RGBA32F) {
            "Merged texture must be a live RGBA32F ${input.width}x${input.height} texture"
        }
        check(query(input.effectiveCountTextureId, GLES31.GL_TEXTURE_WIDTH) == input.width / 2 &&
            query(input.effectiveCountTextureId, GLES31.GL_TEXTURE_HEIGHT) == input.height / 2 &&
            query(input.effectiveCountTextureId, GLES31.GL_TEXTURE_INTERNAL_FORMAT) == GLES30.GL_R32F) {
            "Effective-count texture must be a live R32F ${input.width / 2}x${input.height / 2} texture"
        }
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
    }

    private fun developProgram(): Int {
        val threadId = Thread.currentThread().id
        val previous = programThreadId
        check(previous == null || previous == threadId) {
            "Merged develop program must stay on its owning thread"
        }
        if (program == 0) {
            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            check(shader != 0) { "Could not allocate merged develop shader" }
            GLES31.glShaderSource(shader, shaderSource)
            GLES31.glCompileShader(shader)
            val status = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(shader)
                GLES31.glDeleteShader(shader)
                error("Merged develop shader compilation failed: $log")
            }
            program = GLES31.glCreateProgram()
            GLES31.glAttachShader(program, shader)
            GLES31.glLinkProgram(program)
            GLES31.glDeleteShader(shader)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(program)
                GLES31.glDeleteProgram(program)
                program = 0
                error("Merged develop shader link failed: $log")
            }
            MemoryLeakDiagnostics.glProgramAllocated()
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

    private companion object {
        const val SHADER = "shaders/merged/develop.glsl"
        const val LOG_TAG = "RawLensDevelop"
    }
}
