// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Presents the exact GLES3.0
// FBO-readback surface RawSrMergeJob uses, so that file compiles
// byte-identical, but routes it to the unified Vulkan backend
// (com.matthew.rawlens.GpuReadback). Until the Vulkan backend lands the
// calls fail loudly with MergeUnavailableException — never silently.
package android.opengl

import com.matthew.rawlens.GpuReadback
import java.nio.Buffer

object GLES30 {
    const val GL_NO_ERROR = 0
    const val GL_FRAMEBUFFER = 0x8D40
    const val GL_COLOR_ATTACHMENT0 = 0x8CE0
    const val GL_TEXTURE_2D = 0x0DE1
    const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
    const val GL_RGBA = 0x1908
    const val GL_RED = 0x1903
    const val GL_FLOAT = 0x1406
    // Internal-format tokens (values match GLES3.0): the ported SR code and
    // VkImage use these so every arena.texture/format call line is identical.
    const val GL_R32F = 0x822E
    const val GL_R32UI = 0x8236
    const val GL_RGBA32F = 0x8814
    const val GL_R16UI = 0x8234

    @JvmStatic fun glGenFramebuffers(n: Int, arrays: IntArray, offset: Int) =
        GpuReadback.glGenFramebuffers(n, arrays, offset)

    @JvmStatic fun glBindFramebuffer(target: Int, framebuffer: Int) =
        GpuReadback.glBindFramebuffer(target, framebuffer)

    @JvmStatic fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) =
        GpuReadback.glFramebufferTexture2D(target, attachment, textarget, texture, level)

    @JvmStatic fun glCheckFramebufferStatus(target: Int): Int =
        GpuReadback.glCheckFramebufferStatus(target)

    @JvmStatic fun glReadPixels(x: Int, y: Int, w: Int, h: Int, format: Int, type: Int, pixels: Buffer) =
        GpuReadback.glReadPixels(x, y, w, h, format, type, pixels)

    @JvmStatic fun glGetError(): Int = GpuReadback.glGetError()

    @JvmStatic fun glDeleteFramebuffers(n: Int, arrays: IntArray, offset: Int) =
        GpuReadback.glDeleteFramebuffers(n, arrays, offset)
}
