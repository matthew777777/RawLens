// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class AmazeGpuOutput(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val internalFormat: AmazeTextureFormat = AmazeTextureFormat.RGBA16F,
    val gainmapTextureId: Int = 0
)

/** RAW plane description retained only until GLES has copied its integer sensor codes. */
data class GpuRawAmazeInput(
    val buffer: ByteBuffer,
    val layout: RawPlaneLayout,
    val crop: RawCrop,
    val normalization: RawNormalization,
    val lensShading: LensShadingModel?
) {
    val width: Int get() = crop.width
    val height: Int get() = crop.height
    val sensorCropLeft: Int get() = layout.sensorOriginX + crop.left
    val sensorCropTop: Int get() = layout.sensorOriginY + crop.top
    val pattern: BayerPattern get() = normalization.sensorPattern.shifted(sensorCropLeft, sensorCropTop)

    init {
        require(layout.pixelStride == Short.SIZE_BYTES) {
            "Direct GPU RAW upload requires a packed 16-bit pixel stride"
        }
        require(layout.rowStride % Short.SIZE_BYTES == 0) {
            "Direct GPU RAW upload requires an even byte row stride"
        }
        require(crop.left + crop.width <= layout.width && crop.top + crop.height <= layout.height)
    }
}

/**
 * Headless GLES 3.1 executor for PhotonCamera's AMaZE compute graph. The output callback runs
 * synchronously on the calling thread while the owning EGL context and RGBA16F texture are alive.
 */
class Gles31AmazeProcessor(
    context: Context,
    private val maxGpuBytes: Long = DEFAULT_MAX_GPU_BYTES
) {
    private val appContext = context.applicationContext
    // RawDevelopmentCoordinator is confined to RawCameraController's one writer thread. Keeping
    // this session there avoids recreating EGL and recompiling all thirteen AMaZE programs for
    // every JPEG, while executor-owned full-resolution textures remain per-frame.
    private var processingSession: ProcessingSession? = null
    private var processingThreadId: Long? = null

    fun probe(width: Int, height: Int): AmazeCapability = EglComputeContext().use {
        AmazePipelineContract.evaluate(queryLimits(), width, height, maxGpuBytes)
    }

    /** Must be called from the same serial worker that owns [process]. */
    fun close() {
        val session = processingSession ?: return
        check(processingThreadId == Thread.currentThread().id) {
            "AMaZE processing session must be closed by its owning worker thread"
        }
        session.egl.makeCurrent()
        session.textures.close()
        session.uploads.close()
        session.programs.close()
        session.egl.close()
        processingSession = null
        processingThreadId = null
    }

    fun <T> process(
        input: UnpackedRawCfa,
        clipPoint: Float = 1f,
        cameraToAcescgColumnMajor: FloatArray = IDENTITY_MATRIX,
        cameraWhiteNormalized: FloatArray = UNIT_WHITE,
        denoise: DenoiseSettings = DenoiseSettings(),
        noiseModel: CfaNoiseModel = CfaNoiseModel.from(null),
        fusedOutputSettings: JpegOutputSettings? = null,
        consume: (AmazeGpuOutput) -> T
    ): T {
        AmazePipelineContract.validateInput(input)
        return processInput(
            CpuAmazeInput(input), clipPoint, cameraToAcescgColumnMajor, cameraWhiteNormalized, denoise, noiseModel,
            fusedOutputSettings, consume
        )
    }

    fun <T> processRaw(
        input: GpuRawAmazeInput,
        clipPoint: Float = 1f,
        cameraToAcescgColumnMajor: FloatArray = IDENTITY_MATRIX,
        cameraWhiteNormalized: FloatArray = UNIT_WHITE,
        denoise: DenoiseSettings = DenoiseSettings(),
        noiseModel: CfaNoiseModel = CfaNoiseModel.from(null),
        fusedOutputSettings: JpegOutputSettings? = null,
        consume: (AmazeGpuOutput) -> T
    ): T {
        require(input.width >= 4 && input.height >= 4 && input.width % 2 == 0 && input.height % 2 == 0) {
            "AMaZE requires an even Bayer crop of at least 4x4 pixels"
        }
        return processInput(
            DirectRawAmazeInput(input), clipPoint, cameraToAcescgColumnMajor, cameraWhiteNormalized, denoise, noiseModel,
            fusedOutputSettings, consume
        )
    }

    private fun <T> processInput(
        input: AmazeInput,
        clipPoint: Float,
        cameraToAcescgColumnMajor: FloatArray,
        cameraWhiteNormalized: FloatArray,
        denoise: DenoiseSettings,
        noiseModel: CfaNoiseModel,
        fusedOutputSettings: JpegOutputSettings?,
        consume: (AmazeGpuOutput) -> T
    ): T {
        require(clipPoint.isFinite() && clipPoint > 0f) { "AMaZE clip point must be finite and positive" }
        require(cameraToAcescgColumnMajor.size == 9 && cameraToAcescgColumnMajor.all(Float::isFinite)) {
            "Camera-to-ACEScg matrix must contain nine finite values"
        }
        require(cameraWhiteNormalized.size == 3 &&
            cameraWhiteNormalized.all { it.isFinite() && it >= 0f } &&
            cameraWhiteNormalized.maxOrNull()!! > 0f
        ) {
            "Camera neutral white must contain three finite non-negative values with a positive peak"
        }
        val session = processingSession()
        return session.egl.run {
            makeCurrent()
            val capability = AmazePipelineContract.evaluate(
                queryLimits(), input.width, input.height, maxGpuBytes
            )
            if (capability is AmazeCapability.Unsupported) {
                throw UnsupportedOperationException(capability.reason)
            }
            val startedAt = SystemClock.elapsedRealtime()
            val hitsBefore = session.textures.hits
            val missesBefore = session.textures.misses
            val result = AmazeExecutor(
                input,
                clipPoint,
                cameraToAcescgColumnMajor,
                cameraWhiteNormalized,
                denoise,
                noiseModel,
                fusedOutputSettings?.resolvedForPlatform()?.takeIf { !denoise.enabled },
                session.programs,
                session.textures,
                session.uploads
            ).use { executor ->
                val preparedAt = SystemClock.elapsedRealtime()
                executor.run()
                checkGl("AMaZE dispatch sequence")
                val submittedAt = SystemClock.elapsedRealtime()
                // Downstream full-frame stages need only the RGBA16F result. Release the R32F
                // upload and fixed AMaZE scratch before allocating encoded output/readback.
                // GL object deletion/reuse is command-ordered, so a blocking glFinish is not
                // needed here. The final glReadPixels is the one unavoidable synchronization.
                executor.releaseIntermediates()
                val result = consume(
                    AmazeGpuOutput(
                        executor.output.id,
                        input.width,
                        input.height,
                        if (executor.fusedOutput) AmazeTextureFormat.RGBA8 else AmazeTextureFormat.RGBA16F,
                        executor.gainmapOutput?.id ?: 0
                    )
                )
                val completedAt = SystemClock.elapsedRealtime()
                Log.i(
                    LOG_TAG,
                    "AMaZE ${input.width}x${input.height}: prepareCpu=${preparedAt - startedAt}ms " +
                        "submitCpu=${submittedAt - preparedAt}ms downstream=${completedAt - submittedAt}ms " +
                        "textureHits=${session.textures.hits - hitsBefore} " +
                        "textureMisses=${session.textures.misses - missesBefore} " +
                        "retained=${session.textures.retainedBytes}B"
                )
                result
            }
            MemoryLeakDiagnostics.sample("amaze-frame-released")
            result
        }
    }

    private fun processingSession(): ProcessingSession {
        val threadId = Thread.currentThread().id
        val previousThreadId = processingThreadId
        check(previousThreadId == null || previousThreadId == threadId) {
            "AMaZE processing session must stay on its owning worker thread"
        }
        return processingSession ?: ProcessingSession(
            EglComputeContext(),
            ProgramCache(appContext),
            TexturePool(MAX_RETAINED_TEXTURE_BYTES),
            UploadBuffers()
        ).also {
            processingThreadId = threadId
            processingSession = it
        }
    }

    private fun queryLimits(): AmazeGpuLimits {
        fun integer(name: Int): Int = IntArray(1).also { GLES30.glGetIntegerv(name, it, 0) }[0]
        fun indexed(name: Int, index: Int): Int =
            IntArray(1).also { GLES30.glGetIntegeri_v(name, index, it, 0) }[0]
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        return AmazeGpuLimits(
            majorVersion = integer(GLES30.GL_MAJOR_VERSION),
            minorVersion = integer(GLES30.GL_MINOR_VERSION),
            maxTextureSize = integer(GLES20.GL_MAX_TEXTURE_SIZE),
            maxImageUnits = integer(GLES31.GL_MAX_IMAGE_UNITS),
            maxWorkGroupInvocations = integer(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS),
            maxWorkGroupSizeX = indexed(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0),
            maxWorkGroupSizeY = indexed(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1),
            halfFloatColorBuffer = "GL_EXT_color_buffer_float" in extensions ||
                "GL_EXT_color_buffer_half_float" in extensions,
            versionString = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        )
    }

    private sealed interface AmazeInput {
        val width: Int
        val height: Int
        val pattern: BayerPattern
    }

    private data class CpuAmazeInput(val cfa: UnpackedRawCfa) : AmazeInput {
        override val width: Int get() = cfa.width
        override val height: Int get() = cfa.height
        override val pattern: BayerPattern get() = cfa.pattern
    }

    private data class DirectRawAmazeInput(val raw: GpuRawAmazeInput) : AmazeInput {
        override val width: Int get() = raw.width
        override val height: Int get() = raw.height
        override val pattern: BayerPattern get() = raw.pattern
    }

    private class AmazeExecutor(
        private val input: AmazeInput,
        private val clipPoint: Float,
        private val cameraToAcescgColumnMajor: FloatArray,
        private val cameraWhiteNormalized: FloatArray,
        private val denoise: DenoiseSettings,
        private val noiseModel: CfaNoiseModel,
        private val fusedSettings: JpegOutputSettings?,
        private val programs: ProgramCache,
        private val texturePool: TexturePool,
        private val uploadBuffers: UploadBuffers
    ) : Closeable {
        private val textures = ArrayList<GlTexture>()
        private val window = AmazePipelineContract.WINDOW
        private val cfaUniform = AmazePipelineContract.cfaUniform(input.pattern)

        // RawTherapee documents AMaZE's CFA input as already white-balanced.  Feeding
        // unbalanced sensor channels makes its adaptive colour-ratio/variance tests see
        // the sensor's normal R/G/B sensitivity difference as chroma structure, which
        // shows up as magenta/yellow zippering and coloured "noise" even with denoise off.
        //
        // Normalize the balance so no channel is amplified above the normalized RAW white
        // point.  For AsShotNeutral ~= (0.50, 1.0, 0.67), this is approximately
        // (1.0, 0.50, 0.75), making a neutral CFA neutral before AMaZE without clipping.
        private val demosaicBalance: FloatArray = run {
            val safe = FloatArray(3) { cameraWhiteNormalized[it].coerceAtLeast(1e-6f) }
            val minNeutral = minOf(safe[0], safe[1], safe[2])
            FloatArray(3) { minNeutral / safe[it] }
        }

        // Pre-balancing is a change of camera-space basis D.  Compensate with M*D^-1
        // after demosaic so the final ACEScg transform remains colorimetrically identical.
        private val demosaicCameraToAcescg: FloatArray = cameraToAcescgColumnMajor.copyOf().also { m ->
            for (column in 0..2) {
                val inv = 1f / demosaicBalance[column]
                for (row in 0..2) m[column * 3 + row] *= inv
            }
        }

        // In the balanced CFA basis the measured camera neutral is achromatic by design.
        private val demosaicCameraWhiteNormalized: FloatArray = run {
            val w = FloatArray(3) { cameraWhiteNormalized[it] * demosaicBalance[it] }
            val peak = maxOf(w[0], w[1], w[2]).coerceAtLeast(1e-6f)
            FloatArray(3) { w[it] / peak }
        }

        private val inputTexture = texture(input.width, input.height, GLES30.GL_R32F).also {
            when (input) {
                is CpuAmazeInput -> it.uploadR32f(input.cfa.values, uploadBuffers)
                is DirectRawAmazeInput -> preprocessRaw(input.raw, it)
            }
        }
        private val rawOutput = if (fusedSettings == null) {
            texture(input.width, input.height, GLES30.GL_RGBA16F)
        } else null
        private val encodedOutput = if (fusedSettings != null) {
            texture(input.width, input.height, GLES30.GL_RGBA8)
        } else null
        val gainmapOutput = if (fusedSettings?.ultraHdr == true) {
            texture(ceilDiv(input.width, GAINMAP_DOWNSCALE), ceilDiv(input.height, GAINMAP_DOWNSCALE), GLES30.GL_RGBA8)
        } else null
        private var denoisedOutput: GlTexture? = null
        val fusedOutput: Boolean get() = encodedOutput != null
        val output: GlTexture get() = encodedOutput ?: denoisedOutput ?: requireNotNull(rawOutput)
        private val cfa = scalar()
        private val grad = vector()
        private val cdA = vector()
        private val cdB = vector()
        private val cd2 = vector()
        private val hvwt = scalar()
        private val nyqTest = scalar()
        private val nyq2 = scalar()
        private val hvwt2 = scalar()
        private val hvwt3 = scalar()
        private val greenD = vector()
        private val greenD2 = vector()
        private val rbpm = vector()
        private val pmrbint = vector()
        private val greenD3 = vector()
        private val dgrb01 = vector()
        private fun preprocessRaw(raw: GpuRawAmazeInput, destination: GlTexture) {
            val codes = texture(raw.width, raw.height, GLES30.GL_R16UI)
            codes.uploadRaw16(raw.buffer, raw.layout, raw.crop)
            val model = raw.lensShading
            val gainRows = model?.rows ?: 1
            val gainColumns = model?.columns ?: 1
            val gains = texture(gainColumns, gainRows, GLES30.GL_RGBA32F)
            gains.uploadRgba32f(
                model?.gains ?: floatArrayOf(1f, 1f, 1f, 1f), uploadBuffers
            )
            val localBlack = FloatArray(4) { index ->
                raw.normalization.blackAt(
                    raw.sensorCropLeft + (index and 1),
                    raw.sensorCropTop + (index shr 1)
                )
            }
            val active = model?.activeArray ?: IntRectSnapshot(0, 0, 1, 1)
            BoundProgram(programs.get("raw/preprocess.glsl")).apply {
                unsignedSampler("u_raw", codes)
                sampler("u_lens", gains)
                ivec2("u_size", raw.width, raw.height)
                ivec2("u_sensor_origin", raw.sensorCropLeft, raw.sensorCropTop)
                ivec4("u_fc", cfaUniform)
                vec4("u_black", localBlack)
                float("u_white", raw.normalization.whiteLevel)
                ivec2("u_lens_size", gainColumns, gainRows)
                ivec4("u_active", intArrayOf(active.left, active.top, active.right, active.bottom))
                integer("u_apply_lens", if (model != null && !model.alreadyApplied) 1 else 0)
                image(0, destination, GLES30.GL_R32F)
                dispatch(raw.width, raw.height)
            }
            releaseTexture(codes)
            releaseTexture(gains)
        }

        fun run() {
            val source = inputTexture
            for (tileY in 0 until ceilDiv(input.height, AmazePipelineContract.TILE)) {
                for (tileX in 0 until ceilDiv(input.width, AmazePipelineContract.TILE)) {
                    runTile(tileX * AmazePipelineContract.TILE, tileY * AmazePipelineContract.TILE, source)
                }
            }
            if (denoise.enabled) {
                // The opponent stage never reads CFA data. Retire both full-resolution R32F
                // allocations before its RGBA16F destination and tile scratch are needed.
                GLES31.glFinish()
                releaseTexture(inputTexture)
                if (source !== inputTexture) releaseTexture(source)
                denoisedOutput = texture(input.width, input.height, GLES30.GL_RGBA16F)
                runOpponentDenoise()
            }
        }

        fun releaseIntermediates() {
            textures.filter { it !== output && it !== gainmapOutput }.asReversed().forEach(::releaseTexture)
        }

        private fun runTile(originX: Int, originY: Int, source: GlTexture) {
            val tileWidth = minOf(AmazePipelineContract.TILE, input.width - originX)
            val tileHeight = minOf(AmazePipelineContract.TILE, input.height - originY)
            pass("amaze/pad.glsl") {
                sampler("u_in", source)
                ivec2("u_insize", input.width, input.height)
                ivec4("u_fc", cfaUniform)
                vec3("u_demosaic_balance", demosaicBalance)
                ivec2(
                    "u_off",
                    originX - AmazePipelineContract.BORDER - AmazePipelineContract.PAD,
                    originY - AmazePipelineContract.BORDER - AmazePipelineContract.PAD
                )
                image(0, cfa, GLES30.GL_R32F)
                dispatch(window, window)
            }
            pass("amaze/gradcd.glsl") {
                sampler("u_cfa", cfa)
                image(0, grad, GLES30.GL_RGBA32F)
                image(1, cdA, GLES30.GL_RGBA32F)
                image(2, cdB, GLES30.GL_RGBA32F)
                dispatch(window, window)
            }
            pass("amaze/bound.glsl") {
                sampler("u_cda", cdA); sampler("u_grad", grad)
                image(0, cd2, GLES30.GL_RGBA32F); dispatch(window, window)
            }
            pass("amaze/hvwt.glsl") {
                sampler("u_cd2", cd2); sampler("u_cdb", cdB); sampler("u_grad", grad)
                image(0, hvwt, GLES30.GL_R32F); image(1, nyqTest, GLES30.GL_R32F)
                dispatch(window, window)
            }
            pass("amaze/nyq2.glsl") {
                sampler("u_nyqtest", nyqTest); image(0, nyq2, GLES30.GL_R32F)
                dispatch(window, window)
            }
            pass("amaze/area.glsl") {
                sampler("u_grad", grad); sampler("u_nyq2", nyq2); sampler("u_hvwt", hvwt)
                image(0, hvwt2, GLES30.GL_R32F); dispatch(window, window)
            }
            pass("amaze/green.glsl") {
                sampler("u_grad", grad); sampler("u_hvwt", hvwt2); sampler("u_cd2", cd2)
                sampler("u_nyq2", nyq2); image(0, greenD, GLES30.GL_RGBA32F)
                image(1, hvwt3, GLES30.GL_R32F); dispatch(window, window)
            }
            pass("amaze/nyqref.glsl") {
                sampler("u_grad", grad); sampler("u_gd", greenD); sampler("u_cd2", cd2)
                sampler("u_nyq2", nyq2); image(0, greenD2, GLES30.GL_RGBA32F)
                dispatch(window, window)
            }
            pass("amaze/rbpm.glsl") {
                sampler("u_grad", grad); image(0, rbpm, GLES30.GL_RGBA32F)
                dispatch(window, window)
            }
            pass("amaze/pmrbint.glsl") {
                sampler("u_grad", grad); sampler("u_rbpm", rbpm)
                image(0, pmrbint, GLES30.GL_RGBA32F); dispatch(window, window)
            }
            pass("amaze/gcorr.glsl") {
                sampler("u_pmrbint", pmrbint); sampler("u_gd2", greenD2)
                sampler("u_hvwt", hvwt3); sampler("u_grad", grad)
                image(0, greenD3, GLES30.GL_RGBA32F); dispatch(window, window)
            }
            pass("amaze/chroma.glsl") {
                sampler("u_gd3", greenD3); image(0, dgrb01, GLES30.GL_RGBA32F)
                dispatch(window, window)
            }
            val finalAsset = if (fusedSettings == null) "amaze/final.glsl" else "amaze/final_display.glsl"
            val finalProgram = BoundProgram(programs.get(finalAsset)).apply {
                ivec2("u_size", window, window)
                ivec4("u_fc", cfaUniform)
                mat3("u_camera_to_acescg", demosaicCameraToAcescg)
                vec3("u_camera_white_normalized", demosaicCameraWhiteNormalized)
                ivec2(
                    "u_inner",
                    AmazePipelineContract.PAD + AmazePipelineContract.BORDER,
                    AmazePipelineContract.PAD + AmazePipelineContract.BORDER
                )
                ivec2("u_outsize", tileWidth, tileHeight)
                ivec2("u_outoff", originX, originY)
                sampler("u_chroma", dgrb01); sampler("u_hvwt", hvwt3)
            }
            if (fusedSettings == null) {
                finalProgram.image(0, requireNotNull(rawOutput), GLES30.GL_RGBA16F)
            } else {
                val settings = requireNotNull(fusedSettings)
                finalProgram.ivec2("u_output_size", input.width, input.height)
                finalProgram.integer("u_display_p3", if (settings.displayP3) 1 else 0)
                finalProgram.float("u_agx_purity_boost", settings.agxPurityBoost)
                finalProgram.float("u_agx_contrast", settings.agxContrast)
                finalProgram.float("u_agx_saturation", settings.agxSaturation)
                finalProgram.float("u_agx_hue_preservation", settings.agxHuePreservation)
                finalProgram.float("u_agx_shadow_ev", settings.agxShadowEv)
                finalProgram.float("u_agx_highlight_ev", settings.agxHighlightEv)
                finalProgram.float("u_agx_gamut_compression", settings.agxGamutCompression)
                finalProgram.integer("u_write_gainmap", if (gainmapOutput != null) 1 else 0)
                finalProgram.image(0, requireNotNull(encodedOutput), GLES30.GL_RGBA8)
                gainmapOutput?.let { finalProgram.image(1, it, GLES30.GL_RGBA8) }
            }
            finalProgram.dispatch(tileWidth, tileHeight)
        }

        private fun runOpponentDenoise() {
            // darktable denoise(profiled) wavelets: chroma only uses seven a-trous bands.
            // Radius at the coarsest band is 2 * 2^6 = 128 pixels, so every output tile
            // carries that exact halo and cannot show tile seams.
            val border = 128
            val tile = 512
            val scratchSize = tile + 2 * border
            // darktable performs the wavelet decomposition in 32-bit float.  Keeping the
            // recursive seven-scale pyramid in RGBA16F quantizes small shadow/chroma
            // coefficients at every pass and turns random sensor noise into structured
            // blotches/banding.  Only the AMaZE input/final scene texture remains fp16.
            val fineA = texture(scratchSize, scratchSize, GLES30.GL_RGBA32F)
            val fineB = texture(scratchSize, scratchSize, GLES30.GL_RGBA32F)
            val horizontal = texture(scratchSize, scratchSize, GLES30.GL_RGBA32F)
            val detail = texture(scratchSize, scratchSize, GLES30.GL_RGBA32F)
            val accum = texture(scratchSize, scratchSize, GLES30.GL_RGBA32F)
            val (noiseScale, noiseOffset) = opponentNoiseModel()
            for (originY in 0 until input.height step tile) {
                for (originX in 0 until input.width step tile) {
                    val outWidth = minOf(tile, input.width - originX)
                    val outHeight = minOf(tile, input.height - originY)
                    val workWidth = outWidth + 2 * border
                    val workHeight = outHeight + 2 * border
                    BoundProgram(programs.get("denoise/opponent_split.glsl")).apply {
                        imageRead(0, requireNotNull(rawOutput), GLES30.GL_RGBA16F)
                        image(1, fineA, GLES30.GL_RGBA32F)
                        image(2, accum, GLES30.GL_RGBA32F)
                        ivec2("u_size", workWidth, workHeight)
                        ivec2("u_source_size", input.width, input.height)
                        ivec2("u_source_offset", originX - border, originY - border)
                        dispatch(workWidth, workHeight)
                    }
                    var fine = fineA
                    var coarse = fineB
                    for (scale in 0 until 7) {
                        val step = 1 shl scale
                        BoundProgram(programs.get("denoise/wavelet_horizontal.glsl")).apply {
                            sampler("u_input", fine)
                            image(0, horizontal, GLES30.GL_RGBA32F)
                            ivec2("u_size", workWidth, workHeight)
                            integer("u_step", step)
                            dispatch(workWidth, workHeight)
                        }
                        BoundProgram(programs.get("denoise/wavelet_vertical_detail.glsl")).apply {
                            sampler("u_horizontal", horizontal)
                            sampler("u_fine", fine)
                            image(0, coarse, GLES30.GL_RGBA32F)
                            image(1, detail, GLES30.GL_RGBA32F)
                            ivec2("u_size", workWidth, workHeight)
                            integer("u_step", step)
                            dispatch(workWidth, workHeight)
                        }
                        BoundProgram(programs.get("denoise/wavelet_shrink.glsl")).apply {
                            sampler("u_detail", detail)
                            sampler("u_coarse", coarse)
                            imageReadWrite(0, accum, GLES30.GL_RGBA32F)
                            ivec2("u_size", workWidth, workHeight)
                            vec3("u_noise_s", noiseScale)
                            vec3("u_noise_o", noiseOffset)
                            float("u_strength", denoise.strength)
                            integer("u_scale", scale)
                            dispatch(workWidth, workHeight)
                        }
                        val swap = fine
                        fine = coarse
                        coarse = swap
                    }
                    BoundProgram(programs.get("denoise/opponent_reconstruct.glsl")).apply {
                        imageRead(0, fine, GLES30.GL_RGBA32F)
                        imageRead(1, accum, GLES30.GL_RGBA32F)
                        image(2, output, GLES30.GL_RGBA16F)
                        ivec2("u_inner_offset", border, border)
                        ivec2("u_output_offset", originX, originY)
                        ivec2("u_output_size", outWidth, outHeight)
                        dispatch(outWidth, outHeight)
                    }
                }
            }
        }

        /** Diagonal of T M diag(a,b) M' T': RAW Poisson-Gaussian noise in darktable-style Y0U0V0. */
        private fun opponentNoiseModel(): Pair<FloatArray, FloatArray> {
            val sensorScale = floatArrayOf(noiseModel.scale[0], .5f * (noiseModel.scale[1] + noiseModel.scale[2]), noiseModel.scale[3])
            val sensorOffset = floatArrayOf(noiseModel.offset[0], .5f * (noiseModel.offset[1] + noiseModel.offset[2]), noiseModel.offset[3])
            val t = arrayOf(
                floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f),
                floatArrayOf(.5f, 0f, -.5f),
                floatArrayOf(.25f, -.5f, .25f)
            )
            fun diagonal(noise: FloatArray) = FloatArray(3) { row ->
                var variance = 0f
                for (sensor in 0..2) {
                    var coefficient = 0f
                    for (rgb in 0..2) coefficient += t[row][rgb] * cameraToAcescgColumnMajor[sensor * 3 + rgb]
                    variance += coefficient * coefficient * noise[sensor]
                }
                variance.coerceAtLeast(1e-12f)
            }
            return diagonal(sensorScale) to diagonal(sensorOffset)
        }

        private inline fun pass(
            asset: String,
            block: BoundProgram.() -> Unit
        ) {
            val bound = BoundProgram(programs.get(asset))
            val uniforms = AmazePipelineContract.uniformsFor(asset)
            if (AmazeUniform.SIZE in uniforms) bound.ivec2("u_size", window, window)
            if (AmazeUniform.CFA_PHASE in uniforms) bound.ivec4("u_fc", cfaUniform)
            if (AmazeUniform.CLIP_POINT in uniforms) bound.float("u_clip", clipPoint)
            bound.block()
        }

        private fun scalar() = texture(window, window, GLES30.GL_R32F)
        private fun vector() = texture(window, window, GLES30.GL_RGBA32F)
        private fun texture(width: Int, height: Int, format: Int): GlTexture =
            texturePool.acquire(width, height, format).also(textures::add)

        private fun releaseTexture(texture: GlTexture) {
            if (textures.remove(texture)) texturePool.release(texture)
        }

        override fun close() {
            textures.asReversed().toList().forEach(::releaseTexture)
        }

        private inner class BoundProgram(private val id: Int) {
            private var textureUnit = 0

            init {
                GLES31.glUseProgram(id)
            }

            fun sampler(name: String, texture: GlTexture) {
                val unit = textureUnit++
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit)
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture.id)
                GLES31.glUniform1i(location(name), unit)
            }

            fun unsignedSampler(name: String, texture: GlTexture) = sampler(name, texture)

            fun image(unit: Int, texture: GlTexture, format: Int) {
                GLES31.glBindImageTexture(unit, texture.id, 0, false, 0, GLES31.GL_WRITE_ONLY, format)
            }

            fun imageRead(unit: Int, texture: GlTexture, format: Int) {
                GLES31.glBindImageTexture(unit, texture.id, 0, false, 0, GLES31.GL_READ_ONLY, format)
            }

            fun imageReadWrite(unit: Int, texture: GlTexture, format: Int) {
                GLES31.glBindImageTexture(unit, texture.id, 0, false, 0, GLES31.GL_READ_WRITE, format)
            }

            fun ivec2(name: String, x: Int, y: Int) = GLES31.glUniform2i(location(name), x, y)
            fun ivec4(name: String, values: IntArray) =
                GLES31.glUniform4i(location(name), values[0], values[1], values[2], values[3])
            fun float(name: String, value: Float) = GLES31.glUniform1f(location(name), value)
            fun integer(name: String, value: Int) = GLES31.glUniform1i(location(name), value)
            fun vec4(name: String, values: FloatArray) =
                GLES31.glUniform4f(location(name), values[0], values[1], values[2], values[3])
            fun vec3(name: String, values: FloatArray) =
                GLES31.glUniform3f(location(name), values[0], values[1], values[2])
            fun mat3(name: String, columnMajor: FloatArray) =
                GLES31.glUniformMatrix3fv(location(name), 1, false, columnMajor, 0)

            fun dispatch(width: Int, height: Int) {
                GLES31.glDispatchCompute(
                    ceilDiv(width, AmazePipelineContract.LOCAL_SIZE_X),
                    ceilDiv(height, AmazePipelineContract.LOCAL_SIZE_Y),
                    1
                )
                GLES31.glMemoryBarrier(
                    GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                )
            }

            private fun location(name: String): Int = GLES31.glGetUniformLocation(id, name).also {
                check(it >= 0) { "AMaZE shader uniform '$name' is missing" }
            }
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }

    internal class ProgramCache(private val context: Context) : Closeable {
        private val programs = LinkedHashMap<String, Int>()
        private val common by lazy { read("shaders/utils/import_amaze.glsl") }
        private val agxOutputCommon by lazy {
            val source = read("shaders/display/agx_srgb8.glsl")
            source.substring(
                source.indexOf("uniform highp int u_display_p3;").also { check(it >= 0) },
                source.indexOf("void main()").also { check(it >= 0) }
            )
        }

        fun get(asset: String): Int = programs.getOrPut(asset) {
            val body = read("shaders/$asset")
                .replace("#import amaze", common)
                .replace("#import agx_output", agxOutputCommon)
            compile(if (body.startsWith("#version")) body else "#version 310 es\n$body", asset)
        }.also(GLES31::glUseProgram)

        private fun read(path: String): String =
            context.assets.open(path).bufferedReader().use { it.readText() }

        private fun compile(source: String, name: String): Int {
            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            check(shader != 0) { "Could not allocate compute shader for $name" }
            GLES31.glShaderSource(shader, source)
            GLES31.glCompileShader(shader)
            val status = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(shader)
                GLES31.glDeleteShader(shader)
                error("AMaZE shader compile failed for $name: $log")
            }
            val program = GLES31.glCreateProgram()
            GLES31.glAttachShader(program, shader)
            GLES31.glLinkProgram(program)
            GLES31.glDeleteShader(shader)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(program)
                GLES31.glDeleteProgram(program)
                error("AMaZE shader link failed for $name: $log")
            }
            MemoryLeakDiagnostics.glProgramAllocated()
            return program
        }

        override fun close() {
            programs.values.forEach {
                GLES31.glDeleteProgram(it)
                MemoryLeakDiagnostics.glProgramReleased()
            }
            programs.clear()
        }
    }

    internal class GlTexture(
        val width: Int,
        val height: Int,
        val internalFormat: Int
    ) : Closeable {
        val byteSize: Long = width.toLong() * height * when (internalFormat) {
            GLES30.GL_R32F -> 4L
            GLES30.GL_RGBA16F -> 8L
            GLES30.GL_RGBA32F -> 16L
            GLES30.GL_R16UI -> 2L
            GLES30.GL_RGBA8 -> 4L
            else -> error("Unsupported pooled texture format 0x${internalFormat.toString(16)}")
        }
        val id: Int = IntArray(1).also { GLES31.glGenTextures(1, it, 0) }[0]
        private var closed = false

        init {
            check(id != 0) { "Could not allocate AMaZE texture" }
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, internalFormat, width, height)
            checkGl("texture allocation")
            MemoryLeakDiagnostics.glTextureAllocated(byteSize)
        }

        fun uploadR32f(values: FloatArray, uploads: UploadBuffers) {
            require(internalFormat == GLES30.GL_R32F && values.size == width * height)
            val buffer = uploads.floats(values.size)
            buffer.put(values).flip()
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
            GLES31.glTexSubImage2D(
                GLES31.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES31.GL_RED, GLES31.GL_FLOAT, buffer
            )
            checkGl("CFA upload")
        }

        fun uploadRgba32f(values: FloatArray, uploads: UploadBuffers) {
            require(internalFormat == GLES30.GL_RGBA32F && values.size == width * height * 4)
            val buffer = uploads.floats(values.size)
            buffer.put(values).flip()
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
            GLES31.glTexSubImage2D(
                GLES31.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES31.GL_RGBA, GLES31.GL_FLOAT, buffer
            )
            checkGl("lens-shading map upload")
        }

        fun uploadRaw16(source: ByteBuffer, layout: RawPlaneLayout, crop: RawCrop) {
            require(internalFormat == GLES30.GL_R16UI && width == crop.width && height == crop.height)
            val input = source.duplicate().order(ByteOrder.nativeOrder())
            val origin = input.position()
            val offset = origin + crop.top * layout.rowStride + crop.left * layout.pixelStride
            val lastByte = offset.toLong() +
                (crop.height - 1).toLong() * layout.rowStride +
                (crop.width - 1).toLong() * layout.pixelStride + Short.SIZE_BYTES
            require(lastByte <= input.limit().toLong()) { "RAW_SENSOR buffer is truncated" }
            input.position(offset)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
            GLES31.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
            GLES31.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, layout.rowStride / Short.SIZE_BYTES)
            try {
                GLES31.glTexSubImage2D(
                    GLES31.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, input
                )
            } finally {
                GLES31.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
                GLES31.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            }
            checkGl("packed RAW upload")
        }

        override fun close() {
            if (closed) return
            closed = true
            GLES31.glDeleteTextures(1, intArrayOf(id), 0)
            MemoryLeakDiagnostics.glTextureReleased(byteSize)
        }
    }

    /** Retains only AMaZE's resolution-independent tile scratch; full-frame textures never linger. */
    private class TexturePool(private val maxRetainedBytes: Long) : Closeable {
        private val available = ArrayList<GlTexture>()
        var retainedBytes: Long = 0L
            private set
        var hits: Long = 0L
            private set
        var misses: Long = 0L
            private set

        fun acquire(width: Int, height: Int, format: Int): GlTexture {
            val index = available.indexOfFirst {
                it.width == width && it.height == height && it.internalFormat == format
            }
            if (index >= 0) {
                hits++
                return available.removeAt(index).also { retainedBytes -= it.byteSize }
            }
            misses++
            return GlTexture(width, height, format)
        }

        fun release(texture: GlTexture) {
            val fixedTileScratch = texture.width == AmazePipelineContract.WINDOW &&
                texture.height == AmazePipelineContract.WINDOW &&
                (texture.internalFormat == GLES30.GL_R32F ||
                    texture.internalFormat == GLES30.GL_RGBA32F)
            if (fixedTileScratch && texture.byteSize <= maxRetainedBytes - retainedBytes) {
                available += texture
                retainedBytes += texture.byteSize
            } else {
                texture.close()
            }
        }

        override fun close() {
            available.asReversed().forEach(GlTexture::close)
            available.clear()
            retainedBytes = 0L
        }
    }

    /** One direct client-upload allocation, grown only when a CPU fallback requires more space. */
    internal class UploadBuffers : Closeable {
        private var storage: ByteBuffer? = null

        fun floats(count: Int): FloatBuffer {
            val required = count * Float.SIZE_BYTES
            var bytes = storage
            if (bytes == null || bytes.capacity() < required) {
                bytes = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
                storage = bytes
            }
            bytes.clear()
            bytes.limit(required)
            return bytes.asFloatBuffer().apply { limit(count) }
        }

        override fun close() {
            storage = null
        }
    }

    internal class EglComputeContext : Closeable {
        private val display: EGLDisplay
        private val surface: EGLSurface
        private val context: EGLContext

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "EGL initialization failed" }
            val config = chooseConfig(display)
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "Could not create GLES 3 context" }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "Could not create EGL pbuffer" }
            makeCurrent()
            MemoryLeakDiagnostics.eglContextAllocated()
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "Could not make AMaZE EGL context current"
            }
        }

        private fun chooseConfig(display: EGLDisplay): EGLConfig {
            val attributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "No EGL config supports GLES 3 pbuffer processing"
            }
            return checkNotNull(configs[0])
        }

        override fun close() {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            MemoryLeakDiagnostics.eglContextReleased()
        }

        private companion object {
            const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        }
    }

    private companion object {
        const val DEFAULT_MAX_GPU_BYTES = 768L * 1024L * 1024L
        // Six R32F plus ten RGBA32F 1120² scratch textures total ~220 MB. They are released
        // when the camera closes/backgrounds; full-resolution sensor/output textures are excluded.
        const val MAX_RETAINED_TEXTURE_BYTES = 256L * 1024L * 1024L
        const val GAINMAP_DOWNSCALE = 4
        const val LOG_TAG = "RawLensDevelop"
        val UNIT_WHITE = floatArrayOf(1f, 1f, 1f)
        val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        fun checkGl(operation: String) {
            val error = GLES31.glGetError()
            check(error == GLES31.GL_NO_ERROR) {
                "$operation failed with GLES error 0x${error.toString(16)}"
            }
        }
    }

    private class ProcessingSession(
        val egl: EglComputeContext,
        val programs: ProgramCache,
        val textures: TexturePool,
        val uploads: UploadBuffers
    )
}
