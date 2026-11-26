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

    /** 14 Ultra (aurora) and 13 Ultra (ishtar, same setup without the mode). */
    fun supported(device: String? = Build.DEVICE): Boolean = device?.lowercase() in setOf("aurora", "ishtar")

    /** Session mode the phone needs, or [fallback] (the lens's own). The 13 Ultra must not get the 14 Ultra's mode. */
    fun sessionMode(fallback: Int, device: String? = Build.DEVICE): Int = when {
        device.equals("aurora", ignoreCase = true) -> SESSION_MODE
        device.equals("ishtar", ignoreCase = true) && fallback == SESSION_MODE -> 0
        else -> fallback
    }

    fun enabled(context: Context): Boolean =
        supported() && preferences(context).getBoolean("enabled", false)

    /** 14 Ultra logcat: a repeating 8192x6144 RAW target kills the HAL within 0.5 s on every lens. */
    fun stillOnly(size: Size?): Boolean =
        supported() && size != null && size.width.toLong() * size.height >= 47_000_000L

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
            // The Ultra accepts this reader even where no table lists it (PlainCamX field report).
            ?: Size(8192, 6144)
    }

}

internal object XiaomiRawRequestKeys {
    fun supported(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    private val previewKeys = VendorKeyProfile(listOf(
        VendorKeyEntry("com.xiaomi.sessionparams.clientName", VendorKeyType.STRING, VendorKeyScope.REQUEST, "com.android.camera")
    ))
    private val qualcommStill = VendorKeyProfile(previewKeys.entries +
        VendorKeyEntry("xiaomi.remosaic.enabled", VendorKeyType.BYTE, VendorKeyScope.REQUEST, "1"))
    private val mediatekStill = VendorKeyProfile(previewKeys.entries +
        VendorKeyEntry("com.mediatek.control.capture.remosaicenable", VendorKeyType.INT_ARRAY, VendorKeyScope.REQUEST, "1"))
    // Field report (14 Ultra): mode 36890 + 8192x6144 + remosaic is the required set; no clientName.
    private val ultra = VendorKeyProfile(listOf(
        VendorKeyEntry("xiaomi.remosaic.enabled", VendorKeyType.BYTE, VendorKeyScope.REQUEST, "1")))

    fun previewKeys(ultraPreset: Boolean): VendorKeyProfile =
        if (ultraPreset) VendorKeyProfile() else previewKeys

    private val remosaic = VendorKeyProfile(qualcommStill.entries.drop(1) + mediatekStill.entries.drop(1))

    /** Any phone's full-size still: keys the camera does not know are skipped when applied. */
    fun genericStillKeys(): VendorKeyProfile = remosaic

    fun stillKeys(ultraPreset: Boolean): VendorKeyProfile {
        if (ultraPreset) return ultra
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
