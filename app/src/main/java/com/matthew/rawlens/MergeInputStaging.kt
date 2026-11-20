// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Rect
import android.media.Image
import android.os.SystemClock
import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single RAW plane plus the Image fields the merge path reads. Built
 * either from a live camera [Image] or from a native copy made by
 * [MergeInputStaging], so merges can run after the camera buffer is back
 * in the reader.
 */
internal data class RawImageView(
    val width: Int,
    val height: Int,
    val format: Int,
    val timestamp: Long,
    val cropRect: Rect?,
    /** The RAW plane; null when the image does not have exactly one plane. */
    val buffer: ByteBuffer?,
    val rowStride: Int,
    val pixelStride: Int,
    val planeCount: Int,
) {
    companion object {
        fun of(image: Image): RawImageView {
            val planes = image.planes
            val plane = planes.singleOrNull()
            return RawImageView(
                image.width, image.height, image.format, image.timestamp,
                image.cropRect?.let(::Rect), plane?.buffer,
                plane?.rowStride ?: 0, plane?.pixelStride ?: 0, planes.size
            )
        }
    }
}

/**
 * Copies a merge burst out of the camera's ImageReader so the next shot
 * does not wait for this one's merge (queued merges used to hold their
 * RAW buffers until their own merge ran, which kept the ZSL ring empty
 * and the shutter locked). The copy is tight RAW16 in native memory (not
 * the Java heap the merges allocate from).
 */
internal object MergeInputStaging {
    private const val LOG_TAG = "RawLensCamera"

    /**
     * Staged frames, or null when the burst must stay in camera buffers:
     * not plain RAW16, staging failed, or the copy would eat into the
     * memory the merges themselves need. The returned frames' [Image]s are
     * NOT released here.
     */
    fun stage(context: Context, frames: List<BufferedRawFrame>, outputScale: Int = 1): List<BufferedRawFrame>? {
        if (frames.isEmpty()) return null
        val views = frames.map { RawImageView.of(it.image) }
        if (views.any { it.planeCount != 1 || it.buffer == null || it.pixelStride != 2 || it.rowStride % 2 != 0 }) {
            return null
        }
        val bytes = views.sumOf { it.width.toLong() * it.height * 2 }
        val headroom = MergeMemoryBudget.systemHeadroomBytes(context)
        // The copy is extra memory (reader buffers stay allocated for reuse).
        // After it, free memory must still hold one merge's full working set
        // (same rule as the queue limit); otherwise keep the camera buffers
        // as before and let the merge's own gate decide.
        val px = views[0].width.toLong() * views[0].height
        if (headroom != null && headroom - bytes < MergeMemoryBudget.mergeWorkingSetBytes(px, frames.size, outputScale)) {
            Log.i(LOG_TAG, "merge inputs kept in camera buffers: copy ${MergeMemoryBudget.mb(bytes.toDouble())}MB, " +
                "headroom ${MergeMemoryBudget.mb(headroom.toDouble())}MB")
            return null
        }
        val t0 = SystemClock.elapsedRealtime()
        val copies = ArrayList<ByteBuffer>(views.size)
        try {
            for (view in views) {
                val src = view.buffer!!.duplicate().also { it.rewind() }
                copies += LkrBridge.nativeStage(src, view.width, view.height, view.rowStride / 2, 16)
                    ?: throw IllegalStateException("native staging returned null")
            }
        } catch (failure: Throwable) {
            copies.forEach(SrpBridge::nativeFreeDirect)
            Log.w(LOG_TAG, "merge input staging failed; keeping camera buffers", failure)
            return null
        }
        Log.i(LOG_TAG, "merge inputs staged: ${frames.size} frames, ${MergeMemoryBudget.mb(bytes.toDouble())}MB in " +
            "${SystemClock.elapsedRealtime() - t0} ms; camera buffers released")
        return frames.mapIndexed { i, frame ->
            frame.copy(staged = views[i].copy(buffer = copies[i], rowStride = views[i].width * 2, pixelStride = 2))
        }
    }
}

/**
 * Owns one queued merge's inputs. Staging runs on [stager] right after the
 * shutter; once it succeeds the camera Images are released
 * ([onCameraBuffersReturned]) and the merge reads the native copies.
 * [release] is idempotent and frees whichever form the inputs ended in.
 */
internal class MergeInputs(
    context: Context,
    private val selected: List<BufferedRawFrame>,
    stager: ExecutorService?,
    outputScale: Int = 1,
    private val onCameraBuffersReturned: () -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val cameraReturned = AtomicBoolean(false)
    private val staging: Future<List<BufferedRawFrame>>? = stager?.let {
        try {
            it.submit(Callable {
                val staged = MergeInputStaging.stage(context, selected, outputScale) ?: return@Callable selected
                returnCameraBuffers()
                staged
            })
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun returnCameraBuffers() {
        if (!cameraReturned.compareAndSet(false, true)) return
        selected.forEach { RawImageOwnership.release(it.image) }
        onCameraBuffersReturned()
    }

    /** The frames to merge (staged when staging worked); waits for staging. */
    fun frames(): List<BufferedRawFrame> =
        staging?.let { runCatching { it.get() }.getOrNull() } ?: selected

    fun release() {
        if (!released.compareAndSet(false, true)) return
        frames().forEach { frame -> frame.staged?.buffer?.let(SrpBridge::nativeFreeDirect) }
        returnCameraBuffers()
    }
}
