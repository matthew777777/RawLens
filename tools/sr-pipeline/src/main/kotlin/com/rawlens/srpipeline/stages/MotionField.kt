package com.rawlens.srpipeline.stages

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reference-conditioned spatial regularization of the finest alignment grid.
 *
 * Independent matches on weakly textured tiles can follow noise by a pixel
 * or more. Interpolating those matches merely warps the noise into rectangular
 * patches. The smaller reference structure-tensor eigenvalue measures support
 * for a two-dimensional displacement (an edge alone constrains one direction).
 * Weak tiles borrow motion from neighbours; well constrained tiles retain
 * their measured motion. Charbonnier neighbour weights preserve supported
 * motion boundaries. The same result must feed rejection and reconstruction.
 *
 * This belongs to the KernelNet reconstruction path; the reference Jamy-L
 * alignment and its parity entry points are unchanged.
 */
internal class MotionField(private val width: Int, private val height: Int, hessian: FloatArray) {
    private val count = width * height
    private val dataWeight: FloatArray

    init {
        require(width > 0 && height > 0 && hessian.size == count * 4)
        val support = FloatArray(count) { i ->
            val a = hessian[4*i].toDouble()
            val b = hessian[4*i+1].toDouble()
            val c = hessian[4*i+2].toDouble()
            require(a.isFinite() && b.isFinite() && c.isFinite())
            max(0.0, .5 * (a + c - sqrt((a-c)*(a-c) + 4*b*b))).toFloat()
        }
        // Relative confidence adapts to guide scaling, exposure and tile size.
        // This is a texture-support scale, not a calibrated sensor-noise model.
        val ordered = support.sortedArray()
        val floor = max(ordered[count / 2], 1e-12f)
        // Keep a weak data anchor even on singular tiles: otherwise a
        // textured moving object could drag an entirely flat background.
        dataWeight = FloatArray(count) { 0.05f + min(16f, max(0f, support[it] - floor) / (4f * floor)) }
    }

    fun regularize(flow: Array<Array<FloatArray>>, motionThreshold: Float = .8f) {
        require(flow.size == height && flow.all { it.size == width && it.all { v -> v.size == 2 && v.all(Float::isFinite) } })
        require(motionThreshold.isFinite())
        val observed = FloatArray(count * 2) { i -> flow[i/2/width][i/2%width][i%2] }
        var current = FloatArray(count * 2)
        var next = FloatArray(count * 2)
        val sx = FloatArray(25); val sy = FloatArray(25)
        // A robust start prevents unconstrained noise outliers from defining
        // their own motion discontinuity in the edge-preserving iterations.
        for (y in 0 until height) for (x in 0 until width) {
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val i = ((y+dy).coerceIn(0,height-1)*width + (x+dx).coerceIn(0,width-1))*2
                sx[n] = observed[i]; sy[n++] = observed[i+1]
            }
            sx.sort(); sy.sort()
            val i = (y*width+x)*2; current[i] = sx[12]; current[i+1] = sy[12]
        }
        val threshold = max(kotlin.math.abs(motionThreshold), 1e-3f)
        val invThreshold2 = 1f / (threshold * threshold)
        for (iteration in 0 until 160) {
            // Jacobi: each tile reads only `current`, so rows are independent
            // and run in parallel (bit-identical; max is order-free).
            val cur = current; val nxt = next
            val rowChange = FloatArray(height)
            // Parallel only for large grids: at tile 32 (~12k tiles) the per-
            // iteration fork cost doubled the phone time (435 -> 905 ms).
            val rows = java.util.stream.IntStream.range(0, height)
            (if (count >= 32768) rows.parallel() else rows).forEach { y ->
            val current = cur; val next = nxt
            var maxChange = 0f
            for (x in 0 until width) {
                val t = y*width+x; val i = t*2
                var sumX = 0f; var sumY = 0f; var sumW = 0f
                for (direction in 0..3) {
                    val nx = when (direction) { 0 -> max(0,x-1); 1 -> min(width-1,x+1); else -> x }
                    val ny = when (direction) { 2 -> max(0,y-1); 3 -> min(height-1,y+1); else -> y }
                    val j = (ny*width+nx)*2
                    val dx = current[j]-current[i]; val dy = current[j+1]-current[i+1]
                    val w = 1f / sqrt(1f + (dx*dx+dy*dy)*invThreshold2)
                    sumX += w*current[j]; sumY += w*current[j+1]; sumW += w
                }
                val q = dataWeight[t]
                next[i] = (q*observed[i] + sumX/sumW) / (q+1f)
                next[i+1] = (q*observed[i+1] + sumY/sumW) / (q+1f)
                maxChange = max(maxChange, kotlin.math.abs(next[i]-current[i]))
                maxChange = max(maxChange, kotlin.math.abs(next[i+1]-current[i+1]))
            }
            rowChange[y] = maxChange
            }
            val maxChange = rowChange.max()
            val old = current; current = next; next = old
            if (maxChange < 1e-4f) break
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y*width+x)*2
            flow[y][x][0] = current[i]; flow[y][x][1] = current[i+1]
        }
    }
}
