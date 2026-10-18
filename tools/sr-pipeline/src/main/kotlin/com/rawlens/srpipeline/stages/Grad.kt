package com.rawlens.srpipeline.stages

/**
 * Gradient / preprocessing stage.
 *
 * Mirrors three Python contracts:
 * - `kerngrad` cascades in `handheld_super_resolution/kernels.py`
 *   (`grad_kernel1 = [[[-0.5, 0.5]], [[0.5, 0.5]]]`,
 *   `grad_kernel2 = [[[[0.5], [0.5]]], [[[-0.5], [0.5]]]]`, default valid
 *   padding): output `[H-1][W-1][2]`.
 * - Sobel gradients from `ICA.init_ica` (`F.conv2d(..., padding='same')`).
 * - `preprocess` in `handheld_super_resolution/utils_image.py`: `sqrt` is
 *   `clamp(x, 0).sqrt()`, `gamma` is `clamp(x, 0)^(1/2.2)`, anything else
 *   raises.
 *
 * Delegates to the real implementations in Kernels.kt ([kernGradients],
 * [sobelGradients], [sqrtPreprocess], [gammaPreprocess]); no logic is
 * duplicated here.
 */
object Grad {

    /** Kernel gradients `[H-1][W-1][2]`, cf. `kerngrad` (`full_grads`). */
    fun kernelGradients(grey: Array<FloatArray>): Array<Array<FloatArray>> {
        requireRectImage(grey, "grey")
        return kernGradients(grey)
    }

    /** Sobel gradients `Pair(gradX, gradY)`, each `[H][W]`. */
    fun sobel(grey: Array<FloatArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
        requireRectImage(grey, "grey")
        return sobelGradients(grey)
    }

    /**
     * Non-linearity `sqrt` / `gamma`, cf. `utils_image.preprocess`.
     * Unknown modes throw, mirroring the Python `ValueError`.
     */
    fun preprocess(image: Array<FloatArray>, mode: String): Array<FloatArray> {
        requireRectImage(image, "image")
        return when (mode) {
            "sqrt" -> sqrtPreprocess(image)
            "gamma" -> gammaPreprocess(image)
            else -> throw IllegalArgumentException("Unknown preprocessing: $mode")
        }
    }
}
