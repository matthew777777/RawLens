package com.rawlens.srpipeline.stages

/**
 * 2x2 decimation to grey stage.
 *
 * Mirrors `cpu_decimate_to_grey` in
 * `handheld_super_resolution/utils_image.py`: input 2D raw `[H, W]` float
 * image, output `[H/2, W/2]` float image where each output pixel is the mean
 * of its 2x2 block (`c/4`). Odd trailing rows/columns are ignored, matching
 * the `H//2, W//2` output shape of the Python path.
 *
 * Accumulation is done in double precision and rounded once to float,
 * mirroring the numba kernel (whose accumulator unifies to float64 before
 * the float32 store). Exact: the recorded envelope is bit-exact (1.0).
 */
object Decimate {

    fun decimateToGrey(image: Array<FloatArray>): Array<FloatArray> {
        requireRectImage(image, "image")
        val h = image.size
        val w = image[0].size
        val outH = h / 2
        val outW = w / 2
        require(outH >= 1 && outW >= 1) { "image is too small to decimate, got ${h}x$w" }
        return Array(outH) { y ->
            FloatArray(outW) { x ->
                val c = image[2 * y][2 * x].toDouble() +
                    image[2 * y][2 * x + 1].toDouble() +
                    image[2 * y + 1][2 * x].toDouble() +
                    image[2 * y + 1][2 * x + 1].toDouble()
                (c / 4.0).toFloat()
            }
        }
    }
}
