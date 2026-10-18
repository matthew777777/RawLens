package com.rawlens.srpipeline.stages

/**
 * End-to-end pipeline wiring (Algorithm 1).
 *
 * Mirrors `main` in `handheld_super_resolution/super_resolution.py`:
 * 1. reference grey (`compute_grey_images`) + reference alignment
 *    initialisation (gaussian pyramid, per-level ICA gradients/Hessians);
 * 2. reference local stats (`init_robustness`) when robustness is enabled;
 * 3. reference kernels + merge of the reference frame (zero flow, `r = 1`);
 * 4. per comparison frame: grey -> align -> robustness -> kernels -> merge;
 * 5. final `divide(num, den)` in place.
 *
 * Python config contract ([PipelineConfig]): `scale` (`config.scale`),
 * `bayer` (`config.mode == 'bayer'`), `greyMethod`
 * (`config.alignment.grey_method`), `factors` / `tileSizes`
 * (`config.alignment`, fine-to-coarse), `kernelParams`
 * (`config.merging`), `robustnessParams` + `sigmaSqCurve` / `dSqCurve`
 * (`config.robustness` / `config.noise_model`), `robustnessEnabled`
 * (`config.robustness.enabled`).
 *
 * Every stage that exists in this repo is called for real ([Grey],
 * [Resample], [Hessian], [Gat] kernel preprocessing, [computeGuideImage] /
 * [computeLocalStats], [Grad], [eigen2x2]/[computeK], [merge],
 * [computeRobustness], [divide], and the coarse-to-fine alignment loop
 * ([initAlignment]/[alignLevels]: moving pyramid, per-level L1/L2 + ICA,
 * flow upscaling)). Pass a custom [alignFrame] to override the default
 * loop wiring; the reference-frame merge and the final divide always run.
 */
data class PipelineConfig(
    val scale: Float = 2f,
    val bayer: Boolean = true,
    val greyMethod: String = "FFT",
    val factors: List<Int> = listOf(1, 2, 4),
    /** Fine-to-coarse tile sizes, cf. `config.alignment.tile_sizes`. */
    val tileSizes: List<Int> = listOf(16, 16, 16),
    /**
     * Fine-to-coarse search radii, cf. `config.alignment.search_radii`.
     * Null selects the Python-pattern default (`1` finest, `4` elsewhere).
     */
    val searchRadii: List<Int>? = null,
    val kernelParams: KernelParams = KernelParams(),
    /**
     * Kernel gradient preprocessing, cf. `config.merging.preprocessing`
     * (`"gat"`, `"sqrt"`/`"gamma"`, or null for none). `"gat"` uses
     * [kernelAlpha]/[kernelBeta], cf. `config.noise_model`.
     */
    val kernelPreprocessing: String? = "gat",
    val kernelAlpha: FloatArray = floatArrayOf(1e-4f, 1e-4f, 1e-4f, 1e-4f),
    val kernelBeta: FloatArray = floatArrayOf(1e-6f, 1e-6f, 1e-6f, 1e-6f),
    val robustnessParams: RobustnessParams = RobustnessParams(),
    val robustnessEnabled: Boolean = true,
    val sigmaSqCurve: FloatArray = floatArrayOf(0f, 0f),
    val dSqCurve: FloatArray = floatArrayOf(0f, 0f),
    /** ICA iterations, cf. `config.alignment.ica.n_iter`. */
    val icaIterations: Int = 3,
    /** Clip the ICA step to the search radius, cf. `config.alignment.ica.clip`. */
    val icaClip: Boolean = true,
    /** Flow upscaling mode, cf. `config.alignment.flow_upscale_mode`. */
    val flowUpscaleMode: String = "bilinear",
    /**
     * Non-linearity applied to the *reference* pyramid only, cf.
     * `config.alignment.preprocessing` (null by default, mirroring Python).
     */
    val alignmentPreprocessing: String? = null,
    /**
     * Memory-tight host discipline for the 512 MB on-device ceiling:
     * collects after every merged frame (all per-frame transients are
     * dead then), so GC timing cannot decide survival at the ragged
     * edge. Values-identical (collection changes no values); desktop
     * defaults to false (allocation rate is not the binding constraint
     * there, wall time is).
     */
    val memoryTight: Boolean = false,
)

object EndToEnd {

    /**
     * Loop config for [config], cf. `config.alignment` (fine-to-coarse).
     * A null [PipelineConfig.searchRadii] selects the Python-pattern
     * default (`1` finest, `4` elsewhere); metrics default to `L1` finest
     * then `L2`, cf. `config.alignment.block_matching.metrics`.
     */
    fun loopConfig(config: PipelineConfig): AlignLoopConfig {
        val nLevels = config.factors.size
        val radii = config.searchRadii
            ?: List(nLevels) { if (it == 0) 1 else 4 }
        require(radii.size == nLevels) {
            "searchRadii must hold $nLevels levels (got ${radii.size})"
        }
        return AlignLoopConfig(
            factors = config.factors,
            tileSizes = config.tileSizes,
            searchRadii = radii,
            nIter = config.icaIterations,
            icaClip = config.icaClip,
            flowUpscaleMode = config.flowUpscaleMode,
            preprocessing = config.alignmentPreprocessing,
        )
    }

    /**
     * Runs the burst pipeline.
     *
     * @param reference reference raw frame `[H][W]` (`J_1`).
     * @param comparisons remaining burst frames (`J_2..J_N`) as decode
     *   thunks, consumed once in order (same bytes and order as an
     *   upfront list; only one comparison is ever live).
     * @param alignFrame per-frame alignment (`frameGrey -> flow`); defaults
     * to the coarse-to-fine loop ([alignLevels]) over the reference
     * initialisation below, cf. `alignment.align`.
     * @return merged RGB image `[H*scale][W*scale][3]` after `divide`.
     */
    fun run(
        reference: Array<FloatArray>,
        comparisons: List<() -> Array<FloatArray>>,
        config: PipelineConfig = PipelineConfig(),
        alignFrame: ((frameGrey: Array<FloatArray>) -> Array<Array<FloatArray>>)? = null,
    ): Array<Array<FloatArray>> {
        requireRectImage(reference, "reference")
        require(config.factors.size == config.tileSizes.size) {
            "factors and tileSizes must match level counts " +
                "(got ${config.factors.size} vs ${config.tileSizes.size})"
        }
        val tile = config.tileSizes.first()
        val loop = loopConfig(config)

        // 1. Reference grey + alignment initialisation (circular pad,
        //    gaussian pyramid with preprocessing, per-level ICA
        //    gradients/Hessians plus tiled spectra), cf. `init_alignment`.
        val refGrey = if (config.bayer) Grey.computeGreyImages(reference, config.greyMethod) else reference
        val refInit = initAlignment(refGrey, loop)
        val finestTiles = refInit.hessians.last()
        val align = alignFrame ?: { frameGrey -> alignLevels(refInit, frameGrey, loop) }

        // 2. Reference local stats.
        val (refMeans, refVars) = if (config.bayer) {
            computeLocalStats(computeGuideImage(reference))
        } else {
            computeLocalStats(arrayOf(reference))
        }

        // 3. Reference kernels + merge (zero flow, r = 1).
        val outH = (reference.size * config.scale).toInt()
        val outW = (reference[0].size * config.scale).toInt()
        val num = Array(outH) { Array(outW) { FloatArray(3) } }
        val den = Array(outH) { Array(outW) { FloatArray(3) } }
        val zeroFlow = Array(finestTiles.size) { Array(finestTiles[0].size) { floatArrayOf(0f, 0f) } }
        val onesR = Array(reference.size) { FloatArray(reference[0].size) { 1f } }
        merge(reference, zeroFlow, frameKernels(reference, config), onesR, num, den, config.bayer, config.scale, tile)

        // 4. Per comparison frame (decoded just in time).
        for (frameThunk in comparisons) {
            val frame = frameThunk()
            val frameGrey = if (config.bayer) Grey.computeGreyImages(frame, config.greyMethod) else frame
            val flow = align(frameGrey)
            val robustness = if (config.robustnessEnabled) {
                computeRobustness(
                    frame, refMeans, refVars, flow,
                    config.sigmaSqCurve, config.dSqCurve,
                    tile, config.bayer, config.robustnessParams,
                )
            } else {
                Array(frame.size) { FloatArray(frame[0].size) { 1f } }
            }
            merge(frame, flow, frameKernels(frame, config), robustness, num, den, config.bayer, config.scale, tile)
        }

        // 5. Normalise in place.
        divide(num, den)
        return num
    }

    /**
     * Per-frame kernel covariances, cf. `estimate_kernels` /
     * `cpu_estimate_kernel`: [PipelineConfig.kernelPreprocessing] first
     * (`gat` by default, cf. `config.merging.preprocessing`), decimating
     * grey in bayer mode, `kerngrad` gradients, then one covariance per
     * *grey* pixel (`[gH][gW][2][2]`) from the 2x2 structure-tensor window
     * into `full_grads` (partial windows at the borders, `iso` writes
     * `diag(kDetail)`).
     *
     * Note: this loops over grey pixels like the Python stage; the existing
     * [estimateKernelCovariances] covers `full_grads` granularity instead.
     */
    fun frameKernels(frame: Array<FloatArray>, config: PipelineConfig): Array<Array<Array<FloatArray>>> {
        requireRectImage(frame, "frame")
        val pre = when (config.kernelPreprocessing) {
            null -> frame
            "gat" -> Gat.gat(frame, config.kernelAlpha, config.kernelBeta)
            "sqrt", "gamma" -> Grad.preprocess(frame, config.kernelPreprocessing)
            else -> throw IllegalArgumentException(
                "Unknown kernel preprocessing: ${config.kernelPreprocessing}",
            )
        }
        val kernelGrey = if (config.bayer) Decimate.decimateToGrey(pre) else pre
        val params = config.kernelParams
        if (params.iso) {
            return Array(kernelGrey.size) { Array(kernelGrey[0].size) {
                arrayOf(floatArrayOf(params.kDetail, 0f), floatArrayOf(0f, params.kDetail))
            } }
        }
        val fullGrads = Grad.kernelGradients(kernelGrey)
        val gh = kernelGrey.size
        val gw = kernelGrey[0].size
        val fh = fullGrads.size
        val fw = fullGrads[0].size
        return Array(gh) { py ->
            Array(gw) { px ->
                var s00 = 0f
                var s01 = 0f
                var s11 = 0f
                for (i in 0..1) {
                    for (j in 0..1) {
                        val y = py - 1 + i
                        val x = px - 1 + j
                        if (y in 0 until fh && x in 0 until fw) {
                            val gx = fullGrads[y][x][0]
                            val gy = fullGrads[y][x][1]
                            s00 += gx * gx
                            s01 += gx * gy
                            s11 += gy * gy
                        }
                    }
                }
                val eig = eigen2x2(s00, s01, s11)
                val (k1, k2) = computeK(eig.l1, eig.l2, params)
                val k1sq = k1 * k1
                val k2sq = k2 * k2
                val e1 = eig.e1
                val e2 = eig.e2
                arrayOf(
                    floatArrayOf(k1sq * e1[0] * e1[0] + k2sq * e2[0] * e2[0], k1sq * e1[0] * e1[1] + k2sq * e2[0] * e2[1]),
                    floatArrayOf(k1sq * e1[0] * e1[1] + k2sq * e2[0] * e2[1], k1sq * e1[1] * e1[1] + k2sq * e2[1] * e2[1]),
                )
            }
        }
    }
}
