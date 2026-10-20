// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraMetadata

/**
 * Debug/log names for Camera2 3A and OIS enum values. Values and names
 * follow android.hardware.camera2.CameraMetadata (verified against the
 * API 37 android.jar with `javap -constants`); see
 * https://developer.android.com/reference/android/hardware/camera2/CameraMetadata.
 * Unlisted values print as UNKNOWN(n) so new HAL values stay visible.
 */
internal object Camera2Names {
    fun oisModeName(value: Int?): String = when (value) {
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
        else -> "UNAVAILABLE"
    }

    fun afModeName(value: Int?): String = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN($value)"
    }

    fun afTriggerName(value: Int?): String = when (value) {
        CameraMetadata.CONTROL_AF_TRIGGER_IDLE, null -> "IDLE"
        CameraMetadata.CONTROL_AF_TRIGGER_START -> "START"
        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL -> "CANCEL"
        else -> "UNKNOWN($value)"
    }

    fun afStateName(value: Int?): String = when (value) {
        CameraMetadata.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        else -> "UNKNOWN($value)"
    }

    fun aeModeName(value: Int?): String = when (value) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "AUTO_REDEYE"
        // API 28 / API 35; compile-time constants, safe to match on API 30.
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "EXTERNAL_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY -> "LOW_LIGHT_BOOST"
        else -> "UNKNOWN($value)"
    }

    fun aeTriggerName(value: Int?): String = when (value) {
        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE, null -> "IDLE"
        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START -> "START"
        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL -> "CANCEL"
        else -> "UNKNOWN($value)"
    }

    fun aeStateName(value: Int?): String = when (value) {
        CameraMetadata.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CameraMetadata.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CameraMetadata.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> "UNKNOWN($value)"
    }
}
