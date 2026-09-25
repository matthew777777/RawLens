// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.sqrt

/**
 * Prompt 4B CPU oracle: Wronski/IPOL anisotropic reconstruction kernels (paper Alg. 1 /
 * IPOL Alg. 5 `ComputeKernelCovariance`).
 *
 * Algorithm reference: Jamy Lafenetre's MIT-licensed IPOL implementation
 * (`handheld_super_resolution/kernels.py::estimate_kernels`, `linalg.py`), itself a
 * transcription of Wronski et al. SIGGRAPH 2019 and Lafenetre et al. IPOL 2023. No
 * upstream code is copied; the separable gradient filters, 2x2 structure-tensor window,
 * analytic eigendecomposition and linear selection law are reimplemented here in the
 * project's tested Float/FloatArray style.
 *
 * Deliberate deviations from the Jamy-L checkout, all covered by tests:
 * - No GAT/variance-stabilizing preprocessing: RawLens feeds the already-normalized
 *   Bayer-quad alignment representation ([RawSrAlignment.bayerQuadGray]), not mosaiced
 *   sensor codes, so per-phase alpha/beta stabilization does not apply.
 * - Exact-flat stabilization: a zero structure tensor makes the anisotropy ratio 0/0.
 *   Such pixels fall back to the isotropic denoise kernel instead of propagating NaN.
 * - Non-finite stabilization: a non-finite tensor (e.g. NaN input) also falls back to
 *   the isotropic denoise kernel, keeping every packed coefficient finite.
 * - The hard-threshold selection law is the default (Jamy-L offers both laws and
 *   defaults to linear). The linear law smears noise/quantization gradients on busy
 *   texture into locally flat, segmented blends — the "oil painting" artifact this
 *   analytic path is known for. The hard law keeps every pixel at or below A = 1.95
 *   isotropic and only fully stretches past it, so the KernelNet fallback degrades
 *   to a safe isotropic kernel instead of a smeared one. Linear stays available for
 *   A/B comparison.
 * - ISO kernel type mirrors Jamy-L exactly, including its quirk: covariance equals
 *   kDetail (linear, not squared) and kDenoise is ignored.
 *
 * The tuning robustness constants (t, s1, s2, Mth) belong to per-pixel robustness
 * weighting, a later prompt; only kDetail, kDenoise, Dth, Dtr, kStretch and kShrink
 * are consumed here.
 *
 * Since Prompt 4B.1 the input gray is the variance-stabilized covariance guide
 * ([RawSrCovarianceGuide]); the estimator itself is unchanged. See the coordinate
 * contract on [RawSrCovarianceGuide] for RAW vs quad units.
 *
 * All arithmetic is scalar Float over flat arrays: no object is allocated per pixel.
 */
object RawSrKernelCovariance {
    /** Jamy-L `kernel_type`: steerable (structure-tensor driven) or plain isotropic. */
    enum class KernelType { STEERABLE, ISO }

    /** Jamy-L `selection_law`: progressive linear blend or 1.95 hard threshold. */
    enum class SelectionLaw { LINEAR, HARD }

    /** Hard-law anisotropy gate, exactly Jamy-L's `A > 1.95` (strict). */
    const val HARD_ANISOTROPY_GATE = 1.95f

    /**
     * Packed row-major 2x2 matrices, RGBA channel order (m00, m01, m10, m11) per
     * Bayer-quad pixel. Precision packing matches the SkyKing `alterCov` consumer:
     * `mat2(v.x, v.y, v.z, v.w)`.
     */
    data class MatrixField(val width: Int, val height: Int, val values: FloatArray) {
        init {
            require(width > 0 && height > 0)
            require(values.size == width * height * 4) {
                "Packed matrix field must hold 4 coefficients per pixel"
            }
        }
        fun get(x: Int, y: Int, channel: Int): Float = values[(y * width + x) * 4 + channel]
    }

    /**
     * Pre-inversion kernel covariance per quad pixel. Oracle checks only; the GLES
     * pipeline stores [precision].
     */
    fun covariance(
        gray: RawSrGrayImage,
        tuning: RawSrTuning,
        kernelType: KernelType = KernelType.STEERABLE,
        selectionLaw: SelectionLaw = SelectionLaw.HARD
    ): MatrixField =
        solve(gray, tuning, kernelType, selectionLaw, withCovariance = true).first!!

    /**
     * Inverse covariance (precision) per quad pixel, packed for GLES RGBA32F upload.
     * Every matrix is symmetric positive-definite by construction, so this is exactly
     * the inverse of [covariance] for the same inputs.
     *
     * Production path: the covariance half (~50MB at full res) is NOT allocated —
     * the merge consumes precision only, and the joint allocation peaks past the
     * 512MB heap during the mosaic save. The precision arithmetic is identical to
     * the joint solver (same operations in the same order), so outputs agree
     * bitwise; the inversion test below pins covariance/precision agreement.
     */
    fun precision(
        gray: RawSrGrayImage,
        tuning: RawSrTuning,
        kernelType: KernelType = KernelType.STEERABLE,
        selectionLaw: SelectionLaw = SelectionLaw.HARD
    ): MatrixField =
        solve(gray, tuning, kernelType, selectionLaw, withCovariance = false).second

    /**
     * Detail-axis multipliers for one anisotropy value, exactly Jamy-L
     * `linear`/`hard_threshold`. Non-finite anisotropy (only from a 0/0 tensor,
     * which the solver stabilizes to 1 before calling) takes the isotropic
     * branch, matching the reference NaN comment.
     */
    internal fun selectionAxes(
        anisotropy: Float,
        kShrink: Float,
        kStretch: Float,
        selectionLaw: SelectionLaw
    ): Pair<Float, Float> = if (selectionLaw == SelectionLaw.HARD) {
        if (anisotropy > HARD_ANISOTROPY_GATE) (1f / kShrink) to kStretch else 1f to 1f
    } else {
        ((2f - anisotropy) + (anisotropy - 1f) / kShrink) to
            ((2f - anisotropy) + (anisotropy - 1f) * kStretch)
    }

    private fun solve(
        gray: RawSrGrayImage,
        tuning: RawSrTuning,
        kernelType: KernelType,
        selectionLaw: SelectionLaw,
        withCovariance: Boolean
    ): Pair<MatrixField?, MatrixField> {
        val width = gray.width
        val height = gray.height
        if (kernelType == KernelType.ISO) return solveIso(width, height, tuning.kDetail.toFloat(), withCovariance)
        val gradWidth = width - 1
        val gradHeight = height - 1
        // Separable Wronski gradient filters, evaluated only where the 2x2 support
        // is in bounds; out-of-bounds samples contribute nothing to the tensor sum.
        val grads = FloatArray(maxOf(gradWidth, 0) * maxOf(gradHeight, 0) * 2)
        RawSrWorkers.forEachShard(gradHeight) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until gradWidth) {
                val a = gray.values[y * width + x]
                val b = gray.values[y * width + x + 1]
                val c = gray.values[(y + 1) * width + x]
                val d = gray.values[(y + 1) * width + x + 1]
                val o = (y * gradWidth + x) * 2
                grads[o] = 0.25f * (-a + b - c + d)
                grads[o + 1] = 0.25f * (-a - b + c + d)
            }
        }
        val covariances = if (withCovariance) FloatArray(width * height * 4) else null
        val precisions = FloatArray(width * height * 4)
        val kDetail = tuning.kDetail.toFloat()
        val kDenoise = tuning.kDenoise.toFloat()
        val dTh = tuning.dTh.toFloat()
        val dTr = tuning.dTr.toFloat()
        val kStretch = tuning.kStretch.toFloat()
        val kShrink = tuning.kShrink.toFloat()
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            // Shard-local eig scratch (was: one shared array, now one per
            // shard — the pixel loop still allocates nothing per pixel).
            val eig = FloatArray(6)
            for (y in y0 until y1) for (x in 0 until width) {
                var t00 = 0f
                var t01 = 0f
                var t11 = 0f
                for (i in 0..1) for (j in 0..1) {
                    val gx = x - 1 + j
                    val gy = y - 1 + i
                    if (gx < 0 || gy < 0 || gx >= gradWidth || gy >= gradHeight) continue
                    val o = (gy * gradWidth + gx) * 2
                    val xx = grads[o]
                    val yy = grads[o + 1]
                    t00 += xx * xx
                    t01 += xx * yy
                    t11 += yy * yy
                }
                // One scratch array per shard: the pixel loop allocates nothing.
                val eigen = if (t00.isFinite() && t01.isFinite() && t11.isFinite()) {
                    eigenInto(t00, t01, t11, eig)
                    eig
                } else null
                // Squared kernel radii along the dominant/minor axes; isotropic denoise
                // fallback keeps flat and non-finite pixels wide, radial and finite.
                var k1Sq = kDetail * kDetail * kDenoise * kDenoise
                var k2Sq = k1Sq
                var e1x = 1f
                var e1y = 0f
                var e2x = 0f
                var e2y = 1f
                if (eigen != null) {
                    e1x = eigen[0]; e1y = eigen[1]; e2x = eigen[2]; e2y = eigen[3]
                    val l1 = eigen[4]
                    val l2 = eigen[5]
                    val ratio = (l1 - l2) / (l1 + l2)
                    val anisotropy = if (ratio >= 0f) 1f + sqrt(ratio) else 1f
                    val detail = if (l1 > 0f) sqrt(l1) else 0f
                    val denoise = (1f - detail / dTr + dTh).coerceIn(0f, 1f)
                    // Same branch as [selectionAxes], inlined: the pixel loop
                    // allocates nothing, and a Pair return would box per pixel.
                    val axis1: Float
                    val axis2: Float
                    if (selectionLaw == SelectionLaw.HARD) {
                        if (anisotropy > HARD_ANISOTROPY_GATE) {
                            axis1 = 1f / kShrink
                            axis2 = kStretch
                        } else {
                            axis1 = 1f
                            axis2 = 1f
                        }
                    } else {
                        axis1 = (2f - anisotropy) + (anisotropy - 1f) / kShrink
                        axis2 = (2f - anisotropy) + (anisotropy - 1f) * kStretch
                    }
                    val k1 = (kDetail * ((1f - denoise) * axis1 + denoise * kDenoise)).toFloat()
                    val k2 = (kDetail * ((1f - denoise) * axis2 + denoise * kDenoise)).toFloat()
                    if (k1.isFinite() && k2.isFinite() && k1 > 0f && k2 > 0f) {
                        k1Sq = k1 * k1
                        k2Sq = k2 * k2
                    } else {
                        e1x = 1f; e1y = 0f; e2x = 0f; e2y = 1f
                    }
                }
                val o = (y * width + x) * 4
                // Covariance writes are skipped (not merely unwritten) on the
                // production path: the array itself is never allocated there.
                // Precision arithmetic below is untouched, hence bitwise-identical.
                if (covariances != null) {
                    covariances[o] = k1Sq * e1x * e1x + k2Sq * e2x * e2x
                    covariances[o + 1] = k1Sq * e1x * e1y + k2Sq * e2x * e2y
                    covariances[o + 2] = covariances[o + 1]
                    covariances[o + 3] = k1Sq * e1y * e1y + k2Sq * e2y * e2y
                }
                // Precision from the same eigenbasis: exactly the matrix inverse and
                // positive-definite whenever the radii are positive and finite.
                val i1 = 1f / k1Sq
                val i2 = 1f / k2Sq
                precisions[o] = i1 * e1x * e1x + i2 * e2x * e2x
                precisions[o + 1] = i1 * e1x * e1y + i2 * e2x * e2y
                precisions[o + 2] = precisions[o + 1]
                precisions[o + 3] = i1 * e1y * e1y + i2 * e2y * e2y
            }
        }
        val covarianceField = covariances?.let { MatrixField(width, height, it) }
        return covarianceField to MatrixField(width, height, precisions)
    }

    /**
     * ISO fast path: covariance is exactly kDetail on the diagonal everywhere,
     * mirroring Jamy-L's early return (linear kDetail, not squared; kDenoise
     * ignored). Precision is its exact inverse, 1/kDetail on the diagonal.
     */
    private fun solveIso(
        width: Int,
        height: Int,
        kDetail: Float,
        withCovariance: Boolean
    ): Pair<MatrixField?, MatrixField> {
        val covariances = if (withCovariance) FloatArray(width * height * 4) else null
        val precisions = FloatArray(width * height * 4)
        val inv = 1f / kDetail
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                val o = (y * width + x) * 4
                if (covariances != null) {
                    covariances[o] = kDetail
                    covariances[o + 1] = 0f
                    covariances[o + 2] = 0f
                    covariances[o + 3] = kDetail
                }
                precisions[o] = inv
                precisions[o + 1] = 0f
                precisions[o + 2] = 0f
                precisions[o + 3] = inv
            }
        }
        return (covariances?.let { MatrixField(width, height, it) }) to MatrixField(width, height, precisions)
    }

    /**
     * Analytic symmetric-2x2 eigendecomposition mirroring Jamy-L `linalg.py`:
     * [e1x, e1y, e2x, e2y, l1, l2] with eigenvalues sorted by descending magnitude
     * and unit, mutually orthogonal eigenvectors. The allocating overload exists
     * for oracle checks; the solver uses the scratch variant and allocates nothing
     * per pixel.
     */
    internal fun eigenDecomposition(t00: Float, t01: Float, t11: Float): FloatArray =
        FloatArray(6).also { eigenInto(t00, t01, t11, it) }

    internal fun eigenInto(t00: Float, t01: Float, t11: Float, out: FloatArray) {
        val b = -(t00 + t11)
        val c = t00 * t11 - t01 * t01
        val delta = maxOf(b * b - 4f * c, 0f)
        val root = sqrt(delta)
        val r1 = (-b + root) * 0.5f
        val r2 = (-b - root) * 0.5f
        // l1 carries the eigenvalue with the biggest module, exactly like the reference.
        val l1: Float
        val l2: Float
        if (kotlin.math.abs(r1) >= kotlin.math.abs(r2)) {
            l1 = r1; l2 = r2
        } else {
            l1 = r2; l2 = r1
        }
        if (t01 == 0f && t00 == t11) {
            out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 1f; out[4] = l1; out[5] = l2
            return
        }
        // Stable major-axis solve: v carries O(|T|) components, while the legacy
        // (T - l2I)(1,1) residual cancels giants into rounding noise at
        // near-collinear tensors and lets 1-ulp differences rotate kernels by
        // degrees. Both forms are the same eigenvector in exact arithmetic, and
        // precision outer products are sign-invariant, so well-conditioned
        // results agree with the legacy form to float rounding.
        // Cancellation-free row selection (4E precision fix): l1 - t00 cancels
        // catastrophically in near-isotropic quads ((t11-t00)/2 + root/2 with
        // opposite signs), rotating the kernel ~1 degree under float32 rounding
        // while k1 != k2. The (l1-t11, t01) row is the same eigenvector in exact
        // arithmetic; using the larger row keeps O(|T|) components on both the
        // CPU and GPU paths. Near-ties imply a repeated eigenvalue, where
        // k1 == k2 makes the axis irrelevant, so the >= tie-break is safe.
        var e1x: Float
        var e1y: Float
        if (kotlin.math.abs(l1 - t11) >= kotlin.math.abs(l1 - t00)) {
            e1x = l1 - t11
            e1y = t01
        } else {
            e1x = t01
            e1y = l1 - t00
        }
        val e2x: Float
        val e2y: Float
        if (e1x == 0f && e1y == 0f) {
            // t01 == 0 with l1 == t00 (t00 >= t11): legacy axis fallback.
            e1x = 1f; e1y = 0f; e2x = 0f; e2y = 1f
        } else if (e1x == 0f) {
            e1x = 0f; e1y = 1f; e2x = 1f; e2y = 0f
        } else if (e1y == 0f) {
            e1x = 1f; e1y = 0f; e2x = 0f; e2y = 1f
        } else {
            val norm = sqrt(e1x * e1x + e1y * e1y)
            e1x /= norm
            e1y /= norm
            val sign = if (e1x >= 0f) 1f else -1f
            e2y = kotlin.math.abs(e1x)
            e2x = -e1y * sign
        }
        out[0] = e1x; out[1] = e1y; out[2] = e2x; out[3] = e2y; out[4] = l1; out[5] = l2
    }
}
