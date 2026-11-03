// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import kotlin.math.ln

/** Camera2 supplies matrices, not a universal Kelvin-to-gain mapping. */
internal object CameraWhiteBalance {
    val presets = linkedMapOf(1 to "Auto", 2 to "Incandescent", 3 to "Fluorescent",
        4 to "Warm fluorescent", 5 to "Daylight", 6 to "Cloudy daylight", 7 to "Twilight", 8 to "Shade")
    data class Setting(val gains: FloatArray, val transform: DoubleArray)
    private fun matrix(c: CameraCharacteristics, key: CameraCharacteristics.Key<ColorSpaceTransform>): Matrix3? =
        c.get(key)?.let { t -> Matrix3(DoubleArray(9) { t.getElement(it % 3, it / 3).toDouble() }) }
            ?.takeIf { it.isUsable() }

    fun setting(c: CameraCharacteristics, kelvin: Int): Setting? {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) ||
            c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(0) != true) return null
        return calculate(
            matrix(c, CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.values() ?: return null,
            matrix(c, CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.values(),
            matrix(c, CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.values(),
            matrix(c, CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.values(),
            c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(), kelvin)
    }

    /** Interpolate in reciprocal temperature, then map the Planckian white to native sensor space. */
    fun calculate(cm1: DoubleArray, cm2: DoubleArray?, cc1: DoubleArray?, cc2: DoubleArray?,
                  ill1: Int?, ill2: Int?, kelvin: Int): Setting? {
        val k = kelvin.coerceIn(2000, 10000).toDouble()
        val t1 = SceneLinearColorProcessor.ILLUMINANT_KELVIN[ill1]?.toDouble()
        val t2 = SceneLinearColorProcessor.ILLUMINANT_KELVIN[ill2]?.toDouble()
        val f = if (t1 != null && t2 != null && t1 != t2) ((1/k-1/t1)/(1/t2-1/t1)).coerceIn(0.0,1.0) else 0.0
        fun blend(a: DoubleArray?, b: DoubleArray?) = Matrix3.lerp(
            a?.let(::Matrix3) ?: Matrix3.IDENTITY, (b ?: a)?.let(::Matrix3) ?: Matrix3.IDENTITY, f)
        val native = blend(cc1, cc2) * blend(cm1, cm2)
        val inverse = native.inverseOrNull() ?: return null
        val white = RawWhiteBalanceFallback.Locus.planckXyz(k)
        val neutral = native * white
        if (!neutral.isFinite() || neutral.values().any { it <= 1e-6 }) return null
        val max = neutral.maxComponent()
        val gains = Vec3(max/neutral.x, max/neutral.y, max/neutral.z)
        if (gains.values().any { !it.isFinite() || it > 32.0 }) return null
        val bradford = Matrix3(doubleArrayOf(.8951,.2664,-.1614,-.7502,1.7135,.0367,.0389,-.0685,1.0296))
        val src = bradford * white
        val dst = bradford * Vec3(.95047,1.0,1.08883)
        val adapt = bradford.inverseOrNull()!! * Matrix3.diagonal(Vec3(dst.x/src.x,dst.y/src.y,dst.z/src.z)) * bradford
        val xyzToRgb = Matrix3(doubleArrayOf(3.2404542,-1.5371385,-.4985314,-.969266,1.8760108,.041556,.0556434,-.2040259,1.0572252))
        val transform = xyzToRgb * adapt * inverse * Matrix3.diagonal(Vec3(1/gains.x,1/gains.y,1/gains.z))
        return Setting(floatArrayOf(gains.x.toFloat(),gains.y.toFloat(),gains.y.toFloat(),gains.z.toFloat()),transform.values())
    }

    fun apply(builder: CaptureRequest.Builder, setting: Setting) {
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        val g = setting.gains
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g[0],g[1],g[2],g[3]))
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(
            Array(9) { Rational(kotlin.math.round(setting.transform[it]*1000000).toInt(),1000000) }))
    }

    /** Nearest calibrated Planckian white; CCT is an estimate for non-Planckian sources. */
    fun estimate(c: CameraCharacteristics, gains: RggbChannelVector): Int? {
        val g = (gains.greenEven + gains.greenOdd)/2.0
        if (g <= 0 || gains.red <= 0 || gains.blue <= 0) return null
        val u = ln(g/gains.red); val v = ln(g/gains.blue)
        return (2000..10000 step 50).mapNotNull { k -> setting(c,k)?.let {
            val a = it.gains
            val du = ln(a[1].toDouble()/a[0])-u
            val dv = ln(a[1].toDouble()/a[3])-v
            k to du*du+dv*dv
        } }.minByOrNull { it.second }?.first
    }
}
