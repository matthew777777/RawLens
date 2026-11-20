package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.TwoBandReconstruction

/**
 * Bench adapter for [TwoBandReconstruction]: oracle global shifts, static
 * scene, unit weights, exact noise variance. Chroma samples are colour
 * differences against the frame's own 4-neighbour green (as ColourDiff).
 */
object TwoBandMerge {
    fun merge(frames: List<Array<FloatArray>>, shifts: List<Pair<Double, Double>>, gains: DoubleArray,
              scale: Int, p: TwoBandReconstruction.Params, noiseA: Double, noiseB: Double): Array<Array<FloatArray>> {
        val n = frames[0].size
        val rec = TwoBandReconstruction(n, n, scale, p)
        for ((k, f) in frames.withIndex()) { val (sx, sy) = shifts[k]
            for (y in 0 until n) for (x in 0 until n) {
                val c = (y and 1) * 2 + (x and 1)
                val raw = f[y][x].toDouble()
                val v = raw / gains[c]
                if (c == 1 || c == 2) {
                    rec.add(1, x - sx, y - sy, v.toFloat(), 1f, ((noiseA * raw.coerceAtLeast(0.0) + noiseB) / (gains[c] * gains[c])).toFloat())
                } else {
                    var gs = 0.0; var gn = 0
                    for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                        val xx = x + dx; val yy = y + dy
                        if (xx in 0 until n && yy in 0 until n) { gs += f[yy][xx] / gains[(yy and 1) * 2 + (xx and 1)]; gn++ }
                    }
                    rec.add(if (c == 0) 0 else 2, x - sx, y - sy, (v - gs / gn).toFloat(), 1f)
                }
            } }
        val planes = rec.finish()
        val no = n * scale
        return Array(no) { y -> Array(no) { x -> val i = y * no + x
            floatArrayOf(((planes[1][i] + planes[0][i]) * gains[0]).toFloat(), planes[1][i],
                ((planes[1][i] + planes[2][i]) * gains[3]).toFloat())
        } }
    }
}
