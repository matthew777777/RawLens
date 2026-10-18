package com.rawlens.srpipeline.stages

/**
 * Hessian / gradient-initialisation stage.
 *
 * Mirrors `init_ica` in `handheld_super_resolution/ICA.py`: input 2D grey
 * `[H, W]` float image plus tile size, output Sobel gradients `[H, W]` and
 * per-tile Hessian sums `[nTy][nTx][2][2]`.
 *
 * Python config contract: `init_ica(image, tile_size, config)` takes the tile
 * size from `config.alignment.tile_sizes[l]` (fine-to-coarse level `l`);
 * `config` carries no other field consumed by `init_ica`, so the Kotlin
 * entry point takes the resolved `tileSize` directly. The Sobel kernels are
 * `[-1, 0, 1]` / `[[-1], [0], [1]]` with same (zero-padded) convolution, and
 * the Hessian tiles hold `[[sum gx^2, sum gx*gy], [.., sum gy^2]]`.
 *
 * Delegates to the real implementations in Kernels.kt ([sobelGradients],
 * [computeHessian]); no logic is duplicated here.
 */
object Hessian {

    /** Gradients plus per-tile Hessians, cf. `init_ica` return triple. */
    data class IcaInit(
        val gradX: Array<FloatArray>,
        val gradY: Array<FloatArray>,
        val hessian: Array<Array<Array<FloatArray>>>,
    )

    fun initIca(image: Array<FloatArray>, tileSize: Int): IcaInit {
        requireRectImage(image, "image")
        require(tileSize >= 1) { "tileSize must be >= 1 (got $tileSize)" }
        val (gradX, gradY) = sobelGradients(image)
        val hessian = computeHessian(gradX, gradY, tileSize)
        return IcaInit(gradX, gradY, hessian)
    }
}
