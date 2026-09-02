// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

enum class AmazeTextureFormat(val bytesPerPixel: Int) {
    R32F(4),
    RGBA16F(8),
    RGBA8(4)
}

data class AmazePass(
    val shader: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val uniforms: Set<AmazeUniform>
)

enum class AmazeUniform { SIZE, CFA_PHASE, CLIP_POINT }

data class AmazeGpuLimits(
    val majorVersion: Int,
    val minorVersion: Int,
    val maxTextureSize: Int,
    val maxImageUnits: Int,
    val maxWorkGroupInvocations: Int,
    val maxWorkGroupSizeX: Int,
    val maxWorkGroupSizeY: Int,
    val halfFloatColorBuffer: Boolean,
    val versionString: String
)

sealed interface AmazeCapability {
    data class Supported(val limits: AmazeGpuLimits, val estimatedGpuBytes: Long) : AmazeCapability
    data class Unsupported(val reason: String) : AmazeCapability
}

/** Exact pass/resource contract of PhotonCamera's verified GLES 3.1 AMaZE node. */
object AmazePipelineContract {
    const val LOCAL_SIZE_X = 8
    const val LOCAL_SIZE_Y = 8
    const val PAD = 16
    const val BORDER = 32
    const val TILE = 1024
    const val WINDOW = TILE + 2 * BORDER + 2 * PAD
    const val SCALAR_SCRATCH_TEXTURES = 6
    const val VECTOR_SCRATCH_TEXTURES = 10

    val passes = listOf(
        AmazePass("amaze/pad.glsl", listOf("input"), listOf("cfa"), uniforms(AmazeUniform.SIZE)),
        AmazePass(
            "amaze/gradcd.glsl", listOf("cfa"), listOf("grad", "cdA", "cdB"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/bound.glsl", listOf("cdA", "grad"), listOf("cd2"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/hvwt.glsl", listOf("cd2", "cdB", "grad"), listOf("hvwt", "nyqTest"),
            uniforms(AmazeUniform.SIZE)
        ),
        AmazePass(
            "amaze/nyq2.glsl", listOf("nyqTest"), listOf("nyq2"),
            uniforms(AmazeUniform.SIZE)
        ),
        AmazePass(
            "amaze/area.glsl", listOf("grad", "nyq2", "hvwt"), listOf("hvwt2"),
            uniforms(AmazeUniform.SIZE)
        ),
        AmazePass(
            "amaze/green.glsl", listOf("grad", "hvwt2", "cd2", "nyq2"),
            listOf("greenD", "hvwt3"), uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/nyqref.glsl", listOf("grad", "greenD", "cd2", "nyq2"),
            listOf("greenD2"), uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/rbpm.glsl", listOf("grad"), listOf("rbpm"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/pmrbint.glsl", listOf("grad", "rbpm"), listOf("pmrbint"),
            uniforms(AmazeUniform.SIZE)
        ),
        AmazePass(
            "amaze/gcorr.glsl", listOf("pmrbint", "greenD2", "hvwt3", "grad"),
            listOf("greenD3"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/chroma.glsl", listOf("greenD3"), listOf("dgrb01"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/final.glsl", listOf("dgrb01", "hvwt3"), listOf("output"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        )
    )

    private val passesByShader = passes.associateBy(AmazePass::shader)

    fun uniformsFor(shader: String): Set<AmazeUniform> =
        passesByShader[shader]?.uniforms ?: error("Unknown AMaZE pass $shader")

    private fun uniforms(vararg values: AmazeUniform): Set<AmazeUniform> = values.toSet()

    fun validateInput(input: UnpackedRawCfa) {
        input.requireAmazeCompatible()
        require(input.values.size == input.width * input.height) { "CFA buffer size mismatch" }
    }

    fun estimatedGpuBytes(width: Int, height: Int): Long {
        require(width > 0 && height > 0)
        val windowPixels = WINDOW.toLong() * WINDOW
        val scratch = windowPixels * (
            SCALAR_SCRATCH_TEXTURES * AmazeTextureFormat.R32F.bytesPerPixel +
                VECTOR_SCRATCH_TEXTURES * AmazeTextureFormat.RGBA16F.bytesPerPixel
            )
        val fullFrame = width.toLong() * height * (
            AmazeTextureFormat.R32F.bytesPerPixel + AmazeTextureFormat.RGBA16F.bytesPerPixel
            )
        return scratch + fullFrame
    }

    fun evaluate(
        limits: AmazeGpuLimits,
        width: Int,
        height: Int,
        maxGpuBytes: Long = Long.MAX_VALUE
    ): AmazeCapability {
        val estimatedBytes = estimatedGpuBytes(width, height)
        val reason = when {
            limits.majorVersion < 3 || limits.majorVersion == 3 && limits.minorVersion < 1 ->
                "OpenGL ES 3.1 compute shaders are required (${limits.versionString})"
            limits.maxTextureSize < maxOf(WINDOW, width, height) ->
                "GL_MAX_TEXTURE_SIZE ${limits.maxTextureSize} cannot hold ${width}x$height AMaZE resources"
            limits.maxImageUnits < 3 -> "AMaZE requires at least three compute image units"
            limits.maxWorkGroupInvocations < LOCAL_SIZE_X * LOCAL_SIZE_Y ->
                "AMaZE requires 64 compute invocations per workgroup"
            limits.maxWorkGroupSizeX < LOCAL_SIZE_X || limits.maxWorkGroupSizeY < LOCAL_SIZE_Y ->
                "AMaZE requires an 8x8 compute workgroup"
            !limits.halfFloatColorBuffer ->
                "RGBA16F framebuffer support is required for downstream/readback compatibility"
            estimatedBytes > maxGpuBytes ->
                "AMaZE needs approximately $estimatedBytes GPU bytes, above budget $maxGpuBytes"
            else -> null
        }
        return if (reason == null) AmazeCapability.Supported(limits, estimatedBytes)
        else AmazeCapability.Unsupported(reason)
    }

    fun cfaUniform(pattern: BayerPattern): IntArray = IntArray(4) { index ->
        when (pattern.colorAt(index and 1, index shr 1)) {
            CfaColor.RED -> 0
            CfaColor.GREEN -> 1
            CfaColor.BLUE -> 2
        }
    }
}
