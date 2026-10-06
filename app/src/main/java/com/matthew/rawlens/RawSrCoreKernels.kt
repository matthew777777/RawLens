// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.sqrt

/**
 * Shared SR core: kernel covariance estimation (CPU donor
 * [RawSrKernelCovariance]; the Vulkan shaders transcribe the same formulas
 * in float32).
 *
 * One formula, two instantiations: every function below is the exact scalar
 * math moved verbatim out of the donor (structure tensor,
 * eigendecomposition, selection axes, blend law, covariance/precision
 * assembly), so CPU behavior is identical by construction. The ISO fast
 * path, the HARD law, and the exact-flat/non-finite stabilization quirks
 * stay intact in the donor. No Android/GL dependencies; per-pixel entry
 * points allocate nothing (scalar axis/radius twins of the Pair-returning
 * oracle helpers).
 */
object RawSrCoreKernels {
    /** Hard-law anisotropy gate, exactly Jamy-L's `A > 1.95` (strict). */
    const val HARD_ANISOTROPY_GATE = 1.95f

    /**
     * Analytic symmetric-2x2 eigendecomposition, Jamy-L `linalg.py`
     * (`get_eigen_val_2x2` + `get_eigen_vect_2x2`) verbatim: [e1x, e1y, e2x,
     * e2y, l1, l2] with eigenvalues sorted by descending magnitude, the major
     * axis as the (T - l2*I)*(1,1) residual, and unit, mutually orthogonal
     * eigenvectors.
     */
    fun eigenInto(t00: Float, t01: Float, t11: Float, out: FloatArray) {
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
        // Reference `get_eigen_vect_2x2` verbatim: the major axis is the
        // (T - l2*I)*(1,1) residual off the SMALLER-magnitude eigenvalue,
        // normalized, with the minor axis rotated off its sign.
        var e1x = t00 + t01 - l2
        var e1y = t01 + t11 - l2
        val e2x: Float
        val e2y: Float
        if (e1x == 0f) {
            e1x = 0f; e1y = 1f; e2x = 1f; e2y = 0f
        } else if (e1y == 0f) {
            e1x = 1f; e1y = 0f; e2x = 0f; e2y = 1f
        } else {
            val norm = sqrt(e1x * e1x + e1y * e1y)
            e1x /= norm
            e1y /= norm
            // Reference copysign(1, e1x), including the signed-zero edge.
            val sign = Math.copySign(1f, e1x)
            e2y = kotlin.math.abs(e1x)
            e2x = -e1y * sign
        }
        out[0] = e1x; out[1] = e1y; out[2] = e2x; out[3] = e2y; out[4] = l1; out[5] = l2
    }

    /** Allocating overload of [eigenInto] for oracle checks. */
    fun eigenDecomposition(t00: Float, t01: Float, t11: Float): FloatArray =
        FloatArray(6).also { eigenInto(t00, t01, t11, it) }

    /**
     * Detail-axis multipliers for one anisotropy value, exactly Jamy-L
     * `linear`/`hard_threshold`. Non-finite anisotropy (only from a 0/0 tensor,
     * which the solver stabilizes to 1 before calling) takes the isotropic
     * branch, matching the reference NaN comment.
     */
    fun selectionAxes(
        anisotropy: Float,
        kShrink: Float,
        kStretch: Float,
        selectionLaw: RawSrKernelCovariance.SelectionLaw
    ): Pair<Float, Float> = if (selectionLaw == RawSrKernelCovariance.SelectionLaw.HARD) {
        if (anisotropy > HARD_ANISOTROPY_GATE) (1f / kShrink) to kStretch else 1f to 1f
    } else {
        ((2f - anisotropy) + (anisotropy - 1f) / kShrink) to
            ((2f - anisotropy) + (anisotropy - 1f) * kStretch)
    }

    /** Scalar twin of [selectionAxes]: the dominant-axis multiplier (no alloc). */
    fun selectionAxis1(
        anisotropy: Float,
        kShrink: Float,
        selectionLaw: RawSrKernelCovariance.SelectionLaw
    ): Float = if (selectionLaw == RawSrKernelCovariance.SelectionLaw.HARD) {
        if (anisotropy > HARD_ANISOTROPY_GATE) 1f / kShrink else 1f
    } else {
        (2f - anisotropy) + (anisotropy - 1f) / kShrink
    }

    /** Scalar twin of [selectionAxes]: the minor-axis multiplier (no alloc). */
    fun selectionAxis2(
        anisotropy: Float,
        kStretch: Float,
        selectionLaw: RawSrKernelCovariance.SelectionLaw
    ): Float = if (selectionLaw == RawSrKernelCovariance.SelectionLaw.HARD) {
        if (anisotropy > HARD_ANISOTROPY_GATE) kStretch else 1f
    } else {
        (2f - anisotropy) + (anisotropy - 1f) * kStretch
    }

    /**
     * Separable Wronski gradient taps over the 2x2 support (a, b / c, d),
     * evaluated only where the support is in bounds.
     */
    fun gradientX(a: Float, b: Float, c: Float, d: Float): Float = 0.25f * (-a + b - c + d)
    fun gradientY(a: Float, b: Float, c: Float, d: Float): Float = 0.25f * (-a - b + c + d)

    /**
     * 2x2 structure-tensor window at quad ([x], [y]) into `out[0..2]`
     * (`t00`, `t01`, `t11`); out-of-bounds gradients contribute nothing.
     * [out] is caller scratch (the donor reuses its eig scratch).
     */
    fun structureTensor(
        grads: FloatArray,
        gradWidth: Int,
        gradHeight: Int,
        x: Int,
        y: Int,
        out: FloatArray
    ) {
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
        out[0] = t00
        out[1] = t01
        out[2] = t11
    }

    /** Anisotropy from the eigenpair (exact-flat 0/0 stabilizes to 1). */
    fun anisotropy(l1: Float, l2: Float): Float {
        val ratio = (l1 - l2) / (l1 + l2)
        return if (ratio >= 0f) 1f + sqrt(ratio) else 1f
    }

    /** Detail strength from the dominant eigenvalue. */
    fun detail(l1: Float): Float = if (l1 > 0f) sqrt(l1) else 0f

    /** Denoise blend weight from the detail strength. */
    fun denoiseWeight(detail: Float, dTh: Float, dTr: Float): Float =
        (1f - detail / dTr + dTh).coerceIn(0f, 1f)

    /**
     * Blended kernel radius along one axis: the coupled path keeps the
     * reference op order verbatim; the decoupled override blends toward the
     * absolute flatSigma instead of the kDetail-scaled product; the detail
     * floor clamps both afterwards (max with 0 is a no-op, so the reference
     * path is untouched).
     */
    fun blendRadius(
        kDetail: Float,
        kDenoise: Float,
        flatSigma: Float?,
        detailFloor: Float,
        denoise: Float,
        axis: Float
    ): Float = if (flatSigma != null) {
        maxOf((1f - denoise) * kDetail * axis + denoise * flatSigma, detailFloor)
    } else {
        maxOf((kDetail * ((1f - denoise) * axis + denoise * kDenoise)).toFloat(), detailFloor)
    }

    /**
     * Covariance assembly from the squared radii and eigenbasis into [out]
     * at [o] (packed row-major `(m00, m01, m10, m11)`).
     */
    fun assembleCovariance(
        k1Sq: Float, k2Sq: Float,
        e1x: Float, e1y: Float, e2x: Float, e2y: Float,
        out: FloatArray, o: Int
    ) {
        out[o] = k1Sq * e1x * e1x + k2Sq * e2x * e2x
        out[o + 1] = k1Sq * e1x * e1y + k2Sq * e2x * e2y
        out[o + 2] = out[o + 1]
        out[o + 3] = k1Sq * e1y * e1y + k2Sq * e2y * e2y
    }

    /**
     * Precision assembly from the same eigenbasis: exactly the matrix
     * inverse and positive-definite whenever the radii are positive and
     * finite.
     */
    fun assemblePrecision(
        k1Sq: Float, k2Sq: Float,
        e1x: Float, e1y: Float, e2x: Float, e2y: Float,
        out: FloatArray, o: Int
    ) {
        val i1 = 1f / k1Sq
        val i2 = 1f / k2Sq
        out[o] = i1 * e1x * e1x + i2 * e2x * e2x
        out[o + 1] = i1 * e1x * e1y + i2 * e2x * e2y
        out[o + 2] = out[o + 1]
        out[o + 3] = i1 * e1y * e1y + i2 * e2y * e2y
    }
}
