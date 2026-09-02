// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import java.util.Locale
import java.util.concurrent.Executors

data class DiscoveredLens(val id: String, val label: String)

/** Probes standard and common vendor camera-ID forms without opening camera devices. */
class LensDiscovery(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    fun discover(onComplete: (List<DiscoveredLens>) -> Unit) {
        executor.execute {
            val official = try {
                manager.cameraIdList.toList()
            } catch (_: CameraAccessException) {
                emptyList()
            }
            val candidates = linkedSetOf<String>()
            candidates.addAll(official)
            (0..MAX_NUMERIC_CAMERA_ID).mapTo(candidates, Int::toString)

            official.forEach { logicalId ->
                val physicalIds = characteristics(logicalId)
                    ?.physicalCameraIds.orEmpty()
                candidates.addAll(physicalIds)
                physicalIds.forEach { physicalId ->
                    candidates += "$logicalId-$physicalId"
                    candidates += "$logicalId/$physicalId"
                }
                // Some vendors expose physical cameras only through undocumented composite IDs.
                for (physicalId in 0..MAX_NUMERIC_CAMERA_ID) {
                    candidates += "$logicalId-$physicalId"
                    candidates += "$logicalId/$physicalId"
                }
            }

            val found = candidates.mapNotNull { id ->
                val c = characteristics(id) ?: return@mapNotNull null
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: return@mapNotNull null
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ||
                    facing == CameraCharacteristics.LENS_FACING_FRONT
                ) return@mapNotNull null

                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val details = buildList {
                    if (focal != null) add(String.format(Locale.US, "%.1f mm", focal))
                    if (pixels != null) add("${pixels.width}×${pixels.height}")
                }.joinToString(" • ")
                DiscoveredLens(id, if (details.isEmpty()) "Camera $id" else "Camera $id • $details")
            }.distinctBy { it.id }
            onComplete(found)
        }
    }

    fun close() = executor.shutdownNow()

    private fun characteristics(id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (_: CameraAccessException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        // Vendor camera services occasionally throw non-standard runtime failures for hidden IDs.
        null
    }

    private companion object {
        const val MAX_NUMERIC_CAMERA_ID = 200
    }
}
