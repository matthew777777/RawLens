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

    fun align(reference: HdrMergeFrame, moving: HdrMergeFrame): HdrFlowField? {
        val a = reference.cfa
        val b = moving.cfa
        require(a.width == b.width && a.height == b.height && a.pattern == b.pattern)
        val brightnessMatch = exposureScale(moving) / exposureScale(reference)
        val base = renderModelInput(a, brightnessMatch)
        val alter = renderModelInput(b, 1f)
        val result = processor.runInference(base, alter, MODEL_WIDTH, MODEL_HEIGHT) ?: return null
        val flow = FloatArray(MODEL_WIDTH * MODEL_HEIGHT * 2)
        result.asFloatBuffer().get(flow)
        if (!flow.all(Float::isFinite)) return null
        // A successful native call can still yield an unusable field (e.g. periodic texture).
        // Reject a field that makes exposure-matched proxy correspondence materially worse.
        if (!hasUsableCorrespondence(base, alter, flow)) return null
        return DenseField(flow, a.width.toFloat() / MODEL_WIDTH, a.height.toFloat() / MODEL_HEIGHT)
    }

    fun isReady(): Boolean = processor.isReady

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
        if (valid < tested * 0.8) return false
        return alignedError / (valid * 3) <= identityError / (valid * 3) + 2.55
    }

    private fun renderModelInput(cfa: UnpackedRawCfa, exposure: Float): FloatBuffer {
        val out = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val quadWidth = cfa.width / 2f
        val quadHeight = cfa.height / 2f
        for (y in 0 until MODEL_HEIGHT) for (x in 0 until MODEL_WIDTH) {
            val qx = ((x + 0.5f) * quadWidth / MODEL_WIDTH).coerceIn(0f, quadWidth - 1f)
            val qy = ((y + 0.5f) * quadHeight / MODEL_HEIGHT).coerceIn(0f, quadHeight - 1f)
            val rgb = sampleQuadRgb(cfa, qx, qy)
            out.put((rgb[2] * exposure).coerceIn(0f, 1f) * 255f)
            out.put((rgb[1] * exposure).coerceIn(0f, 1f) * 255f)
            out.put((rgb[0] * exposure).coerceIn(0f, 1f) * 255f)
            out.put(255f)
        }
        return out.apply { rewind() }
    }

    private fun sampleQuadRgb(cfa: UnpackedRawCfa, qx: Float, qy: Float): FloatArray {
        val x0 = floor(qx).toInt().coerceIn(0, cfa.width / 2 - 1)
        val y0 = floor(qy).toInt().coerceIn(0, cfa.height / 2 - 1)
        val x1 = (x0 + 1).coerceAtMost(cfa.width / 2 - 1)
        val y1 = (y0 + 1).coerceAtMost(cfa.height / 2 - 1)
        val a = quadRgb(cfa, x0, y0)
        val b = quadRgb(cfa, x1, y0)
        val c = quadRgb(cfa, x0, y1)
        val d = quadRgb(cfa, x1, y1)
        val fx = qx - x0
        val fy = qy - y0
        return FloatArray(3) { i ->
            (a[i] * (1f - fx) + b[i] * fx) * (1f - fy) +
                (c[i] * (1f - fx) + d[i] * fx) * fy
        }
    }

    private fun quadRgb(cfa: UnpackedRawCfa, qx: Int, qy: Int): FloatArray {
        val rgb = FloatArray(3)
        val counts = IntArray(3)
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
        return rgb
    }

    private fun exposureScale(frame: HdrMergeFrame): Float =
        frame.exposureTimeNanos.toFloat() * frame.sensitivityIso / (frame.aperture * frame.aperture)

    private class DenseField(
        private val flow: FloatArray,
        private val scaleX: Float,
        private val scaleY: Float
    ) : HdrFlowField {
        override fun displacement(x: Int, y: Int): Pair<Float, Float> {
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
            return sample(0) * scaleX to sample(1) * scaleY
        }
    }

    companion object {
        const val MODEL_WIDTH = 512
        const val MODEL_HEIGHT = 384
    }
}
