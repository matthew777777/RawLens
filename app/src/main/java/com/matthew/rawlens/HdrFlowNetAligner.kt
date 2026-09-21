// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/** PhotonCamera FlowNet-v2 adapter for normalized RawLens Bayer frames. */
class HdrFlowNetAligner(context: Context) {
    private val processor = FlowNetNcnnProcessor.start(context.applicationContext)

    /** Per-stage cost of the last [align] call (any thread). */
    data class Timings(val renderMs: Long, val inferMs: Long, val checkMs: Long)
    @Volatile var lastTimings = Timings(0, 0, 0)
        private set

    fun align(reference: HdrMergeFrame, moving: HdrMergeFrame): HdrFlowField? {
        val brightnessMatch = exposureScale(moving) / exposureScale(reference)
        val base = scaleInput(renderRawInput(reference.cfa), brightnessMatch)
        return alignWithBase(base, moving)
    }

    /**
     * Unscaled RGBA model input for [cfa] (alpha 255; RGB raw×255, unclamped).
     * Render once per bracket; per-frame exposure matching is a cheap linear
     * pass in [scaleInput]. Removes ~1s of repeated reference renders per bracket.
     */
    fun renderRawInput(cfa: UnpackedRawCfa): FloatBuffer = renderModelInput(cfa, 1f, raw = true)

    /** Exposure-matches a [renderRawInput] buffer (RGB×exposure→255 clamp; alpha kept). */
    fun scaleInput(raw: FloatBuffer, exposure: Float): FloatBuffer {
        val dup = raw.duplicate()
        val out = ByteBuffer.allocateDirect(dup.remaining() * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        var i = 0
        while (dup.hasRemaining()) {
            val v = dup.get()
            out.put(if (i % 4 == 3) v else (v * exposure).coerceIn(0f, 255f))
            i++
        }
        return out.apply { rewind() }
    }

    fun alignWithBase(base: FloatBuffer, moving: HdrMergeFrame): HdrFlowField? {
        // Base carries the reference frame's pixels; geometry must match it.
        val a = moving.cfa
        require(a.width % 2 == 0 && a.height % 2 == 0)
        var t = System.nanoTime()
        val alter = renderModelInput(moving.cfa, 1f)
        val renderMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        val result = processor.runInference(base, alter, MODEL_WIDTH, MODEL_HEIGHT)
        val inferMs = (System.nanoTime() - t) / 1_000_000
        if (result == null) {
            lastTimings = Timings(renderMs, inferMs, 0)
            return null
        }
        t = System.nanoTime()
        val flow = FloatArray(MODEL_WIDTH * MODEL_HEIGHT * 2)
        result.asFloatBuffer().get(flow)
        if (!flow.all(Float::isFinite)) {
            lastTimings = Timings(renderMs, inferMs, (System.nanoTime() - t) / 1_000_000)
            return null
        }
        // Bound outlier damage (e.g. periodic texture runaway): model pixels
        // past this are never plausible residuals, let alone full shifts.
        // Clamping happens before validation so the check judges the field
        // that would actually warp.
        clampFlow(flow)
        // A successful native call can still yield an unusable field (e.g. periodic texture).
        // Reject anything that does not strictly improve exposure-matched
        // proxy correspondence: a field that merely ties identity still
        // resamples (softens) with zero geometric benefit.
        if (!hasUsableCorrespondence(base, alter, flow)) {
            lastTimings = Timings(renderMs, inferMs, (System.nanoTime() - t) / 1_000_000)
            return null
        }
        lastTimings = Timings(renderMs, inferMs, (System.nanoTime() - t) / 1_000_000)
        return DenseField(flow, a.width.toFloat() / MODEL_WIDTH, a.height.toFloat() / MODEL_HEIGHT)
    }

    fun isReady(): Boolean = processor.isReady

    internal fun exposureMatchScale(moving: HdrMergeFrame, reference: HdrMergeFrame): Float =
        exposureScale(moving) / exposureScale(reference)

    private fun hasUsableCorrespondence(base: FloatBuffer, moving: FloatBuffer, flow: FloatArray): Boolean {
        var alignedError = 0.0
        var identityError = 0.0
        var valid = 0
        var tested = 0
        for (y in 8 until MODEL_HEIGHT - 8 step 8) for (x in 8 until MODEL_WIDTH - 8 step 8) {
            val p = y * MODEL_WIDTH + x
            val sx = x + flow[p * 2]
            val sy = y + flow[p * 2 + 1]
            tested++
            if (sx < 0f || sy < 0f || sx >= MODEL_WIDTH - 1f || sy >= MODEL_HEIGHT - 1f) continue
            val ix = sx.toInt(); val iy = sy.toInt()
            val fx = sx - ix; val fy = sy - iy
            for (c in 0..2) {
                val ref = base.get(p * 4 + c)
                val a = moving.get((iy * MODEL_WIDTH + ix) * 4 + c)
                val b = moving.get((iy * MODEL_WIDTH + ix + 1) * 4 + c)
                val d = moving.get(((iy + 1) * MODEL_WIDTH + ix) * 4 + c)
                val e = moving.get(((iy + 1) * MODEL_WIDTH + ix + 1) * 4 + c)
                val warped = (a * (1f - fx) + b * fx) * (1f - fy) +
                    (d * (1f - fx) + e * fx) * fy
                alignedError += kotlin.math.abs(ref - warped)
                identityError += kotlin.math.abs(ref - moving.get(p * 4 + c))
            }
            valid++
        }
        if (valid < tested * 0.85) return false
        return alignedError <= identityError
    }

    private fun clampFlow(flow: FloatArray) {
        for (i in flow.indices) {
            flow[i] = flow[i].coerceIn(-MAX_MODEL_FLOW, MAX_MODEL_FLOW)
        }
    }

    private fun renderModelInput(cfa: UnpackedRawCfa, exposure: Float, raw: Boolean = false): FloatBuffer {
        val out = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val quadWidth = cfa.width / 2f
        val quadHeight = cfa.height / 2f
        // Scratch reused across all model pixels (was: 9 small arrays per pixel).
        val qa = FloatArray(3)
        val qb = FloatArray(3)
        val qc = FloatArray(3)
        val qd = FloatArray(3)
        val counts = IntArray(3)
        val rgb = FloatArray(3)
        for (y in 0 until MODEL_HEIGHT) for (x in 0 until MODEL_WIDTH) {
            val qx = ((x + 0.5f) * quadWidth / MODEL_WIDTH).coerceIn(0f, quadWidth - 1f)
            val qy = ((y + 0.5f) * quadHeight / MODEL_HEIGHT).coerceIn(0f, quadHeight - 1f)
            sampleQuadRgb(cfa, qx, qy, qa, qb, qc, qd, counts, rgb)
            if (raw) {
                out.put(rgb[2] * 255f)
                out.put(rgb[1] * 255f)
                out.put(rgb[0] * 255f)
            } else {
                out.put((rgb[2] * exposure).coerceIn(0f, 1f) * 255f)
                out.put((rgb[1] * exposure).coerceIn(0f, 1f) * 255f)
                out.put((rgb[0] * exposure).coerceIn(0f, 1f) * 255f)
            }
            out.put(255f)
        }
        return out.apply { rewind() }
    }

    private fun sampleQuadRgb(
        cfa: UnpackedRawCfa, qx: Float, qy: Float,
        a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray,
        counts: IntArray, out: FloatArray
    ) {
        val x0 = floor(qx).toInt().coerceIn(0, cfa.width / 2 - 1)
        val y0 = floor(qy).toInt().coerceIn(0, cfa.height / 2 - 1)
        val x1 = (x0 + 1).coerceAtMost(cfa.width / 2 - 1)
        val y1 = (y0 + 1).coerceAtMost(cfa.height / 2 - 1)
        quadRgb(cfa, x0, y0, a, counts)
        quadRgb(cfa, x1, y0, b, counts)
        quadRgb(cfa, x0, y1, c, counts)
        quadRgb(cfa, x1, y1, d, counts)
        val fx = qx - x0
        val fy = qy - y0
        for (i in 0..2) {
            out[i] = (a[i] * (1f - fx) + b[i] * fx) * (1f - fy) +
                (c[i] * (1f - fx) + d[i] * fx) * fy
        }
    }

    private fun quadRgb(cfa: UnpackedRawCfa, qx: Int, qy: Int, rgb: FloatArray, counts: IntArray) {
        rgb[0] = 0f; rgb[1] = 0f; rgb[2] = 0f
        counts[0] = 0; counts[1] = 0; counts[2] = 0
        for (dy in 0..1) for (dx in 0..1) {
            val x = qx * 2 + dx
            val y = qy * 2 + dy
            val channel = when (cfa.pattern.colorAt(x, y)) {
                CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
            }
            rgb[channel] += cfa.values[y * cfa.width + x]
            counts[channel]++
        }
        for (i in 0..2) rgb[i] /= counts[i].coerceAtLeast(1)
    }

    private fun exposureScale(frame: HdrMergeFrame): Float =
        frame.exposureTimeNanos.toFloat() * frame.sensitivityIso / (frame.aperture * frame.aperture)

    internal class DenseField(
        private val flow: FloatArray,
        private val scaleX: Float,
        private val scaleY: Float
    ) : FastFlow {
        override fun displacement(x: Int, y: Int): Pair<Float, Float> {
            val tmp = FloatArray(2)
            sampleInto(x, y, tmp)
            return tmp[0] to tmp[1]
        }

        override fun sampleInto(x: Int, y: Int, out: FloatArray) {
            val px = ((x + 0.5f) / scaleX - 0.5f).coerceIn(0f, MODEL_WIDTH - 1f)
            val py = ((y + 0.5f) / scaleY - 0.5f).coerceIn(0f, MODEL_HEIGHT - 1f)
            val mx = px.toInt(); val my = py.toInt()
            val nx = (mx + 1).coerceAtMost(MODEL_WIDTH - 1)
            val ny = (my + 1).coerceAtMost(MODEL_HEIGHT - 1)
            val fx = px - mx; val fy = py - my
            fun sample(channel: Int): Float {
                val a = flow[(my * MODEL_WIDTH + mx) * 2 + channel]
                val b = flow[(my * MODEL_WIDTH + nx) * 2 + channel]
                val c = flow[(ny * MODEL_WIDTH + mx) * 2 + channel]
                val d = flow[(ny * MODEL_WIDTH + nx) * 2 + channel]
                return (a * (1f - fx) + b * fx) * (1f - fy) +
                    (c * (1f - fx) + d * fx) * fy
            }
            out[0] = sample(0) * scaleX
            out[1] = sample(1) * scaleY
        }
    }

    companion object {
        const val MODEL_WIDTH = 512
        const val MODEL_HEIGHT = 384
        /** Per-axis model-px clamp for raw FlowNet output (outlier bound). */
        const val MAX_MODEL_FLOW = 32f
    }
}
