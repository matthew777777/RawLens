// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration

/** A lens-row identity is not necessarily a Camera2 device ID (e.g. 6/5). */
internal data class VideoCameraRoute(
    val openCameraId: String,
    val physicalCameraId: String?,
    val characteristics: CameraCharacteristics
) {
    fun bind(output: OutputConfiguration): OutputConfiguration = output.apply {
        physicalCameraId?.let(::setPhysicalCameraId)
    }
}

internal fun resolveVideoCameraRoute(manager: CameraManager, identity: String): VideoCameraRoute? =
    resolveVideoCameraRoute(identity) { manager.getCameraCharacteristics(it) }

/** Match the photo route convention, including hidden vendor memberships and hyphens. */
internal fun resolveVideoCameraRoute(
    identity: String,
    isFront: (CameraCharacteristics) -> Boolean = {
        it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    },
    hasRaw: (CameraCharacteristics) -> Boolean = {
        it.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true ||
            it.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.outputFormats
                ?.contains(android.graphics.ImageFormat.RAW_SENSOR) == true
    },
    lookup: (String) -> CameraCharacteristics
): VideoCameraRoute? {
    fun read(id: String): CameraCharacteristics? = runCatching { lookup(id) }.getOrNull()

    for (index in identity.indices) {
        if (identity[index] != '/' && identity[index] != '-') continue
        val logicalId = identity.substring(0, index)
        val physicalId = identity.substring(index + 1)
        if (logicalId.isBlank() || physicalId.isBlank()) continue
        val logical = read(logicalId) ?: continue
        if (isFront(logical)) continue
        val sensor = read(identity)?.takeIf(hasRaw) ?: read(physicalId)?.takeIf(hasRaw) ?: continue
        return VideoCameraRoute(logicalId, physicalId, sensor)
    }
    val direct = read(identity)?.takeIf(hasRaw) ?: return null
    return VideoCameraRoute(identity, null, direct)
}
