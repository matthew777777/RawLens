package com.matthew.rawlens

import kotlin.math.ln

/**
 * Brightness past the sensor's highest real ISO (e.g. the vivo X300 Ultra
 * telephoto caps at ISO 800), rawr-style: the request runs at the sensor max
 * and the rest is digital gain applied after RAW. Develop multiplies it in
 * (SceneLinearColorProcessor), DNGs carry it as BaselineExposure, the VF
 * shows it. RAW samples and the recorded sensor ISO stay untouched, so noise
 * estimation sees the real analog frame.
 *
 * Two sources: a manual ISO above the sensor max ([requestedIso]) or the
 * PROGRAM AE loop running out of ISO and shutter ([autoGain], capped per lens
 * by ProgramAeProfile.digitalGainEv).
 */
object DigitalGain {
    /** Manual ISO may go up to 3 EV past the sensor max. */
    const val MAX_FACTOR = 8

    /** Requested manual ISO (null = auto); written by the camera thread. */
    @Volatile var requestedIso: Int? = null

    /** Gain the PROGRAM AE asks for on top of its sensor pair; 1 = none. */
    @Volatile var autoGain: Float = 1f

    /** Digital gain for a frame captured at [sensorIso] with sensor max [sensorMax]. */
    fun gainFor(sensorIso: Int?, sensorMax: Int?): Float {
        if (sensorIso == null || sensorMax == null || sensorIso <= 0) return 1f
        val req = requestedIso
        if (req == null) return autoGain.coerceIn(1f, 65536f)
        if (req <= sensorMax || sensorIso < sensorMax * 0.97f) return 1f
        return (req.toFloat() / sensorIso).coerceIn(1f, MAX_FACTOR.toFloat())
    }

    fun ev(gain: Float): Double = if (gain > 1.0001f) ln(gain.toDouble()) / ln(2.0) else 0.0

    /** "ISO 3200 (800+2EV)" style label when gain is active. */
    fun isoLabel(sensorIso: Int, gain: Float): String =
        if (gain <= 1.0001f) "ISO $sensorIso"
        else "ISO ${(sensorIso * gain).toInt()} ($sensorIso+${"%.1f".format(ev(gain)).removeSuffix(".0")}EV)"
}
