// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

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
 * - The linear selection law is the default, exactly like the reference
 *   (`selection_law='linear'`). The hard-threshold law (`A > 1.95`, strict)
 *   stays available for A/B comparison.
 * - ISO kernel type mirrors Jamy-L exactly, including its quirk: covariance equals
 *   kDetail (linear, not squared) and kDenoise is ignored.
 * - Decoupled flat radius (RawSrTuning.flatSigma, default off): when set, the
 *   denoise end of the blend is the absolute flatSigma instead of the
 *   kDetail * kDenoise product, so flats smooth at flatSigma while edges
 *   stay kDetail-sharp. The ISO fast path ignores flatSigma like kDenoise.
 * - Detail floor (RawSrTuning.detailFloor, default off): both radii clamp
 *   to at least the floor, a guardrail against degenerate razor radii
 *   (~0.01) that would let one tap win by hundreds of nats. The ISO fast
 *   path ignores the floor (its quirk pins kDetail exactly).
 *
 * The tuning robustness constants (t, s1, s2, Mth) belong to per-pixel robustness
 * weighting, a later prompt; only kDetail, kDenoise, Dth, Dtr, kStretch, kShrink
 * and the optional flatSigma/detailFloor are consumed here.
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

    /** Hard-law anisotropy gate, exactly Jamy-L's `A > 1.95` (strict); see [RawSrCoreKernels]. */
    const val HARD_ANISOTROPY_GATE = RawSrCoreKernels.HARD_ANISOTROPY_GATE

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
     *
     * The precision half (~50MB at full res) is NOT allocated — the CPU merge
     * consumes covariance only, and the joint allocation peaks past the
     * 512MB heap during the mosaic save. The covariance arithmetic is
     * untouched, so outputs agree bitwise; the inversion test pins
     * covariance/precision agreement.
     */
    fun covariance(
        gray: RawSrGrayImage,
        tuning: RawSrTuning,
        kernelType: KernelType = KernelType.STEERABLE,
        selectionLaw: SelectionLaw = SelectionLaw.LINEAR
    ): MatrixField =
        solve(gray, tuning, kernelType, selectionLaw, withCovariance = true, withPrecision = false).first!!

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
        selectionLaw: SelectionLaw = SelectionLaw.LINEAR
    ): MatrixField =
        solve(gray, tuning, kernelType, selectionLaw, withCovariance = false, withPrecision = true).second!!

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
    ): Pair<Float, Float> = RawSrCoreKernels.selectionAxes(anisotropy, kShrink, kStretch, selectionLaw)

    private fun solve(
        gray: RawSrGrayImage,
        tuning: RawSrTuning,
        kernelType: KernelType,
        selectionLaw: SelectionLaw,
        withCovariance: Boolean,
        withPrecision: Boolean
    ): Pair<MatrixField?, MatrixField?> {
        val width = gray.width
        val height = gray.height
        if (kernelType == KernelType.ISO) return solveIso(
            width, height, tuning.kDetail.toFloat(), withCovariance, withPrecision)
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
                grads[o] = RawSrCoreKernels.gradientX(a, b, c, d)
                grads[o + 1] = RawSrCoreKernels.gradientY(a, b, c, d)
            }
        }
        val covariances = if (withCovariance) FloatArray(width * height * 4) else null
        val precisions = if (withPrecision) FloatArray(width * height * 4) else null
        val kDetail = tuning.kDetail.toFloat()
        val kDenoise = tuning.kDenoise.toFloat()
        val flatSigma = tuning.flatSigma?.toFloat()
        val detailFloor = tuning.detailFloor?.toFloat() ?: 0f
        val dTh = tuning.dTh.toFloat()
        val dTr = tuning.dTr.toFloat()
        val kStretch = tuning.kStretch.toFloat()
        val kShrink = tuning.kShrink.toFloat()
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            // Shard-local eig scratch (was: one shared array, now one per
            // shard — the pixel loop still allocates nothing per pixel).
            val eig = FloatArray(6)
            for (y in y0 until y1) for (x in 0 until width) {
                // One scratch array per shard: the pixel loop allocates nothing
                // (the tensor triplet lands in the eig scratch, then the
                // eigendecomposition overwrites it).
                RawSrCoreKernels.structureTensor(grads, gradWidth, gradHeight, x, y, eig)
                val t00 = eig[0]
                val t01 = eig[1]
                val t11 = eig[2]
                val eigen = if (t00.isFinite() && t01.isFinite() && t11.isFinite()) {
                    RawSrCoreKernels.eigenInto(t00, t01, t11, eig)
                    eig
                } else null
                // Squared kernel radii along the dominant/minor axes; isotropic denoise
                // fallback keeps flat and non-finite pixels wide, radial and finite.
                // Decoupled override sits outside the reference expression so the
                // coupled path keeps its exact float operations.
                val kIso = kDetail * kDenoise
                var k1Sq = kIso * kIso
                var k2Sq = k1Sq
                if (flatSigma != null) {
                    k1Sq = flatSigma * flatSigma
                    k2Sq = k1Sq
                }
                var e1x = 1f
                var e1y = 0f
                var e2x = 0f
                var e2y = 1f
                if (eigen != null) {
                    e1x = eigen[0]; e1y = eigen[1]; e2x = eigen[2]; e2y = eigen[3]
                    val l1 = eigen[4]
                    val l2 = eigen[5]
                    val anisotropy = RawSrCoreKernels.anisotropy(l1, l2)
                    val detail = RawSrCoreKernels.detail(l1)
                    val denoise = RawSrCoreKernels.denoiseWeight(detail, dTh, dTr)
                    // Scalar axis twins of [selectionAxes]: the pixel loop
                    // allocates nothing, and a Pair return would box per pixel.
                    val axis1 = RawSrCoreKernels.selectionAxis1(anisotropy, kShrink, selectionLaw)
                    val axis2 = RawSrCoreKernels.selectionAxis2(anisotropy, kStretch, selectionLaw)
                    // Decoupled: the denoise end of the blend is the absolute
                    // flatSigma, not the kDetail-scaled product — the coupled
                    // branch below keeps the reference op order verbatim.
                    // The detail floor clamps both radii afterwards (max with
                    // 0 is a no-op, so the reference path is untouched).
                    val k1 = RawSrCoreKernels.blendRadius(
                        kDetail, kDenoise, flatSigma, detailFloor, denoise, axis1)
                    val k2 = RawSrCoreKernels.blendRadius(
                        kDetail, kDenoise, flatSigma, detailFloor, denoise, axis2)
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
                    RawSrCoreKernels.assembleCovariance(k1Sq, k2Sq, e1x, e1y, e2x, e2y, covariances, o)
                }
                // Precision from the same eigenbasis: exactly the matrix inverse and
                // positive-definite whenever the radii are positive and finite.
                // Skipped (not merely unwritten) when only covariance is
                // needed: the array itself is never allocated there.
                if (precisions != null) {
                    RawSrCoreKernels.assemblePrecision(k1Sq, k2Sq, e1x, e1y, e2x, e2y, precisions, o)
                }
            }
        }
        val covarianceField = covariances?.let { MatrixField(width, height, it) }
        val precisionField = precisions?.let { MatrixField(width, height, it) }
        return covarianceField to precisionField
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
        withCovariance: Boolean,
        withPrecision: Boolean
    ): Pair<MatrixField?, MatrixField?> {
        val covariances = if (withCovariance) FloatArray(width * height * 4) else null
        val precisions = if (withPrecision) FloatArray(width * height * 4) else null
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
                if (precisions != null) {
                    precisions[o] = inv
                    precisions[o + 1] = 0f
                    precisions[o + 2] = 0f
                    precisions[o + 3] = inv
                }
            }
        }
        return (covariances?.let { MatrixField(width, height, it) }) to
            (precisions?.let { MatrixField(width, height, it) })
    }

    /**
     * Analytic symmetric-2x2 eigendecomposition, Jamy-L `linalg.py`
     * (`get_eigen_val_2x2` + `get_eigen_vect_2x2`) verbatim: [e1x, e1y, e2x,
     * e2y, l1, l2] with eigenvalues sorted by descending magnitude, the major
     * axis as the (T - l2*I)*(1,1) residual, and unit, mutually orthogonal
     * eigenvectors. The allocating overload exists for oracle checks; the
     * solver uses the scratch variant and allocates nothing per pixel.
     */
    internal fun eigenDecomposition(t00: Float, t01: Float, t11: Float): FloatArray =
        RawSrCoreKernels.eigenDecomposition(t00, t01, t11)

    /**
     * Per-texel symmetric-2x2 inverse of a packed [MatrixField]. The merge
     * stores covariance and inverts after interpolation (Jamy-L Alg. 4); this
     * bridges precision-space producers (the KernelNet A/B path) into the
     * covariance-space merge. Degenerate texels (non-positive or non-finite
     * determinant) fall back to identity rather than poisoning neighbours.
     */
    fun invertField(field: MatrixField): MatrixField {
        val values = FloatArray(field.values.size)
        RawSrWorkers.forEachShard(field.width * field.height) { i0, i1 ->
            for (i in i0 until i1) {
                val o = i * 4
                val a = field.values[o].toDouble()
                val b = field.values[o + 1].toDouble()
                val d = field.values[o + 3].toDouble()
                val det = a * d - b * b
                if (det.isFinite() && det > 0.0 && a.isFinite() && b.isFinite() && d.isFinite()) {
                    values[o] = (d / det).toFloat()
                    values[o + 1] = (-b / det).toFloat()
                    values[o + 2] = (-b / det).toFloat()
                    values[o + 3] = (a / det).toFloat()
                } else {
                    values[o] = 1f
                    values[o + 1] = 0f
                    values[o + 2] = 0f
                    values[o + 3] = 1f
                }
            }
        }
        return MatrixField(field.width, field.height, values)
    }

    /**
     * In-place core of [invertField]: inverts the packed texels of [values]
     * (4 floats per kernel) without allocating, so the KernelNet
     * scratch-reuse contract survives the precision→covariance bridge.
     * Per-texel reads complete before writes and texels are disjoint, hence
     * bitwise-identical to [invertField].
     */
    fun invertFieldInPlace(values: FloatArray) {
        require(values.size % 4 == 0) { "Packed field must hold 4 coefficients per texel" }
        val n = values.size / 4
        RawSrWorkers.forEachShard(n) { i0, i1 ->
            for (i in i0 until i1) {
                val o = i * 4
                val a = values[o].toDouble()
                val b = values[o + 1].toDouble()
                val d = values[o + 3].toDouble()
                val det = a * d - b * b
                if (det.isFinite() && det > 0.0 && a.isFinite() && b.isFinite() && d.isFinite()) {
                    values[o] = (d / det).toFloat()
                    values[o + 1] = (-b / det).toFloat()
                    values[o + 2] = (-b / det).toFloat()
                    values[o + 3] = (a / det).toFloat()
                } else {
                    values[o] = 1f
                    values[o + 1] = 0f
                    values[o + 2] = 0f
                    values[o + 3] = 1f
                }
            }
        }
    }

    internal fun eigenInto(t00: Float, t01: Float, t11: Float, out: FloatArray) =
        RawSrCoreKernels.eigenInto(t00, t01, t11, out)
}
