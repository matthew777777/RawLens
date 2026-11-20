// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES20 as GL
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the viewfinder's [VfToneLut].
 *
 * - The bake runs off the GL thread on two background-priority workers, so it never starves
 *   the camera or VF threads. The latest request wins, so a slider drag never queues stale bakes. The half-float conversion also happens there; the GL
 *   thread only uploads.
 * - The last LUT is cached for the whole process and on disk, keyed by the resolved settings.
 *   GL context loss, a second VF instance or an app restart reuse it instead of re-baking, so
 *   the VF never drops back to the RAW look once any LUT exists for the current settings.
 * - Once the texture exists, the GL thread re-uploads with glTexSubImage2D (no reallocation).
 */
internal class VfToneLutGl(private val cacheDir: File?) {
    private class Baked(val key: String, val half: ByteBuffer)

    private val requested = AtomicReference<JpegOutputSettings?>(null)
    private val ready = AtomicReference<Baked?>(null)
    private var texture = 0
    private var textureEs3 = false
    @Volatile private var uploadedKey: String? = null

    /** Any thread. Schedules a bake if [settings] changes the tone pipeline. */
    fun request(settings: JpegOutputSettings) {
        val resolved = settings.resolvedForPlatform()
        if (resolved == requested.getAndSet(resolved)) return
        val key = keyOf(resolved)
        // Memory hit (other VF instance, context loss): ready immediately, no bake.
        memory?.takeIf { it.key == key }?.let { ready.set(it); return }
        executor.execute { bakeLatest() }
    }

    private fun bakeLatest() {
        val target = requested.get() ?: return
        val key = keyOf(target)
        if (ready.get()?.key == key || uploadedKey == key) return
        try {
            val baked = memory?.takeIf { it.key == key } ?: loadDisk(key) ?: run {
                val t0 = System.nanoTime()
                // Parallel streams run inside the pool that submits them: 2 background threads.
                val rgba = bakePool.submit(java.util.concurrent.Callable { VfToneLut.bake(target, parallel = true) }).get()
                val half = ByteBuffer.allocateDirect(rgba.size * 2).order(ByteOrder.nativeOrder())
                val shorts = half.asShortBuffer()
                for (v in rgba) shorts.put(toHalf(v))
                android.util.Log.i("VfToneLut", "baked ${target.toneMapper} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                Baked(key, half).also { saveDisk(it) }
            }
            memory = baked
            // A newer request may have arrived while baking; publish only if still wanted.
            if (requested.get()?.let(::keyOf) == key) ready.set(baked)
            else executor.execute { bakeLatest() }
        } catch (t: Throwable) {
            android.util.Log.w("VfToneLut", "bake failed", t)
        }
    }

    /**
     * GL thread. Uploads a finished bake, binds the LUT on [unit] and points [program]'s
     * u_toneLut at it. Returns false only before the first LUT ever exists.
     */
    fun bind(program: Int, unit: Int, es3: Boolean): Boolean {
        val pending = ready.getAndSet(null) ?: if (texture == 0) memory else null
        if (pending != null && pending.key != uploadedKey) upload(pending, es3)
        if (texture == 0) return false
        val location = GL.glGetUniformLocation(program, "u_toneLut")
        if (location < 0) return false
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        GL.glUniform1i(location, unit)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        return true
    }

    private fun upload(baked: Baked, es3: Boolean) {
        val w = VfToneLut.SIZE_C * VfToneLut.SIZE_I
        val h = VfToneLut.SIZE_C
        val previous = IntArray(1)
        GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE, previous, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        val fresh = texture == 0
        if (fresh) {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0)
            texture = ids[0]
            textureEs3 = es3
            GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        } else GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        baked.half.position(0)
        if (textureEs3) {
            // RGBA16F keeps the sign-extended pre-clip values (the shader clamps after filtering).
            if (fresh) GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GLES30_RGBA16F, w, h, 0, GL.GL_RGBA, GLES30_HALF_FLOAT, baked.half)
            else GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, w, h, GL.GL_RGBA, GLES30_HALF_FLOAT, baked.half)
        } else {
            // ES2 fallback (rare): 8-bit, clipped. Converted here because ES2 has no half-float upload.
            val bytes = ByteBuffer.allocateDirect(w * h * 4)
            val shorts = baked.half.asShortBuffer()
            for (i in 0 until w * h * 4) bytes.put((fromHalf(shorts.get(i)).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            bytes.position(0)
            if (fresh) GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
            else GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
        }
        uploadedKey = baked.key
        GL.glActiveTexture(previous[0])
    }

    /** GL thread: drops the texture. The baked LUT stays cached and is re-uploaded on the next bind. */
    fun close() {
        if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        uploadedKey = null
    }

    private fun file(key: String) = cacheDir?.let { File(it, "vf_tone_lut_v$FORMAT_VERSION.bin") }

    private fun loadDisk(key: String): Baked? = try {
        val f = file(key)?.takeIf { it.isFile }
        f?.inputStream()?.use { input ->
            val header = ByteArray(key.toByteArray().size)
            if (input.read(header) != header.size || String(header) != key) return@use null
            val bytes = input.readBytes()
            if (bytes.size != VfToneLut.SIZE_I * VfToneLut.SIZE_C * VfToneLut.SIZE_C * 4 * 2) return@use null
            Baked(key, ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.position(0) })
                .also { android.util.Log.i("VfToneLut", "loaded cached LUT") }
        }
    } catch (t: Throwable) { null }

    private fun saveDisk(baked: Baked) {
        val f = file(baked.key) ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(baked.key.toByteArray())
                val copy = ByteArray(baked.half.capacity())
                baked.half.position(0); baked.half.get(copy); baked.half.position(0)
                out.write(copy)
            }
            tmp.renameTo(f)
        } catch (t: Throwable) {
            android.util.Log.w("VfToneLut", "cache write failed", t)
        }
    }

    companion object {
        private const val GLES30_RGBA16F = 0x881A
        private const val GLES30_HALF_FLOAT = 0x140B
        /** Bump when the LUT layout, shaper or bake pipeline changes meaning. */
        private const val FORMAT_VERSION = 1

        /** Process-wide: one bake thread and the last LUT, shared by every VF instance. */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() },
                "VfToneLutBake").apply { isDaemon = true }
        }
        /** Two background-priority workers: the bake must never starve the camera/VF threads. */
        private val bakePool = java.util.concurrent.ForkJoinPool(2, { pool ->
            object : java.util.concurrent.ForkJoinWorkerThread(pool) {
                override fun onStart() {
                    super.onStart()
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                }
            }.apply { isDaemon = true; name = "VfToneLutBake-w" }
        }, null, false)
        @Volatile private var memory: Baked? = null

        /** Stable key: every resolved setting plus the LUT geometry (the data class prints all fields). */
        private fun keyOf(resolved: JpegOutputSettings): String =
            "${VfToneLut.SIZE_I}x${VfToneLut.SIZE_C}:" + Integer.toHexString(resolved.toString().hashCode()) +
                ":" + resolved.toString().length + "\n"

        /** IEEE 754 binary16, round to nearest. */
        fun toHalf(f: Float): Short {
            val bits = java.lang.Float.floatToIntBits(f)
            val sign = (bits ushr 16) and 0x8000
            val exp = ((bits ushr 23) and 0xFF) - 127 + 15
            var mant = bits and 0x7FFFFF
            if (exp <= 0) {
                if (exp < -10) return sign.toShort()
                mant = mant or 0x800000
                val shift = 14 - exp
                val half = (mant shr shift) + ((mant shr (shift - 1)) and 1)
                return (sign or half).toShort()
            }
            if (exp >= 31) return (sign or 0x7C00).toShort()
            val rounded = (exp shl 10) + (mant shr 13) + ((mant shr 12) and 1)
            return (sign or rounded).toShort()
        }

        fun fromHalf(h: Short): Float {
            val v = h.toInt() and 0xFFFF
            val sign = if (v and 0x8000 != 0) -1f else 1f
            val exp = (v ushr 10) and 0x1F
            val mant = v and 0x3FF
            return sign * when (exp) {
                0 -> mant / 1024f * Math.pow(2.0, -14.0).toFloat()
                31 -> Float.POSITIVE_INFINITY
                else -> (1f + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            }
        }
    }
}
