// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES20 as GL
import java.nio.ByteBuffer

/** One lazily allocated LUT per owning GL context; call close before destroying it. */
internal class Aces2Gl {
    private var texture = 0

    fun bind(program: Int, mode: Int, look: FloatArray = ToneLook.DEFAULT) {
        val lookLocation = GL.glGetUniformLocation(program, ToneLook.UNIFORM)
        if (lookLocation >= 0) GL.glUniform4fv(lookLocation, look.size / 4, look, 0)
        val location = GL.glGetUniformLocation(program, "u_aces2")
        if (location < 0) return
        GL.glUniform1i(location, 7)
        if (mode != PhotoToneMapper.ACES_2.shaderMode) return
        val previous = IntArray(1)
        GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE, previous, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0 + 7)
        if (texture == 0) {
            val pixels = ByteBuffer.allocateDirect(585 * 1040 * 4)
            for (target in 0..1) {
                val nodes = Aces2DisplayTransform.nodes(target == 1)
                for (b in 0..64) for (g in 0..64) for (r in 0..64) {
                    val source = ((b * 65 + g) * 65 + r) * 3
                    val dest = (((target * 520 + b / 9 * 65 + g) * 585) + b % 9 * 65 + r) * 4
                    for (c in 0..2) pixels.put(dest + c, nodes[source + c])
                    pixels.put(dest + 3, 255.toByte())
                }
            }
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0)
            texture = ids[0]
            check(texture != 0) { "Could not allocate ACES 2.0 LUT" }
            GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 585, 1040, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
        } else GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        GL.glUniform1i(location, 7)
        GL.glActiveTexture(previous[0])
    }

    fun close() {
        if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
    }
}
