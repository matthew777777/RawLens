// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.pm.PackageManager

/**
 * Startup Vulkan requirement gate (pure, unit-tested).
 *
 * One runtime check for the install-time bars the manifest already
 * declares: `android.hardware.vulkan.level` 1 plus
 * `android.hardware.vulkan.version` 1.1 (`0x401000`), with a live probe of
 * the loader, the 1.1 physical device, the AHardwareBuffer extension, and
 * a real buffer import that reports any failing stage as "unsupported".
 * RawLens needs this floor: every native context requests Vulkan 1.1
 * (`vf_vulkan_vf.cpp`, `wavelet_host.cpp`), and the record/RCD path
 * additionally allocates RGBA_FP16 HardwareBuffers ([DirectLogRecorder],
 * [VulkanEncoderBridge]) guarded by [isFp16BufferSupported]. Pipelines
 * without Vulkan fall back (CPU viewfinder tier, DNG-only stills); callers
 * use [userMessage] to tell the user which stage failed instead of
 * failing silently.
 */
internal object VulkanSupport {
    /** Minimum Vulkan API version, packed `VK_MAKE_VERSION` form: 1.1.0. */
    const val MIN_PACKED_VERSION = 0x00401000

    val LEVEL_FEATURE: String = PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
    val VERSION_FEATURE: String = PackageManager.FEATURE_VULKAN_HARDWARE_VERSION

    sealed interface Verdict {
        data object Supported : Verdict
        data class Unsupported(val reason: String) : Verdict
    }

    /** True when the packed Vulkan version meets the 1.1 floor. */
    fun isVersionSupported(packedVersion: Int): Boolean =
        packedVersion >= MIN_PACKED_VERSION

    /** `major.minor.patch` for a packed Vulkan version, for diagnostics. */
    fun versionString(packedVersion: Int): String {
        val major = (packedVersion shr 22) and 0x3FF
        val minor = (packedVersion shr 12) and 0x3FF
        val patch = packedVersion and 0xFFF
        return "$major.$minor.$patch"
    }

    /**
     * Probe semantics: the native probe returns a diagnostics string that
     * names the failing stage as "unsupported". A missing or blank report
     * means the probe never ran, which is also a failure — the pipeline
     * must not start unprobed.
     */
    fun isProbeOk(diagnostics: String?): Boolean =
        !diagnostics.isNullOrBlank() &&
            !diagnostics.contains("unsupported", ignoreCase = true)

    /**
     * Ordered gate: Play-level feature, then the 1.1 version floor from
     * the manifest pair, then the live probe verdict. First failure wins
     * so the reason names the exact missing stage.
     */
    fun check(
        hasLevelFeature: Boolean,
        packedVersion: Int?,
        probeDiagnostics: String?,
    ): Verdict {
        if (!hasLevelFeature) return Verdict.Unsupported("no-vulkan-level")
        val version = packedVersion
            ?: return Verdict.Unsupported("no-vulkan-version")
        if (!isVersionSupported(version)) {
            return Verdict.Unsupported("vulkan-${versionString(version)}-below-1.1")
        }
        if (!isProbeOk(probeDiagnostics)) return Verdict.Unsupported("probe-failed")
        return Verdict.Supported
    }

    /** Android entry point: reads the level/version features, then runs [probe]. */
    fun check(
        packageManager: PackageManager,
        probe: () -> String?,
    ): Verdict {
        val hasLevel = packageManager.hasSystemFeature(LEVEL_FEATURE, 1)
        val packed = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == VERSION_FEATURE }
            ?.version
        return check(hasLevel, packed, runCatching(probe).getOrNull())
    }

    /**
     * Half-float buffer floor: RGBA_FP16 HardwareBuffers need Android 13.
     * Injectable [sdkInt] keeps the rule JVM-testable; callers pass the
     * default on device.
     */
    fun isFp16BufferSupported(sdkInt: Int = android.os.Build.VERSION.SDK_INT): Boolean =
        sdkInt >= FP16_MIN_SDK

    /** Lowest API level with RGBA_FP16 HardwareBuffers (Android 13). */
    const val FP16_MIN_SDK = 33

    /**
     * User-facing explanation for [verdict], or null when supported. Names
     * the failing stage and what still works, so older devices get an
     * answer instead of a silent fallback.
     */
    fun userMessage(verdict: Verdict): String? = when (verdict) {
        Verdict.Supported -> null
        Verdict.Unsupported("no-vulkan-level") ->
            "RawLens could not use this device's Vulkan GPU (no Vulkan support reported). " +
                "CPU viewfinder preview and DNG capture still work; " +
                "GPU video record and HDR+ merge are unavailable."
        Verdict.Unsupported("no-vulkan-version") ->
            "RawLens could not use this device's Vulkan GPU (no Vulkan version reported). " +
                "CPU viewfinder preview and DNG capture still work; " +
                "GPU video record and HDR+ merge are unavailable."
        is Verdict.Unsupported -> if (verdict.reason.startsWith("vulkan-")) {
            val have = verdict.reason.removePrefix("vulkan-").removeSuffix("-below-1.1")
            "RawLens could not use this device's Vulkan GPU " +
                "(Vulkan $have found, 1.1 required). " +
                "CPU viewfinder preview and DNG capture still work; " +
                "GPU video record and HDR+ merge are unavailable."
        } else {
            "RawLens could not use this device's Vulkan GPU (the GPU probe failed). " +
                "CPU viewfinder preview and DNG capture still work; " +
                "GPU video record and HDR+ merge are unavailable."
        }
    }
}
