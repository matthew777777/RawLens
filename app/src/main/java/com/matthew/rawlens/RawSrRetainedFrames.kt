// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Super-resolution burst inputs detached from the camera [Image]s.
 *
 * The merge (GPU Bayer-direct and CPU streaming mosaic alike) runs for tens
 * of seconds. Holding the source gralloc slots for the whole merge wedges
 * the small [android.media.ImageReader] queue: the ZSL ring cannot refill
 * (merge-held + ring + pairing window >= maxImages), the relief valve spins
 * evicting pairing candidates faster than pairs arrive, and the watchdog
 * latches the session into burst-mode after a few captures. Retaining exact
 * pixel copies up front and releasing the [Image]s immediately keeps the
 * reader fluid while the merge runs from memory it owns.
 */
internal data class RetainedSrFrame(
    /** Exact copy of the source plane (capacity, position and limit preserved). */
    val plane: ByteBuffer,
    val metadata: RawFrameMetadata,
    val result: TotalCaptureResult,
    val timestampNanos: Long
)

/**
 * Byte-exact copy of a camera plane into a natively-ordered direct buffer.
 * Position and limit are preserved so [RawSrPackedFrame] validation and
 * offsets behave identically on the copy. Pure and unit-testable.
 */
internal fun copyPlaneByteBuffer(src: ByteBuffer): ByteBuffer {
    val position = src.position()
    val limit = src.limit()
    val dst = ByteBuffer.allocateDirect(src.capacity()).order(ByteOrder.nativeOrder())
    val view = src.duplicate()
    view.clear()
    dst.put(view)
    dst.position(position)
    dst.limit(limit)
    return dst
}

/**
 * Retains every frame's pixels, then releases the camera [Image]s via
 * [RawImageOwnership] (lease-aware: an in-flight viewfinder borrow keeps its
 * image alive until the GPU read finishes), except [keepOpenIndex] (the
 * merge reference), which stays open so a later merge failure can still
 * fall back to a truthful single-frame save — the platform DNG writer needs
 * the live [Image]. One pinned slot cannot wedge the reader (1 + ring +
 * pairing window << maxImages). All-or-nothing in three phases (validate,
 * copy, release): a throw anywhere before the release phase leaves every
 * [Image] owned by the caller. Retained copies are GC-managed direct
 * buffers; the job owner releases whatever is still open at the end.
 */
internal fun retainSrBurstPlanes(
    frames: List<RawSuperResolutionFrame>,
    keepOpenIndex: Int = -1
): List<RetainedSrFrame> {
    val planes = frames.map { frame ->
        frame.image.planes.singleOrNull()?.buffer
            ?: throw MergeUnavailableException("RAW SR image must have one plane")
    }
    val copies = planes.map(::copyPlaneByteBuffer)
    frames.forEachIndexed { index, frame ->
        if (index != keepOpenIndex) RawImageOwnership.release(frame.image)
    }
    return frames.mapIndexed { index, frame ->
        RetainedSrFrame(copies[index], frame.metadata, frame.result, frame.timestampNanos)
    }
}
