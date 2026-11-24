// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size

internal object SamsungRawCompatibility {
    private const val MAX_PREVIEW_PIXELS = 30_000_000L

    val enabled: Boolean
        get() = Build.BRAND.equals("samsung", ignoreCase = true)

    fun trialSize(characteristics: CameraCharacteristics): Size? {
        if (!enabled) return null
        return runCatching {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.let { trialSize(characteristics, it) }
        }.getOrNull()
    }

    // A preview size is a candidate for a RAW_SENSOR buffer. The camera HAL
    // still has to accept the session and deliver a real RAW image.
    fun trialSize(
        characteristics: CameraCharacteristics,
        map: StreamConfigurationMap
    ): Size? {
        if (!enabled) return null
        return runCatching {
            val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            if (cfa == null || cfa !in CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB..
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR
            ) return null
            if (characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) == null ||
                (characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0) <= 0
            ) return null
            sequenceOf(ImageFormat.JPEG, ImageFormat.YUV_420_888).firstNotNullOfOrNull { format ->
                map.getOutputSizes(format)
                    ?.filter {
                        it.width > 0 && it.height > 0 &&
                            it.width.toLong() * it.height <= MAX_PREVIEW_PIXELS
                    }
                    ?.maxByOrNull { it.width.toLong() * it.height }
            }
        }.getOrNull()
    }
}
