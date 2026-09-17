// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import kotlin.math.pow

/** Single-axis lock for PROGRAM custom AE. Slider sets the balance; locks fix one axis. */
enum class ProgramLockMode {
    NONE,
    ISO_LOCK,
    SHUTTER_LOCK;

    companion object {
        fun fromName(name: String?): ProgramLockMode =
            values().firstOrNull { it.name == name } ?: NONE
    }
}

/** RAW metering statistic for PROGRAM custom AE. Center-weighted mirrors the AE regions. */
enum class ProgramMetering {
    CENTER_WEIGHTED,
    MEDIAN;

    companion object {
        fun fromName(name: String?): ProgramMetering =
            values().firstOrNull { it.name == name } ?: CENTER_WEIGHTED
    }
}

/**
 * Per-lens PROGRAM AE profile.
 *
 * Custom AE engine: RAW brightness sets sensor exposure directly (ISO + shutter),
 * not digital gain. [balance] 0..1 maps to the internal 0.25x..4x multiplier
 * (0 = ISO priority / lower gain, 0.5 = balanced, 1 = shutter priority / faster shutter).
 * Zero bounds mean "sensor bound" (or auto-safe handheld for [shutterMaxNanos]).
 * [evBias] compensates stock Android + spektra underexposure (default slightly bright).
 */
data class ProgramAeProfile(
    val balance: Float = 0.5f,
    val isoMin: Int = 0,
    val isoMax: Int = 0,
    val shutterMinNanos: Long = 0L,
    val shutterMaxNanos: Long = 0L,
    val useAutoSafeShutter: Boolean = true,
    val lockMode: ProgramLockMode = ProgramLockMode.NONE,
    val lockedIso: Int = 0,
    val lockedShutterNanos: Long = 0L,
    val evBias: Float = 0.5f,
    val metering: ProgramMetering = ProgramMetering.CENTER_WEIGHTED
) {
    fun validated(): ProgramAeProfile {
        require(balance.isFinite()) { "Balance must be finite" }
        require(evBias.isFinite()) { "EV bias must be finite" }
        if (isoMin > 0 && isoMax > 0) require(isoMin <= isoMax) { "ISO min must be <= max" }
        if (shutterMinNanos > 0L && shutterMaxNanos > 0L) {
            require(shutterMinNanos <= shutterMaxNanos) { "Shutter min must be <= max" }
        }
        return copy(
            balance = balance.coerceIn(0f, 1f),
            evBias = evBias.coerceIn(MIN_EV_BIAS, MAX_EV_BIAS)
        )
    }

    fun toJson(): String = buildString {
        append("{")
        append("\"balance\":").append(balance).append(",")
        append("\"isoMin\":").append(isoMin).append(",")
        append("\"isoMax\":").append(isoMax).append(",")
        append("\"shutterMinNanos\":").append(shutterMinNanos).append(",")
        append("\"shutterMaxNanos\":").append(shutterMaxNanos).append(",")
        append("\"useAutoSafeShutter\":").append(useAutoSafeShutter).append(",")
        append("\"lockMode\":\"").append(lockMode.name).append("\",")
        append("\"lockedIso\":").append(lockedIso).append(",")
        append("\"lockedShutterNanos\":").append(lockedShutterNanos).append(",")
        append("\"evBias\":").append(evBias).append(",")
        append("\"metering\":\"").append(metering.name).append("\"")
        append("}")
    }

    companion object {
        const val MIN_EV_BIAS = -1f
        const val MAX_EV_BIAS = 2f

        /** UI slider 0..1 to internal 0.25x..4x multiplier (log-symmetric around 0.5 = 1x). */
        fun balanceToMultiplier(balance: Float): Float {
            val b = balance.coerceIn(0f, 1f)
            // 0 -> 0.25x, 0.5 -> 1x, 1 -> 4x  (2 ^ ((b-0.5)*4))
            return 2.0.pow(((b - 0.5f) * 4f).toDouble()).toFloat()
                .coerceIn(0.25f, 4f)
        }

        fun multiplierToBalance(multiplier: Float): Float {
            val m = multiplier.coerceIn(0.25f, 4f)
            val log = kotlin.math.ln(m.toDouble()) / kotlin.math.ln(2.0)
            return ((log / 4.0) + 0.5).toFloat().coerceIn(0f, 1f)
        }

        fun fromJson(json: String?): ProgramAeProfile {
            if (json.isNullOrBlank()) return ProgramAeProfile()
            return runCatching {
                fun number(key: String): String? =
                    Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                        .find(json)?.groupValues?.getOrNull(1)
                fun text(key: String): String? =
                    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                        .find(json)?.groupValues?.getOrNull(1)
                fun flag(key: String, default: Boolean): Boolean =
                    Regex("\"$key\"\\s*:\\s*(true|false)")
                        .find(json)?.groupValues?.getOrNull(1)?.toBoolean() ?: default
                ProgramAeProfile(
                    balance = number("balance")?.toFloatOrNull() ?: 0.5f,
                    isoMin = number("isoMin")?.toDoubleOrNull()?.toInt() ?: 0,
                    isoMax = number("isoMax")?.toDoubleOrNull()?.toInt() ?: 0,
                    shutterMinNanos = number("shutterMinNanos")?.toDoubleOrNull()?.toLong() ?: 0L,
                    shutterMaxNanos = number("shutterMaxNanos")?.toDoubleOrNull()?.toLong() ?: 0L,
                    useAutoSafeShutter = flag("useAutoSafeShutter", true),
                    lockMode = ProgramLockMode.fromName(text("lockMode")),
                    lockedIso = number("lockedIso")?.toDoubleOrNull()?.toInt() ?: 0,
                    lockedShutterNanos = number("lockedShutterNanos")?.toDoubleOrNull()?.toLong() ?: 0L,
                    evBias = number("evBias")?.toFloatOrNull() ?: 0.5f,
                    metering = ProgramMetering.fromName(text("metering"))
                ).validated()
            }.getOrNull() ?: ProgramAeProfile()
        }

        /** Seed a per-lens profile from legacy global DynamicExposureSettings. */
        fun fromLegacy(balanceMultiplier: Float, isoLimit: Int, shutterLimitNanos: Long,
                       useAutoSafeShutter: Boolean): ProgramAeProfile =
            ProgramAeProfile(
                balance = multiplierToBalance(balanceMultiplier),
                isoMin = 0,
                isoMax = isoLimit,
                shutterMinNanos = 0L,
                shutterMaxNanos = shutterLimitNanos,
                useAutoSafeShutter = useAutoSafeShutter,
                lockMode = ProgramLockMode.NONE,
                evBias = 0.5f
            ).validated()
    }
}

/** Per-lens PROGRAM AE profile storage. Mirrors DngMetadataOverrideStore keying. */
class ProgramAeProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(cameraId: String?): ProgramAeProfile =
        ProgramAeProfile.fromJson(cameraId?.let { preferences.getString(key(it), null) })

    fun hasProfile(cameraId: String?): Boolean =
        cameraId != null && preferences.contains(key(cameraId))

    fun save(cameraId: String, profile: ProgramAeProfile) {
        profile.validated()
        preferences.edit().putString(key(cameraId), profile.toJson()).apply()
    }

    fun clear(cameraId: String) = preferences.edit().remove(key(cameraId)).apply()

    private fun key(cameraId: String) = "profile_$cameraId"

    companion object {
        const val PREFS_NAME = "rawlens_program_ae"
    }
}
