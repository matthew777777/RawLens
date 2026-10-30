// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import java.util.Locale

/**
 * One-line colour diagnostics for field logs: which white balance and colour
 * matrices the viewfinder and the save path fed into calibrated development,
 * which capture result (logical or a physical sub-result) they came from, and
 * what the other results reported for the same frame. Exists for the Xiaomi
 * 14 Ultra aux-lens green tint, where direct and routed IDs report identical
 * black/white levels, so the difference has to be in these keys.
 *
 * Pure formatting; Camera2 reads live in the extension functions below.
 */
internal object ColorDiagnostics {
    /** Colour keys read from one capture result. */
    class ResultColor(
        /** `used`, `logical` or `phys:<id>`. */
        val source: String,
        /** COLOR_CORRECTION_GAINS as R, Geven, Godd, B. */
        val gains: FloatArray?,
        val neutral: DoubleArray?,
        /** COLOR_CORRECTION_TRANSFORM in Camera2 storage order. */
        val ccm: DoubleArray?,
        val awbMode: Int?,
        val awbState: Int?
    )

    /**
     * [route] names the stream (route ID or open context), [used] is the set
     * development actually consumed, [others] every other result for the same
     * frame, [statics] the matrices and illuminants with [used]'s neutral/gains.
     */
    fun format(
        prefix: String,
        route: String,
        used: ResultColor,
        others: List<ResultColor>,
        statics: SceneLinearColorMetadata
    ): String = buildString {
        append(prefix).append(" route=").append(route)
        append(' ').append(result(used))
        others.forEach { append(' ').append(result(it)) }
        append(" ill=").append(statics.referenceIlluminant1 ?: "-")
            .append('/').append(statics.referenceIlluminant2 ?: "-")
        append(" cm1=").append(values(statics.colorMatrix1?.toDoubleArray()))
        append(" cm2=").append(values(statics.colorMatrix2?.toDoubleArray()))
        append(" fm1=").append(values(statics.forwardMatrix1?.toDoubleArray()))
        append(" fm2=").append(values(statics.forwardMatrix2?.toDoubleArray()))
        append(" cal1=").append(values(statics.cameraCalibration1?.toDoubleArray()))
        append(" cal2=").append(values(statics.cameraCalibration2?.toDoubleArray()))
        append(" -> ").append(resolution(statics))
    }

    /** What calibrated development resolves from [statics]: the save path's exact call. */
    fun resolution(statics: SceneLinearColorMetadata): String {
        val resolved = runCatching { SceneLinearColorProcessor.resolve(statics) }
        val transform = resolved.getOrNull()
            ?: return "resolve=FAILED(${resolved.exceptionOrNull()?.message}) halCcmFallback"
        val warnings = if (transform.warnings.isEmpty()) "" else
            " warnings=[${transform.warnings.joinToString("; ")}]"
        return "wb=${transform.whiteBalanceSource} xyz=${transform.cameraToXyzPolicy} " +
            "camWhite=${values(transform.cameraWhiteNormalized.toDoubleArray())}$warnings"
    }

    private fun result(r: ResultColor): String =
        "${r.source}[gains=${values(r.gains?.map(Float::toDouble)?.toDoubleArray())} " +
            "neutral=${values(r.neutral)} awb=${r.awbMode ?: "-"}/${r.awbState ?: "-"} " +
            "ccm=${values(r.ccm)}]"

    fun values(v: DoubleArray?): String =
        v?.joinToString(",") { String.format(Locale.US, "%.4f", it) } ?: "-"
}

/** Colour keys of this result, labelled [source]. */
internal fun CaptureResult.colorDiag(source: String): ColorDiagnostics.ResultColor =
    ColorDiagnostics.ResultColor(
        source = source,
        gains = get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) },
        neutral = get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.let { r -> DoubleArray(r.size) { r[it].toDouble() } },
        ccm = get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toImmutableDoubles()?.toDoubleArray(),
        awbMode = get(CaptureResult.CONTROL_AWB_MODE),
        awbState = get(CaptureResult.CONTROL_AWB_STATE)
    )

/**
 * `activePhys=<id> physResults=[..]` plus one entry per physical sub-result;
 * empty on pre-31 or non-total results.
 */
internal fun CaptureResult.physicalColorDiags(): Pair<String, List<ColorDiagnostics.ResultColor>> {
    val active = get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
    val physical = if (Build.VERSION.SDK_INT >= 31 && this is TotalCaptureResult) {
        runCatching { physicalCameraTotalResults }.getOrNull().orEmpty()
    } else emptyMap()
    val header = "activePhys=${active ?: "-"} physResults=${physical.keys.sorted()}"
    return header to physical.entries.sortedBy { it.key }.map { (id, r) -> r.colorDiag("phys:$id") }
}

/** Static matrices of [this] with the per-frame neutral/gains of [used]. */
internal fun CameraCharacteristics.colorStatics(used: ColorDiagnostics.ResultColor): SceneLinearColorMetadata =
    SceneLinearColorMetadata(
        asShotNeutral = used.neutral?.let(::ImmutableDoubleValues),
        wbGains = used.gains?.let(::ImmutableFloatValues),
        cameraCalibration1 = get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1).toImmutableDoubles(),
        cameraCalibration2 = get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2).toImmutableDoubles(),
        forwardMatrix1 = get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1).toImmutableDoubles(),
        forwardMatrix2 = get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2).toImmutableDoubles(),
        colorMatrix1 = get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1).toImmutableDoubles(),
        colorMatrix2 = get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2).toImmutableDoubles(),
        referenceIlluminant1 = get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
        referenceIlluminant2 = get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
    )
