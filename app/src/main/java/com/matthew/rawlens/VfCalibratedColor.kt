// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Calibrated JPEG color for one viewfinder frame: the same camera-to-ACEScg
 * contract the saved JPEG develops with, plus the highlight-neutralization
 * white. [cameraToAcescgColumnMajor] is GLSL column-major (index column*3+row);
 * exposure is NOT folded in — the VF shader multiplies scene-linear camera RGB
 * by exp2(preview EV) before this matrix, which is linear-equivalent to the
 * save path folding EV into its matrix.
 */
data class VfCalibratedColor(
    val cameraToAcescgColumnMajor: FloatArray,
    val cameraWhiteNormalized: FloatArray,
    /** False when the HAL CCM fallback below stands in for missing calibration. */
    val calibrated: Boolean
) {
    init {
        require(cameraToAcescgColumnMajor.size == 9 && cameraWhiteNormalized.size == 3)
    }

    override fun equals(other: Any?): Boolean =
        other is VfCalibratedColor &&
            cameraToAcescgColumnMajor.contentEquals(other.cameraToAcescgColumnMajor) &&
            cameraWhiteNormalized.contentEquals(other.cameraWhiteNormalized) &&
            calibrated == other.calibrated

    override fun hashCode(): Int =
        31 * (31 * cameraToAcescgColumnMajor.contentHashCode() +
            cameraWhiteNormalized.contentHashCode()) + calibrated.hashCode()
}

object VfCalibratedColorResolver {
    /**
     * Linear sRGB -> ACEScg, mathematical row-major: the inverse of
     * (REC2020_TO_SRGB * ACESCG_TO_REC2020_D65) from the pinned AgX constants.
     * Maps the HAL COLOR_CORRECTION_TRANSFORM output into the ACEScg working
     * space the JPEG AgX tail expects when no DNG calibration exists.
     */
    private val SRGB_TO_ACESCG = doubleArrayOf(
        0.613063711512, 0.339477393287, 0.047380526502,
        0.070193650692, 0.916368854813, 0.013450721344,
        0.020619704477, 0.109587044446, 0.870001252085
    )

    /**
     * Resolve the VF JPEG color transform for one frame. Calibrated DNG
     * matrices win (identical inputs to the save path); otherwise the HAL
     * CCM/WB fallback keeps the preview live with approximately-correct color.
     * Never throws: the camera thread must never drop the VF on bad metadata.
     *
     * @param canonicalWbGains sanitized [R, Gr, Gb, B] in quad output order.
     * @param halCcmColumnMajor sanitized HAL transform in FrameState layout
     * (index column*3+row = getElement(column, row)).
     */
    fun resolve(
        metadata: SceneLinearColorMetadata,
        canonicalWbGains: FloatArray,
        halCcmColumnMajor: FloatArray
    ): VfCalibratedColor {
        require(canonicalWbGains.size == 4 && halCcmColumnMajor.size == 9)
        runCatching {
            val transform = SceneLinearColorProcessor.resolve(metadata, 0.0)
            val matrix = transform.glslColumnMajorMatrix()
            val white = transform.glslCameraWhiteNormalized()
            if (matrix.all(Float::isFinite) && white.all(Float::isFinite)) {
                return VfCalibratedColor(matrix, white, true)
            }
        }
        return fallback(canonicalWbGains, halCcmColumnMajor)
    }

    /**
     * HAL fallback: fallback = SRGB_TO_ACESCG * CCM * diag(R, Gavg, B), with the
     * neutral derived from the WB gains exactly like the Camera2-gains branch of
     * [SceneLinearColorProcessor]. Feeds un-gained camera RGB (the JPEG tail never
     * applies the `gains` uniform).
     */
    fun fallback(
        canonicalWbGains: FloatArray,
        halCcmColumnMajor: FloatArray
    ): VfCalibratedColor {
        require(canonicalWbGains.size == 4 && halCcmColumnMajor.size == 9)
        fun gain(index: Int): Double {
            val value = canonicalWbGains[index].toDouble()
            return if (value.isFinite() && value > 0.0) value else 1.0
        }
        val gains = doubleArrayOf(gain(0), 0.5 * (gain(1) + gain(2)), gain(3))
        // FrameState layout is column-major storage: math[r, c] = hal[c * 3 + r].
        fun hal(row: Int, column: Int): Double {
            val value = halCcmColumnMajor[column * 3 + row].toDouble()
            return if (value.isFinite()) value else if (row == column) 1.0 else 0.0
        }
        val rowMajor = DoubleArray(9)
        for (row in 0..2) for (column in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += SRGB_TO_ACESCG[row * 3 + k] * hal(k, column)
            rowMajor[row * 3 + column] = sum * gains[column]
        }
        val neutral = DoubleArray(3) { 1.0 / gains[it] }
        val peak = neutral.maxOrNull() ?: 1.0
        val white = FloatArray(3) { (neutral[it] / peak).toFloat() }
        val matrix = FloatArray(9) { index ->
            val column = index / 3
            val row = index % 3
            rowMajor[row * 3 + column].toFloat()
        }
        if (!matrix.all(Float::isFinite) || !white.all(Float::isFinite)) {
            return VfCalibratedColor(
                floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                floatArrayOf(1f, 1f, 1f),
                false
            )
        }
        return VfCalibratedColor(matrix, white, false)
    }
}

/**
 * Camera-thread single-entry cache for [VfCalibratedColorResolver.resolve]: the static
 * DNG matrices never change within a session and AWB outputs hold still most
 * frames, so the resolve (dual-illuminant solver) runs only on change.
 *
 * Compare-before-copy: steady-state frames only run content comparisons over
 * the caller's arrays (no per-frame copies); copies happen on a miss, when the
 * inputs are frozen into the resolve anyway. The caller refreshes [staticMats]
 * only when the characteristics identity changes (once per session).
 */
class VfCalibratedColorCache {
    private var lastStatic: List<DoubleArray?>? = null
    private var lastIlluminants: List<Int?>? = null
    private var lastNeutral: DoubleArray? = null
    private var lastGains: FloatArray? = null
    private var lastCanonical: FloatArray? = null
    private var lastCcm: FloatArray? = null
    private var lastResult: VfCalibratedColor? = null

    /**
     * @param staticMats [cameraCalibration1, cameraCalibration2, forwardMatrix1,
     * forwardMatrix2, colorMatrix1, colorMatrix2] in Camera2 storage order.
     * @param neutral as-shot neutral triple, or null when unreported.
     * @param gainsSensor WB gains in sensor order [R, Geven, Godd, B].
     * @param canonicalGains sanitized [R, Gr, Gb, B] in quad output order.
     * @param ccm sanitized HAL transform in FrameState layout.
     */
    fun resolve(
        staticMats: List<DoubleArray?>,
        illuminant1: Int?,
        illuminant2: Int?,
        neutral: DoubleArray?,
        gainsSensor: FloatArray?,
        canonicalGains: FloatArray,
        ccm: FloatArray
    ): VfCalibratedColor {
        require(staticMats.size == 6 && canonicalGains.size == 4 && ccm.size == 9)
        val cached = lastResult
        if (cached != null &&
            nullableListEquals(staticMats, lastStatic) &&
            illuminant1 == lastIlluminants?.get(0) && illuminant2 == lastIlluminants?.get(1) &&
            nullableEquals(neutral, lastNeutral) &&
            nullableEquals(gainsSensor, lastGains) &&
            nullableEquals(canonicalGains, lastCanonical) &&
            nullableEquals(ccm, lastCcm)
        ) return cached
        val metadata = SceneLinearColorMetadata(
            asShotNeutral = neutral?.let { ImmutableDoubleValues(it.copyOf()) },
            wbGains = gainsSensor?.let { ImmutableFloatValues(it.copyOf()) },
            cameraCalibration1 = staticMats[0]?.let { ImmutableDoubleValues(it.copyOf()) },
            cameraCalibration2 = staticMats[1]?.let { ImmutableDoubleValues(it.copyOf()) },
            forwardMatrix1 = staticMats[2]?.let { ImmutableDoubleValues(it.copyOf()) },
            forwardMatrix2 = staticMats[3]?.let { ImmutableDoubleValues(it.copyOf()) },
            colorMatrix1 = staticMats[4]?.let { ImmutableDoubleValues(it.copyOf()) },
            colorMatrix2 = staticMats[5]?.let { ImmutableDoubleValues(it.copyOf()) },
            referenceIlluminant1 = illuminant1,
            referenceIlluminant2 = illuminant2
        )
        val result = VfCalibratedColorResolver.resolve(metadata, canonicalGains, ccm)
        lastStatic = staticMats.map { it?.copyOf() }
        lastIlluminants = listOf(illuminant1, illuminant2)
        lastNeutral = neutral?.copyOf()
        lastGains = gainsSensor?.copyOf()
        lastCanonical = canonicalGains.copyOf()
        lastCcm = ccm.copyOf()
        lastResult = result
        return result
    }

    private fun nullableEquals(a: DoubleArray?, b: DoubleArray?): Boolean =
        if (a == null || b == null) a === b else a.contentEquals(b)

    private fun nullableEquals(a: FloatArray?, b: FloatArray?): Boolean =
        if (a == null || b == null) a === b else a.contentEquals(b)

    private fun nullableListEquals(a: List<DoubleArray?>, b: List<DoubleArray?>?): Boolean {
        if (b == null || a.size != b.size) return false
        for (i in a.indices) if (!nullableEquals(a[i], b[i])) return false
        return true
    }
}
