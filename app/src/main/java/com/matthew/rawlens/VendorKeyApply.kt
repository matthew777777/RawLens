// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest

/** Builder application outcome for one lens; not sensor verification. */
data class VendorKeyApplyReport(
    val applied: List<String>,
    val skipped: List<String>,
    val detail: String? = null
) {
    fun rejected(reason: String) = VendorKeyApplyReport(emptyList(), (applied + skipped).distinct(), reason)
}

/** Probe-before-save outcome shown in the edit dialog. */
data class VendorKeyProbeResult(
    val supported: Boolean,
    val detail: String
)

/**
 * Session-parameter and per-request application.
 *
 * - SESSION entries are built into a `CaptureRequest` via a TEMPLATE_PREVIEW
 *   builder and attached with `SessionConfiguration.setSessionParameters()`
 *   *before* `createCaptureSession()`, as in PhotonCamera buildSessionParams.
 *   Vendor operating modes use the same SessionConfiguration path.
 *   See `docs/refactor/vendor-keys-study.md` for source and device limits.
 * - REQUEST entries ride on every `CaptureRequest.Builder` after the standard
 *   controls. Acceptance is tested by setting the actual destination, as in
 *   PhotonCamera's session-init path; physical-advertised keys use
 *   `setPhysicalCameraKey` without a preliminary logical read.
 *
 * Every key is guarded individually: an unknown name, bad value, or HAL
 * rejection during builder application skips that key with a log line.
 * Builder acceptance does not prove HAL acceptance or a sensor-mode change.
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

    /** Session-early parameters plus their report; request is null when nothing applied. */
    fun buildSessionParameters(
        device: CameraDevice,
        profile: VendorKeyProfile,
        characteristics: CameraCharacteristics,
        log: (String) -> Unit
    ): Pair<CaptureRequest?, VendorKeyApplyReport> {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val entries = profile.sessionEntries()
        if (entries.isEmpty()) return null to VendorKeyApplyReport(applied, skipped)
        val builder = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        } catch (failure: Exception) {
            log("vendor session: template failed ($failure)")
            return null to VendorKeyApplyReport(emptyList(), entries.map { it.name })
        }
        for (entry in entries) {
            if (isLevelTag(entry.name)) {
                log("vendor session: skip ${entry.name} (level tags are not request keys; use DNG calibration)")
                skipped += entry.name
                continue
            }
            if (setOnBuilder(builder, entry, log, "session", null, characteristics)) applied += entry.name
            else skipped += entry.name
        }
        if (applied.isEmpty()) return null to VendorKeyApplyReport(applied, skipped)
        return try {
            builder.build() to VendorKeyApplyReport(applied, skipped)
        } catch (failure: Exception) {
            log("vendor session: build failed ($failure)")
            null to VendorKeyApplyReport(emptyList(), entries.map { it.name })
        }
    }

    /** Pure fingerprint of session entries backing the reopen-needed badge. */
    fun fingerprint(entries: List<VendorKeyEntry>): String =
        if (entries.isEmpty()) "" else VendorKeyProfile(entries.sortedBy { it.name.trim() }).toJson()

    /** Throwaway-builder probe against the live HAL. Never throws. */
    fun probe(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        entry: VendorKeyEntry,
        physicalCameraId: String?
    ): VendorKeyProbeResult {
        if (isLevelTag(entry.name)) {
            return VendorKeyProbeResult(false, "Level tags are not camera keys")
        }
        val value = try {
            entry.parsedValue()
        } catch (failure: IllegalArgumentException) {
            return VendorKeyProbeResult(false, "Bad value (${failure.message})")
        }
        val tagHex = VendorKeyAndroid.resolveNativeTag(characteristics, entry.name)
            ?.let(VendorKeyAndroid::tagHex)
        if (VendorKeyEntry.isNumericTag(entry.name) && tagHex == null) {
            return VendorKeyProbeResult(false, "Numeric tag not resolved on this camera; use its advertised name")
        }
        val tagSuffix = tagHex?.let { " ($it)" }.orEmpty()
        val ok = setAny(builder, entry.name, entry.type, value, physicalCameraId.takeIf { entry.scope == VendorKeyScope.REQUEST }, characteristics)
        if (!ok) {
            return if (tagHex != null) {
                VendorKeyProbeResult(false, "Builder rejected resolved tag $tagHex")
            } else {
                VendorKeyProbeResult(false, "Builder rejected name or value type")
            }
        }
        return if (entry.scope == VendorKeyScope.SESSION) {
            VendorKeyProbeResult(true, "Builder accepted — reopen to apply$tagSuffix")
        } else {
            VendorKeyProbeResult(true, "Builder accepted on this lens$tagSuffix")
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
        characteristics: CameraCharacteristics
    ): Boolean {
        val value = try {
            entry.parsedValue()
        } catch (failure: IllegalArgumentException) {
            log("vendor $where: skip ${entry.name} (bad value: ${failure.message})")
            return false
        }
        if (!setAny(builder, entry.name, entry.type, value, physicalCameraId, characteristics)) {
            log("vendor $where: skip ${entry.name} (HAL rejected)")
            return false
        }
        return true
    }

    /** Typed dispatch shared by the capture path and the probe. Never throws. */
    private fun setAny(
        builder: CaptureRequest.Builder,
        name: String,
        type: VendorKeyType,
        value: Any,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics
    ): Boolean = try {
        when (type) {
            VendorKeyType.STRING -> setTyped(builder, name, value as String, String::class.java, physicalCameraId, characteristics)
            VendorKeyType.INT -> setTyped(builder, name, value as Int, Int::class.java, physicalCameraId, characteristics)
            VendorKeyType.LONG -> setTyped(builder, name, value as Long, Long::class.java, physicalCameraId, characteristics)
            VendorKeyType.FLOAT -> setTyped(builder, name, value as Float, Float::class.java, physicalCameraId, characteristics)
            VendorKeyType.DOUBLE -> setTyped(builder, name, value as Double, Double::class.java, physicalCameraId, characteristics)
            VendorKeyType.BOOLEAN -> setTyped(builder, name, value as Boolean, Boolean::class.java, physicalCameraId, characteristics)
            VendorKeyType.BYTE -> setTyped(builder, name, value as Byte, Byte::class.java, physicalCameraId, characteristics)
            VendorKeyType.INT_ARRAY -> setTyped(builder, name, value as IntArray, IntArray::class.java, physicalCameraId, characteristics)
            VendorKeyType.LONG_ARRAY -> setTyped(builder, name, value as LongArray, LongArray::class.java, physicalCameraId, characteristics)
            VendorKeyType.FLOAT_ARRAY -> setTyped(builder, name, value as FloatArray, FloatArray::class.java, physicalCameraId, characteristics)
            VendorKeyType.BYTE_ARRAY -> setTyped(builder, name, value as ByteArray, ByteArray::class.java, physicalCameraId, characteristics)
        }
    } catch (_: Exception) {
        false
    }

    private fun <T> setTyped(
        builder: CaptureRequest.Builder,
        name: String,
        value: T,
        clazz: Class<T>,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics
    ): Boolean {
        val key = VendorKeyAndroid.requestKeyOf(name, clazz, characteristics) ?: return false
        if (isLevelTag(key.name)) return false
        // Test the actual destination. A physical-only key need not support a logical get().
        if (physicalCameraId != null && VendorKeyAndroid.isPhysicalKey(characteristics, key.name)) {
            try {
                builder.setPhysicalCameraKey(key, value, physicalCameraId)
                return true
            } catch (_: IllegalArgumentException) {
                // Preserve the existing logical fallback for OEM physical-set refusals.
            }
        }
        builder.set(key, value)
        return true
    }
}
