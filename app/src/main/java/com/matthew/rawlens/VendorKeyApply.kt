// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.SessionConfiguration

/** What Step 2 applied (or deliberately skipped) for one lens. */
data class VendorKeyApplyReport(
    val applied: List<String>,
    val skipped: List<String>
)

/**
 * Step 2 of the bit-by-bit build: SESSION-early + REQUEST application.
 *
 * - SESSION entries are built into a `CaptureRequest` via a TEMPLATE_PREVIEW
 *   builder and attached with `SessionConfiguration.setSessionParameters()`
 *   *before* `createCaptureSession()`. Vivo X300 Ultra DCG
 *   (`vivo.control.forceSensorMode`) only takes effect this way; a per-request
 *   set is silently ignored, which is why PhotonCamera-style request-only
 *   application cannot do it.
 * - REQUEST entries ride on every `CaptureRequest.Builder` after the standard
 *   controls, probed with `VendorKeyAndroid.isSupported` (PhotonVidCam
 *   `VendorTagUtils.isSupported`) and routed via `setPhysicalCameraKey` on
 *   logical/physical routes when advertised.
 *
 * Every key is guarded individually: an unknown name, bad value, or HAL
 * rejection skips that key with a log line and never fails capture.
 * Black/white levels are intentionally NOT request keys (SDK-verified: no such
 * `CaptureRequest` keys exist). They stay on the per-lens DNG calibration
 * editor; [isLevelTag] names the tags so a stale profile explains itself
 * instead of silently no-op-ing.
 */
object VendorKeyApply {
    /** Standard level tags some profiles name; neither is a request/session key. */
    val LEVEL_TAGS: Set<String> = setOf(
        "android.sensor.blackLevelPattern",
        "android.sensor.info.whiteLevel"
    )

    fun isLevelTag(name: String): Boolean = name.trim() in LEVEL_TAGS

    /**
     * Pure partition for tests and logs: entries the HAL advertises versus
     * entries it does not. An empty [advertised] set means "unknown" (the
     * query failed), so everything stays attemptable; each set is still
     * guarded individually.
     */
    fun partition(
        entries: List<VendorKeyEntry>,
        advertised: Set<String>
    ): Pair<List<VendorKeyEntry>, List<VendorKeyEntry>> {
        if (advertised.isEmpty()) return entries to emptyList()
        val names = advertised.map(String::trim).toSet()
        return entries.partition { it.name.trim() in names }
    }

    /** Session-early parameters, or null when this lens enables no session keys. */
    fun buildSessionParameters(
        device: CameraDevice,
        profile: VendorKeyProfile,
        characteristics: CameraCharacteristics,
        log: (String) -> Unit
    ): CaptureRequest? {
        val entries = profile.sessionEntries()
        if (entries.isEmpty()) return null
        val advertised = runCatching {
            characteristics.availableSessionKeys.map { it.name }.toSet()
        }.getOrDefault(emptySet())
        val (attempt, refused) = partition(entries, advertised)
        refused.forEach { log("vendor session: skip ${it.name} (not in availableSessionKeys)") }
        val builder = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        } catch (failure: Exception) {
            log("vendor session: template failed ($failure)")
            return null
        }
        var applied = 0
        for (entry in attempt) {
            if (isLevelTag(entry.name)) {
                log("vendor session: skip ${entry.name} (level tags are not request keys; use DNG calibration)")
                continue
            }
            if (setOnBuilder(builder, entry, log, "session", null, null)) applied++
        }
        if (applied == 0) return null
        return try {
            builder.build()
        } catch (failure: Exception) {
            log("vendor session: build failed ($failure)")
            null
        }
    }

    /** Per-request keys on an already-configured builder. Never throws. */
    fun applyRequestKeys(
        builder: CaptureRequest.Builder,
        profile: VendorKeyProfile,
        characteristics: CameraCharacteristics,
        physicalCameraId: String?,
        log: (String) -> Unit
    ): VendorKeyApplyReport {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (entry in profile.requestEntries()) {
            if (isLevelTag(entry.name)) {
                log("vendor request: skip ${entry.name} (level tags are not request keys; use DNG calibration)")
                skipped += entry.name
                continue
            }
            if (setOnBuilder(builder, entry, log, "request", physicalCameraId, characteristics)) {
                applied += entry.name
            } else {
                skipped += entry.name
            }
        }
        return VendorKeyApplyReport(applied, skipped)
    }

    private fun setOnBuilder(
        builder: CaptureRequest.Builder,
        entry: VendorKeyEntry,
        log: (String) -> Unit,
        where: String,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics?
    ): Boolean {
        val value = try {
            entry.parsedValue()
        } catch (failure: IllegalArgumentException) {
            log("vendor $where: skip ${entry.name} (bad value: ${failure.message})")
            return false
        }
        return try {
            when (entry.type) {
                VendorKeyType.INT -> setTyped(builder, entry.name, value as Int, Int::class.java, physicalCameraId, characteristics)
                VendorKeyType.LONG -> setTyped(builder, entry.name, value as Long, Long::class.java, physicalCameraId, characteristics)
                VendorKeyType.FLOAT -> setTyped(builder, entry.name, value as Float, Float::class.java, physicalCameraId, characteristics)
                VendorKeyType.DOUBLE -> setTyped(builder, entry.name, value as Double, Double::class.java, physicalCameraId, characteristics)
                VendorKeyType.BOOLEAN -> setTyped(builder, entry.name, value as Boolean, Boolean::class.java, physicalCameraId, characteristics)
                VendorKeyType.BYTE -> setTyped(builder, entry.name, value as Byte, Byte::class.java, physicalCameraId, characteristics)
                VendorKeyType.INT_ARRAY -> setTyped(builder, entry.name, value as IntArray, IntArray::class.java, physicalCameraId, characteristics)
                VendorKeyType.LONG_ARRAY -> setTyped(builder, entry.name, value as LongArray, LongArray::class.java, physicalCameraId, characteristics)
                VendorKeyType.FLOAT_ARRAY -> setTyped(builder, entry.name, value as FloatArray, FloatArray::class.java, physicalCameraId, characteristics)
                VendorKeyType.BYTE_ARRAY -> setTyped(builder, entry.name, value as ByteArray, ByteArray::class.java, physicalCameraId, characteristics)
            }
        } catch (failure: IllegalArgumentException) {
            log("vendor $where: skip ${entry.name} (HAL rejected: $failure)")
            false
        } catch (failure: Exception) {
            log("vendor $where: skip ${entry.name} ($failure)")
            false
        }
    }

    private fun <T> setTyped(
        builder: CaptureRequest.Builder,
        name: String,
        value: T,
        clazz: Class<T>,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics?
    ): Boolean {
        val key = VendorKeyAndroid.requestKeyOf(name, clazz) ?: return false
        if (!VendorKeyAndroid.isSupported(builder, key)) return false
        if (physicalCameraId != null && characteristics != null) {
            val physicalNames = runCatching {
                characteristics.availablePhysicalCameraRequestKeys.map { it.name }.toSet()
            }.getOrDefault(emptySet())
            if (name.trim() in physicalNames) {
                try {
                    builder.setPhysicalCameraKey(key, value, physicalCameraId)
                    return true
                } catch (_: IllegalArgumentException) {
                    // Fall through to the logical builder below.
                }
            }
        }
        builder.set(key, value)
        return true
    }
}
