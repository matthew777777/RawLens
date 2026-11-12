// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer

/**
 * RAW Video (.craw) temporal 4x4 DCT denoiser (`vf_rawdct.comp`, own Vulkan
 * device in `rawdct/raw_dct_vulkan.cpp`), motion-aligned by `vf_rawdct_align.comp`
 * (per-tile quad shift of the previous denoised frame before the DCT merge). The camera thread [submit]s the crop
 * rows of each frame into a slot; the encode worker [awaitOutput]s that slot
 * and encodes the returned compact frame (stride = width * 2, crop row 0 at
 * buffer row 0), then [release]s it.
 *
 * Slot rule: a slot is free only when its worker released it AND it is no
 * longer the previous (recursive reference) frame. [acquire] never blocks;
 * no free slot means the caller encodes the frame undenoised and the
 * temporal history restarts.
 */
internal class RawDct private constructor(
    private val handle: Long,
    val width: Int,
    val height: Int,
    slotCount: Int,
) : AutoCloseable {
    private val lock = Object()
    private val workerHeld = BooleanArray(slotCount)
    private var prevSlot = -1
    private var closed = false
    private val outputs = arrayOfNulls<ByteBuffer>(slotCount)

    /** Temporal strength, spatial Wiener multiplier, motion gate (sigmas). */
    var tune = floatArrayOf(0.8f, 1.0f, 3.0f)

    /** Free slot index, or -1. Marks it worker-held. */
    fun acquire(): Int = synchronized(lock) {
        if (closed) return -1
        for (i in workerHeld.indices) {
            if (!workerHeld[i] && i != prevSlot) { workerHeld[i] = true; return i }
        }
        -1
    }

    /**
     * Copy rows [rowTop, rowTop + height) of [plane] and dispatch. [levels] /
     * [white] are per absolute sensor quad; this remaps them to the compact
     * frame's phase. Returns false (slot released, history reset) on failure.
     */
    fun submit(slot: Int, plane: ByteBuffer, rowStride: Int, rowTop: Int,
               levels: FloatArray, white: Float, greenQuad: Int): Boolean {
        val phase = (rowTop and 1) shl 1
        val compact = FloatArray(4) { levels[it xor phase] }
        val gq = greenQuad xor phase
        val rc = synchronized(lock) {
            if (closed) -100 else nativeSubmit(handle, plane, rowStride, rowTop, slot, compact, white, gq, tune)
        }
        if (rc != 0) {
            Log.w(TAG, "submit failed rc=$rc slot=$slot")
            synchronized(lock) { workerHeld[slot] = false; prevSlot = -1 }
            nativeResetHistory(handle)
            return false
        }
        synchronized(lock) { prevSlot = slot }
        return true
    }

    /** Frame skipped by the caller: the next frame must not blend across the gap. */
    fun resetHistory() {
        synchronized(lock) { if (closed) return; prevSlot = -1; nativeResetHistory(handle) }
    }

    /** Worker: wait for the GPU, return the compact denoised frame or null. */
    fun awaitOutput(slot: Int, timeoutMs: Int = 2000): ByteBuffer? {
        if (!nativeWait(handle, slot, timeoutMs)) return null
        val buf = outputs[slot] ?: nativeOutput(handle, slot)?.also { outputs[slot] = it } ?: return null
        return buf.duplicate().apply { clear() }.order(java.nio.ByteOrder.nativeOrder())
    }

    fun release(slot: Int) = synchronized(lock) { workerHeld[slot] = false }

    /** [a, b, valid]: var = a*mean + b in 0..1 normalized units. */
    fun noise(): FloatArray? = synchronized(lock) { if (closed) null else nativeNoise(handle) }

    /** Call only after every worker has stopped (no slot in use). */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        nativeDestroy(handle)
    }

    companion object {
        private const val TAG = "RawDct"
        private val loaded: Boolean = try {
            System.loadLibrary("rawLensRawDct"); true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "rawLensRawDct unavailable: ${e.message}"); false
        }

        /** null when Vulkan / the library is unavailable (take records undenoised). */
        fun create(context: Context, width: Int, height: Int, slots: Int): RawDct? {
            if (!loaded || width < 8 || height < 8 || width % 2 != 0 || height % 2 != 0) return null
            val (spv, alignSpv) = try {
                context.assets.open("shaders/vf/vf_rawdct.spv").use { it.readBytes() } to
                    context.assets.open("shaders/vf/vf_rawdct_align.spv").use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "shader missing", e); return null
            }
            val h = nativeCreate(spv, alignSpv, width, height, slots)
            return if (h == 0L) null else RawDct(h, width, height, slots)
        }

        @JvmStatic private external fun nativeCreate(spv: ByteArray, alignSpv: ByteArray, width: Int, height: Int, slots: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSubmit(
            handle: Long, src: ByteBuffer, srcStride: Int, rowTop: Int, slot: Int,
            levels: FloatArray, white: Float, grQuad: Int, tune: FloatArray,
        ): Int
        @JvmStatic private external fun nativeWait(handle: Long, slot: Int, timeoutMs: Int): Boolean
        @JvmStatic private external fun nativeOutput(handle: Long, slot: Int): ByteBuffer?
        @JvmStatic private external fun nativeResetHistory(handle: Long)
        @JvmStatic private external fun nativeNoise(handle: Long): FloatArray?
    }
}
