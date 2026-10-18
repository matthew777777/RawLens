package com.rawlens.srpipeline.stages

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

// Stage: merge accumulation (Algorithm 4).
//
// Mirrors `handheld_super_resolution/merge.py` (`merge` / `cpu_accumulate`):
// each HR pixel maps to LR via `(hr + 0.5) / scale`, gathers its tile flow,
// nearest-neighbour robustness, bilinearly interpolated + inverted kernel
// covariance, and splats the 3x3 neighbourhood with `w = exp(-0.5*max(0,z))`
// into `num`/`den` in place.
//
// `num`/`den` layout is `[hrH][hrW][3]` (bayer) or `[hrH][hrW][1]`;
// `covs` is `[greyH][greyW][2][2]` sampled at bayer-quad centres.
// See Align.kt for the `core.*`-absent note, which applies here too.

/**
 * Algorithm 4 accumulation. Mirrors `cpu_accumulate`, including the
 * truncation-towards-zero fractional covariances (`int()` semantics via
 * [truncIdx]), the `max(int(k), 0)` floor with clamped ceil, and the RGGB
 * channel rule `channel = i % 2 + j % 2` (even/even -> 0, odd/odd -> 2,
 * mixed -> 1).
 */
fun accumulate(
    comp: Array<FloatArray>,
    flow: Array<Array<FloatArray>>,
    covs: Array<Array<Array<FloatArray>>>,
    robustness: Array<FloatArray>,
    bayer: Boolean,
    scale: Float,
    tileSize: Int,
    num: Array<Array<FloatArray>>,
    den: Array<Array<FloatArray>>,
) {
    val lrH = comp.size
    val lrW = comp[0].size
    val hrH = num.size
    val hrW = num[0].size
    val nChannels = if (bayer) 3 else 1
    for (hrI in 0 until hrH) {
        for (hrJ in 0 until hrW) {
            val lrX = (hrJ + 0.5f) / scale
            val lrY = (hrI + 0.5f) / scale

            val px = (lrX / tileSize).toInt()
            val py = (lrY / tileSize).toInt()
            val flowX = flow[py][px][0]
            val flowY = flow[py][px][1]

            val localR = if (bayer) {
                // Mirror Python `int(lr//2 - 0.5)` bit-for-bit: float64
                // FLOOR-division first, then truncation. float32
                // `(lr/2f - 0.5f).toInt()` is algebraically different
                // (e.g. lr=69: int(34.0-0.5)=33 vs int(34.5-0.5)=34).
                val iR = minOf((floor(lrY.toDouble() / 2.0) - 0.5).toInt(), lrH / 2 - 1)
                val jR = minOf((floor(lrX.toDouble() / 2.0) - 0.5).toInt(), lrW / 2 - 1)
                robustness[iR][jR]
            } else {
                val iR = minOf(lrY.toInt(), lrH - 1)
                val jR = minOf(lrX.toInt(), lrW - 1)
                robustness[iR][jR]
            }

            val movX = lrX + flowX
            val movY = lrY + flowY
            if (movX < 0f || movX >= lrW || movY < 0f || movY >= lrH) continue

            val kmapJ: Float
            val kmapI: Float
            if (bayer) {
                kmapJ = movX / 2f - 0.5f
                kmapI = movY / 2f - 0.5f
            } else {
                kmapJ = movX - 0.5f
                kmapI = movY - 0.5f
            }
            // `math.modf`-style frac: truncation keeps the sign for negatives.
            val fracX = kmapJ - truncIdx(kmapJ)
            val fracY = kmapI - truncIdx(kmapI)
            val floorX = max(truncIdx(kmapJ), 0)
            val floorY = max(truncIdx(kmapI), 0)
            val ceilX = minOf(floorX + 1, covs[0].size - 1)
            val ceilY = minOf(floorY + 1, covs.size - 1)

            // Bilinear interpolation over (floor/ceil, floor/ceil) corners.
            val tlXx = covs[floorY][floorX][0][0]
            val tlXy = covs[floorY][floorX][0][1]
            val tlYy = covs[floorY][floorX][1][1]
            val trXx = covs[floorY][ceilX][0][0]
            val trXy = covs[floorY][ceilX][0][1]
            val trYy = covs[floorY][ceilX][1][1]
            val blXx = covs[ceilY][floorX][0][0]
            val blXy = covs[ceilY][floorX][0][1]
            val blYy = covs[ceilY][floorX][1][1]
            val brXx = covs[ceilY][ceilX][0][0]
            val brXy = covs[ceilY][ceilX][0][1]
            val brYy = covs[ceilY][ceilX][1][1]
            val topXx = tlXx + fracX * (trXx - tlXx)
            val topXy = tlXy + fracX * (trXy - tlXy)
            val topYy = tlYy + fracX * (trYy - tlYy)
            val botXx = blXx + fracX * (brXx - blXx)
            val botXy = blXy + fracX * (brXy - blXy)
            val botYy = blYy + fracX * (brYy - blYy)
            val covXx = topXx + fracY * (botXx - topXx)
            val covXy = topXy + fracY * (botXy - topXy)
            val covYy = topYy + fracY * (botYy - topYy)

            val det = covXx * covYy - covXy * covXy
            val invDet = 1f / det
            val invXx = invDet * covYy
            val invXy = -invDet * covXy
            val invYy = invDet * covXx

            val centerJ = movX.toInt()
            val centerI = movY.toInt()
            val movJ = movX - 0.5f
            val movI = movY - 0.5f
            val valAcc = FloatArray(3)
            val wAcc = FloatArray(3)
            for (di in -1..1) {
                for (dj in -1..1) {
                    val j = centerJ + dj
                    val i = centerI + di
                    if (j !in 0 until lrW || i !in 0 until lrH) continue
                    val channel = if (bayer) i % 2 + j % 2 else 0
                    val c = comp[i][j]
                    val distX = j - movJ
                    val distY = i - movI
                    var z = invXx * distX * distX + 2f * invXy * distX * distY + invYy * distY * distY
                    z = max(0f, z)
                    val w = exp(-0.5f * z)
                    valAcc[channel] += w * localR * c
                    wAcc[channel] += w * localR
                }
            }
            for (ch in 0 until nChannels) {
                num[hrI][hrJ][ch] += valAcc[ch]
                den[hrI][hrJ][ch] += wAcc[ch]
            }
        }
    }
}

/** Truncation towards zero, mirroring Python `int()` on floats. */
private fun truncIdx(v: Float): Int = v.toInt()

/**
 * Algorithm 4 entry point. Mirrors `merge()` (`scale`, `bayer_mode`,
 * `tile_size` forwarded to [accumulate]).
 */
fun merge(
    comp: Array<FloatArray>,
    flow: Array<Array<FloatArray>>,
    covs: Array<Array<Array<FloatArray>>>,
    robustness: Array<FloatArray>,
    num: Array<Array<FloatArray>>,
    den: Array<Array<FloatArray>>,
    bayer: Boolean,
    scale: Float,
    tileSize: Int,
) {
    accumulate(comp, flow, covs, robustness, bayer, scale, tileSize, num, den)
}
