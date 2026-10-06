// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.util.Log
import com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * PhotonCamera KernelNet anisotropic weights for the SR merge.
 *
 * The analytic path ([RawSrKernelCovariance]) estimates kernel shape from local
 * image gradients (structure tensor + linear selection law). On busy texture
 * that estimator fires on noise/quantization gradients and smears neighbours
 * into locally flat, segmented blends — the "oil painting" artifact. KernelNet
 * is a tiny learned parameter model (~9.6k params) that predicts the
 * anisotropic kernel shape (s1, s2, rho) from sqrt-compressed luma + a scalar
 * noise estimate; using its output directly for the merge precision keeps
 * edges narrow along the true edge direction instead of the gradient-noise
 * direction.
 *
 * Model provenance: PhotonCamera
 * `kernelnet_aniso_v2_2_params.ncnn.{param,bin}` (see NOTICE.md). The input
 * domain matches upstream exactly (`merge/mergeGrayscale.glsl` and
 * `upscalecrop/singleluma.glsl`): quad-resolution sqrt-compressed quad-mean
 * luma — one sample per 2x2 Bayer quad — with the noise sigma evaluated at
 * mid-brightness (`sqrt(S*0.5+O)`, pre-merge-inflation). Feeding full-res
 * mosaiced luma instead is out-of-distribution: the model never saw Bayer
 * oscillation in training and answers with over-sharp, mosaic-oriented
 * kernels that zipper along every edge. The model halves the input
 * internally, so its output grid is quarter-res of the CFA; [samplePlane]
 * resamples it onto the quad grid with the same bilinear rule upstream's
 * linear-filtered `texture(kernelsMap)` sampling applies.
 *
 * s1/s2/rho -> precision conversion is PhotonCamera's merge weights
 * (`merge/mergeCombineWeight*.glsl`, same convention in
 * `upscalecrop/anisoupscale.glsl`) verbatim: s1 is the y-sigma and s2 the
 * x-sigma (`a = 1/(s1^2*det)` is the dy^2 coefficient, `c = 1/(s2^2*det)`
 * the dx^2 one). Swapping them rotates every anisotropic kernel 90 degrees
 * — narrow along the edge instead of across it — which starves the
 * along-edge taps and zippers. Sigmas arrive in model-input-texel units
 * (quads), and the SR merge evaluates `w = exp(-0.5 d^T P d)` with `d` in
 * quad pixels while upstream consumers evaluate `exp(-q)`, so
 * `P_quad = 2 * M(s)` makes `z/2` exactly upstream's `q`. The model is a
 * denoising-kernel predictor and its output converts verbatim, exactly
 * like upstream; unusable triples (non-finite or sub-[SIGMA_MIN] axes)
 * are the only rejections (caller substitutes fallback).
 *
 * Two consumer-driven guards ([zipperGates], desktop `--zipper-gates`)
 * adapt the denoising predictions to the CFA-merge consumer, which
 * starves below the same-colour tap lattice: the width cap
 * ([KERNEL_SIGMA_MAX]) trims the saturated wide end, and the kernel-area
 * floor ([MIN_KERNEL_AREA]) guarantees total support. Each binds a
 * different regime — saturated darks/flats for the cap, razor texture
 * and weak diagonals for the floor — and mid sigmas pass through
 * untouched. A narrow-axis clamp
 * ([MosaicSrReconstructor.clampMinorAxisInPlace]) over the converted
 * field (plus the analytic-field clamp in [RawSrMergeJob.mergeFrame])
 * floors each kernel's narrow axis at
 * [MosaicSrReconstructor.MIN_MINOR_SIGMA], since the floor above cannot
 * see orientation: a strong edge keeps its ratio and stays sub-pixel
 * sharp while its across-axis never starves its own lattice.
 *
 * Every failure mode (model absent, not ready, inference error, OOM,
 * non-finite output) falls back per-pixel — or wholesale — to the analytic
 * field the caller already computed. The merge stores covariance, so the
 * precision-space conversion is inverted at the boundary; the packing is the
 * same [RawSrKernelCovariance.MatrixField] `(m00, m01, m10, m11)` either way.
 */
object RawSrKernelNetAniso {
    private const val TAG = "RawLensKernelNet"
    private const val DET_FLOOR = 1e-4
    private const val SIGMA_MIN = 1e-3
    private const val RHO_MAX = 0.999f
    /**
     * Merge-exponent bridge: sigmas arrive in model-input-texel units
     * (quads, the [lumaPlane] grid) while the SR merge evaluates
     * `exp(-0.5 d^T P d)` and upstream consumers evaluate `exp(-q)`, so
     * `P_quad = 2 * M(s)` makes `z/2` exactly upstream's `q`.
     */
    const val QUAD_PRECISION_SCALE = 2.0
    /**
     * Minimum kernel support σ1σ2 in quad². The merge routes each tap to
     * its own CFA channel, so a kernel narrower than the same-colour tap
     * lattice (~1 quad) collapses to a nearest-tap lottery and speckles
     * chroma in texture and weak-diagonal areas. 0.2 lifts isotropic
     * texture to σ ≈ 0.45 while a strong edge keeps its ratio and stays
     * sub-pixel sharp.
     */
    const val MIN_KERNEL_AREA = 0.2
    /**
     * A/B override for the kernel-area floor (tests / textured-target
     * evaluation). The default is [MIN_KERNEL_AREA]; lowering it toward the
     * analytic texture width trades chroma speckle for micro-contrast, and
     * the per-burst stats log tells which side a scene lands on. Read once
     * per triple, so a mid-burst flip only affects later quads (tests flip
     * between frames, never inside one).
     */
    @Volatile var kernelAreaFloor = MIN_KERNEL_AREA
    /**
     * Maximum model sigma (either axis) admitted into the merge. The model
     * saturates near s = 2 in darks and flats, but the multi-frame average
     * already denoises (~√N), so per-frame kernels need not smooth beyond
     * σ ≈ 0.71 quads (≈1.4 raw px — the widest admissible smoothing;
     * anything wider mushes detail and mottles shadows as the per-channel
     * smoothing varies faster than the signal). The joint scale preserves
     * the anisotropy ratio and orientation exactly; edges and texture below
     * the cap pass through untouched.
     */
    const val KERNEL_SIGMA_MAX = 0.71
    /**
     * Zipper-gate master switch (desktop `--zipper-gates`). ON restores the
     * consumer-driven guards: the width cap ([KERNEL_SIGMA_MAX]) on raw
     * model sigmas, the kernel-area floor ([kernelAreaFloor]) on the
     * scaled triple, and the narrow-axis clamp
     * ([MosaicSrReconstructor.clampMinorAxisInPlace]) over every produced
     * field — plus the analytic-field clamp in [RawSrMergeJob.mergeFrame].
     * OFF (default) converts verbatim with the multiplier law only,
     * preserving the reference-parity base path bitwise.
     */
    @Volatile var zipperGates = false

    /**
     * Master switch. ON by default: KernelNet covariances with auto-sigma
     * ([sigmaFor]) are the default merge path on every backend. Set false
     * for the analytic opt-out (desktop `--no-kernelnet`): every entry
     * point then returns the analytic field (or null for the kernel-only
     * bridge) without touching the model, and [RawSrMergeJob] skips the
     * noise-ratio measurement.
     */
    @Volatile var enabled = true

    /**
     * Test-only multiplier on [sigmaFor] (desktop `--kernelnet-sigma-mpy`,
     * default 1.0). Mirrors upstream PhotonCamera's `ESD4D.noiseMpy`
     * ("Network merge noise multiplier", default 1.0): scales the scalar
     * noise estimate fed to the model. Higher widens the predicted kernels
     * (more denoise), lower sharpens them. No-op at the default; clamped to
     * a positive floor so a bad CLI value cannot invert the model input.
     */
    @Volatile var sigmaMpy = 1.0f

    /**
     * Default predicted-kernel scaling: 0.5 halves s1/s2 (precision ×4) for
     * sharper SR-merge kernels, per eszdman's recommendation for reusing
     * single-frame denoising predictions in a merge context (wall-crop A/B:
     * +27% Laplacian energy over verbatim with the support floor in place).
     * Upstream precedent: `dynamicgaussian_kernelnet.glsl` scales the same
     * predictions by `sqrt(postVar/preVar)` when denoising a merged image.
     * 1.0 restores the verbatim upstream law. The narrow end is fenced by
     * [minSigma]: unclamped ×0.5 starves kernels below the sample lattice
     * (wall crop: worst-checker 0.12 vs 0.06 verbatim).
     */
    const val DEFAULT_KERNEL_SIGMA_MPY = 0.5f

    /**
     * Multiplier on the predicted kernel sigmas (desktop
     * `--kernelnet-kernel-mpy`, default [DEFAULT_KERNEL_SIGMA_MPY]).
     * Applied post-model in [precisionOf], after the usability gate, then
     * fenced below by [minSigma]. Scales the shorter model axis always;
     * the longer axis keeps [kernelSigmaMajorMpy] where the native
     * anisotropy clears [MAJOR_ANISO_LO]. Clamped to a positive floor.
     */
    @Volatile var kernelSigmaMpy = DEFAULT_KERNEL_SIGMA_MPY

    /**
     * Default longer-axis multiplier: 1.0 keeps the model's verbatim
     * along-edge support. Symmetric x0.5 halves the longer axis too, which
     * collapses edge kernels to near-isotropic floor blobs (slat crop:
     * median aniso 1.11 vs analytic 2.49) with too few along-edge taps:
     * each output pixel's green quotient latches onto 1-2
     * Bayer-phase-dependent taps and the latch phase disagrees along the
     * edge (zipper teeth, worst on green since R/B already merge 2x
     * wider). Keeping the longer axis restores analytic-like along-edge
     * support (model peak / sqrt(2) ~= analytic major) while the shorter
     * axis keeps the full x0.5 across-edge sharpening, so edges stay
     * sharp across and average clean along. 0.5 restores the symmetric
     * law bit-exactly.
     */
    const val DEFAULT_KERNEL_SIGMA_MAJOR_MPY = 1.0f

    /**
     * Multiplier on the longer predicted axis (desktop
     * `--kernelnet-major-mpy`, default
     * [DEFAULT_KERNEL_SIGMA_MAJOR_MPY]). Gated by native model
     * anisotropy ([MAJOR_ANISO_LO]/[MAJOR_ANISO_HI]): near-isotropic
     * predictions (flats, texture) stay symmetric so noise never invents
     * an orientation. Clamped to a positive floor.
     */
    @Volatile var kernelSigmaMajorMpy = DEFAULT_KERNEL_SIGMA_MAJOR_MPY

    /**
     * Native-anisotropy window fading the asymmetric law in: at or below
     * [MAJOR_ANISO_LO] both axes scale by [kernelSigmaMpy] (symmetric,
     * current behavior); at or above [MAJOR_ANISO_HI] the longer axis
     * scales by [kernelSigmaMajorMpy] fully. Inferred model stats put
     * flats at aniso ~1.02-1.06 and edge kernels one-floored (native
     * aniso > ~1.3), so the window separates confident edge direction
     * from isotropic noise with a continuous ramp (no seam).
     */
    const val MAJOR_ANISO_LO = 1.2f

    /** Upper end of the asymmetric-law fade window (see [MAJOR_ANISO_LO]). */
    const val MAJOR_ANISO_HI = 1.5f

    /**
     * Minimum effective kernel axis in quad units, applied post-multiplier
     * in [precisionOf] (desktop `--kernelnet-min-sigma`). Below ~0.3 quads a
     * kernel covers fewer samples than the 8-frame Bayer lattice provides
     * per output pixel (~0.5-quad effective same-channel spacing), so each
     * pixel grabs 1-2 samples with alignment-dependent weights: the
     * checkerboard/maze. Field dumps on the reference burst show maze quads
     * at effective smax 0.27 (near-isotropic, model peak ~0.55 — the model's
     * own width prediction does NOT separate maze from detail, so a
     * peak-gated ramp was tried and dropped) vs clean texture at 0.48; the
     * floor lifts the starved end onto supported widths while mid kernels
     * keep the full ×0.5 sharpening (sweep: 0.45 kills the wall maze at
     * 0.075 worst-checker vs 0.122 unclamped, keeping +27% Laplacian over
     * verbatim). Tuned on the 8-frame reference burst; fewer frames widen
     * the effective spacing and may want a higher floor. 0 disables.
     */
    const val DEFAULT_MIN_SIGMA = 0.45f

    /** Test/CLI override for the support floor (default above). */
    @Volatile var minSigma = DEFAULT_MIN_SIGMA

    /** Idempotent background preload; call from the activity/controller startup path. */
    fun preload(context: Context) {
        runCatching { KernelNetNcnnProcessor.start(context.applicationContext) }
    }

    /** Non-blocking readiness probe (never blocks the save thread on init). */
    fun isReady(): Boolean = try {
        KernelNetNcnnProcessor.getInstance()?.isReady == true
    } catch (_: Throwable) {
        false
    }

    /**
     * Auto-sigma ceiling: the [RawSrFrameNoiseMeter] ratio reads high on
     * texture (3.9x on the reference burst, where 2x was the visual sweet
     * spot), so the upward correction caps here instead of tracking the
     * raw ratio into over-smoothing.
     */
    const val AUTO_SIGMA_MAX = 2.0f

    /**
     * Per-frame noise sigma in the normalized domain, matching upstream
     * (`ESD4D.kernelSigma`, pre-merge-inflation): Poisson-Gaussian sigma at
     * mid-brightness, `sqrt(S*0.5+O)`, over the averaged sensor model — not
     * at the frame mean. The model keys its denoise width off this scalar;
     * a dark frame's mean sigma lands on its sharp cliff and starves every
     * kernel, while the mid-brightness sigma sits on the wide plateau the
     * model was calibrated for.
     *
     * [measuredRatio] is the [RawSrFrameNoiseMeter] measured/profile ratio
     * for the burst reference: an understated OEM profile feeds the model
     * too little noise and it invents maze in flats, so the ratio corrects
     * upward (clamped to [1, [AUTO_SIGMA_MAX]]), stacking with [sigmaMpy].
     * The correction never goes below 1: underestimating noise is the worse
     * failure mode, and the meter reads ~5% low by construction.
     */
    fun sigmaFor(noiseProfile: ImmutableDoubleValues?, measuredRatio: Float? = null): Float {
        val model = CfaNoiseModel.from(noiseProfile)
        val auto = measuredRatio
            ?.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(1.0f, AUTO_SIGMA_MAX) ?: 1.0f
        return RawNindPack.sigmaFor(0.5f, model.averageScale, model.averageOffset) *
            auto * sigmaMpy.coerceAtLeast(1e-3f)
    }

    /**
     * Quad-resolution sqrt-compressed quad-mean luma plane (upstream training
     * domain: `merge/mergeGrayscale.glsl`, `upscalecrop/singleluma.glsl`) as
     * a direct buffer for JNI. One sample per 2x2 Bayer quad: the mean of the
     * four CFA sites (each clamped to [0,1], non-finite treated as 0),
     * square-rooted. Quad means are CFA-agnostic and mosaic-free; feeding
     * per-pixel mosaiced luma instead is out-of-distribution and the model
     * answers with over-sharp, mosaic-oriented kernels. When [scratch] is
     * direct with capacity for the whole plane it is filled from its base
     * and returned; otherwise a fresh buffer is allocated.
     */
    fun lumaPlane(cfa: UnpackedRawCfa, scratch: java.nio.FloatBuffer? = null): java.nio.FloatBuffer {
        require(cfa.width % 2 == 0 && cfa.height % 2 == 0) {
            "KernelNet luma needs an even CFA crop"
        }
        val outW = cfa.width / 2
        val outH = cfa.height / 2
        val n = outW * outH
        val buf = if (scratch != null && scratch.isDirect && scratch.capacity() >= n) {
            scratch.clear()
            scratch
        } else {
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        val values = cfa.values
        val w = cfa.width
        // Row-sharded with indexed puts into disjoint plane slots (same
        // values, same slots as the serial loop — order immaterial).
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            for (qy in y0 until y1) {
                val row = qy * 2 * w
                for (qx in 0 until outW) {
                    val o = row + qx * 2
                    var mean = 0.0
                    for (k in 0..3) {
                        val v = values[o + (k / 2) * w + (k % 2)].coerceIn(0f, 1f)
                        if (v.isFinite()) mean += v
                    }
                    buf.put(qy * outW + qx, sqrt((mean * 0.25).toFloat()))
                }
            }
        }
        buf.rewind()
        return buf
    }

    /**
     * Bilinear resampling of one channel-major model-output plane onto the
     * quad grid, mirroring upstream's linear-filtered `texture(kernelsMap)`
     * sampling: quad (qx, qy) reads plane-texel coordinates
     * `(qx * planeW / outW, qy * planeH / outH)` with texel centers at
     * half-integers and clamped edges (same convention as the merge's
     * precision interpolation). [planes] holds the three [s1][s2][rho]
     * planes back to back, each `planeW * planeH` floats; [plane] selects
     * one. Returns NaN when any tap in the 2x2 window is non-finite or the
     * coordinates are (caller falls back).
     */
    internal fun samplePlane(
        planes: java.nio.FloatBuffer,
        plane: Int,
        planeW: Int,
        planeH: Int,
        qx: Int,
        qy: Int,
        outW: Int,
        outH: Int
    ): Float {
        require(plane in 0..2 && planeW > 0 && planeH > 0 && outW > 0 && outH > 0)
        val gx = qx.toDouble() * planeW / outW - 0.5
        val gy = qy.toDouble() * planeH / outH - 0.5
        if (!gx.isFinite() || !gy.isFinite()) return Float.NaN
        val cx = gx.coerceIn(0.0, (planeW - 1).toDouble())
        val cy = gy.coerceIn(0.0, (planeH - 1).toDouble())
        val x0 = floor(cx).toInt().coerceIn(0, planeW - 1)
        val y0 = floor(cy).toInt().coerceIn(0, planeH - 1)
        val x1 = minOf(x0 + 1, planeW - 1)
        val y1 = minOf(y0 + 1, planeH - 1)
        val fx = (cx - x0).toFloat()
        val fy = (cy - y0).toFloat()
        val base = plane * planeW * planeH
        val v00 = planes.get(base + y0 * planeW + x0)
        val v10 = planes.get(base + y0 * planeW + x1)
        val v01 = planes.get(base + y1 * planeW + x0)
        val v11 = planes.get(base + y1 * planeW + x1)
        if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) {
            return Float.NaN
        }
        return v00 * (1f - fx) * (1f - fy) + v10 * fx * (1f - fy) +
            v01 * (1f - fx) * fy + v11 * fx * fy
    }

    internal fun samplePlane(
        planes: FloatArray,
        plane: Int,
        planeW: Int,
        planeH: Int,
        qx: Int,
        qy: Int,
        outW: Int,
        outH: Int
    ): Float {
        require(plane in 0..2 && planeW > 0 && planeH > 0 && outW > 0 && outH > 0)
        val gx = qx.toDouble() * planeW / outW - 0.5
        val gy = qy.toDouble() * planeH / outH - 0.5
        if (!gx.isFinite() || !gy.isFinite()) return Float.NaN
        val cx = gx.coerceIn(0.0, (planeW - 1).toDouble())
        val cy = gy.coerceIn(0.0, (planeH - 1).toDouble())
        val x0 = floor(cx).toInt().coerceIn(0, planeW - 1)
        val y0 = floor(cy).toInt().coerceIn(0, planeH - 1)
        val x1 = minOf(x0 + 1, planeW - 1)
        val y1 = minOf(y0 + 1, planeH - 1)
        val fx = (cx - x0).toFloat()
        val fy = (cy - y0).toFloat()
        val base = plane * planeW * planeH
        val v00 = planes[base + y0 * planeW + x0]
        val v10 = planes[base + y0 * planeW + x1]
        val v01 = planes[base + y1 * planeW + x0]
        val v11 = planes[base + y1 * planeW + x1]
        if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) {
            return Float.NaN
        }
        return v00 * (1f - fx) * (1f - fy) + v10 * fx * (1f - fy) +
            v01 * (1f - fx) * fy + v11 * fx * fy
    }

    /**
     * Per-triple guard report for [precisionOf]: single decision source for
     * the width cap and the area floor, so the merge conversion and the
     * per-burst stats log cannot disagree about which regime a triple took.
     * With [zipperGates] off only REJECTED reports (the verbatim law).
     */
    internal object KernelTripleFlags {
        const val CAPPED = 1
        const val FLOORED = 2
        const val REJECTED = 4
    }

    /**
     * Classify one KernelNet output triple without converting it. Mirrors
     * the [precisionOf] gates exactly (same thresholds, same order):
     * unusable triples report REJECTED, otherwise the cap and floor bits
     * report which guards engaged. The floor bit sees the scaled triple
     * (cap, then the multiplier law, then the floor check), exactly like
     * the converter. With [zipperGates] off usable triples report 0.
     */
    internal fun classifyTriple(s1: Float, s2: Float, rho: Float): Int {
        if (!s1.isFinite() || !s2.isFinite() || !rho.isFinite()) return KernelTripleFlags.REJECTED
        if (s1 < SIGMA_MIN || s2 < SIGMA_MIN) return KernelTripleFlags.REJECTED
        if (!zipperGates) return 0
        var flags = 0
        var c1 = s1.toDouble()
        var c2 = s2.toDouble()
        val peak = maxOf(s1, s2)
        if (peak > KERNEL_SIGMA_MAX) {
            flags = flags or KernelTripleFlags.CAPPED
            val kc = KERNEL_SIGMA_MAX / peak
            c1 *= kc
            c2 *= kc
        }
        // Multiplier law (mirrors [precisionOf] op for op): the floor bit
        // reports the scaled triple the converter floors.
        val m = kernelSigmaMpy.coerceAtLeast(1e-3f).toDouble()
        val floor = minSigma.toDouble().coerceAtLeast(0.0)
        val mMaj = kernelSigmaMajorMpy.coerceAtLeast(1e-3f).toDouble()
        val aniso = maxOf(c1, c2) / minOf(c1, c2)
        val t = ((aniso - MAJOR_ANISO_LO) / (MAJOR_ANISO_HI - MAJOR_ANISO_LO)).coerceIn(0.0, 1.0)
        val mLong = m + (mMaj - m) * t
        val m1 = if (c1 >= c2) mLong else m
        val m2 = if (c2 > c1) mLong else m
        val f1 = maxOf(c1 * m1, floor)
        val f2 = maxOf(c2 * m2, floor)
        val r = rho.coerceIn(-RHO_MAX, RHO_MAX)
        val det = maxOf(1.0 - r * r, DET_FLOOR)
        if (f1 * f2 * sqrt(det) / 2.0 < kernelAreaFloor) {
            flags = flags or KernelTripleFlags.FLOORED
        }
        return flags
    }

    /**
     * Per-burst KernelNet regime census: guard trigger rates plus the
     * peak-axis (max(s1,s2)) sigma range. Shard-local by design (the
     * conversion loops shard rows): each shard accumulates its own instance
     * and merges at the end, so counting never serializes the merge.
     */
    internal class KernelStats {
        var total = 0
        var capped = 0
        var floored = 0
        var rejected = 0
        var peakMin = Float.POSITIVE_INFINITY
        var peakMax = 0f
        var peakSum = 0.0

        fun add(s1: Float, s2: Float, flags: Int) {
            total++
            if (flags and KernelTripleFlags.REJECTED != 0) {
                rejected++
                return
            }
            if (flags and KernelTripleFlags.CAPPED != 0) capped++
            if (flags and KernelTripleFlags.FLOORED != 0) floored++
            val peak = maxOf(s1, s2)
            if (peak < peakMin) peakMin = peak
            if (peak > peakMax) peakMax = peak
            peakSum += peak
        }

        fun merge(other: KernelStats) {
            total += other.total
            capped += other.capped
            floored += other.floored
            rejected += other.rejected
            if (other.peakMin < peakMin) peakMin = other.peakMin
            if (other.peakMax > peakMax) peakMax = other.peakMax
            peakSum += other.peakSum
        }

        /** One logcat line per frame: regime rates answer floor-vs-cap-vs-mid at a glance. */
        fun logLine(label: String): String {
            val usable = total - rejected
            val mean = if (usable > 0) peakSum / usable else Double.NaN
            val range = if (usable > 0) "peakS=[$peakMin,$peakMax] mean=$mean" else "peakS=n/a"
            return "KernelNet $label quads=$total capped=$capped floored=$floored rejected=$rejected $range"
        }
    }

    /**
     * Convert one KernelNet output triple to a packed precision texel.
     * s1 is the y-sigma and s2 the x-sigma (upstream `mergeCombineWeight`
     * convention: `a = 1/(s1^2*det)` weights dy^2). Gate order: the
     * usability gate sees the raw triple (a sharpening scale never rejects
     * a usable kernel into the analytic fallback); with [zipperGates] the
     * width cap ([KERNEL_SIGMA_MAX], joint scale, shape-preserving) trims
     * the saturated wide end first; then the multiplier law scales the
     * shorter axis by [kernelSigmaMpy] (1.0 = verbatim; the default is
     * [DEFAULT_KERNEL_SIGMA_MPY]) and the longer axis by
     * [kernelSigmaMajorMpy] where the native model anisotropy clears
     * [MAJOR_ANISO_LO] (ramps in over [MAJOR_ANISO_LO]..[MAJOR_ANISO_HI]),
     * clamping each axis to [minSigma] (0 disables); finally the
     * kernel-area floor ([kernelAreaFloor]) widens support-starved scaled
     * triples uniformly. The aniso gate sees capped-model anisotropy, so
     * near-isotropic predictions stay symmetric; when
     * [kernelSigmaMajorMpy] equals [kernelSigmaMpy] the blend collapses
     * to the symmetric law bit-exactly. With [zipperGates] off the cap is
     * ×1.0 (exact no-op) and the floor never engages, reproducing the
     * verbatim multiplier law bit-exactly. Returns false when the triple
     * is unusable (caller substitutes fallback).
     */
    fun precisionOf(s1: Float, s2: Float, rho: Float, out: FloatArray, offset: Int): Boolean {
        if (classifyTriple(s1, s2, rho) and KernelTripleFlags.REJECTED != 0) return false
        // Width cap first (joint scale, shape-preserving), on the raw model
        // sigmas; each binds a different regime. ×1.0 when the gates are
        // off (or below the cap) is an exact no-op, so the verbatim law is
        // untouched bit-for-bit.
        val peak = maxOf(s1, s2)
        val kc = if (zipperGates && peak > KERNEL_SIGMA_MAX) KERNEL_SIGMA_MAX / peak else 1.0
        val q1 = s1 * kc
        val q2 = s2 * kc
        val m = kernelSigmaMpy.coerceAtLeast(1e-3f).toDouble()
        val floor = minSigma.toDouble().coerceAtLeast(0.0)
        // Aniso-gated asymmetric law: the longer model axis (along-edge
        // where the model predicts direction) keeps mMaj, the shorter
        // (across-edge) always takes the sharpening m. Model rho is ~0
        // (median |rho| 0.04), so s1/s2 are effectively the eigen-axes and
        // no eigendecomposition is needed. The blend factor is exact 0/1
        // at the window ends, and mMaj == m collapses mLong to m exactly,
        // so the symmetric law reproduces bit-exactly.
        val mMaj = kernelSigmaMajorMpy.coerceAtLeast(1e-3f).toDouble()
        val aniso = maxOf(q1, q2) / minOf(q1, q2)
        val t = ((aniso - MAJOR_ANISO_LO) / (MAJOR_ANISO_HI - MAJOR_ANISO_LO)).coerceIn(0.0, 1.0)
        val mLong = m + (mMaj - m) * t
        val m1 = if (q1 >= q2) mLong else m
        val m2 = if (q2 > q1) mLong else m
        // Per-axis support floor (orientation-preserving): axes are clamped
        // independently so an edge kernel keeps its direction, just never
        // narrower than the sample lattice can feed.
        val c1 = maxOf(q1 * m1, floor)
        val c2 = maxOf(q2 * m2, floor)
        val r = rho.coerceIn(-RHO_MAX, RHO_MAX)
        val det = maxOf(1.0 - r * r, DET_FLOOR)
        var p00 = QUAD_PRECISION_SCALE / (c2 * c2 * det)
        var p01 = QUAD_PRECISION_SCALE * -r / (c1 * c2 * det)
        var p11 = QUAD_PRECISION_SCALE / (c1 * c1 * det)
        // Kernel-area floor: orientation and anisotropy are the model's to
        // choose, total support is the consumer's to guarantee. With our
        // Σ = P^-1, σ1σ2 = s1·s2·√det/2; scaling P by area/floor
        // when below widens uniformly. Extreme-rho ridges fatten the same
        // way, since their area → 0 as |rho| → 1.
        if (zipperGates) {
            val area = c1 * c2 * sqrt(det) / 2.0
            if (area < kernelAreaFloor) {
                val k = area / kernelAreaFloor
                p00 *= k
                p01 *= k
                p11 *= k
            }
        }
        if (!p00.isFinite() || !p01.isFinite() || !p11.isFinite()) return false
        out[offset] = p00.toFloat()
        out[offset + 1] = p01.toFloat()
        out[offset + 2] = p01.toFloat()
        out[offset + 3] = p11.toFloat()
        return true
    }

    /**
     * KernelNet precision for one frame, or [analytic] when unavailable.
     * [analytic] doubles as the per-pixel fallback for rejected triples, so
     * its grid must be the quad grid (`cfa.width/2 x cfa.height/2`).
     * [grayScratch]/[outScratch] reuse one caller-owned direct pair across
     * burst frames (see the wrapper overload); null allocates per call.
     * The model halves its quad-res input internally; its quarter-res output
     * is resampled onto the quad grid ([samplePlane]) before conversion.
     */
    fun precisionFor(
        cfa: UnpackedRawCfa,
        analytic: RawSrKernelCovariance.MatrixField,
        sigma: Float,
        grayScratch: java.nio.FloatBuffer? = null,
        outScratch: java.nio.FloatBuffer? = null
    ): RawSrKernelCovariance.MatrixField {
        if (!enabled) return analytic
        val processor = try {
            KernelNetNcnnProcessor.getInstance()?.takeIf { it.isReady }
        } catch (_: Throwable) {
            null
        } ?: return analytic
        return try {
            precisionForLocked(cfa, analytic, sigma, processor, grayScratch, outScratch)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "KernelNet OOM, analytic fallback", oom)
            analytic
        } catch (failure: Exception) {
            Log.w(TAG, "KernelNet failed, analytic fallback", failure)
            analytic
        }
    }

    /**
     * Allocation-light variant for the GPU save path: no analytic field is
     * built (that would cost a retained 50MB/frame on top of the GL
     * textures). Returns null on any failure — the caller runs the analytic
     * `kernel_covariance` shader instead, which is the zero-extra-heap
     * fallback. Rejected triples (rare: non-finite/out-of-range model
     * outputs) get the widest admissible isotropic kernel
     * (`s1 = s2 = 2`, the top of the model's range) instead of an analytic
     * sample. Null scratch buffers allocate per call; pass a reused pair.
     * The model halves its quad-res input internally; its quarter-res output
     * is resampled onto the quad grid ([samplePlane]) before conversion.
     */
    fun precisionForKernelOnly(
        cfa: UnpackedRawCfa,
        sigma: Float,
        grayScratch: java.nio.FloatBuffer? = null,
        outScratch: java.nio.FloatBuffer? = null
    ): RawSrKernelCovariance.MatrixField? {
        if (!enabled) return null
        val processor = try {
            KernelNetNcnnProcessor.getInstance()?.takeIf { it.isReady }
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            precisionForKernelOnlyLocked(cfa, sigma, processor, grayScratch, outScratch)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun precisionForLocked(
        cfa: UnpackedRawCfa,
        analytic: RawSrKernelCovariance.MatrixField,
        sigma: Float,
        processor: KernelNetNcnnProcessor,
        grayScratch: java.nio.FloatBuffer?,
        outScratch: java.nio.FloatBuffer?
    ): RawSrKernelCovariance.MatrixField {
        require(cfa.width % 2 == 0 && cfa.height % 2 == 0) {
            "KernelNet SR needs an even CFA crop"
        }
        val outW = cfa.width / 2
        val outH = cfa.height / 2
        require(analytic.width == outW && analytic.height == outH) {
            "Analytic fallback grid must match the quad grid"
        }
        // The merge stores covariance; this path converts in precision space,
        // so the fallback is inverted at entry and the result back at exit.
        val analyticPrecision = RawSrKernelCovariance.invertField(analytic)
        val result = processor.runInference(
            lumaPlane(cfa, grayScratch), outW, outH, sigma, outScratch)
            ?: return analytic
        val planeW = (outW - 1) / 2 + 1
        val planeH = (outH - 1) / 2 + 1
        if (result.width != planeW || result.height != planeH) {
            Log.w(TAG, "KernelNet grid ${result.width}x${result.height} != quarter $planeW x $planeH")
            return analytic
        }
        val planes = result.params()
        val plane = planeW * planeH
        if (planes.capacity() < plane * 3) return analytic
        val quads = outW * outH
        val values = FloatArray(quads * 4)
        // Shard-local census (conversion shards rows in parallel): merged
        // after the loop into one per-frame log line answering floor-vs-cap
        // vs-mid at a glance — the oil-painting attribution signal.
        val statsQueue = java.util.concurrent.ConcurrentLinkedQueue<KernelStats>()
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val stats = KernelStats()
            for (qy in y0 until y1) for (qx in 0 until outW) {
                val i = qy * outW + qx
                val s1 = samplePlane(planes, 0, planeW, planeH, qx, qy, outW, outH)
                val s2 = samplePlane(planes, 1, planeW, planeH, qx, qy, outW, outH)
                val rho = samplePlane(planes, 2, planeW, planeH, qx, qy, outW, outH)
                stats.add(s1, s2, classifyTriple(s1, s2, rho))
                if (!precisionOf(s1, s2, rho, values, i * 4)) {
                    values[i * 4] = analyticPrecision.values[i * 4]
                    values[i * 4 + 1] = analyticPrecision.values[i * 4 + 1]
                    values[i * 4 + 2] = analyticPrecision.values[i * 4 + 2]
                    values[i * 4 + 3] = analyticPrecision.values[i * 4 + 3]
                }
            }
            statsQueue.add(stats)
        }
        val stats = KernelStats()
        statsQueue.forEach(stats::merge)
        Log.i(TAG, stats.logLine("${outW}x${outH}"))
        // Narrow-axis floor over the converted field: razor across-axes
        // starve their own lattice and ring into zipper along edges, the
        // same failure the mosaic target's downstream clamp already cures.
        if (zipperGates) {
            MosaicSrReconstructor.clampMinorAxisInPlace(values, MosaicSrReconstructor.minorAxisSigmaFloor)
        }
        // Back to the covariance-space merge field the base path consumes.
        // In place: no extra field-sized allocation on top of the caller's
        // scratch.
        RawSrKernelCovariance.invertFieldInPlace(values)
        return RawSrKernelCovariance.MatrixField(outW, outH, values)
    }

    /**
     * Isotropic fallback texel for rejected triples in the kernel-only path:
     * `P = 0.5 * I` (sigma 2 in quad units, the widest admissible kernel).
     */
    private fun isotropicTexel(out: FloatArray, offset: Int) {
        out[offset] = 0.5f
        out[offset + 1] = 0f
        out[offset + 2] = 0f
        out[offset + 3] = 0.5f
    }

    internal fun precisionForPackedKernelOnly(
        frame: RawSrPackedFrame, sigma: Float,
        grayScratch: java.nio.FloatBuffer, outScratch: java.nio.FloatBuffer,
        valuesScratch: FloatArray, planesScratch: FloatArray
    ): RawSrKernelCovariance.MatrixField? {
        if (!enabled) return null
        val processor = KernelNetNcnnProcessor.getInstance()?.takeIf { it.isReady } ?: return null
        val outW = frame.width / 2
        val outH = frame.height / 2
        val result = processor.runInference(RawSrPackedKernelInput.luma(frame, grayScratch),
            outW, outH, sigma, outScratch) ?: return null
        return precisionFromModel(result, outW, outH, valuesScratch, planesScratch)
    }

    private fun precisionForKernelOnlyLocked(
        cfa: UnpackedRawCfa,
        sigma: Float,
        processor: KernelNetNcnnProcessor,
        grayScratch: java.nio.FloatBuffer?,
        outScratch: java.nio.FloatBuffer?
    ): RawSrKernelCovariance.MatrixField? {
        if (cfa.width % 2 != 0 || cfa.height % 2 != 0) return null
        val outW = cfa.width / 2
        val outH = cfa.height / 2
        val result = processor.runInference(
            lumaPlane(cfa, grayScratch), outW, outH, sigma, outScratch)
            ?: return null
        return precisionFromModel(result, outW, outH)
    }

    /** Scratch belongs to one serialized GPU burst and is uploaded before reuse. */
    internal fun precisionFromModel(
        result: KernelNetNcnnProcessor.Result, outW: Int, outH: Int,
        valuesScratch: FloatArray? = null, planesScratch: FloatArray? = null
    ): RawSrKernelCovariance.MatrixField? {
        val planeW = (outW - 1) / 2 + 1
        val planeH = (outH - 1) / 2 + 1
        if (result.width != planeW || result.height != planeH) return null
        val source = result.params()
        val plane = planeW * planeH
        if (source.capacity() < plane * 3) return null
        // One bulk copy replaces millions of checked direct-buffer reads on ART.
        val planes = planesScratch?.takeIf { it.size == plane * 3 } ?: FloatArray(plane * 3)
        source.duplicate().apply { clear(); get(planes) }
        val quads = outW * outH
        val values = valuesScratch?.takeIf { it.size == quads * 4 } ?: FloatArray(quads * 4)
        return convertPlanesToField(planes, planeW, planeH, outW, outH, values)
    }

    /**
     * Resample channel-major model planes onto the quad grid and convert
     * each triple to a packed precision texel ([precisionOf], isotropic
     * fallback for rejected triples), then invert into the covariance field
     * the merge consumes (reference Alg. 4 inverts per pixel).
     * Internal seam: the NCNN `Result` holder is not constructible on the
     * JVM, so tests drive this directly.
     */
    internal fun convertPlanesToField(
        planes: FloatArray,
        planeW: Int,
        planeH: Int,
        outW: Int,
        outH: Int,
        values: FloatArray
    ): RawSrKernelCovariance.MatrixField {
        require(planeW > 0 && planeH > 0 && outW > 0 && outH > 0)
        require(planes.size == planeW * planeH * 3) { "Model planes must hold 3 channels" }
        require(values.size == outW * outH * 4) { "Covariance field must hold 4 coefficients per quad" }
        val statsQueue = java.util.concurrent.ConcurrentLinkedQueue<KernelStats>()
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val stats = KernelStats()
            for (qy in y0 until y1) for (qx in 0 until outW) {
                val i = qy * outW + qx
                val s1 = samplePlane(planes, 0, planeW, planeH, qx, qy, outW, outH)
                val s2 = samplePlane(planes, 1, planeW, planeH, qx, qy, outW, outH)
                val rho = samplePlane(planes, 2, planeW, planeH, qx, qy, outW, outH)
                stats.add(s1, s2, classifyTriple(s1, s2, rho))
                if (!precisionOf(s1, s2, rho, values, i * 4)) {
                    isotropicTexel(values, i * 4)
                }
            }
            statsQueue.add(stats)
        }
        val stats = KernelStats()
        statsQueue.forEach(stats::merge)
        Log.i(TAG, stats.logLine("${outW}x${outH} kernel-only"))
        // Narrow-axis floor over the converted field (same zipper guard as
        // the fallback path above).
        if (zipperGates) {
            MosaicSrReconstructor.clampMinorAxisInPlace(values, MosaicSrReconstructor.minorAxisSigmaFloor)
        }
        // In place: the returned field reuses the caller's scratch (the GPU
        // burst's zero-extra-heap contract), bitwise-identical to invertField.
        RawSrKernelCovariance.invertFieldInPlace(values)
        return RawSrKernelCovariance.MatrixField(outW, outH, values)
    }

}
