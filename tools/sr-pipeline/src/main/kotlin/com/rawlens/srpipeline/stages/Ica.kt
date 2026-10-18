package com.rawlens.srpipeline.stages

/**
 * ICA refinement stage.
 *
 * Mirrors `handheld_super_resolution/ICA.py` (`align_lvl_ica` /
 * `cpu_ica`): per-tile Gauss-Newton refinement of the flow using the
 * reference gradients and Hessian covariances from [Hessian.initIca]:
 * - singular systems (`|det| < 1e-10`) leave the tile untouched;
 * - 8x8 tiles sample with clamped borders and clip tree-reduction
 *   operands (no update clipping);
 * - larger tiles sample zero outside the image and clip the update step
 *   to `+-clipRadius`.
 *
 * Python config contract: `tile_size` comes from
 * `config.alignment.tile_sizes[l]`, the radius from
 * `config.alignment.search_radii[l]` when `config.alignment.ica.clip` is
 * set (otherwise the max-int16 radius 32767), `n_iter` from
 * `config.alignment.ica.n_iter` (default 3). A 64px tile ignores clipping
 * (Python emits a warning and falls back to 32767).
 *
 * Delegates to the real implementation in Kernels.kt ([icaRefine]); no
 * logic is duplicated here.
 */
object Ica {

    /**
     * Per-level ICA dispatch, cf. `align_lvl_ica`. Updates [flow] in place.
     */
    fun alignLvl(
        ref: Array<FloatArray>,
        gradX: Array<FloatArray>,
        gradY: Array<FloatArray>,
        hessian: Array<Array<Array<FloatArray>>>,
        moving: Array<FloatArray>,
        flow: Array<Array<FloatArray>>,
        tileSize: Int,
        nIter: Int = 3,
        clip: Boolean = true,
        searchRadius: Int,
    ) {
        requireRectImage(ref, "ref")
        requireRectImage(moving, "moving")
        require(tileSize >= 1) { "tileSize must be >= 1 (got $tileSize)" }
        require(nIter >= 0) { "nIter must be >= 0 (got $nIter)" }
        val radius = when {
            tileSize == 64 -> 32767
            clip -> searchRadius
            else -> 32767
        }
        icaRefine(ref, gradX, gradY, hessian, moving, flow, nIter, radius.toFloat(), tileSize)
    }

    /**
     * Covariance initialisation, cf. `init_ica`: Sobel gradients plus
     * per-tile Hessian sums. See [Hessian.initIca].
     */
    fun init(image: Array<FloatArray>, tileSize: Int): Hessian.IcaInit {
        requireRectImage(image, "image")
        return Hessian.initIca(image, tileSize)
    }
}
