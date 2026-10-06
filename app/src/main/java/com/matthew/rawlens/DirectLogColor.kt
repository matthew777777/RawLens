// SPDX-License-Identifier: GPL-3.0-or-later
// RawLens — static DNG ForwardMatrix CCM for the Direct-Log video path.
//
// Why this file exists: per-frame capture results (COLOR_CORRECTION gains
// and transform) are usually absent on the record path — the result-callback
// miss keeps the CCM fallback engaged and the fallback is identity, which
// renders as a cyan/green overcast. Rather than depending on the frame
// callback, the video path reads the two static calibration matrices
// (SENSOR_FORWARD_MATRIX1/2) once per recording and bakes one constant
// camera-native -> linear-sRGB matrix. Observation only: no vendor code was
// consulted; the math is the published DNG 1.4 color pipeline plus the stock
// IEC 61966-2-1 and Bradford constants.
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform

/**
 * Static color helper for Direct-Log video.
 *
 * Pipeline: interpolate the two calibration ForwardMatrices at a fixed 5500K
 * (DNG inverse-CCT blend), normalize white (D50), adapt the result D50 -> D65
 * with a Bradford CAT, then convert XYZ(D65) -> linear sRGB. The grade shader
 * applies its S-Log3 + contrast curve downstream, so this matrix must output
 * linear light — never a gamma-encoded space.
 */
internal object DirectLogColor {
    /** Fixed daylight CCT the static CCM is baked at (K). */
    const val STATIC_CCT_KELVIN = 5_500.0

    /** D50 reference white (XYZ, Y = 1). */
    private val D50_WHITE = Vec3(0.96422, 1.0, 0.82521)

    /** D65 reference white (XYZ, Y = 1). */
    private val D65_WHITE = Vec3(0.95047, 1.0, 1.08883)

    /**
     * XYZ(D65) -> linear-sRGB, row-major (IEC 61966-2-1 §4.3.2, rounded),
     * shared with the stills SceneLinear chain which uses the same matrix.
     */
    private val SRGB_FROM_XYZ_D65 = Matrix3(
        doubleArrayOf(
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252
        )
    )

    /** Bradford cone-response matrix, row-major. */
    private val BRADFORD = Matrix3(
        doubleArrayOf(
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
        )
    )

    /**
     * Constant tail of the static pipeline: sRGB-from-XYZ(D65) x
     * Bradford(D50 -> D65). Internal so unit tests can pin the
     * white-preserving invariant directly.
     */
    internal val K: Matrix3 by lazy {
        val bInv = BRADFORD.inverseOrNull() ?: error("Bradford matrix must be invertible")
        val src = BRADFORD * D50_WHITE
        val dst = BRADFORD * D65_WHITE
        val gain = Vec3(dst.x / src.x, dst.y / src.y, dst.z / src.z)
        val adapt = bInv * Matrix3.diagonal(gain) * BRADFORD
        SRGB_FROM_XYZ_D65 * adapt
    }

    /** Static ForwardMatrix pair plus its illuminant tags, math row-major. */
    data class ForwardPair(
        val fm1: DoubleArray,
        val fm2: DoubleArray,
        val illuminant1: Int,
        val illuminant2: Int
    )

    /**
     * Reads the static calibration matrices from camera characteristics.
     * Returns null unless BOTH matrices and BOTH illuminant tags are present.
     *
     * Ingestion matches the live CCM path ([DirectLogRecorder.ccmFromTransform]):
     * getElement takes (column, row), so math row-major element [row, col]
     * is getElement(col, row). ([RawFrameMetadata] snapshots the transpose
     * and [SceneLinearColorProcessor] transposes back on consume; this path
     * needs row-major directly.)
     */
    fun readForwardPair(c: CameraCharacteristics): ForwardPair? {
        val t1 = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) ?: return null
        val t2 = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) ?: return null
        val ill1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: return null
        val ill2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: return null
        return ForwardPair(ingest(t1), ingest(t2), ill1.toInt(), ill2.toInt())
    }

    private fun ingest(t: ColorSpaceTransform) =
        ingestRowMajor { col, row -> t.getElement(col, row).toDouble() }

    /**
     * Row-major ingestion over a getElement(column, row) accessor (split so
     * JVM tests can pin the orientation without android classes).
     */
    internal fun ingestRowMajor(get: (column: Int, row: Int) -> Double): DoubleArray =
        DoubleArray(9) { i -> get(i % 3, i / 3) }

    /**
     * Bakes the static camera-native -> linear-sRGB CCM.
     *
     * @param fm1 forward matrix at illuminant 1, math row-major, length 9
     * @param fm2 forward matrix at illuminant 2, math row-major, length 9
     * @param cctKelvin fixed blend point; clamped into the calibrated [C1, C2]
     *   span (no extrapolation past the vendor's two anchors).
     * @return 9 floats, row-major, or null when the inputs are unusable.
     */
    fun staticCcm(
        fm1: DoubleArray,
        fm2: DoubleArray,
        illuminant1: Int,
        illuminant2: Int,
        cctKelvin: Double = STATIC_CCT_KELVIN
    ): FloatArray? {
        if (fm1.size != 9 || fm2.size != 9) return null
        if (fm1.any { !it.isFinite() } || fm2.any { !it.isFinite() }) return null
        val temp1 = SceneLinearColorProcessor.ILLUMINANT_KELVIN[illuminant1] ?: return null
        val temp2 = SceneLinearColorProcessor.ILLUMINANT_KELVIN[illuminant2] ?: return null
        val m1 = Matrix3(fm1).normalizedForward() ?: return null
        val m2 = Matrix3(fm2).normalizedForward() ?: return null
        val factor = blendFactor(temp1, temp2, cctKelvin)
        val m = Matrix3.lerp(m2, m1, factor)
        val ccm = K * m
        val values = ccm.values()
        if (values.any { !it.isFinite() }) return null
        return FloatArray(9) { i -> values[i].toFloat() }
    }

    /**
     * DNG inverse-CCT blend factor: 1.0 at temp1, 0.0 at temp2, clamped to
     * [0, 1] so out-of-span CCTs pin to the nearest anchor instead of
     * extrapolating. Equal anchors with differing matrices have no preferred
     * side, so the midpoint wins.
     */
    private fun blendFactor(temp1: Int, temp2: Int, cctKelvin: Double): Double {
        if (!cctKelvin.isFinite() || cctKelvin <= 0.0) return 0.5
        if (temp1 == temp2) return 0.5
        val position = 1.0 / cctKelvin
        val raw = (position - 1.0 / temp2) / (1.0 / temp1 - 1.0 / temp2)
        return raw.coerceIn(0.0, 1.0)
    }
}
