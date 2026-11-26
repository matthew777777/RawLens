// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

enum class AmazeTextureFormat(val bytesPerPixel: Int) {
    R32F(4),
    RGBA16F(8),
    RGBA32F(16),
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

/** Pass/resource contract for the GLES 3.1 AMaZE port; not a claim of RawTherapee parity. */
object AmazePipelineContract {
    const val LOCAL_SIZE_X = 8
    const val LOCAL_SIZE_Y = 8
    const val PAD = 16
    const val BORDER = 32
    const val TILE = 1024
    const val WINDOW = TILE + 2 * BORDER + 2 * PAD
    /** Live scratch per window pixel: cfa, 4 full vec4 slots, 3 half-width scalars, 2 half-width vec4. */
    const val SCRATCH_BYTES_PER_PIXEL = 4 + 4 * 16 + 3 * 4 / 2 + 2 * 16 / 2

    val passes = listOf(
        AmazePass("amaze/pad.glsl", listOf("input"), listOf("cfa"), uniforms(AmazeUniform.SIZE)),
        AmazePass(
            "amaze/gradcd.glsl", listOf("cfa"), listOf("grad", "cdA", "cdB"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/bound.glsl", listOf("cdA", "cdB", "cfa"), listOf("cd2"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/hvwt.glsl", listOf("cd2", "grad"), listOf("hvwt", "nyqTest"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/nyq2.glsl", listOf("nyqTest"), listOf("nyq2"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/area.glsl", listOf("cfa", "nyq2", "hvwt"), listOf("hvwt2"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/green.glsl", listOf("cfa", "hvwt2", "cd2", "nyq2"),
            listOf("greenD", "hvwt3"), uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/nyqref.glsl", listOf("cfa", "greenD", "cd2", "nyq2"),
            listOf("greenD2"), uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/rbpm.glsl", listOf("cfa"), listOf("rbpm"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/pmrbint.glsl", listOf("cfa", "rbpm"), listOf("pmrbint"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/gcorr.glsl", listOf("pmrbint", "greenD2", "hvwt3", "grad", "cfa"),
            listOf("greenD3"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE, AmazeUniform.CLIP_POINT)
        ),
        AmazePass(
            "amaze/chroma.glsl", listOf("greenD3"), listOf("dgrb01"),
            uniforms(AmazeUniform.SIZE, AmazeUniform.CFA_PHASE)
        ),
        AmazePass(
            "amaze/final.glsl", listOf("dgrb01", "hvwt3"), listOf("output"),
            uniforms(AmazeUniform.CFA_PHASE)
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

    fun estimatedGpuBytes(width: Int, height: Int, fusedOutput: Boolean = false, gainmap: Boolean = false): Long {
        require(width > 0 && height > 0)
        val windowPixels = WINDOW.toLong() * WINDOW
        val scratch = windowPixels * SCRATCH_BYTES_PER_PIXEL
        val fullFrame = width.toLong() * height * (
            AmazeTextureFormat.R32F.bytesPerPixel +
                (if (fusedOutput) AmazeTextureFormat.RGBA8 else AmazeTextureFormat.RGBA16F).bytesPerPixel
            )
        return scratch + fullFrame + if (fusedOutput && gainmap)
            ((width.toLong() + 3) / 4) * ((height.toLong() + 3) / 4) * 4 else 0
    }

    fun evaluate(
        limits: AmazeGpuLimits,
        width: Int,
        height: Int,
        maxGpuBytes: Long = Long.MAX_VALUE,
        fusedOutput: Boolean = false,
        gainmap: Boolean = false
    ): AmazeCapability {
        val estimatedBytes = estimatedGpuBytes(width, height, fusedOutput, gainmap)
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

    /**
     * AMaZE highlight-guard threshold in the attenuate-only balanced working
     * basis (`pad.glsl` multiplies the CFA by [demosaicBalance], whose largest
     * gain is 1). RawTherapee compares its interpolations against
     * `clip_pt = 1 / initialGain` — the achromatic neutral level at
     * first-channel clip — and this is the same quantity here: a neutral pixel
     * at sensor white reads [demosaicBalance] minimum in every channel, so the
     * guards (gradcd ratio-to-HA fallback, bound/gcorr green clamps, rbpm R/B
     * clamps) must compare against `clipPoint * min(demosaicBalance)`.
     * Binding the unbalanced sensor white (1.0) instead leaves the guards
     * ~1/min-gain stops late, so ratio interpolation runs through
     * single-channel clipping and its chroma overshoot survives to white
     * balance as a purple highlight halo.
     */
    fun balancedClipPoint(clipPoint: Float, demosaicBalance: FloatArray): Float {
        require(clipPoint.isFinite() && clipPoint > 0f) {
            "AMaZE clip point must be finite and positive"
        }
        require(demosaicBalance.size == 3 &&
            demosaicBalance.all { it.isFinite() && it > 0f }
        ) {
            "AMaZE demosaic balance must contain three finite positive gains"
        }
        return clipPoint * demosaicBalance.minOrNull()!!
    }
}
