// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.Image
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/** Internal validation route. No preference/UI exposure before Android acceptance.
 * FP32 reconstruction is synchronous; separate grading owns each RGB buffer
 * through a duplicated completion fence. This is not a throughput-qualified path.
 */
internal class PhotoRcdRenderer private constructor(private var handle: Long, private val reconstructionScale: Int = 1, private val gpuImage: Boolean = false) : AutoCloseable {
    internal class IncompleteGrade : IllegalStateException("Photo RCD grade fence incomplete; RGB buffer retained")
    private class Slot(val buffer: HardwareBuffer, val completion: PhotoRcdCompletion = PhotoRcdCompletion())
    private val slots = arrayOfNulls<Slot>(3)

    @Synchronized
    fun render(image: Image, slot: Int, left: Int, top: Int, width: Int, height: Int,
               channels: IntArray, black: FloatArray, white: Float, wb: FloatArray,
               shadeDims: IntArray?, shadeGains: ShortArray?): HardwareBuffer {
        require(image.format == android.graphics.ImageFormat.RAW_SENSOR)
        val plane = image.planes[0]
        return renderRawBuffer(plane.buffer.slice(), image.width, image.height, plane.rowStride,
            plane.pixelStride, slot, left, top, width, height, channels, black, white, wb, shadeDims, shadeGains)
    }

    /** Same production adapter with an explicit RAW plane, for headless validation. */
    @Synchronized
    internal fun renderRawBuffer(raw: ByteBuffer, sensorWidth: Int, sensorHeight: Int,
               rowStride: Int, pixelStride: Int, slot: Int, left: Int, top: Int, width: Int, height: Int,
               channels: IntArray, black: FloatArray, white: Float, wb: FloatArray,
               shadeDims: IntArray?, shadeGains: ShortArray?): HardwareBuffer {
        check(handle != 0L); require(slot in slots.indices)
        require(channels.size == 4 && channels.sorted() == listOf(0, 1, 2, 3))
        require(wb.size == 3 && black.size == 4)
        val outputWidth=if(reconstructionScale==1) width else 2*((width+2*reconstructionScale-1)/(2*reconstructionScale))
        val outputHeight=if(reconstructionScale==1) height else 2*((height+2*reconstructionScale-1)/(2*reconstructionScale))
        var target = slots[slot]
        target?.let { awaitGrade(it) }
        if (target != null && (target.buffer.width != outputWidth || target.buffer.height != outputHeight)) {
            target.buffer.close(); target = null; slots[slot] = null
        }
        if (target == null) {
            target = Slot(HardwareBuffer.create(outputWidth, outputHeight, HardwareBuffer.RGBA_FP16, 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or (if(gpuImage) outputUsageNative(handle) else 0L)))
            slots[slot] = target
        }
        var phase = 0
        for (q in 0..3) {
            val sensorSite = (((q / 2 + top) and 1) * 2) + ((q % 2 + left) and 1)
            val color = when (sensorSite) { channels[0] -> 0; channels[3] -> 2; else -> 1 }
            phase = phase or (color shl (q * 2))
        }
        renderNative(handle, raw, intArrayOf(sensorWidth, sensorHeight,
            rowStride, pixelStride, left, top, width, height, phase), black, white,
            wb, shadeDims, shadeGains, target.buffer)
        return target.buffer
    }

    /** Canonical FP32 readback before product corrections/final half storage. */
    @Synchronized internal fun canonical(width: Int, height: Int, phase: Int, cfa: FloatArray): FloatArray {
        check(handle != 0L)
        return canonicalNative(handle, width, height, phase, cfa)
    }

    @Synchronized internal fun canonicalRaw(raw: ByteBuffer, geometry: IntArray, black: FloatArray, white: Float): FloatArray {
        check(handle!=0L)
        return canonicalRawNative(handle,raw,geometry,black,white)
    }
    private external fun canonicalRawNative(handle: Long, raw: ByteBuffer, geometry: IntArray, black: FloatArray, white: Float): FloatArray

    /** Call immediately after successful grade submission, before handing off its fd. */
    @Synchronized
    fun gradeSubmitted(slot: Int, completionFd: Int): Int {
        require(completionFd >= 0)
        val target = checkNotNull(slots[slot])
        // Adopt the original before duplicating: even an EMFILE failure must
        // retain a fence protecting the buffer already submitted to the GPU.
        return target.completion.submitted(completionFd) { ParcelFileDescriptor.fromFd(it).detachFd() }
    }

    private fun awaitGrade(slot: Slot) {
        if (!slot.completion.complete(30_000, VfEglImport::awaitSyncFd, VfEglImport::closeSyncFd)) throw IncompleteGrade()
    }

    @Synchronized override fun close() {
        val old = handle; handle = 0
        try { if (old != 0L) closeNative(old) } finally {
            for (i in slots.indices) slots[i]?.let { slot ->
                // A timeout/error cannot authorize buffer reuse or destruction.
                // Retain it with its fence; future opens collect only signaled work.
                if (slot.completion.complete(30_000, VfEglImport::awaitSyncFd, VfEglImport::closeSyncFd)) {
                    slot.buffer.close()
                } else synchronized(retained) { retained.add(slot) }
                slots[i] = null
            }
        }
    }

    companion object {
        private val retained = mutableListOf<Slot>()
        fun open(context: Context, optimized: Boolean = false, cacheResponses: Boolean = false, tiled: Boolean = false, images: Boolean = false, recordingCandidate: Boolean = false, baked: Boolean = false, packed: Boolean = false, directRaw: Boolean = false, requirementsInput: Boolean = false, reconstructionScale: Int = 1, normalizeOnce: Boolean = false, sharedFinal: Boolean = false, tileLocal: Boolean = false, gpuImage: Boolean = false, frameGlobal: Boolean = false): PhotoRcdRenderer {
            synchronized(retained) {
                val iterator = retained.iterator()
                while (iterator.hasNext()) {
                    val slot = iterator.next()
                    if (slot.completion.complete(0, VfEglImport::awaitSyncFd, VfEglImport::closeSyncFd)) {
                        slot.buffer.close(); iterator.remove()
                    }
                }
            }
            System.loadLibrary("rawLensPhotoRcd")
            val asset = if(requirementsInput && frameGlobal) (if(gpuImage) "requirements_frame_global_image_rcd" else "requirements_frame_global_rcd") else if(requirementsInput) (if(gpuImage) "requirements_tile_image_rcd" else if(tileLocal) "requirements_tile_local_rcd" else if(normalizeOnce) (if(sharedFinal) "requirements_normalized_shared_rcd" else if(cacheResponses) "requirements_normalized_cached_rcd" else "requirements_normalized_rcd") else "requirements_rcd") else if(recordingCandidate) (if(directRaw) "recording_candidate_direct_raw" else if(packed) "recording_candidate_packed" else if(cacheResponses) "recording_candidate_cached" else if(images) "recording_candidate_images" else "recording_candidate") else if(images) "images" else if(tiled) "tiled" else if(cacheResponses) "responsecache" else if (optimized) "optimized" else "baseline"
            val code = context.assets.open("shaders/photo_rcd/$asset.spv").use { it.readBytes() }
            require(reconstructionScale in 1..8 && (requirementsInput || reconstructionScale==1))
            require(!gpuImage || tileLocal)
            require(!frameGlobal || tileLocal)
            require(!tileLocal || (requirementsInput && normalizeOnce && !sharedFinal && !cacheResponses && reconstructionScale==1))
            require(!sharedFinal || (requirementsInput && normalizeOnce && cacheResponses && reconstructionScale==1))
            require(!normalizeOnce || (requirementsInput && reconstructionScale==1))
            val renderer = PhotoRcdRenderer(0,reconstructionScale,gpuImage)
            require(!requirementsInput || (!recordingCandidate && (!cacheResponses || normalizeOnce) && !tiled && !images && !baked && !packed && !directRaw))
            val rawPack=if(requirementsInput) context.assets.open("shaders/photo_rcd/${if(normalizeOnce) "requirements_normalize" else "requirements_pack"}.spv").use { it.readBytes() } else null
            require(!directRaw || packed)
            require(!packed || (recordingCandidate && !cacheResponses && !images && !baked))
            require(!baked || (recordingCandidate && cacheResponses && !images))
            val stages=if(baked) Array<ByteArray?>(8) { stage -> if(stage==5) null else context.assets.open("shaders/photo_rcd/recording_candidate_stage$stage.spv").use { it.readBytes() } } else null
            renderer.handle = renderer.openNative(code, optimized || cacheResponses || tiled || images || recordingCandidate || requirementsInput,
                if(recordingCandidate) cacheResponses else (cacheResponses || tiled || images),
                !recordingCandidate && tiled, images, recordingCandidate,stages,packed,rawPack,reconstructionScale,normalizeOnce,tileLocal,gpuImage,frameGlobal)
            check(renderer.handle != 0L) { "Photo RCD unavailable" }
            return renderer
        }
    }
    private external fun openNative(shader: ByteArray, specializePasses: Boolean, cacheResponses: Boolean, tiled: Boolean, images: Boolean, recordingCandidate: Boolean, stageCodes: Array<ByteArray?>?, packed: Boolean, rawPack: ByteArray?, reconstructionScale: Int, normalizeOnce: Boolean, tileLocal: Boolean, imageOutput: Boolean, frameGlobal: Boolean): Long
    private external fun outputUsageNative(handle: Long): Long
    private external fun closeNative(handle: Long)
    @Synchronized internal fun frameCosts(): DoubleArray = frameCostsNative(handle)
    private external fun frameCostsNative(handle: Long): DoubleArray
    private external fun canonicalNative(handle: Long, width: Int, height: Int, phase: Int, cfa: FloatArray): FloatArray
    private external fun renderNative(handle: Long, raw: java.nio.ByteBuffer, geometry: IntArray,
        black: FloatArray, white: Float, wb: FloatArray, shadeDims: IntArray?, shadeGains: ShortArray?,
        output: HardwareBuffer)
}
