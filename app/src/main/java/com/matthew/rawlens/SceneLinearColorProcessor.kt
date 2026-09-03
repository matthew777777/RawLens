// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Frozen inputs for the post-demosaic color stage. Matrix arrays use RawFrameMetadata's storage:
 * Camera2 ColorSpaceTransform elements in API order, not mathematical row-major order.
 */
data class SceneLinearColorMetadata(
    val asShotNeutral: ImmutableDoubleValues?,
    val wbGains: ImmutableFloatValues?,
    val analogBalance: ImmutableDoubleValues? = null,
    val cameraCalibration1: ImmutableDoubleValues?,
    val cameraCalibration2: ImmutableDoubleValues?,
    val forwardMatrix1: ImmutableDoubleValues?,
    val forwardMatrix2: ImmutableDoubleValues?,
    val colorMatrix1: ImmutableDoubleValues?,
    val colorMatrix2: ImmutableDoubleValues?,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?
) {
    companion object {
        fun from(metadata: RawFrameMetadata): SceneLinearColorMetadata = SceneLinearColorMetadata(
            asShotNeutral = metadata.neutralColorPoint,
            wbGains = metadata.wbGains,
            // Camera2 has no AnalogBalance key. Camera2 matrices therefore use the DNG default,
            // identity, unless a future calibrated profile supplies an explicit value here.
            analogBalance = null,
            cameraCalibration1 = metadata.cameraCalibration1,
            cameraCalibration2 = metadata.cameraCalibration2,
            forwardMatrix1 = metadata.forwardMatrix1,
            forwardMatrix2 = metadata.forwardMatrix2,
            colorMatrix1 = metadata.colorMatrix1,
            colorMatrix2 = metadata.colorMatrix2,
            referenceIlluminant1 = metadata.referenceIlluminant1,
            referenceIlluminant2 = metadata.referenceIlluminant2
        )
    }
}

enum class CameraToXyzPolicy { FORWARD_MATRIX, COLOR_MATRIX_BRADFORD_FALLBACK }
enum class WhiteBalanceSource { AS_SHOT_NEUTRAL, CAMERA2_GAINS, UNITY_FALLBACK }

data class ResolvedSceneLinearTransform(
    /** Mathematical row-major matrix; CPU vectors are columns: acescg = M * cameraRgb. */
    val cameraToAcescg: ImmutableDoubleValues,
    val cameraToXyzD50: ImmutableDoubleValues,
    /** Sensor/camera-space neutral white, normalized so its largest channel is 1.0. */
    val cameraWhiteNormalized: ImmutableDoubleValues,
    val whiteBalance: ImmutableDoubleValues,
    val exposureEv: Double,
    val interpolationFactor: Double,
    val cameraToXyzPolicy: CameraToXyzPolicy,
    val whiteBalanceSource: WhiteBalanceSource,
    val warnings: List<String>
) {
    init {
        require(cameraToAcescg.size == 9 && cameraToXyzD50.size == 9)
        require(cameraWhiteNormalized.size == 3 && whiteBalance.size == 3)
        require(exposureEv.isFinite() && interpolationFactor.isFinite())
    }

    /** GLSL mat3 uniforms are column-major; transpose the mathematical row-major storage. */
    fun glslColumnMajorMatrix(): FloatArray {
        val rowMajor = cameraToAcescg.toDoubleArray()
        return FloatArray(9) { index ->
            val column = index / 3
            val row = index % 3
            rowMajor[row * 3 + column].toFloat()
        }
    }

    fun glslCameraWhiteNormalized(): FloatArray = FloatArray(3) { cameraWhiteNormalized[it].toFloat() }
}

/**
 * CPU reference for AMaZE camera RGB -> white balance/calibration -> XYZ D50 -> XYZ D60 -> ACEScg.
 *
 * Conventions:
 * - all CPU matrices are mathematical row-major 3x3 matrices;
 * - RGB/XYZ values are column vectors and `out = matrix * in`;
 * - RawFrameMetadata matrices are transposed exactly once on ingestion, matching the proven
 *   Camera2-to-DNG orientation fix in NativeDngWriter;
 * - no RGB range clamp is performed; alpha is explicitly written as 1.
 *
 * The dual-illuminant solver is pinned to PhotonCamera Converter.java as present in the local
 * reference checkout on 2026-08-28. The final transforms follow Adobe DNG 1.7.1.0 chapter 6.
 */
object SceneLinearColorProcessor {
    const val CPU_GLSL_TOLERANCE = 2.5e-4f

    fun resolve(metadata: SceneLinearColorMetadata, exposureEv: Double = 0.0): ResolvedSceneLinearTransform {
        require(exposureEv.isFinite() && exposureEv in -16.0..16.0) {
            "Technical exposure must be finite and within -16..+16 EV"
        }
        val warnings = mutableListOf<String>()
        val (neutral, neutralSource) = resolveNeutral(metadata, warnings)
        val neutralMax = neutral.maxComponent()
        val cameraWhiteNormalized = Vec3(
            neutral.x / neutralMax,
            neutral.y / neutralMax,
            neutral.z / neutralMax
        )
        val analogBalance = diagonalOrIdentity(metadata.analogBalance, "AnalogBalance", warnings)
        val calibration1 = camera2MatrixOrNull(metadata.cameraCalibration1)
        val calibration2 = camera2MatrixOrNull(metadata.cameraCalibration2)
        val calibrations = matrixPair(
            calibration1,
            calibration2,
            Matrix3.IDENTITY,
            "CameraCalibration",
            warnings
        )
        val forward1 = camera2MatrixOrNull(metadata.forwardMatrix1)?.normalizedForward()
        val forward2 = camera2MatrixOrNull(metadata.forwardMatrix2)?.normalizedForward()
        val color1 = camera2MatrixOrNull(metadata.colorMatrix1)
        val color2 = camera2MatrixOrNull(metadata.colorMatrix2)

        val interpolation = findInterpolationFactor(
            metadata.referenceIlluminant1,
            metadata.referenceIlluminant2,
            calibrations.first,
            calibrations.second,
            analogBalance,
            color1,
            color2,
            neutral
        ) ?: run {
            warnings += "Dual-illuminant interpolation unavailable; using matrix set 1"
            0.0
        }

        val calibration = Matrix3.lerp(calibrations.first, calibrations.second, interpolation)
        val analogCalibration = analogBalance * calibration
        val inverseAnalogCalibration = analogCalibration.inverseOrNull()
            ?: throw UnsupportedOperationException("AnalogBalance * CameraCalibration is singular")
        val referenceNeutral = inverseAnalogCalibration * neutral
        require(validNeutral(referenceNeutral)) {
            "Camera neutral becomes zero, negative, NaN, or infinite after calibration"
        }
        val neutralScale = referenceNeutral.maxComponent()
        val whiteBalance = Vec3(
            neutralScale / referenceNeutral.x,
            neutralScale / referenceNeutral.y,
            neutralScale / referenceNeutral.z
        )

        val forwardPair = validPairOrNull(forward1, forward2, "ForwardMatrix", warnings)
        val cameraToXyz: Matrix3
        val policy: CameraToXyzPolicy
        if (forwardPair != null) {
            val forward = Matrix3.lerp(forwardPair.first, forwardPair.second, interpolation)
            cameraToXyz = forward * Matrix3.diagonal(whiteBalance) * inverseAnalogCalibration
            policy = CameraToXyzPolicy.FORWARD_MATRIX
        } else {
            warnings += "ForwardMatrix missing or invalid; using ColorMatrix plus linear Bradford"
            val colorPair = validPairOrNull(color1, color2, "ColorMatrix", warnings)
                ?: throw UnsupportedOperationException(
                    "No valid ForwardMatrix or invertible ColorMatrix is available for calibrated JPEG development"
                )
            val xyzToCamera = analogCalibration * Matrix3.lerp(
                colorPair.first,
                colorPair.second,
                interpolation
            )
            val cameraToSourceXyz = xyzToCamera.inverseOrNull()
                ?: throw UnsupportedOperationException("Interpolated ColorMatrix path is singular")
            val sourceWhite = cameraToSourceXyz * neutral
            require(validNeutral(sourceWhite)) {
                "ColorMatrix path cannot derive a valid selected white point"
            }
            cameraToXyz = bradfordAdaptation(sourceWhite.normalizedY(), D50) * cameraToSourceXyz
            policy = CameraToXyzPolicy.COLOR_MATRIX_BRADFORD_FALLBACK
        }

        val exposure = 2.0.pow(exposureEv)
        val cameraToAces = Matrix3.diagonal(Vec3(exposure, exposure, exposure)) *
            XYZ_D60_TO_ACESCG * D50_TO_D60 * cameraToXyz
        require(cameraToAces.isFinite()) { "Resolved camera-to-ACEScg matrix is not finite" }
        return ResolvedSceneLinearTransform(
            cameraToAcescg = ImmutableDoubleValues(cameraToAces.values()),
            cameraToXyzD50 = ImmutableDoubleValues(cameraToXyz.values()),
            cameraWhiteNormalized = ImmutableDoubleValues(cameraWhiteNormalized.values()),
            whiteBalance = ImmutableDoubleValues(whiteBalance.values()),
            exposureEv = exposureEv,
            interpolationFactor = interpolation,
            cameraToXyzPolicy = policy,
            whiteBalanceSource = neutralSource,
            warnings = warnings.toList()
        )
    }

    fun processRgba(input: FloatArray, transform: ResolvedSceneLinearTransform): FloatArray {
        require(input.size % 4 == 0) { "Scene-linear RGBA input must contain four floats per pixel" }
        val matrix = Matrix3(transform.cameraToAcescg.toDoubleArray())
        val output = FloatArray(input.size)
        for (offset in input.indices step 4) {
            val mapped = matrix * Vec3(
                input[offset].toDouble(),
                input[offset + 1].toDouble(),
                input[offset + 2].toDouble()
            )
            require(mapped.isFinite()) { "Scene-linear color conversion produced NaN or infinity" }
            output[offset] = mapped.x.toFloat()
            output[offset + 1] = mapped.y.toFloat()
            output[offset + 2] = mapped.z.toFloat()
            output[offset + 3] = 1f
        }
        return output
    }

    /** Float-order mirror of the fused AMaZE-final color transform, used by deterministic JVM tests. */
    fun evaluateGlslContract(
        rgb: FloatArray,
        glslColumnMajorMatrix: FloatArray
    ): FloatArray {
        require(rgb.size == 3 && glslColumnMajorMatrix.size == 9)
        // GLSL mat3 constructor/uniform storage is column-major. Index column*3+row.
        return FloatArray(3) { row ->
            glslColumnMajorMatrix[row] * rgb[0] +
                glslColumnMajorMatrix[3 + row] * rgb[1] +
                glslColumnMajorMatrix[6 + row] * rgb[2]
        }
    }

    /** Mirrors the pre-WB shader highlight neutralization used by both JPEG paths. */
    fun neutralizeCameraHighlight(
        rgb: FloatArray,
        cameraWhiteNormalized: FloatArray,
        start: Float = 0.70f,
        end: Float = 0.99f
    ): FloatArray {
        require(rgb.size == 3 && cameraWhiteNormalized.size == 3)
        require(start.isFinite() && end.isFinite() && start < end)
        val nonNegative = FloatArray(3) { rgb[it].coerceAtLeast(0f) }
        val peak = nonNegative.maxOrNull() ?: 0f
        val t = ((peak - start) / (end - start)).coerceIn(0f, 1f)
        val blend = t * t * (3f - 2f * t)
        return FloatArray(3) { channel ->
            nonNegative[channel] + (cameraWhiteNormalized[channel] - nonNegative[channel]) * blend
        }
    }

    fun d50ToD60Matrix(): DoubleArray = D50_TO_D60.values()
    fun xyzD60ToAcescgMatrix(): DoubleArray = XYZ_D60_TO_ACESCG.values()
    fun d50White(): DoubleArray = D50.values()
    fun d60White(): DoubleArray = D60.values()

    private fun resolveNeutral(
        metadata: SceneLinearColorMetadata,
        warnings: MutableList<String>
    ): Pair<Vec3, WhiteBalanceSource> {
        metadata.asShotNeutral?.toDoubleArray()?.takeIf(::validTriple)?.let {
            val neutral = Vec3(it[0], it[1], it[2])
            if (validNeutral(neutral)) return neutral to WhiteBalanceSource.AS_SHOT_NEUTRAL
        }
        metadata.wbGains?.toFloatArray()?.takeIf { gains ->
            gains.size == 4 && gains.all { it.isFinite() && it > 0f }
        }?.let { gains ->
            warnings += "AsShotNeutral missing or invalid; derived neutral from Camera2 WB gains"
            val green = 0.5 * (gains[1] + gains[2])
            return Vec3(1.0 / gains[0], 1.0 / green, 1.0 / gains[3]) to
                WhiteBalanceSource.CAMERA2_GAINS
        }
        warnings += "AsShotNeutral and WB gains missing or invalid; using unity neutral"
        return Vec3.ONE to WhiteBalanceSource.UNITY_FALLBACK
    }

    private fun diagonalOrIdentity(
        values: ImmutableDoubleValues?,
        name: String,
        warnings: MutableList<String>
    ): Matrix3 {
        val triple = values?.toDoubleArray()
        return if (triple != null && validTriple(triple) && triple.all { it > 0.0 }) {
            Matrix3.diagonal(Vec3(triple[0], triple[1], triple[2]))
        } else {
            if (values != null) warnings += "$name invalid; using DNG identity default"
            Matrix3.IDENTITY
        }
    }

    private fun camera2MatrixOrNull(values: ImmutableDoubleValues?): Matrix3? {
        val frozen = values?.toDoubleArray() ?: return null
        if (frozen.size != 9 || frozen.any { !it.isFinite() }) return null
        // Match PhotonCamera Converter.convertColorspaceTransform and the working DNG tags:
        // output[row, column] = Camera2.getElement(column, row). Camera2's transform tag layout
        // is not the row-major camera-RGB-to-XYZ matrix consumed by this development pipeline.
        val rowMajor = DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            frozen[column * 3 + row]
        }
        return Matrix3(rowMajor).takeIf { it.isUsable() }
    }

    private fun matrixPair(
        first: Matrix3?,
        second: Matrix3?,
        default: Matrix3,
        name: String,
        warnings: MutableList<String>
    ): Pair<Matrix3, Matrix3> = when {
        first != null && second != null -> first to second
        first != null -> {
            warnings += "$name 2 missing or invalid; reusing $name 1"
            first to first
        }
        second != null -> {
            warnings += "$name 1 missing or invalid; reusing $name 2"
            second to second
        }
        else -> {
            warnings += "$name missing or invalid; using DNG identity default"
            default to default
        }
    }

    private fun validPairOrNull(
        first: Matrix3?,
        second: Matrix3?,
        name: String,
        warnings: MutableList<String>
    ): Pair<Matrix3, Matrix3>? = when {
        first != null && second != null -> first to second
        first != null -> {
            warnings += "$name 2 missing or invalid; reusing $name 1"
            first to first
        }
        second != null -> {
            warnings += "$name 1 missing or invalid; reusing $name 2"
            second to second
        }
        else -> null
    }

    private fun findInterpolationFactor(
        illuminant1: Int?,
        illuminant2: Int?,
        calibration1: Matrix3,
        calibration2: Matrix3,
        analogBalance: Matrix3,
        color1: Matrix3?,
        color2: Matrix3?,
        cameraNeutral: Vec3
    ): Double? {
        val temperature1 = ILLUMINANT_KELVIN[illuminant1] ?: return null
        val temperature2 = ILLUMINANT_KELVIN[illuminant2] ?: return null
        if (temperature1 == temperature2) return 0.0
        val cm1 = color1?.normalizedForward() ?: return null
        val cm2 = color2?.normalizedForward() ?: return null
        // PhotonCamera has no non-identity AnalogBalance source; with Camera2 metadata this is
        // byte-for-byte the pinned Photon policy. Explicit profile overrides follow DNG's AB*CC*CM.
        val xyzToCamera1 = analogBalance * calibration1 * cm1
        val xyzToCamera2 = analogBalance * calibration2 * cm2
        var factor = 0.5
        var previous = factor
        var difference = Double.MAX_VALUE
        var remaining = 30
        val lower = minOf(temperature1, temperature2).toDouble()
        val upper = maxOf(temperature1, temperature2).toDouble()
        while (difference > 0.0001 && remaining-- > 0) {
            val inverse = Matrix3.lerp(xyzToCamera1, xyzToCamera2, factor).inverseOrNull()
                ?: return null
            val xyz = inverse * cameraNeutral
            val sum = xyz.x + xyz.y + xyz.z
            if (!sum.isFinite() || abs(sum) < MATRIX_EPSILON) return null
            val x = xyz.x / sum
            val y = xyz.y / sum
            val denominator = y - 0.1858
            if (abs(denominator) < MATRIX_EPSILON) return null
            val n = (x - 0.332) / denominator
            val cct = -449.0 * n.pow(3) + 3525.0 * n.pow(2) - 6823.3 * n + 5520.33
            if (!cct.isFinite() || cct <= 0.0) return null
            factor = when {
                cct <= lower -> 1.0
                cct >= upper -> 0.0
                else -> ((1.0 / cct) - (1.0 / upper)) / ((1.0 / lower) - (1.0 / upper))
            }
            if (lower == temperature1.toDouble()) factor = 1.0 - factor
            factor = 0.5 * (factor + previous)
            difference = abs(previous - factor)
            previous = factor
        }
        return factor.takeIf { it.isFinite() && it in 0.0..1.0 }
    }

    private fun bradfordAdaptation(sourceWhite: Vec3, destinationWhite: Vec3): Matrix3 {
        val sourceCone = BRADFORD * sourceWhite
        val destinationCone = BRADFORD * destinationWhite
        require(validNeutral(sourceCone) && validNeutral(destinationCone)) {
            "Bradford adaptation received an invalid white point"
        }
        return BRADFORD_INVERSE * Matrix3.diagonal(
            Vec3(
                destinationCone.x / sourceCone.x,
                destinationCone.y / sourceCone.y,
                destinationCone.z / sourceCone.z
            )
        ) * BRADFORD
    }

    private fun validTriple(values: DoubleArray): Boolean =
        values.size == 3 && values.all(Double::isFinite)

    private fun validNeutral(value: Vec3): Boolean =
        value.isFinite() && value.x > MATRIX_EPSILON && value.y > MATRIX_EPSILON &&
            value.z > MATRIX_EPSILON

    private val D50 = Vec3(0.96422, 1.0, 0.82521)
    private val D60 = Vec3(0.9526460746, 1.0, 1.0088251844)
    private val BRADFORD = Matrix3(doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296
    ))
    private val BRADFORD_INVERSE = checkNotNull(BRADFORD.inverseOrNull())
    private val D50_TO_D60 = bradfordAdaptation(D50, D60)
    private val XYZ_D60_TO_ACESCG = Matrix3(doubleArrayOf(
        1.6410233797, -0.3248032942, -0.2364246952,
        -0.6636628587, 1.6153315917, 0.0167563477,
        0.0117218943, -0.0082844420, 0.9883948585
    ))

    // CameraMetadata illuminant codes and kelvin values copied from PhotonCamera Converter.java.
    private val ILLUMINANT_KELVIN = mapOf(
        1 to 6504, 21 to 6504, 23 to 5003, 20 to 5503, 22 to 7504,
        17 to 2856, 18 to 4874, 19 to 6774, 12 to 6430, 14 to 4230, 15 to 3450
    )
    private const val MATRIX_EPSILON = 1e-10
}

private data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    fun values() = doubleArrayOf(x, y, z)
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun maxComponent() = max(x, max(y, z))
    fun normalizedY() = this * (1.0 / y)

    companion object {
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}

private class Matrix3(values: DoubleArray) {
    private val m = values.copyOf()

    init {
        require(m.size == 9)
    }

    fun values() = m.copyOf()
    fun isFinite() = m.all(Double::isFinite)
    fun isUsable() = isFinite() && inverseOrNull() != null

    operator fun times(vector: Vec3) = Vec3(
        m[0] * vector.x + m[1] * vector.y + m[2] * vector.z,
        m[3] * vector.x + m[4] * vector.y + m[5] * vector.z,
        m[6] * vector.x + m[7] * vector.y + m[8] * vector.z
    )

    operator fun times(other: Matrix3): Matrix3 = Matrix3(DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        (0..2).sumOf { k -> m[row * 3 + k] * other.m[k * 3 + column] }
    })

    fun inverseOrNull(): Matrix3? {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]
        val a10 = m[3]; val a11 = m[4]; val a12 = m[5]
        val a20 = m[6]; val a21 = m[7]; val a22 = m[8]
        val t00 = a11 * a22 - a21 * a12
        val t01 = a21 * a02 - a01 * a22
        val t02 = a01 * a12 - a11 * a02
        val t10 = a20 * a12 - a10 * a22
        val t11 = a00 * a22 - a20 * a02
        val t12 = a10 * a02 - a00 * a12
        val t20 = a10 * a21 - a20 * a11
        val t21 = a20 * a01 - a00 * a21
        val t22 = a00 * a11 - a10 * a01
        val determinant = a00 * t00 + a01 * t10 + a02 * t20
        if (!determinant.isFinite() || abs(determinant) < 1e-10) return null
        return Matrix3(doubleArrayOf(
            t00 / determinant, t01 / determinant, t02 / determinant,
            t10 / determinant, t11 / determinant, t12 / determinant,
            t20 / determinant, t21 / determinant, t22 / determinant
        )).takeIf(Matrix3::isFinite)
    }

    fun normalizedForward(): Matrix3? {
        val white = this * Vec3.ONE
        if (!white.isFinite() || abs(white.x) < 1e-10 || abs(white.y) < 1e-10 ||
            abs(white.z) < 1e-10
        ) return null
        return diagonal(Vec3(D50_X / white.x, 1.0 / white.y, D50_Z / white.z)) * this
    }

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        ))

        fun diagonal(value: Vec3) = Matrix3(doubleArrayOf(
            value.x, 0.0, 0.0,
            0.0, value.y, 0.0,
            0.0, 0.0, value.z
        ))

        fun lerp(first: Matrix3, second: Matrix3, factor: Double) = Matrix3(
            DoubleArray(9) { index -> first.m[index] * (1.0 - factor) + second.m[index] * factor }
        )

        private const val D50_X = 0.96422
        private const val D50_Z = 0.82521
    }
}
