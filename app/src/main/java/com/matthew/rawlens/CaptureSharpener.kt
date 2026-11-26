// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer

/** Capture sharpening (RAWR Richardson-Lucy, Vulkan) on pre-WB camera-linear RGBA16F, in place. */
class CaptureSharpener {
    private var handle = 0L

    @Synchronized
    fun release() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
        MemoryLeakDiagnostics.vulkanHandleReleased()
    }

    @Synchronized
    fun process(pixels: ByteBuffer, width: Int, height: Int,
                wb: FloatArray, pattern: BayerPattern, settings: CaptureSharpeningSettings,
                clipState: CaptureSharpeningClipState? = null) {
        if (!settings.enabled) return
        require(clipState == null || (clipState.width == width && clipState.height == height && clipState.pattern == pattern))
        if (handle == 0L) {
            System.loadLibrary("rawLensSharpen")
            handle = nativeInit()
            check(handle != 0L) { "Capture sharpening Vulkan initialization failed" }
            MemoryLeakDiagnostics.vulkanHandleAllocated()
        }
        val gpuMs = nativeProcess(handle, pixels, width, height, wb, pattern.ordinal, settings.level.iterations,
            clipState?.directBuffer())
        Log.i("CaptureSharpener", "Capture sharpening ${width}x${height} ${settings.level.label} " +
            "iterations=${settings.level.iterations} cfa=$pattern gpuMs=$gpuMs")
    }

    private external fun nativeInit(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, pixels: ByteBuffer, w: Int, h: Int, wb: FloatArray, cfa: Int,
                                       iterations: Int, clipState: ByteBuffer?): Double
}
