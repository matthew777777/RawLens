// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.Buffer

/**
 * DESKTOP-OWNED Vulkan readback backend behind the android.opengl.GLES30
 * shim. Today it fails loudly (the unified Vulkan compute backend has not
 * landed yet); the Vulkan turn implements these against Vulkan buffers with
 * identical signatures, so no ported line changes then either.
 */
object GpuReadback {
    fun glGenFramebuffers(n: Int, arrays: IntArray, offset: Int) {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }

    fun glBindFramebuffer(target: Int, framebuffer: Int) {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }

    fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }

    fun glCheckFramebufferStatus(target: Int): Int {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }

    fun glReadPixels(x: Int, y: Int, w: Int, h: Int, format: Int, type: Int, pixels: Buffer) {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }

    fun glGetError(): Int {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }

    fun glDeleteFramebuffers(n: Int, arrays: IntArray, offset: Int) {
        throw MergeUnavailableException("sr-vulkan: GPU backend not initialized (Vulkan pending)")
    }
}
