package com.rawlens.srpipeline.stages

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Two-band MFSR reconstruction (anti oil-painting), CPU reference (desktop
 * oracle; the Vulkan port comes only if it wins on real bursts). Samples are
 * CFA values placed at their reference-frame positions (u, v in sensor px,
 * pixel centres on integers), each with a weight (robustness) and a noise
 * variance.
 *
 * Green:
 *   low  = wide isotropic normalised Gaussian splat (sigmaLow sensor px) on a
 *          2x sensor grid (smooth, bilinear-accurate at sample positions)
 *   high = every sample's residual vs low, splatted with a narrow isotropic
 *          Gaussian (sigmaHigh sensor px) on the output grid, normalised and
 *          faded where the narrow kernel sees little weight (falls back to the
 *          low band: soft, never zippered)
 *   G = low + high: transfer N + L (1 - N), so the merge blur is only the
 *   narrow kernel's (no steering, so no painted strokes).
 *   Optional overlapped 8x8 DCT Wiener on the high band with the exact
 *   per-pixel variance sum(w^2 var) / sum(w)^2, keeping >= grainFloor.
 * Chroma (channels 0 / 2, colour differences): wide splat only.
 * Known-truth bench (static, oracle shifts): see the a5fd2b86 commit.
 */
class TwoBandReconstruction(
    private val width: Int, private val height: Int, private val scale: Int,
    private val params: Params = Params(),
) {
    data class Params(
        val sigmaLow: Double = 1.0,
        val sigmaHigh: Double = 0.35,
        /**
         * Coverage fade c = Nw / (Nw + confK) on the high band. 0.5 faded the
         * whole high band to ~0.77 on a normal 8-frame burst (bench fit
         * T = L + c N (1 - L), c 0.74-0.79 in every band); 0.05 fades holes only.
         */
        val confK: Double = 0.05,
        /**
         * Merge-matched sharpening: Wiener-deconvolve H^strength of the narrow
         * kernel H in the high-band DCT (0 = off, 1 = full inverse). Same as
         * deconvolving a Gaussian of sigmaHigh * sqrt(strength).
         */
        // Known-truth bench (real pipeline, cap 1.5), PSNR / texture 0.5-0.7 c/px at 1x:
        //   strength 0    clean 45.02 / 0.53   dim 39.25 / 0.38
        //   strength 0.25 clean 44.96 / 0.60   dim 38.97 / 0.43   (2x 0.68)
        //   strength 0.5  clean 44.64 / 0.67   dim 38.58 / 0.48
        //   strength 1    clean 43.71 / 0.78   dim 37.56 / 0.59
        val deconvStrength: Double = 0.25,
        /** Largest per-coefficient deconvolution gain. */
        val gainCap: Double = 2.0,
        /**
         * Optical blur (lens + pixel aperture) as a Gaussian sigma in sensor
         * px, deconvolved together with the merge kernel in the same
         * noise-gated Wiener (blurs add in variance:
         * sigma^2 = deconvStrength sigmaHigh^2 + opticsSigma^2). 0 = merge only.
         * 133623 1x static crop (2-px energy row / col, sky noise): 0 4.65 /
         * 3.92, 0.13; 0.5 5.63 / 4.26, 0.21; 0.7 6.57 / 4.84, 0.30 (Wronski
         * merge 4.62 / 4.15, sky 0.27). The app passes the per-shot RT auto
         * radius (capped at 0.6: above it the sky is noisier than the old merge).
         */
        val opticsSigma: Double = 0.5,
        val denoise: Boolean = true,
        val grainFloor: Double = 0.0,
        /**
         * GPU form (one pass over the frames, no stored samples): the low band
         * accumulates on the sensor grid; the high band is the narrow
         * weighted mean minus low, corrected to first order for where the
         * samples sat: high = N/Nw - L(x) - grad L(x) . m/Nw, m = sum w (p - x).
         */
        val singlePass: Boolean = true,
        /**
         * GPU only: steer the narrow kernel along edges (twoband_tensor: the
         * reference grey's structure tensor over 5x5 gradients). Across an edge
         * it stays sigmaHigh wide, along it it stretches by
         * 1 + (maxStretch - 1) coherence^2. An isotropic narrow kernel is sharp
         * on green sites and a 4-neighbour average on R/B sites wherever the
         * frames' offsets leave the green quincunx gaps, which alternates along
         * edges: a zipper. Real burst 170823_957, checkerboard energy on edges
         * (with the default denoise + sharpening): isotropic 1.05, stretch 2
         * 0.85, 3 0.80, Wronski merge 0.81. Known-truth bench, clean 1x PSNR /
         * texture 0.5-0.7 c/px: isotropic 44.96 / 0.60, stretch 2 44.45 / 0.55,
         * 3 43.07 / 0.51 (random isotropic waves are steering's worst case;
         * the bench's random shifts fill the gaps, so it has no zipper to fix);
         * dim 38.97 -> 39.21, moving-edge trail 65.0 -> 68.5 dB at stretch 2.
         */
        val steer: Boolean = true,
        val maxStretch: Double = 2.0,
        /**
         * GPU only: where the weight field alternates on the green quincunx
         * (motion-rejected areas with one or two frames), blend the high band
         * toward its [1 2 1]^2 filter by that alternation (twoband_resolve
         * pass 3): even, organic grain instead of a pixel checkerboard.
         */
        val phaseInvariant: Boolean = true,
        /**
         * GPU only: feather each frame's robustness on the guide grid (erode
         * then average over this many cells, rob_feather.comp) so rejection
         * boundaries are smooth ramps, not the square morphology's blocks.
         * Never raises a weight. 0 = off.
         */
        val featherRadius: Int = 2,
    )

    private class Samples {
        var n = 0
        var data = FloatArray(5 * 4096)
        fun add(u: Double, v: Double, value: Float, w: Float, variance: Float) {
            if (5 * (n + 1) > data.size) data = data.copyOf(data.size * 2)
            val i = 5 * n++
            data[i] = u.toFloat(); data[i + 1] = v.toFloat(); data[i + 2] = value; data[i + 3] = w; data[i + 4] = variance
        }
    }
    private val samples = Array(3) { Samples() }

    /** channel: 0 = R colour difference, 1 = green, 2 = B colour difference. */
    fun add(channel: Int, u: Double, v: Double, value: Float, weight: Float, variance: Float = 0f) {
        if (weight > 0f && value.isFinite()) samples[channel].add(u, v, value, weight, variance)
    }

    private class Grid(val nw: Int, val nh: Int, val sg: Double) {
        val num = DoubleArray(nw * nh); val den = DoubleArray(nw * nh); val varAcc = DoubleArray(nw * nh)
        fun splat(u: Double, v: Double, value: Double, weight: Double, sigma: Double, variance: Double) {
            val gx = (u + 0.5) * sg - 0.5; val gy = (v + 0.5) * sg - 0.5
            val r = 3.0 * sigma * sg
            val x0 = max(0, ceil(gx - r).toInt()); val x1 = min(nw - 1, floor(gx + r).toInt())
            val y0 = max(0, ceil(gy - r).toInt()); val y1 = min(nh - 1, floor(gy + r).toInt())
            val inv = 1.0 / (2 * sigma * sigma * sg * sg)
            for (y in y0..y1) { val dy = y - gy
                for (x in x0..x1) { val dx = x - gx
                    val w = weight * exp(-(dx * dx + dy * dy) * inv)
                    val i = y * nw + x
                    num[i] += w * value; den[i] += w; varAcc[i] += w * w * variance
                } }
        }
        fun value(i: Int) = if (den[i] > 1e-12) num[i] / den[i] else 0.0
        fun at(field: DoubleArray, u: Double, v: Double): Double {
            val gx = ((u + 0.5) * sg - 0.5).coerceIn(0.0, nw - 1.0001); val gy = ((v + 0.5) * sg - 0.5).coerceIn(0.0, nh - 1.0001)
            val x = gx.toInt(); val y = gy.toInt(); val fx = gx - x; val fy = gy - y
            val i = y * nw + x
            return (field[i] * (1 - fx) + field[i + 1] * fx) * (1 - fy) + (field[i + nw] * (1 - fx) + field[i + nw + 1] * fx) * fy
        }
    }

    /** Output planes (row-major, width*scale x height*scale): [R diff, G, B diff]. */
    fun finish(): Array<FloatArray> {
        val p = params
        val ow = width * scale; val oh = height * scale
        val g = samples[1]
        if (p.singlePass) return finishSinglePass(ow, oh)
        val low = Grid(2 * width, 2 * height, 2.0)
        for (k in 0 until g.n) { val i = 5 * k
            low.splat(g.data[i].toDouble(), g.data[i + 1].toDouble(), g.data[i + 2].toDouble(), g.data[i + 3].toDouble(), p.sigmaLow, 0.0) }
        val lowF = DoubleArray(low.nw * low.nh) { low.value(it) }
        val high = Grid(ow, oh, scale.toDouble())
        for (k in 0 until g.n) { val i = 5 * k
            val u = g.data[i].toDouble(); val v = g.data[i + 1].toDouble()
            high.splat(u, v, g.data[i + 2] - low.at(lowF, u, v), g.data[i + 3].toDouble(), p.sigmaHigh, g.data[i + 4].toDouble())
        }
        val conf = DoubleArray(ow * oh) { i -> high.den[i] / (high.den[i] + p.confK) }
        var hf = DoubleArray(ow * oh) { i -> high.value(i) * conf[i] }
        if (p.denoise) {
            val nv = DoubleArray(ow * oh) { i ->
                if (high.den[i] > 1e-12) high.varAcc[i] / (high.den[i] * high.den[i]) * conf[i] * conf[i] else 0.0 }
            hf = dctWiener(hf, nv, ow, oh, p.grainFloor)
        }
        val gOut = FloatArray(ow * oh) { i ->
            val y = i / ow; val x = i % ow
            (low.at(lowF, (x + 0.5) / scale - 0.5, (y + 0.5) / scale - 0.5) + hf[i]).toFloat()
        }
        fun chroma(s: Samples): FloatArray {
            val grid = Grid(ow, oh, scale.toDouble())
            for (k in 0 until s.n) { val i = 5 * k
                grid.splat(s.data[i].toDouble(), s.data[i + 1].toDouble(), s.data[i + 2].toDouble(), s.data[i + 3].toDouble(), p.sigmaLow, 0.0) }
            return FloatArray(ow * oh) { grid.value(it).toFloat() }
        }
        return arrayOf(chroma(samples[0]), gOut, chroma(samples[2]))
    }

    private fun finishSinglePass(ow: Int, oh: Int): Array<FloatArray> {
        val p = params
        val g = samples[1]
        val low = Grid(width, height, 1.0)
        val high = Grid(ow, oh, scale.toDouble())
        val mx = DoubleArray(ow * oh); val my = DoubleArray(ow * oh)
        val sg = scale.toDouble()
        val r = 3.0 * p.sigmaHigh * sg
        val inv = 1.0 / (2 * p.sigmaHigh * p.sigmaHigh * sg * sg)
        for (k in 0 until g.n) { val i = 5 * k
            val u = g.data[i].toDouble(); val v = g.data[i + 1].toDouble(); val value = g.data[i + 2].toDouble()
            val wt = g.data[i + 3].toDouble(); val variance = g.data[i + 4].toDouble()
            low.splat(u, v, value, wt, p.sigmaLow, 0.0)
            val gx = (u + 0.5) * sg - 0.5; val gy = (v + 0.5) * sg - 0.5
            val x0 = max(0, ceil(gx - r).toInt()); val x1 = min(ow - 1, floor(gx + r).toInt())
            val y0 = max(0, ceil(gy - r).toInt()); val y1 = min(oh - 1, floor(gy + r).toInt())
            for (y in y0..y1) { val dy = y - gy
                for (x in x0..x1) { val dx = x - gx
                    val w = wt * exp(-(dx * dx + dy * dy) * inv)
                    val j = y * ow + x
                    high.num[j] += w * value; high.den[j] += w; high.varAcc[j] += w * w * variance
                    mx[j] -= w * dx / sg; my[j] -= w * dy / sg   // sample minus pixel, sensor px
                } }
        }
        val lowF = DoubleArray(width * height) { low.value(it) }
        val gxF = DoubleArray(width * height) { i -> val x = i % width
            (lowF[i + if (x < width - 1) 1 else 0] - lowF[i - if (x > 0) 1 else 0]) / (if (x in 1 until width - 1) 2.0 else 1.0) }
        val gyF = DoubleArray(width * height) { i -> val y = i / width
            (lowF[i + if (y < height - 1) width else 0] - lowF[i - if (y > 0) width else 0]) / (if (y in 1 until height - 1) 2.0 else 1.0) }
        val lOut = DoubleArray(ow * oh); var hf = DoubleArray(ow * oh)
        for (j in 0 until ow * oh) {
            val u = (j % ow + 0.5) / scale - 0.5; val v = (j / ow + 0.5) / scale - 0.5
            val l = low.at(lowF, u, v); lOut[j] = l
            val d = high.den[j]
            if (d > 1e-12) {
                val c = d / (d + p.confK)
                hf[j] = c * (high.num[j] / d - l - (low.at(gxF, u, v) * mx[j] + low.at(gyF, u, v) * my[j]) / d)
            }
        }
        if (p.denoise) {
            val nv = DoubleArray(ow * oh) { i -> val d = high.den[i]
                if (d > 1e-12) { val c = d / (d + p.confK); high.varAcc[i] / (d * d) * c * c } else 0.0 }
            hf = dctWiener(hf, nv, ow, oh, p.grainFloor)
        }
        val gOut = FloatArray(ow * oh) { (lOut[it] + hf[it]).toFloat() }
        fun chroma(s: Samples): FloatArray {
            val grid = Grid(width, height, 1.0)
            for (k in 0 until s.n) { val i = 5 * k
                grid.splat(s.data[i].toDouble(), s.data[i + 1].toDouble(), s.data[i + 2].toDouble(), s.data[i + 3].toDouble(), p.sigmaLow, 0.0) }
            val f = DoubleArray(width * height) { grid.value(it) }
            return FloatArray(ow * oh) { j -> grid.at(f, (j % ow + 0.5) / scale - 0.5, (j / ow + 0.5) / scale - 0.5).toFloat() }
        }
        return arrayOf(chroma(samples[0]), gOut, chroma(samples[2]))
    }

    /** Overlapped (step 4) 8x8 DCT empirical Wiener with per-block noise variance from nv. */
    private fun dctWiener(src: DoubleArray, nv: DoubleArray, nw: Int, nh: Int, floorFrac: Double): DoubleArray {
        val sig = kotlin.math.sqrt(params.deconvStrength.coerceAtLeast(0.0) * params.sigmaHigh * params.sigmaHigh +
            params.opticsSigma * params.opticsSigma)
        val b = 8
        val c = Array(b) { k -> DoubleArray(b) { x -> (if (k == 0) sqrt(1.0 / b) else sqrt(2.0 / b)) * cos(PI * (2 * x + 1) * k / (2 * b)) } }
        val out = DoubleArray(nw * nh); val wsum = DoubleArray(nw * nh)
        val blk = Array(b) { DoubleArray(b) }; val tmp = Array(b) { DoubleArray(b) }
        val win = DoubleArray(b) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / b) }
        var by = 0
        while (by + b <= nh) { var bx = 0
            while (bx + b <= nw) {
                var sv = 0.0
                for (y in 0 until b) for (x in 0 until b) { blk[y][x] = src[(by + y) * nw + bx + x]; sv += nv[(by + y) * nw + bx + x] }
                sv /= b * b
                for (y in 0 until b) for (k in 0 until b) { var s = 0.0; for (x in 0 until b) s += c[k][x] * blk[y][x]; tmp[y][k] = s }
                for (k in 0 until b) for (l in 0 until b) { var s = 0.0; for (y in 0 until b) s += c[l][y] * tmp[y][k]; blk[l][k] = s }
                for (l in 0 until b) for (k in 0 until b) {
                    val e = blk[l][k] * blk[l][k]
                    var g = if (e > 1e-30) max(floorFrac, max(0.0, e - sv) / e) else 1.0
                    if (sig > 0) {
                        val fx = k * scale / 16.0; val fy = l * scale / 16.0
                        g = min(g * (1 + (1 / exp(-2 * PI * PI * sig * sig * (fx * fx + fy * fy)) - 1) * g), params.gainCap)
                    }
                    blk[l][k] *= g
                }
                for (y in 0 until b) for (k in 0 until b) { var s = 0.0; for (l in 0 until b) s += c[l][y] * blk[l][k]; tmp[y][k] = s }
                for (y in 0 until b) for (x in 0 until b) { var s = 0.0; for (k in 0 until b) s += c[k][x] * tmp[y][k]
                    val w = win[y] * win[x]; out[(by + y) * nw + bx + x] += w * s; wsum[(by + y) * nw + bx + x] += w }
                bx += 4 }
            by += 4 }
        return DoubleArray(nw * nh) { if (wsum[it] > 1e-9) out[it] / wsum[it] else src[it] }
    }
}

/**
 * Desktop recorder: takes each frame from VulkanPipeline's frameObserver
 * (colour-difference CFA frame, per-tile flow in sensor px: reference
 * position r maps to frame position r + flow, guide-grid robustness at half
 * resolution) and runs [TwoBandReconstruction] over the whole (cropped) frame.
 */
class TwoBandRecorder(private val width: Int, private val height: Int, private val config: PipelineConfig,
                      private val params: TwoBandReconstruction.Params = TwoBandReconstruction.Params()) {
    private val rec = TwoBandReconstruction(width, height, config.scale.toInt(), params)
    private val alpha = config.kernelAlpha.average().toFloat()
    private val beta = config.kernelBeta.average().toFloat()
    var frames = 0; private set

    fun record(frame: java.nio.ByteBuffer, flow: Array<Array<FloatArray>>, rob: java.nio.ByteBuffer) {
        val fb = frame.duplicate().order(java.nio.ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
        val rb = rob.duplicate().order(java.nio.ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
        val tile = config.tileSizes.first()
        val rw = width / 2; val rh = height / 2
        for (y in 0 until height) for (x in 0 until width) {
            val f = flow[min(y / tile, flow.lastIndex)][min(x / tile, flow[0].lastIndex)]
            val u = x - f[0].toDouble(); val v = y - f[1].toDouble()
            if (u < -0.5 || v < -0.5 || u > width - 0.5 || v > height - 0.5) continue
            val r = rb.get(((v + 0.5) / 2).toInt().coerceIn(0, rh - 1) * rw + ((u + 0.5) / 2).toInt().coerceIn(0, rw - 1)).coerceIn(0f, 1f)
            val value = fb.get(y * width + x)
            val ch = if ((x + y) and 1 == 1) 1 else if (y and 1 == 0) 0 else 2
            rec.add(ch, u, v, value, r, if (ch == 1) max(0f, alpha * max(value, 0f) + beta) else 0f)
        }
        frames++
    }

    /** Writes [R, G, B] (camera RGB after ColourDiff.reconstruct) into the pipeline's output buffer. */
    fun finish(rgb: java.nio.ByteBuffer) {
        val planes = rec.finish()
        val wb = requireNotNull(config.colourDiff)
        val dst = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
        val n = planes[1].size
        for (i in 0 until n) { dst.put(3 * i, planes[0][i]); dst.put(3 * i + 1, planes[1][i]); dst.put(3 * i + 2, planes[2][i]) }
        ColourDiff.reconstruct(dst, n, wb[0], wb[1])
    }
}
