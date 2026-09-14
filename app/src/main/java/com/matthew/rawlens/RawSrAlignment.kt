// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Deterministic CPU oracle for the GLES RAW-SR alignment pipeline. Coordinates are Bayer quads. */
data class RawSrGrayImage(val width: Int, val height: Int, val values: FloatArray) {
    init { require(width > 0 && height > 0 && values.size == width * height) }
    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

data class RawSrAlignmentConfig(
    val levels: Int = 4,
    val tileSize: Int = 12,
    val searchRadius: Int = 4,
    val lkIterations: Int = 4,
    val minHessianDeterminant: Float = 1e-5f,
    val maxMeanAbsoluteResidual: Float = 0.12f
) {
    init {
        require(levels in 1..6)
        require(tileSize >= 4 && searchRadius >= 1 && lkIterations >= 0)
    }
}

object RawSrAlignmentTuning {
    /** Wronski-style SNR schedule; tile sizes are expressed in Bayer-quad pixels. */
    fun forSnr(snr: Float): RawSrAlignmentConfig {
        val safe = snr.coerceAtLeast(0f)
        return when {
            safe < 2f -> RawSrAlignmentConfig(tileSize = 32, searchRadius = 5,
                maxMeanAbsoluteResidual = 0.18f)
            safe < 8f -> RawSrAlignmentConfig(tileSize = 16, searchRadius = 4,
                maxMeanAbsoluteResidual = 0.12f)
            else -> RawSrAlignmentConfig(tileSize = 8, searchRadius = 3,
                maxMeanAbsoluteResidual = 0.08f)
        }
    }
}

data class RawSrTileFlow(
    val centerX: Float,
    val centerY: Float,
    /** Displacement sampled in the moving image so moving(x + dx, y + dy) matches reference(x,y). */
    val dx: Float,
    val dy: Float,
    val residual: Float,
    val reliable: Boolean
)

data class RawSrAlignmentField(
    val imageWidth: Int,
    val imageHeight: Int,
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    val tiles: List<RawSrTileFlow>
) {
    init { require(tiles.size == columns * rows) }

    fun flowAt(x: Float, y: Float): RawSrTileFlow {
        val tx = (x / tileSize).toInt().coerceIn(0, columns - 1)
        val ty = (y / tileSize).toInt().coerceIn(0, rows - 1)
        return tiles[ty * columns + tx]
    }
}

object RawSrAlignment {
    /** Removes CFA colour modulation by averaging each complete 2x2 Bayer cell. */
    fun bayerQuadGray(raw: UnpackedRawCfa): RawSrGrayImage {
        require(raw.width % 2 == 0 && raw.height % 2 == 0)
        val width = raw.width / 2
        val height = raw.height / 2
        val out = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val p = (y * 2) * raw.width + x * 2
            out[y * width + x] = (raw.values[p] + raw.values[p + 1] +
                raw.values[p + raw.width] + raw.values[p + raw.width + 1]) * 0.25f
        }
        return RawSrGrayImage(width, height, out)
    }

    fun pyramid(base: RawSrGrayImage, requestedLevels: Int): List<RawSrGrayImage> {
        require(requestedLevels >= 1)
        val result = arrayListOf(base)
        while (result.size < requestedLevels) {
            val source = result.last()
            if (source.width < 8 || source.height < 8) break
            val width = source.width / 2
            val height = source.height / 2
            val values = FloatArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val sx = x * 2
                val sy = y * 2
                values[y * width + x] = (source[sx, sy] + source[sx + 1, sy] +
                    source[sx, sy + 1] + source[sx + 1, sy + 1]) * 0.25f
            }
            result += RawSrGrayImage(width, height, values)
        }
        return result
    }

    fun align(
        reference: RawSrGrayImage,
        moving: RawSrGrayImage,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig()
    ): RawSrAlignmentField {
        require(reference.width == moving.width && reference.height == moving.height)
        val refs = pyramid(reference, config.levels)
        val movs = pyramid(moving, refs.size)
        var previous: RawSrAlignmentField? = null
        for (level in refs.indices.reversed()) {
            val ref = refs[level]; val mov = movs[level]
            val columns = (ref.width + config.tileSize - 1) / config.tileSize
            val rows = (ref.height + config.tileSize - 1) / config.tileSize
            val flows = ArrayList<RawSrTileFlow>(columns * rows)
            for (ty in 0 until rows) for (tx in 0 until columns) {
                val left = tx * config.tileSize; val top = ty * config.tileSize
                val right = min(left + config.tileSize, ref.width)
                val bottom = min(top + config.tileSize, ref.height)
                val seed = previous?.let { prior ->
                    val px = (((tx + 0.5f) * prior.columns / columns).toInt()).coerceIn(0, prior.columns - 1)
                    val py = (((ty + 0.5f) * prior.rows / rows).toInt()).coerceIn(0, prior.rows - 1)
                    prior.tiles[py * prior.columns + px]
                }
                val initialDx = (seed?.dx ?: 0f) * if (previous == null) 1f else 2f
                val initialDy = (seed?.dy ?: 0f) * if (previous == null) 1f else 2f
                val best = blockMatch(ref, mov, intArrayOf(left, top, right, bottom),
                    initialDx, initialDy, config.searchRadius)
                val refined = refineLk(ref, mov, left, top, right, bottom,
                    best.first, best.second, config)
                flows += RawSrTileFlow((left + right - 1) * 0.5f, (top + bottom - 1) * 0.5f,
                    refined[0], refined[1], refined[2], refined[3] > 0f)
            }
            previous = RawSrAlignmentField(ref.width, ref.height, config.tileSize, columns, rows, flows)
        }
        val final = requireNotNull(previous)
        return RawSrAlignmentField(reference.width, reference.height, config.tileSize,
            final.columns, final.rows, final.tiles)
    }

    private fun blockMatch(ref: RawSrGrayImage, mov: RawSrGrayImage, bounds: IntArray,
                           initialDx: Float, initialDy: Float, radius: Int): Pair<Float, Float> {
        val baseX = initialDx.toInt()
        val baseY = initialDy.toInt()
        var bestX = baseX
        var bestY = baseY
        var best = Float.POSITIVE_INFINITY
        for (oy in baseY - radius..baseY + radius) for (ox in baseX - radius..baseX + radius) {
            var error = 0f
            var count = 0
            for (y in bounds[1] until min(bounds[3], ref.height)) for (x in bounds[0] until min(bounds[2], ref.width)) {
                val mx = x + ox
                val my = y + oy
                if (mx in 0 until mov.width && my in 0 until mov.height) {
                    val d = mov[mx, my] - ref[x, y]
                    error += d * d
                    count++
                }
            }
            if (count >= 4 && error / count < best) {
                best = error / count
                bestX = ox
                bestY = oy
            }
        }
        return bestX.toFloat() to bestY.toFloat()
    }

    private fun refineLk(ref: RawSrGrayImage, mov: RawSrGrayImage, left: Int, top: Int,
                         right: Int, bottom: Int, startDx: Float, startDy: Float,
                         config: RawSrAlignmentConfig): FloatArray {
        var dx = startDx
        var dy = startDy
        var determinant = 0f
        repeat(config.lkIterations) {
            var hxx = 0f; var hxy = 0f; var hyy = 0f
            var bx = 0f; var by = 0f
            for (y in max(1, top) until min(bottom, ref.height - 1)) {
                for (x in max(1, left) until min(right, ref.width - 1)) {
                    val sample = bilinearOrNull(mov, x + dx, y + dy) ?: continue
                    val gx = (ref[x + 1, y] - ref[x - 1, y]) * 0.5f
                    val gy = (ref[x, y + 1] - ref[x, y - 1]) * 0.5f
                    val error = sample - ref[x, y]
                    hxx += gx * gx; hxy += gx * gy; hyy += gy * gy
                    bx += gx * error; by += gy * error
                }
            }
            determinant = hxx * hyy - hxy * hxy
            if (determinant <= config.minHessianDeterminant) return@repeat
            val stepX = (hyy * bx - hxy * by) / determinant
            val stepY = (hxx * by - hxy * bx) / determinant
            dx -= stepX.coerceIn(-1f, 1f)
            dy -= stepY.coerceIn(-1f, 1f)
        }
        var residual = 0f
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            bilinearOrNull(mov, x + dx, y + dy)?.let {
                residual += abs(it - ref[x, y]); count++
            }
        }
        val mean = if (count == 0) Float.POSITIVE_INFINITY else residual / count
        val reliable = determinant > config.minHessianDeterminant &&
            mean <= config.maxMeanAbsoluteResidual
        return floatArrayOf(dx, dy, mean, if (reliable) 1f else 0f)
    }

    internal fun bilinearOrNull(image: RawSrGrayImage, x: Float, y: Float): Float? {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= image.width || y0 + 1 >= image.height) return null
        val fx = x - x0; val fy = y - y0
        return (image[x0, y0] * (1f - fx) + image[x0 + 1, y0] * fx) * (1f - fy) +
            (image[x0, y0 + 1] * (1f - fx) + image[x0 + 1, y0 + 1] * fx) * fy
    }
}

data class RawSrRgbImage(val width: Int, val height: Int, val values: FloatArray) {
    init { require(values.size == width * height * 3) }
    operator fun get(x: Int, y: Int, channel: Int): Float = values[(y * width + x) * 3 + channel]
}

data class RawSrMergeResult(
    val image: RawSrRgbImage,
    val denominator: FloatArray,
    val alignments: List<RawSrAlignmentField>,
    val acceptedFrames: Int
)

/** Phase-B 1x RGB accumulator. It is deliberately simple and serves as an A/B oracle for GLES. */
object RawSrMergePrototype {
    fun merge(
        frames: List<UnpackedRawCfa>,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig(),
        referenceOnly: Boolean = false
    ): RawSrMergeResult {
        require(frames.isNotEmpty())
        val reference = frames.first()
        require(frames.all {
            it.width == reference.width && it.height == reference.height && it.pattern == reference.pattern
        }) { "RAW-SR frames must have identical dimensions and CFA phase" }
        val referenceGray = RawSrAlignment.bayerQuadGray(reference)
        val alignments = if (referenceOnly) emptyList() else frames.drop(1).map {
            RawSrAlignment.align(referenceGray, RawSrAlignment.bayerQuadGray(it), config)
        }
        val rgbFrames = (if (referenceOnly) frames.take(1) else frames).map(::demosaic)
        val numerator = FloatArray(reference.width * reference.height * 3)
        val denominator = FloatArray(reference.width * reference.height)
        for (frameIndex in rgbFrames.indices) {
            val rgb = rgbFrames[frameIndex]
            val field = alignments.getOrNull(frameIndex - 1)
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val flow = field?.flowAt(x * 0.5f, y * 0.5f)
                if (flow != null && !flow.reliable) continue
                val sx = x + (flow?.dx ?: 0f) * 2f
                val sy = y + (flow?.dy ?: 0f) * 2f
                val sample = sampleRgb(rgb, sx, sy) ?: continue
                val p = y * reference.width + x
                denominator[p] += 1f
                for (channel in 0..2) numerator[p * 3 + channel] += sample[channel]
            }
        }
        for (p in denominator.indices) {
            val weight = denominator[p]
            if (weight > 0f) for (channel in 0..2) numerator[p * 3 + channel] /= weight
        }
        return RawSrMergeResult(
            RawSrRgbImage(reference.width, reference.height, numerator), denominator,
            alignments, if (referenceOnly) 1 else 1 + alignments.count { field ->
                field.tiles.count(RawSrTileFlow::reliable) >= field.tiles.size / 2
            }
        )
    }

    /** Phase-safe bilinear-like interpolation that never mixes unlike CFA samples directly. */
    fun demosaic(raw: UnpackedRawCfa): RawSrRgbImage {
        val out = FloatArray(raw.width * raw.height * 3)
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            val p = (y * raw.width + x) * 3
            for (channel in 0..2) {
                val wanted = when (channel) { 0 -> CfaColor.RED; 1 -> CfaColor.GREEN; else -> CfaColor.BLUE }
                var sum = 0f
                var weight = 0f
                for (oy in -1..1) for (ox in -1..1) {
                    val sx = x + ox; val sy = y + oy
                    if (sx !in 0 until raw.width || sy !in 0 until raw.height) continue
                    if (raw.pattern.colorAt(sx, sy) != wanted) continue
                    val w = if (ox == 0 && oy == 0) 4f else if (ox == 0 || oy == 0) 2f else 1f
                    sum += raw.values[sy * raw.width + sx] * w
                    weight += w
                }
                if (weight == 0f) {
                    // Only possible at tiny borders; expand by one Bayer period.
                    for (oy in -2..2) for (ox in -2..2) {
                        val sx = x + ox; val sy = y + oy
                        if (sx in 0 until raw.width && sy in 0 until raw.height &&
                            raw.pattern.colorAt(sx, sy) == wanted) {
                            sum += raw.values[sy * raw.width + sx]; weight += 1f
                        }
                    }
                }
                out[p + channel] = if (weight > 0f) sum / weight else 0f
            }
        }
        return RawSrRgbImage(raw.width, raw.height, out)
    }

    private fun sampleRgb(image: RawSrRgbImage, x: Float, y: Float): FloatArray? {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= image.width || y0 + 1 >= image.height) return null
        val fx = x - x0; val fy = y - y0
        return FloatArray(3) { channel ->
            (image[x0, y0, channel] * (1f - fx) + image[x0 + 1, y0, channel] * fx) * (1f - fy) +
                (image[x0, y0 + 1, channel] * (1f - fx) + image[x0 + 1, y0 + 1, channel] * fx) * fy
        }
    }
}
