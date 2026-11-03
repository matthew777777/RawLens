// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Preserve the explicit high-rate Qualcomm mode across request templates.
 * Staged startup matches the reported Motorola Session -> live Request workflow. */
internal object VendorSensorModePolicy {
    const val CURRENT_MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode"
    fun deferred(entry: VendorKeyEntry, fps: Int): Boolean = fps > 30 &&
        entry.enabled && entry.name.trim() == CURRENT_MODE &&
        entry.scope == VendorKeyScope.REQUEST && entry.type == VendorKeyType.INT &&
        entry.value.trim().toIntOrNull() == 8

    fun startupProfile(profile: VendorKeyProfile, rawArrived: Boolean): VendorKeyProfile =
        if (rawArrived) profile else profile.copy(entries = profile.entries.filterNot {
            deferred(it, profile.rawPreviewFps)
        })
}
