// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Values shown in the camera debug overlay, read by the controller on the
 * camera thread from the latest preview request/result and its own state.
 */
internal data class DebugOverlaySnapshot(
    val afMode: Int?,
    val afTrigger: Int?,
    val afState: Int?,
    val cameraSequenceBusy: Boolean,
    val saveQueue: Int,
    val saveQueueLimit: Int,
    val aeMode: Int?,
    val aeTrigger: Int?,
    val aeState: Int?,
    val oisMode: Int?,
    val iso: Int,
    val exposureNanos: Long,
    val programEnabled: Boolean,
    val programProfile: ProgramAeProfile,
    val isoCapHit: Boolean,
    val shutterCapHit: Boolean,
    val ettrCaptureActive: Boolean,
    val programTargetIso: Int?,
    val programTargetShutterNanos: Long?,
    val liveIso: Int?,
    val liveShutterNanos: Long?,
    val programConverged: Boolean,
    val programBrightness: Float,
    val ettrEnabled: Boolean,
    val ettrModeAvailable: Boolean,
    val ettrIso: Int?,
    val ettrShutterNanos: Long?,
    val ettrConverged: Boolean,
    val ettrHottest: Float,
    val ettrClipHot: Float,
    val ettrClipSecond: Float,
    val zslStreaming: Boolean,
    val zslBuffered: Int,
    val zslCapacity: Int,
    val gyroMotion: Float,
)

/**
 * Text the controller publishes for diagnosis: the debug overlay, the RAW VF
 * stats line and the RAW ZSL status chip. Pure; extracted from
 * RawCameraController (A7, docs/refactor/controller-remaining-plan.md) with
 * every string unchanged.
 */
internal object ControllerDebugText {
    /**
     * Shown when the ring capacity is 0: an empty RAW size, or a session
     * reader with too few buffers for a ring
     * ([RawCameraController.clampZslCapacityForReader]). There is no
     * memory-budget check behind it.
     */
    const val ZSL_NO_CAPACITY_DETAIL =
        "RAW ZSL unavailable: not enough RAW buffers in this camera session"

    fun formatExposure(nanos: Long): String {
        if (nanos >= 1_000_000_000L) return String.format(
            java.util.Locale.US, "%.1fs", nanos / 1_000_000_000.0
        )
        return "1/${kotlin.math.round(1_000_000_000.0 / nanos).toInt()}"
    }

    fun clipPercent(fraction: Float): String =
        if (fraction.isFinite()) {
            String.format(java.util.Locale.US, "%.3f%%", fraction * 100f)
        } else "--"

    fun debugOverlay(s: DebugOverlaySnapshot): String =
        "AF_MODE  ${Camera2Names.afModeName(s.afMode)}\n" +
            "AF_TRIGGER ${Camera2Names.afTriggerName(s.afTrigger)}\n" +
            "AF_STATE ${Camera2Names.afStateName(s.afState)}\n" +
            "AF_POLICY TOUCH_HOLD/CONT_PICTURE  CAMERA_SEQ ${if (s.cameraSequenceBusy) "BUSY" else "IDLE"}\n" +
            "SAVE_QUEUE ${s.saveQueue}/${s.saveQueueLimit}\n" +
            "AE_MODE  ${Camera2Names.aeModeName(s.aeMode)}\n" +
            "AE_TRIGGER ${Camera2Names.aeTriggerName(s.aeTrigger)}\n" +
            "AE_STATE ${Camera2Names.aeStateName(s.aeState)}\n" +
            "OIS_MODE ${Camera2Names.oisModeName(s.oisMode)}\n" +
            "ISO ${s.iso}  EXP ${formatExposure(s.exposureNanos.coerceAtLeast(1L))}\n" +
            "P_AE ${if (s.programEnabled) "ON" else "OFF"} " +
            "B01 ${String.format(java.util.Locale.US, "%.2f", s.programProfile.balance)} " +
            "METER ${s.programProfile.metering.name} " +
            "LOCK ${s.programProfile.lockMode.name} " +
            "EV ${String.format(java.util.Locale.US, "%+.1f", s.programProfile.evBias)} " +
            "ISO_CAP ${if (s.isoCapHit) "HIT" else "OK"} " +
            "S_CAP ${if (s.shutterCapHit) "HIT" else "OK"}\n" +
            "P_TARGET " + (if (s.ettrCaptureActive) "FROZEN>ETTR"
            else s.programTargetIso?.let { targetIso ->
                s.programTargetShutterNanos?.let { targetShutter ->
                    val live = s.liveIso?.let { liveIso ->
                        s.liveShutterNanos?.let { liveShutter ->
                            "LIVE ISO $liveIso EXP ${formatExposure(liveShutter)}"
                        }
                    } ?: "LIVE --"
                    "TGT ISO $targetIso EXP ${formatExposure(targetShutter)} $live " +
                        (if (s.programConverged) "LOCKED" else "TRACKING") +
                        " BR ${if (s.programBrightness.isFinite()) String.format(java.util.Locale.US, "%.2f", s.programBrightness) else "--"}"
                }
            } ?: "METERING") + "\n" +
            "ETTR " + when {
                !s.ettrEnabled -> "OFF"
                !s.ettrModeAvailable -> "OFF (AUTO ONLY)"
                else -> "ON"
            } + " " +
            (s.ettrIso?.let { targetIso ->
                s.ettrShutterNanos?.let { targetShutter ->
                    "ISO $targetIso EXP ${formatExposure(targetShutter)} " +
                        (if (s.ettrConverged) "LOCKED" else "TRACKING") +
                        " HOT ${if (s.ettrHottest.isFinite()) String.format(java.util.Locale.US, "%.2f", s.ettrHottest) else "--"}" +
                        " CLIP ${clipPercent(s.ettrClipHot)}/${clipPercent(s.ettrClipSecond)}"
                }
            } ?: "METERING") + "\n" +
            "RAW_ZSL ${if (s.zslStreaming) "ON" else "OFF"} " +
            "BUF ${s.zslBuffered}/${s.zslCapacity} " +
            String.format(java.util.Locale.US, "GYRO %.3f", s.gyroMotion)

    /** RAW VF stats line; [devBits] from [vfOverlayEffectBits]. */
    fun rawVfDebug(stats: RawVfStats, devBits: List<String>): String {
        val fpsText = if (stats.fps > 0f) {
            String.format(java.util.Locale.US, "%.1f FPS", stats.fps)
        } else "-- FPS"
        val msText = if (stats.frameMs > 0f) {
            String.format(java.util.Locale.US, "%.1f ms", stats.frameMs)
        } else "-- ms"
        val modeText = if (stats.jpeg) {
            "JPG " + String.format(java.util.Locale.US, "%+.1fEV", stats.exposureEv)
        } else "RAW"
        val vfText = if (stats.vfWidth > 0 && stats.vfHeight > 0) {
            val path = if (stats.gpu) "GPU" else "NEON"
            "VF: ${stats.vfWidth}×${stats.vfHeight} $path ${stats.engine.shortLabel()} ⇄ $modeText"
        } else "VF: --"
        val rawText = if (stats.rawWidth > 0 && stats.rawHeight > 0) {
            "RAW: ${stats.rawWidth}×${stats.rawHeight}"
        } else "RAW: --"
        val glLabel = if (stats.glActive) "RAW" else "FALLBACK"
        val effects = if (!stats.glActive || devBits.isNotEmpty()) {
            "Yes ($glLabel${if (devBits.isNotEmpty()) " +" + devBits.joinToString("+") else ""})"
        } else "No"
        // A dead forced engine shows as starved (VfEngineMode contract), never
        // as a silently black VF.
        val starveText = if (stats.starved) " STARVED" else ""
        return "$fpsText$starveText $msText\n$vfText · $rawText\nEffects: $effects"
    }

    /** RAW ZSL chip state when the caller passes no explicit state. */
    fun rawZslStatus(
        requested: Boolean,
        disabledForSession: Boolean,
        fallbackDetail: String?,
        capacity: Int,
        streamBroken: Boolean,
        streaming: Boolean,
        filled: Boolean,
        buffered: Int,
    ): RawZslStatus = when {
        !requested -> RawZslStatus(RawZslState.OFF, "Disabled in settings")
        disabledForSession -> RawZslStatus(
            RawZslState.FALLBACK,
            "${fallbackDetail ?: "Unavailable"}; using normal RAW capture"
        )
        capacity == 0 -> RawZslStatus(
            RawZslState.FALLBACK,
            ZSL_NO_CAPACITY_DETAIL
        )
        streamBroken -> RawZslStatus(
            RawZslState.WARMING_UP,
            "ZSL burst mode: streaming unavailable on this HAL, captures fire instantly"
        )
        streaming && filled -> RawZslStatus(
            RawZslState.ACTIVE,
            "$buffered/$capacity RAW frames buffered"
        )
        else -> RawZslStatus(RawZslState.WARMING_UP, "Waiting for a paired RAW frame")
    }
}
