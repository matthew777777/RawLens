package com.rawlens.srpipeline.stages

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

// Stage: kernel-covariance estimation (Algorithm 5) + ICA refinement +
// Hessian/gradient pre-ops + resampling + flow-spread (smap) helpers.
//
// Mirrors:
//   - `kernels.py`: `estimate_kernels` preprocessing contract,
//     `kerngrad` cascades, `cpu_estimate_kernel`, `compute_k`,
//     `hard_threshold`, `linear`
//   - `ICA.py`: `init_ica` (Sobel grads + `cpu_compute_hessian`),
//     `align_lvl_ica` / `cpu_ica` (T8 vs larger-tile semantics, det skip,
//     radius clipping)
//   - `linalg.py`: `get_eigen_elmts_2x2` (values sorted by |.| desc)
//   - `utils_image.preprocess` (`sqrt` / `gamma`), `downsample` factor-1
//     passthrough, `compute_grey_images` decimating mean, `alignment`
//     flow upscaling
//   - `robustness.compute_s` / `cpu_compute_s` (smap helper consumed by
//     [computeRobustness][com.rawlens.srpipeline.stages.computeRobustness])
//
// See Align.kt for the `core.*`-absent note, which applies here too:
// grey-FFT / GAT preprocessing live in core and are represented only by
// their contracts ([sqrtPreprocess]/[gammaPreprocess] cover the
// non-linearity branch).

/** Kernel selection law, cf. `config.merging.selection_law`. */
enum class SelectionLaw { HARD_THRESHOLD, LINEAR }

/** Kernel covariance parameters, cf. `config.merging.kernel`. */
data class KernelParams(
    val kDetail: Float = 1f,
    val kDenoise: Float = 1f,
    val dTh: Float = 0f,
    val dTr: Float = 1f,
    val kStretch: Float = 4f,
    val kShrink: Float = 2f,
    val selection: SelectionLaw = SelectionLaw.LINEAR,
    val iso: Boolean = false,
)

/**
 * Sobel gradients with same (zero-padded) convolution, kernels `[-1, 0, 1]`
 * and `[[-1], [0], [1]]`. Mirrors the `F.conv2d(..., padding='same')` part
 * of `init_ica`.
 *
 * @return `Pair(gradX, gradY)`, each `[H][W]`.
 */
fun sobelGradients(image: Array<FloatArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
    val h = image.size
    val w = image[0].size
    val gx = Array(h) { FloatArray(w) }
    val gy = Array(h) { FloatArray(w) }
    for (y in 0 until h) {
        for (x in 0 until w) {
            val xm = if (x > 0) image[y][x - 1] else 0f
            val xp = if (x < w - 1) image[y][x + 1] else 0f
            val ym = if (y > 0) image[y - 1][x] else 0f
            val yp = if (y < h - 1) image[y + 1][x] else 0f
            gx[y][x] = -xm + xp
            gy[y][x] = -ym + yp
        }
    }
    return Pair(gx, gy)
}

/**
 * Per-tile Hessian sums `[[sum gx^2, sum gx*gy], [.., sum gy^2]]`.
 * Mirrors `cpu_compute_hessian` (tiles index `[ty][tx][2][2]`).
 */
fun computeHessian(
    gradX: Array<FloatArray>,
    gradY: Array<FloatArray>,
    tileSize: Int,
): Array<Array<Array<FloatArray>>> {
    val nTy = gradX.size / tileSize
    val nTx = gradX[0].size / tileSize
    return Array(nTy) { ty ->
        Array(nTx) { tx ->
            var h00 = 0f
            var h01 = 0f
            var h11 = 0f
            for (i in 0 until tileSize) {
                for (j in 0 until tileSize) {
                    val gx = gradX[ty * tileSize + i][tx * tileSize + j]
                    val gy = gradY[ty * tileSize + i][tx * tileSize + j]
                    h00 += gx * gx
                    h01 += gx * gy
                    h11 += gy * gy
                }
            }
            arrayOf(floatArrayOf(h00, h01), floatArrayOf(h01, h11))
        }
    }
}

/**
 * Inverse-compositional refinement, one Gauss-Newton tile update per
 * iteration. Mirrors `cpu_ica`:
 * - singular systems (`|det| < 1e-10`) leave the tile untouched;
 * - 8x8 tiles sample with clamped borders and clip reduction operands
 *   (tree reduction, no update clipping);
 * - larger tiles sample zero outside and clip the update step to
 *   `+-clipRadius`. Updates [flow] in place.
 */
fun icaRefine(
    ref: Array<FloatArray>,
    gradX: Array<FloatArray>,
    gradY: Array<FloatArray>,
    hessian: Array<Array<Array<FloatArray>>>,
    moving: Array<FloatArray>,
    flow: Array<Array<FloatArray>>,
    nIter: Int = 3,
    clipRadius: Float = 32767f,
    tileSize: Int,
) {
    val h = moving.size
    val w = moving[0].size
    val nTy = flow.size
    val nTx = flow[0].size
    for (tileY in 0 until nTy) {
        for (tileX in 0 until nTx) {
            val a00 = hessian[tileY][tileX][0][0]
            val a01 = hessian[tileY][tileX][0][1]
            val a10 = hessian[tileY][tileX][1][0]
            val a11 = hessian[tileY][tileX][1][1]
            val det = a00 * a11 - a01 * a10
            if (abs(det) < 1e-10f) continue
            val detInv = 1f / det
            var ax = flow[tileY][tileX][0]
            var ay = flow[tileY][tileX][1]
            repeat(nIter) {
                if (tileSize == 8) {
                    val c0 = FloatArray(64)
                    val c1 = FloatArray(64)
                    var idx = 0
                    for (ty in 0 until 8) {
                        val y = tileY * 8 + ty
                        for (tx in 0 until 8) {
                            val x = tileX * 8 + tx
                            // njit promotes `x + ax` (int + float32) to
                            // float64, so sampling positions, fractions and
                            // the interpolation chain are double precision;
                            // only the stored products stay float32.
                            val sx = x.toDouble() + ax
                            val sy = y.toDouble() + ay
                            val x0 = floor(sx).toInt().coerceIn(0, w - 1)
                            val y0 = floor(sy).toInt().coerceIn(0, h - 1)
                            val x1 = (x0 + 1).coerceIn(0, w - 1)
                            val y1 = (y0 + 1).coerceIn(0, h - 1)
                            val fracX = sx - floor(sx).toInt()
                            val fracY = sy - floor(sy).toInt()
                            val top = moving[y0][x0] + (moving[y0][x1] - moving[y0][x0]) * fracX
                            val bot = moving[y1][x0] + (moving[y1][x1] - moving[y1][x0]) * fracX
                            val interp = top + (bot - top) * fracY
                            val gt = interp - ref[y][x]
                            c0[idx] = (-gradX[y][x] * gt).toFloat()
                            c1[idx] = (-gradY[y][x] * gt).toFloat()
                            idx++
                        }
                    }
                    var n = 32
                    while (n > 0) {
                        for (t in 0 until n) {
                            c0[t] += c0[t + n].coerceIn(-clipRadius, clipRadius)
                            c1[t] += c1[t + n].coerceIn(-clipRadius, clipRadius)
                        }
                        n /= 2
                    }
                    ax += detInv * (a11 * c0[0] - a01 * c1[0])
                    ay += detInv * (-a10 * c0[0] + a00 * c1[0])
                } else {
                    var b0 = 0f
                    var b1 = 0f
                    for (ty in 0 until tileSize) {
                        val y = tileY * tileSize + ty
                        for (tx in 0 until tileSize) {
                            val x = tileX * tileSize + tx
                            // Double sampling chain, cf. the 8x8 path: njit
                            // promotes `x + ax` to float64; the accumulators
                            // stay float32 (`B0 += float32` casts each step).
                            val sx = x.toDouble() + ax
                            val sy = y.toDouble() + ay
                            val ix = floor(sx).toInt()
                            val iy = floor(sy).toInt()
                            val fracX = sx - ix
                            val fracY = sy - iy
                            val m00 = if (iy in 0 until h && ix in 0 until w) moving[iy][ix] else 0f
                            val m01 = if (iy in 0 until h && ix + 1 in 0 until w) moving[iy][ix + 1] else 0f
                            val m10 = if (iy + 1 in 0 until h && ix in 0 until w) moving[iy + 1][ix] else 0f
                            val m11 = if (iy + 1 in 0 until h && ix + 1 in 0 until w) moving[iy + 1][ix + 1] else 0f
                            val top = m00 + (m01 - m00) * fracX
                            val bot = m10 + (m11 - m10) * fracX
                            val interp = top + (bot - top) * fracY
                            val gt = interp - ref[y][x]
                            b0 += (-gradX[y][x] * gt).toFloat()
                            b1 += (-gradY[y][x] * gt).toFloat()
                        }
                    }
                    ax += (detInv * (a11 * b0 - a01 * b1)).coerceIn(-clipRadius, clipRadius)
                    ay += (detInv * (-a10 * b0 + a00 * b1)).coerceIn(-clipRadius, clipRadius)
                }
            }
            flow[tileY][tileX][0] = ax
            flow[tileY][tileX][1] = ay
        }
    }
}

/**
 * Kernel gradients via the `[-0.5, 0.5] / [0.5, 0.5]` horizontal cascade
 * followed by the grouped vertical cascade. Output `[H-1][W-1][2]`.
 * Mirrors `kerngrad` (`F.conv2d` with default (valid) padding).
 */
fun kernGradients(grey: Array<FloatArray>): Array<Array<FloatArray>> {
    val h = grey.size
    val w = grey[0].size
    val tmp0 = Array(h) { y -> FloatArray(w - 1) { x -> -0.5f * grey[y][x] + 0.5f * grey[y][x + 1] } }
    val tmp1 = Array(h) { y -> FloatArray(w - 1) { x -> 0.5f * grey[y][x] + 0.5f * grey[y][x + 1] } }
    return Array(h - 1) { y ->
        Array(w - 1) { x ->
            floatArrayOf(
                0.5f * tmp0[y][x] + 0.5f * tmp0[y + 1][x],
                -0.5f * tmp1[y][x] + 0.5f * tmp1[y + 1][x],
            )
        }
    }
}

/** Real-symmetric 2x2 eigen-decomposition, values sorted by |.| descending. */
data class Eigen2x2(val l1: Float, val l2: Float, val e1: FloatArray, val e2: FloatArray)

/**
 * Mirrors `get_eigen_elmts_2x2` (`delta = max(b^2-4ac, 0)`, roots ordered
 * by magnitude, Wikipedia 2x2 vector averaging with the identity-matrix
 * and axis fallbacks).
 */
fun eigen2x2(m00: Float, m01: Float, m11: Float): Eigen2x2 {
    val b = -(m00 + m11)
    val c = m00 * m11 - m01 * m01
    val delta = maxOf(b * b - 4f * c, 0f)
    val sq = sqrt(delta)
    val r1 = (-b + sq) / 2f
    val r2 = (-b - sq) / 2f
    val (l1, l2) = if (abs(r1) >= abs(r2)) Pair(r1, r2) else Pair(r2, r1)
    val e1 = FloatArray(2)
    val e2 = FloatArray(2)
    if (m01 == 0f && m00 == m11) {
        e1[0] = 1f; e1[1] = 0f
        e2[0] = 0f; e2[1] = 1f
    } else {
        var v0 = m00 + m01 - l2
        var v1 = m01 + m11 - l2
        if (v0 == 0f) {
            v1 = 1f
            e1[0] = 0f; e1[1] = 1f
            e2[0] = 1f; e2[1] = 0f
        } else if (v1 == 0f) {
            v0 = 1f
            e1[0] = 1f; e1[1] = 0f
            e2[0] = 0f; e2[1] = 1f
        } else {
            val norm = sqrt(v0 * v0 + v1 * v1)
            e1[0] = v0 / norm
            e1[1] = v1 / norm
            // math.copysign(1, e1[0]): sign of e1[0] carried on magnitude 1.
            val sign = if (e1[0] >= 0f) 1f else -1f
            e2[1] = abs(e1[0])
            e2[0] = -e1[1] * sign
        }
    }
    return Eigen2x2(l1, l2, e1, e2)
}

/** Hard-threshold kernel branch (`A > 1.95`, NaN falls through to 1, 1). */
fun hardThreshold(a: Float, kShrink: Float, kStretch: Float): Pair<Float, Float> =
    if (a > 1.95f) Pair(1f / kShrink, kStretch) else Pair(1f, 1f)

/** Linear kernel branch. */
fun linearSelection(a: Float, kShrink: Float, kStretch: Float): Pair<Float, Float> =
    Pair((2f - a) + (a - 1f) / kShrink, (2f - a) + (a - 1f) * kStretch)

/**
 * Per-direction kernel scales. Mirrors `compute_k`:
 * `A = 1+sqrt((l1-l2)/(l1+l2))`, `D = clamp(1-sqrt(l1)/Dtr+Dth, 0, 1)`,
 * `k = kDetail*((1-D)*kSel + D*kDenoise)`.
 */
fun computeK(l1: Float, l2: Float, params: KernelParams): Pair<Float, Float> {
    val a = 1f + sqrt((l1 - l2) / (l1 + l2))
    val d = (1f - sqrt(l1) / params.dTr + params.dTh).coerceIn(0f, 1f)
    val (k1, k2) = when (params.selection) {
        SelectionLaw.HARD_THRESHOLD -> hardThreshold(a, params.kShrink, params.kStretch)
        SelectionLaw.LINEAR -> linearSelection(a, params.kShrink, params.kStretch)
    }
    return Pair(
        params.kDetail * ((1f - d) * k1 + d * params.kDenoise),
        params.kDetail * ((1f - d) * k2 + d * params.kDenoise),
    )
}

/**
 * Covariance per grey pixel from the 3x3 structure tensor of [fullGrads]
 * (`[y][x][2]` from [kernGradients]). `iso` writes `diag(kDetail)`.
 * Mirrors `cpu_estimate_kernel`.
 *
 * @return `[H][W][2][2]` covariances.
 */
fun estimateKernelCovariances(
    fullGrads: Array<Array<FloatArray>>,
    params: KernelParams,
): Array<Array<Array<FloatArray>>> {
    val h = fullGrads.size
    val w = fullGrads[0].size
    return Array(h) { py ->
        Array(w) { px ->
            if (params.iso) {
                arrayOf(floatArrayOf(params.kDetail, 0f), floatArrayOf(0f, params.kDetail))
            } else {
                var s00 = 0f
                var s01 = 0f
                var s11 = 0f
                for (i in 0..1) {
                    for (j in 0..1) {
                        val y = py - 1 + i
                        val x = px - 1 + j
                        if (y in 0 until h && x in 0 until w) {
                            val gx = fullGrads[y][x][0]
                            val gy = fullGrads[y][x][1]
                            s00 += gx * gx
                            s01 += gx * gy
                            s11 += gy * gy
                        }
                    }
                }
                val eig = eigen2x2(s00, s01, s11)
                val e1 = eig.e1
                val e2 = eig.e2
                val (k1, k2) = computeK(eig.l1, eig.l2, params)
                val k1sq = k1 * k1
                val k2sq = k2 * k2
                arrayOf(
                    floatArrayOf(k1sq * e1[0] * e1[0] + k2sq * e2[0] * e2[0], k1sq * e1[0] * e1[1] + k2sq * e2[0] * e2[1]),
                    floatArrayOf(k1sq * e1[0] * e1[1] + k2sq * e2[0] * e2[1], k1sq * e1[1] * e1[1] + k2sq * e2[1] * e2[1]),
                )
            }
        }
    }
}

/** `sqrt` non-linearity, `clamp(x, 0)` first. Mirrors `preprocess(sqrt)`. */
fun sqrtPreprocess(image: Array<FloatArray>): Array<FloatArray> =
    Array(image.size) { y -> FloatArray(image[y].size) { x -> sqrt(maxOf(image[y][x], 0f)) } }

/** `gamma` non-linearity `clamp(x,0)^(1/2.2)`. Mirrors `preprocess(gamma)`. */
fun gammaPreprocess(image: Array<FloatArray>): Array<FloatArray> =
    Array(image.size) { y ->
        FloatArray(image[y].size) { x ->
            Math.pow(maxOf(image[y][x], 0f).toDouble(), 1.0 / 2.2).toFloat()
        }
    }

/**
 * 2x2 mean decimation (`grey[y,x] = mean`, factor 2). Mirrors
 * `cpu_decimate_to_grey`; factor 1 is a passthrough like `downsample`.
 */
fun decimateMean(image: Array<FloatArray>, factor: Int = 2): Array<FloatArray> {
    if (factor == 1) return Array(image.size) { y -> image[y].copyOf() }
    require(factor == 2) { "only factors 1 and 2 are supported (got $factor)" }
    val h = image.size / 2
    val w = image[0].size / 2
    return Array(h) { y ->
        FloatArray(w) { x ->
            (image[2 * y][2 * x] + image[2 * y][2 * x + 1] +
                image[2 * y + 1][2 * x] + image[2 * y + 1][2 * x + 1]) / 4f
        }
    }
}

/**
 * Flow upsampling by an integer ratio with bilinear interpolation and
 * magnitude scaling. Mirrors `alignment.upscale` (`repeat`, bilinear
 * `F.interpolate`, `*= factor`, zero-pad shortfall).
 */
fun upscaleFlow(flow: Array<Array<FloatArray>>, ratio: Int, factor: Float): Array<Array<FloatArray>> {
    require(ratio >= 1) { "ratio must be >= 1 (got $ratio)" }
    if (ratio == 1) {
        return Array(flow.size) { ty ->
            Array(flow[ty].size) { tx ->
                floatArrayOf(flow[ty][tx][0] * factor, flow[ty][tx][1] * factor)
            }
        }
    }
    val nTy = flow.size * ratio
    val nTx = flow[0].size * ratio
    return Array(nTy) { ty ->
        Array(nTx) { tx ->
            val gy = ty.toFloat() / ratio
            val gx = tx.toFloat() / ratio
            val y0 = floor(gy).toInt().coerceIn(0, flow.size - 1)
            val x0 = floor(gx).toInt().coerceIn(0, flow[0].size - 1)
            val y1 = (y0 + 1).coerceIn(0, flow.size - 1)
            val x1 = (x0 + 1).coerceIn(0, flow[0].size - 1)
            val fy = gy - floor(gy)
            val fx = gx - floor(gx)
            val v00 = flow[y0][x0]
            val v01 = flow[y0][x1]
            val v10 = flow[y1][x0]
            val v11 = flow[y1][x1]
            floatArrayOf(
                ((v00[0] * (1 - fx) + v01[0] * fx) * (1 - fy) + (v10[0] * (1 - fx) + v11[0] * fx) * fy) * factor,
                ((v00[1] * (1 - fx) + v01[1] * fx) * (1 - fy) + (v10[1] * (1 - fx) + v11[1] * fx) * fy) * factor,
            )
        }
    }
}

/**
 * Flow-spread map: 3x3 min/max spread, `s1` when
 * `diff0^2 + diff1^2 > Mt^2` else `s2`. Mirrors `cpu_compute_s`
 * (out-of-window neighbours are skipped, not clamped).
 */
fun computeS(flow: Array<Array<FloatArray>>, mTh: Float, s1: Float, s2: Float): Array<FloatArray> {
    val nTy = flow.size
    val nTx = flow[0].size
    return Array(nTy) { py ->
        FloatArray(nTx) { px ->
            var min0 = Float.POSITIVE_INFINITY
            var min1 = Float.POSITIVE_INFINITY
            var max0 = Float.NEGATIVE_INFINITY
            var max1 = Float.NEGATIVE_INFINITY
            for (i in -1..1) {
                for (j in -1..1) {
                    val y = py + i
                    val x = px + j
                    if (y in 0 until nTy && x in 0 until nTx) {
                        val f0 = flow[y][x][0]
                        val f1 = flow[y][x][1]
                        if (f0 < min0) min0 = f0
                        if (f0 > max0) max0 = f0
                        if (f1 < min1) min1 = f1
                        if (f1 > max1) max1 = f1
                    }
                }
            }
            val d0 = max0 - min0
            val d1 = max1 - min1
            if (d0 * d0 + d1 * d1 > mTh * mTh) s1 else s2
        }
    }
}
