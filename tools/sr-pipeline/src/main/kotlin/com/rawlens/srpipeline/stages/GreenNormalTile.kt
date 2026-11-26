package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/** Streaming 1× green fit. Each merge callback contributes once. */
class GreenNormalTile internal constructor(
    private val handle: Long, private val shader: ByteArray,
    private val width: Int, private val height: Int, private val tile: Int,
    private val frameCount: Int,
    private val residentSolve: ByteArray? = null,
) : AutoCloseable {
    companion object {
        fun createResident(
            handle: Long, gather: ByteArray, solve: ByteArray,
            width: Int, height: Int, tile: Int, frameCount: Int,
        ): GreenNormalTile = GreenNormalTile(handle, gather, width, height, tile, frameCount, solve)
    }

    init { require(frameCount > 0) }

    private val fw = (width + tile - 1) / tile
    private val fh = (height + tile - 1) / tile
    private val pixels = width * height
    private val counts = longArrayOf(1, pixels.toLong(), 2L * fw * fh, pixels / 4L, if (residentSolve == null) 6L * pixels else 9L * pixels / 4)
    private val residents = LongArray(counts.size)
    private val flow = SrpBridge.directFloats(2 * fw * fh)
    // The scatter path uses 32-bit statistics. The resident gather
    // contributes at most one unit of weight per row and frame.
    private val quantum = if (residentSolve == null) {
        minOf(8192f, Int.MAX_VALUE / (17000f * frameCount))
    } else {
        minOf(2048, 32767 / frameCount).coerceAtLeast(1).toFloat()
    }
    // The reference contributes no off-diagonal weights. Reserve rounding
    // headroom per companion before packing the axial and diagonal fields.
    private val companions = (frameCount - 1).coerceAtLeast(1)
    private val offQuantum = minOf(256f, 4f * (511 / companions), 16f * (127 / companions))
        .coerceAtLeast(1f)
    private val push = IntArray(32)
    private var frames = 0
    private var recordNanos = 0L

    init {
        require(width % 2 == 0 && height % 2 == 0)
        try {
            for (i in residents.indices) {
                if (residentSolve != null && i == 1) continue
                residents[i] = SrpBridge.createResident(handle, counts[i])
                check(residents[i] != 0L)
            }
            intArrayOf(if (residentSolve == null) 2 else 4, width, height, 0, 0, width, 0, 0, width, height, 0, 0,
                width, height, 1, tile, fw, fh, 0, 0, quantum.toRawBits(), width / 2, 0, 0, offQuantum.toRawBits()).copyInto(push)
            if (residentSolve == null) {
                dispatch(6 * pixels)
                push[0] = 3
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun dispatch(count: Int) {
        check(SrpBridge.dispatchRes(handle, shader, residents, counts, push, count.toLong()) == 0)
    }

    private fun dispatchResident(count: Int, raw: Long) {
        val bindings = residents.copyOf()
        bindings[1] = raw
        check(SrpBridge.dispatchRes(handle, shader, bindings, counts, push, count.toLong()) == 0)
    }

    internal fun recordResident(raw: Long, registration: Array<Array<FloatArray>>, rob: ByteBuffer) {
        check(residentSolve != null && ++frames <= frameCount)
        val start = System.nanoTime()
        if (frames == 1) {
            dispatchResident(pixels, raw)
            push[0] = 5
            recordNanos += System.nanoTime() - start
            return
        }
        check(registration.size == fh && registration.all { it.size == fw })
        flow.asFloatBuffer().put(AlignGpu.flowToFlat(registration))
        check(SrpBridge.uploadResident(handle, residents[2], flow, counts[2]) == 0)
        check(SrpBridge.uploadResident(handle, residents[3], rob.duplicate().order(rob.order()).clear(), counts[3]) == 0)
        dispatchResident(pixels / 4, raw)
        recordNanos += System.nanoTime() - start
    }

    internal val onDevice get() = residentSolve != null

    /** Records one frame from images (green_gather.comp); may run inside a batch. */
    internal fun recordImages(gather: ByteArray, cd: Long, field: Long, rob: Long) {
        check(residentSolve != null && ++frames <= frameCount)
        val first = frames == 1
        push[0] = if (first) 4 else 5
        check(SrpBridge.dispatch2d(handle, gather, longArrayOf(cd, field, rob, residents[4]),
            longArrayOf(SrpBridge.SAMPLED, SrpBridge.SAMPLED, SrpBridge.SAMPLED, counts[4]), push,
            if (first) width else width / 2, if (first) height else height / 2) == 0)
    }

    internal fun solveResident(
        rgb: Long, scratch: Long, alpha: Float, beta: Float, pedestal: Float, wb: FloatArray,
    ) {
        val solve = requireNotNull(residentSolve)
        val params = IntArray(32)
        intArrayOf(width, height, 0, alpha.toRawBits(), beta.toRawBits(), quantum.toRawBits(),
            offQuantum.toRawBits(), pedestal.toRawBits(), wb[0].toRawBits(), wb[1].toRawBits()).copyInto(params)
        val bindings = longArrayOf(residents[4], rgb, scratch)
        val sizes = longArrayOf(counts[4], 3L * pixels, 2L * pixels)
        val start = System.nanoTime()
        for (pass in 0..3) {
            params[2] = pass
            check(SrpBridge.dispatchRes(handle, solve, bindings, sizes, params, pixels.toLong()) == 0)
        }
        println("sr-pipeline: resident fit accumulation %.2f ms; three updates and apply %.2f ms; extra buffers %.2f MiB".format(
            recordNanos / 1e6, (System.nanoTime() - start) / 1e6, (counts.sum() - counts[1]) * 4 / 1048576.0))
    }

    fun record(raw: ByteBuffer, registration: Array<Array<FloatArray>>, rob: ByteBuffer) {
        val start = System.nanoTime()
        check(++frames <= frameCount)
        check(registration.size == fh && registration.all { it.size == fw })
        flow.asFloatBuffer().put(AlignGpu.flowToFlat(registration))
        check(SrpBridge.uploadResident(handle, residents[1], raw.duplicate().order(raw.order()).clear(), counts[1]) == 0)
        check(SrpBridge.uploadResident(handle, residents[2], flow, counts[2]) == 0)
        check(SrpBridge.uploadResident(handle, residents[3], rob.duplicate().order(rob.order()).clear(), counts[3]) == 0)
        dispatch(pixels)
        recordNanos += System.nanoTime() - start
    }

    fun finish(
        base: Array<Array<FloatArray>>, wb: FloatArray, alpha: Float, beta: Float,
        passes: Int = 1, step: Float = 0.75f, limitSigma: Float = 2f,
    ): Array<Array<FloatArray>> {
        require(passes > 0 && step in 0f..2f && limitSigma > 0f)
        val host = SrpBridge.directFloats(6 * pixels)
        check(SrpBridge.downloadResident(handle, residents[4], host, counts[4]) == 0)
        val stats = host.asIntBuffer()
        val offsets = arrayOf(
            intArrayOf(0, 0, 1),
            intArrayOf(1, 0, 2), intArrayOf(0, 1, 3),
            intArrayOf(1, 1, 4), intArrayOf(-1, 1, 5),
        )
        val green = FloatArray(pixels) { base[it / width][it % width][1] }
        val confidence = FloatArray(pixels)
        val rhs = FloatArray(pixels)
        val inverseMass = FloatArray(pixels)
        val stencil = Array(9) { FloatArray(pixels) }
        val neighbors = Array(9) { IntArray(pixels) }
        val damping = 0.25f
        for (i in 0 until pixels) {
            val x = i % width
            val y = i / width
            val g = green[i]
            var residual = stats.get(6 * i) / quantum
            var mass = 0f
            var slot = 0
            fun include(dx: Int, dy: Int, coeff: Int, reverse: Boolean) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until width && yy in 0 until height) {
                    val j = yy * width + xx
                    val weight = stats.get(6 * (if (reverse) j else i) + coeff) / quantum
                    neighbors[slot][i] = j
                    stencil[slot][i] = weight
                    residual -= weight * (green[j] - 0.5f)
                    mass += weight
                }
                slot++
            }
            for ((dx, dy, coeff) in offsets) {
                include(dx, dy, coeff, false)
                if (dx != 0 || dy != 0) include(-dx, -dy, coeff, true)
            }
            val left = green[y * width + (x - 1).coerceAtLeast(0)]
            val right = green[y * width + (x + 1).coerceAtMost(width - 1)]
            val up = green[(y - 1).coerceAtLeast(0) * width + x]
            val down = green[(y + 1).coerceAtMost(height - 1) * width + x]
            val contrast = (abs(left - g) + abs(right - g) + abs(up - g) + abs(down - g)) * 0.25f
            val noise = sqrt(maxOf(alpha * maxOf(g, 0f) + beta, 1e-8f))
            val t = ((contrast / noise - 0.65f) / 0.85f).coerceIn(0f, 1f)
            confidence[i] = t * t * (3f - 2f * t)
            rhs[i] = residual
            inverseMass[i] = 1f / (mass + damping)
        }
        val maxCorrection = limitSigma * sqrt(maxOf(alpha * 0.42f + beta, 1e-8f))
        var delta = FloatArray(pixels)
        var next = FloatArray(pixels)
        // Normal equations are fixed across all passes. Damping anchors the
        // correction to the merge, and the texture mask is frozen to avoid
        // amplifying grain into new active pixels on later passes.
        repeat(passes) {
            for (i in 0 until pixels) {
                var residual = rhs[i] - damping * delta[i]
                for (slot in stencil.indices) residual -= stencil[slot][i] * delta[neighbors[slot][i]]
                val limit = maxCorrection * confidence[i]
                next[i] = (delta[i] + step * inverseMass[i] * confidence[i] * residual).coerceIn(-limit, limit)
            }
            val previous = delta
            delta = next
            next = previous
        }
        return Array(height) { y -> Array(width) { x ->
            val i = y * width + x
            val rgb = base[y][x]
            val d = delta[i]
            floatArrayOf(rgb[0] + d / wb[0], green[i] + d, rgb[2] + d / wb[1])
        } }
    }

    fun finishGpu(rgb: ByteBuffer, solve: ByteArray, wb: FloatArray, alpha: Float, beta: Float) {
        val finishStart = System.nanoTime()
        val extra = LongArray(3)
        SrpBridge.NativeFloats().use { scratch ->
            try {
                for (i in extra.indices) {
                    extra[i] = SrpBridge.createResident(handle, pixels.toLong())
                    check(extra[i] != 0L)
                }
                val host = scratch.floats(pixels)
                val data = rgb.asFloatBuffer()
                val plane = host.asFloatBuffer()
                for (i in 0 until pixels) plane.put(i, data.get(3 * i + 1))
                check(SrpBridge.uploadResident(handle, extra[0], host, pixels.toLong()) == 0)
                val params = IntArray(32)
                intArrayOf(width, height, 1, alpha.toRawBits(), beta.toRawBits(), quantum.toRawBits()).copyInto(params)
                val sizes = longArrayOf(counts[4], pixels.toLong(), pixels.toLong(), pixels.toLong())
                val start = System.nanoTime()
                repeat(6) { pass ->
                    val input = 1 + (pass and 1)
                    val buffers = longArrayOf(residents[4], extra[0], extra[input], extra[3 - input])
                    check(SrpBridge.dispatchRes(handle, solve, buffers, sizes, params, pixels.toLong()) == 0)
                    params[2] = 0
                }
                val solveMs = (System.nanoTime() - start) / 1e6
                check(SrpBridge.downloadResident(handle, extra[1], host, pixels.toLong()) == 0)
                for (i in 0 until pixels) {
                    val d = plane.get(i)
                    val p = i * 3
                    data.put(p, data.get(p) + d / wb[0])
                    data.put(p + 1, data.get(p + 1) + d)
                    data.put(p + 2, data.get(p + 2) + d / wb[1])
                }
                println("sr-pipeline: six GPU updates %.2f ms; fit device buffers %.2f MiB".format(
                    solveMs, (counts.sum() + 3L * pixels) * 4 / 1048576.0))
                println("sr-pipeline: fit accumulation %.2f ms; fit finish %.2f ms".format(
                    recordNanos / 1e6, (System.nanoTime() - finishStart) / 1e6))
            } finally {
                for (resident in extra) if (resident != 0L) SrpBridge.freeResident(handle, resident)
            }
        }
    }

    override fun close() {
        for (resident in residents) if (resident != 0L) SrpBridge.freeResident(handle, resident)
    }
}
