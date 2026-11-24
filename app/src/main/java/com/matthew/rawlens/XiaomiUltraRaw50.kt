// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size

internal object XiaomiUltraRaw50 {
    const val SESSION_MODE = 36890

    fun supported(): Boolean = Build.DEVICE.equals("aurora", ignoreCase = true)

    fun enabled(context: Context): Boolean =
        supported() && preferences(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("xiaomi_ultra_raw50", Context.MODE_PRIVATE)

    // PhotonCamera's Quad Bayer path checks the high-resolution and vendor stream tables.
    fun size(characteristics: CameraCharacteristics, map: StreamConfigurationMap): Size? {
        if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return null
        val sizes = buildList {
            addAll(map.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty())
            addAll(map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR).orEmpty())
            for (key in characteristics.keys) {
                if (!key.name.contains("StreamConfigurations") && !key.name.contains("raw.size")) continue
                when (val value = runCatching { characteristics.get(key) }.getOrNull()) {
                    is Size -> add(value)
                    is IntArray -> for (index in 0..(value.size - 4) step 4) {
                        if (value[index] == ImageFormat.RAW_SENSOR && value[index + 3] == 0) {
                            add(Size(value[index + 1], value[index + 2]))
                        }
                    }
                }
            }
        }
        return sizes.filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height in 47_000_000L..52_000_000L }
            .maxByOrNull { it.width.toLong() * it.height }
    }

}

internal object XiaomiRawRequestKeys {
    fun supported(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    val previewKeys = VendorKeyProfile(listOf(
        VendorKeyEntry("com.xiaomi.sessionparams.clientName", VendorKeyType.STRING, VendorKeyScope.REQUEST, "com.android.camera")
    ))
    private val qualcommStill = VendorKeyProfile(previewKeys.entries +
        VendorKeyEntry("xiaomi.remosaic.enabled", VendorKeyType.BYTE, VendorKeyScope.REQUEST, "1"))
    private val mediatekStill = VendorKeyProfile(previewKeys.entries +
        VendorKeyEntry("com.mediatek.control.capture.remosaicenable", VendorKeyType.INT_ARRAY, VendorKeyScope.REQUEST, "1"))
    private val ultraStill = VendorKeyProfile(qualcommStill.entries +
        VendorKeyEntry("xiaomi.quadcfa.enabled", VendorKeyType.BYTE, VendorKeyScope.REQUEST, "1")
    )

    fun stillKeys(ultraPreset: Boolean): VendorKeyProfile {
        if (ultraPreset) return ultraStill
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        return when {
            soc.equals("MediaTek", ignoreCase = true) ||
                Build.HARDWARE.startsWith("mt", ignoreCase = true) -> mediatekStill
            soc.equals("Qualcomm", ignoreCase = true) ||
                Build.HARDWARE.equals("qcom", ignoreCase = true) -> qualcommStill
            else -> previewKeys
        }
    }
}
