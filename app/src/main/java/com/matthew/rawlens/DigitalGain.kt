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

    /**
     * PROGRAM: exposure (ISO x ns x digital) a still taken now gets; 0 = not
     * PROGRAM-driven. The viewfinder brings every frame to it ([viewfinderGainFor]).
     */
    @Volatile var programStillTotal: Double = 0.0

    /** rawr residual clamp (kMinResidualEv / kMaxResidualEv). */
    private const val MIN_RESIDUAL = 0.25f
    private const val MAX_RESIDUAL = 16f

    /**
     * Viewfinder gain for one frame (rawr residual gain): in PROGRAM the frame
     * is shown at the exposure a still taken now would get, whatever exposure
     * the frame itself landed with (request latency, integer ISO, flicker
     * rounding, the deadband). Elsewhere the plain digital gain.
     */
    fun viewfinderGainFor(sensorIso: Int?, exposureNanos: Long?, sensorMax: Int?): Float {
        val total = programStillTotal
        if (total > 0.0 && requestedIso == null && sensorIso != null && sensorIso > 0 &&
            exposureNanos != null && exposureNanos > 0L
        ) {
            val residual = (total / (sensorIso.toDouble() * exposureNanos)).toFloat()
            if (residual.isFinite()) return residual.coerceIn(MIN_RESIDUAL, MAX_RESIDUAL)
        }
        return gainFor(sensorIso, sensorMax)
    }

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
