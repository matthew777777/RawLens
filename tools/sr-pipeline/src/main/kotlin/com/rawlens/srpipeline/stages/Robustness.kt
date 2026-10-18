package com.rawlens.srpipeline.stages

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

// Stage: robustness estimation (Algorithm 6) + final divide.
//
// Mirrors `handheld_super_resolution/robustness.py`
// (`compute_robustness`, `compute_guide_image`, `compute_local_stats`,
// `warp_stats`, `compute_d_sigma`, `compute_s`, `robustness_threshold`,
// `local_min`) and `utils.divide` (called from `super_resolution.main`).
//
// Guide/stat layout is `[channel][y][x]` (bayer: 3 channels, grey: 1),
// matching the Python `guide_img` / local-stat arrays. The robustness map
// `r` is sampled at bayer-quad centres, shape `[H/2][W/2]`.
//
// Depends on the Kotlin stdlib only; flow-spread input comes from
// [computeS] in Kernels.kt (same package). See Align.kt for the
// `core.*`-absent note, which applies to this module too.

/** Defaults from `config.RobustnessConfig`: t=0.12, s1=2, s2=12, Mt=0.8. */
data class RobustnessParams(
    val t: Float = 0.12f,
    val s1: Float = 2f,
    val s2: Float = 12f,
    val mt: Float = 0.8f,
    val noiseCorrection: Boolean = true,
)

/**
 * Algorithm 7: guide image `G` of shape `[3][H/2][W/2]`:
 * `R = sqrt(max(J00,0))`, `G = sqrt(max(mean(G1,G2),0))`,
 * `B = sqrt(max(J11,0))`. Mirrors `cpu_compute_guide_image`.
 */
fun computeGuideImage(raw: Array<FloatArray>): Array<Array<FloatArray>> {
    val h = raw.size / 2
    val w = raw[0].size / 2
    val guide = Array(3) { Array(h) { FloatArray(w) } }
    for (ty in 0 until h) {
        for (tx in 0 until w) {
            guide[0][ty][tx] = sqrt(max(raw[2 * ty][2 * tx], 0f))
            guide[1][ty][tx] = sqrt(max(0.5f * (raw[2 * ty][2 * tx + 1] + raw[2 * ty + 1][2 * tx]), 0f))
            guide[2][ty][tx] = sqrt(max(raw[2 * ty + 1][2 * tx + 1], 0f))
        }
    }
    return guide
}

/**
 * Algorithm 8: 3x3 mean/variance per guide pixel, clamped borders,
 * normalised by 9. Mirrors `cpu_compute_local_stats`.
 *
 * @return `Pair(means, vars)`, each `[C][Gy][Gx]`.
 */
fun computeLocalStats(guide: Array<Array<FloatArray>>): Pair<Array<Array<FloatArray>>, Array<Array<FloatArray>>> {
    val c = guide.size
    val h = guide[0].size
    val w = guide[0][0].size
    val means = Array(c) { Array(h) { FloatArray(w) } }
    val vars = Array(c) { Array(h) { FloatArray(w) } }
    for (ch in 0 until c) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var sumSq = 0f
                for (i in -1..1) {
                    for (j in -1..1) {
                        val v = guide[ch][(y + i).coerceIn(0, h - 1)][(x + j).coerceIn(0, w - 1)]
                        sum += v
                        sumSq += v * v
                    }
                }
                val mean = sum / 9f
                means[ch][y][x] = mean
                vars[ch][y][x] = sumSq / 9f - mean * mean
            }
        }
    }
    return Pair(means, vars)
}

/** Dodgson quadratic kernel; mirrors `dogson_quadratic_kernel`. */
fun dogsonQuadratic(x: Float): Float {
    val a = if (x >= 0f) x else -x
    return when {
        a <= 0.5f -> -2f * a * a + 1f
        a <= 1.5f -> a * a - 2.5f * a + 1.5f
        else -> 0f
    }
}

/**
 * Warps + normalises local-stat maps by half the raw-basis flow using the
 * 3x3 Dodgson biquadratic kernel. Out-of-bounds targets yield `+Inf`
 * (forcing `R = 0` downstream). Mirrors `cpu_warp_dogson`
 * (`tile_size //= 2`, `round_half_away` centres, weight normalisation).
 */
fun warpStats(
    source: Array<Array<FloatArray>>,
    flow: Array<Array<FloatArray>>,
    tileSize: Int,
): Array<Array<FloatArray>> {
    val half = tileSize / 2
    val c = source.size
    val h = source[0].size
    val w = source[0][0].size
    val warped = Array(c) { Array(h) { FloatArray(w) } }
    for (y in 0 until h) {
        for (x in 0 until w) {
            val py = y / half
            val px = x / half
            val fx = flow[py][px][0]
            val fy = flow[py][px][1]
            val yMov = y + fy * 0.5f
            val xMov = x + fx * 0.5f
            if (yMov < 0f || yMov >= h || xMov < 0f || xMov >= w) {
                for (ch in 0 until c) warped[ch][y][x] = Float.POSITIVE_INFINITY
                continue
            }
            val cy = roundHalfAway(yMov)
            val cx = roundHalfAway(xMov)
            val buf = FloatArray(c)
            var wAcc = 0f
            for (i in -1..1) {
                val ys = (cy + i).coerceIn(0, h - 1)
                val wy = dogsonQuadratic(ys - yMov)
                for (j in -1..1) {
                    val xs = (cx + j).coerceIn(0, w - 1)
                    val weight = wy * dogsonQuadratic(xs - xMov)
                    for (ch in 0 until c) buf[ch] += source[ch][ys][xs] * weight
                    wAcc += weight
                }
            }
            for (ch in 0 until c) warped[ch][y][x] = buf[ch] / wAcc
        }
    }
    return warped
}

/**
 * Channel-summed squared colour distance `d_sq` and summed reference
 * variance `sigma_sq`, with optional LUT noise correction (brightness from
 * reference means, LUT index `round((n-1)*brightness)`, shrink
 * `d^2/(d^2+dn^2)` applied squared). Mirrors `cpu_compute_d_sigma`.
 *
 * @return `Pair(dSq, sigmaSq)`, each `[Gy][Gx]`.
 */
fun computeDSigma(
    meansR: Array<Array<FloatArray>>,
    meansM: Array<Array<FloatArray>>,
    varR: Array<Array<FloatArray>>,
    sigmaSqCurve: FloatArray,
    dSqCurve: FloatArray,
    doNoiseCorrection: Boolean,
): Pair<Array<FloatArray>, Array<FloatArray>> {
    val c = meansR.size
    val h = meansR[0].size
    val w = meansR[0][0].size
    val dSq = Array(h) { FloatArray(w) }
    val sigmaSq = Array(h) { FloatArray(w) }
    for (y in 0 until h) {
        for (x in 0 until w) {
            var d = 0f
            var s = 0f
            for (ch in 0 until c) {
                val e = meansR[ch][y][x] - meansM[ch][y][x]
                d += e * e
                s += varR[ch][y][x]
            }
            if (doNoiseCorrection) {
                var brightness = 0f
                for (ch in 0 until c) brightness += meansR[ch][y][x]
                brightness = (brightness / c).coerceIn(0f, 1f)
                val idx = roundHalfAway((sigmaSqCurve.size - 1) * brightness)
                    .coerceIn(0, sigmaSqCurve.size - 1)
                val dNoise = dSqCurve[idx]
                s = max(s, sigmaSqCurve[idx])
                if (d > 0f) {
                    val shrink = d / (d + dNoise)
                    d *= shrink * shrink
                }
            }
            dSq[y][x] = d
            sigmaSq[y][x] = s
        }
    }
    return Pair(dSq, sigmaSq)
}

/**
 * Per-pixel robustness `R = clamp(S*exp(-d/sigma) - t, 0, 1)` with `S`
 * gathered per `tileSize/2` block. Mirrors `cpu_robustness_threshold`.
 */
/**
 * Numba `clamp(x, lo, hi) = min(hi, max(lo, x))` bit-for-bit: numba lowers
 * float min/max to minnum/maxnum, so NaN maps to `lo` (`max(lo,NaN)=lo`,
 * then `min(hi,lo)=lo` for `lo <= hi`), while Kotlin's [coerceIn]
 * propagates NaN. OOB-warped pixels carry infinite means, whose `d_sq`
 * shrink (`inf/inf`) is NaN; Python writes `R = 0` there (measured), so
 * must we. Finite inputs behave exactly like [coerceIn].
 */
internal fun numbaClamp(v: Float, lo: Float, hi: Float): Float =
    if (v.isNaN()) lo else v.coerceIn(lo, hi)

fun robustnessThreshold(
    dSq: Array<FloatArray>,
    sigmaSq: Array<FloatArray>,
    s: Array<FloatArray>,
    t: Float,
    tileSize: Int,
): Array<FloatArray> {
    val half = tileSize / 2
    val h = dSq.size
    val w = dSq[0].size
    return Array(h) { y ->
        FloatArray(w) { x ->
            numbaClamp(s[y / half][x / half] * exp(-dSq[y][x] / sigmaSq[y][x]) - t, 0f, 1f)
        }
    }
}

/**
 * Algorithm 9: 5x5 local minimum of `R`, clamped borders.
 * Mirrors `cpu_compute_local_min`.
 */
fun localMin(r: Array<FloatArray>): Array<FloatArray> {
    val h = r.size
    val w = r[0].size
    return Array(h) { y ->
        FloatArray(w) { x ->
            var mini = Float.POSITIVE_INFINITY
            for (i in -2..2) {
                for (j in -2..2) {
                    val v = r[(y + i).coerceIn(0, h - 1)][(x + j).coerceIn(0, w - 1)]
                    if (v < mini) mini = v
                }
            }
            mini
        }
    }
}

/**
 * Algorithm 6: full robustness map for one compared frame.
 * Mirrors `compute_robustness` (guide -> local stats -> warp -> d/sigma ->
 * flow-spread penalty [S][computeS] -> threshold -> local min).
 *
 * @param refMeans/`refVars` reference local stats `[C][Gy][Gx]`.
 * @param flow tile flow in the raw-image basis.
 * @param tileSize raw-basis tile size (halved internally for guide grids).
 * @param bayer selects the 3-channel guide path; grey uses 1 channel.
 */
fun computeRobustness(
    comp: Array<FloatArray>,
    refMeans: Array<Array<FloatArray>>,
    refVars: Array<Array<FloatArray>>,
    flow: Array<Array<FloatArray>>,
    sigmaSqCurve: FloatArray,
    dSqCurve: FloatArray,
    tileSize: Int,
    bayer: Boolean,
    params: RobustnessParams = RobustnessParams(),
): Array<FloatArray> {
    val guide = if (bayer) {
        computeGuideImage(comp)
    } else {
        arrayOf(comp)
    }
    val (compMeans, _) = computeLocalStats(guide)
    val warped = warpStats(compMeans, flow, tileSize)
    val (dSq, sigmaSq) = computeDSigma(refMeans, warped, refVars, sigmaSqCurve, dSqCurve, params.noiseCorrection)
    val s = computeS(flow, params.mt, params.s1, params.s2)
    val r = robustnessThreshold(dSq, sigmaSq, s, params.t, tileSize)
    return localMin(r)
}

/**
 * In-place `num /= den` with IEEE semantics (0/0 -> NaN, x/0 -> Inf).
 * Mirrors `utils.divide` (`np.errstate(divide='ignore', invalid='ignore')`).
 */
fun divide(num: Array<Array<FloatArray>>, den: Array<Array<FloatArray>>) {
    for (y in num.indices) {
        for (x in num[y].indices) {
            for (ch in num[y][x].indices) {
                num[y][x][ch] = num[y][x][ch] / den[y][x][ch]
            }
        }
    }
}
