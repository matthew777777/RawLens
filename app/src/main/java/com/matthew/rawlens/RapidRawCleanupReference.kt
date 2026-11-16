// SPDX-License-Identifier: AGPL-3.0-only
package com.matthew.rawlens

import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.nio.ByteBuffer

/** RapidRAW's default preprocessing, independent of the optional AI denoiser.
 * Input/output ACEScg; the native bridge filters in scene-linear sRGB.
 * Runs on the caller's current EGL context, including the merge context.
 */
internal object RapidRawCleanupReference {
    init { System.loadLibrary("dngCreator") }

    fun <T> withCleaned(scene: AmazeGpuOutput, consume: (AmazeGpuOutput) -> T): T {
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F && scene.colorDomain == AmazeColorDomain.ACESCG)
        val started = SystemClock.elapsedRealtime()
        val bytes = scene.width.toLong() * scene.height * 8L
        require(bytes <= Int.MAX_VALUE) { "Cleanup frame exceeds direct-buffer capacity" }
        val buffer = HdrPlusVulkan.allocBuffer(bytes)
        val fbo = IntArray(1)
        val texture = IntArray(1)
        val previousFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, previousFbo, 0)
        var tracked = false
        try {
            GLES30.glGenFramebuffers(1, fbo, 0)
            check(fbo[0] != 0) { "Cleanup framebuffer allocation failed" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, scene.textureId, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glReadPixels(0, 0, scene.width, scene.height, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
            checkGl("cleanup readback")
            val error = nativeProcess(buffer, scene.width, scene.height, 0.5f, 0.35f)
            check(error == null) { "RapidRAW preprocessing failed: $error" }
            GLES30.glGenTextures(1, texture, 0)
            check(texture[0] != 0) { "Cleanup texture allocation failed" }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, scene.width, scene.height)
            MemoryLeakDiagnostics.glTextureAllocated(bytes)
            tracked = true
            buffer.rewind()
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, scene.width, scene.height,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
            checkGl("cleanup upload")
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_UPDATE_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previousFbo[0])
            Log.i("RapidRawCleanup", "${scene.width}x${scene.height} chroma=0.5 detail=0.35 ${SystemClock.elapsedRealtime()-started}ms")
            return consume(scene.copy(textureId = texture[0]))
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previousFbo[0])
            if (fbo[0] != 0) GLES30.glDeleteFramebuffers(1, fbo, 0)
            if (texture[0] != 0) GLES30.glDeleteTextures(1, texture, 0)
            if (tracked) MemoryLeakDiagnostics.glTextureReleased(bytes)
            HdrPlusVulkan.freeBuffer(buffer)
        }
    }

    private fun checkGl(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$stage failed: GL 0x${error.toString(16)}" }
    }
    private external fun nativeProcess(rgba16f: ByteBuffer, width: Int, height: Int, color: Float, detail: Float): String?
}
