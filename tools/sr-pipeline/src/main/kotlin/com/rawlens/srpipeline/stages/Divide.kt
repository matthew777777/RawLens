package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.core.Image

/**
 * Robust divide-accumulate stage.
 *
 * Mirrors `divide` in `handheld_super_resolution/utils.py` (called from
 * `super_resolution.main` for the final `num /= den`): in-place
 * element-wise division with IEEE semantics (`0/0 -> NaN`, `x/0 -> Inf`
 * preserved, cf. `np.errstate(divide='ignore', invalid='ignore')`).
 * Shapes must match, cf. the Python `assert`.
 *
 * Depends on `core` ([Image]) for the channel-interleaved overload; the
 * 3D-array overload delegates to [divide] in Robustness.kt.
 */
object Divide {

    /**
     * In-place `num /= den` over `[y][x][channel]` buffers.
     */
    fun divide(num: Array<Array<FloatArray>>, den: Array<Array<FloatArray>>) {
        com.rawlens.srpipeline.stages.divide(num, den)
    }

    /**
     * In-place `num /= den` over [Image] buffers (`[height, width,
     * channels]` row-major).
     */
    fun divide(num: Image, den: Image) {
        require(num.height == den.height && num.width == den.width && num.channels == den.channels) {
            "num ${num.height}x${num.width}x${num.channels} != den ${den.height}x${den.width}x${den.channels}"
        }
        for (i in num.data.indices) {
            num.data[i] = num.data[i] / den.data[i]
        }
    }
}
