// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Rect
import android.media.Image
import android.os.Build
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Duration

/** Full-size stills whose reader has no CPU usage, so Image planes cannot be read. */
internal object FullSizeRaw {
    private val library by lazy { System.loadLibrary("rawLensFullSizeRaw") }

    /**
     * Native copy of [image]'s top-left part, at most [scale] x the binned pixel array: vivo
     * buffers can be wider than the sensor (8192 vs 8160). Free [RawImageView.buffer] with [free].
     */
    @Throws(IOException::class)
    fun copy(image: Image, pixelWidth: Int, pixelHeight: Int, block: Int, order: Int): RawImageView {
        if (Build.VERSION.SDK_INT >= 33) image.fence.use { fence ->
            if (fence.isValid && !fence.await(Duration.ofSeconds(3))) throw IOException("RAW buffer fence timed out")
        }
        library
        val (width, height) = cropSize(image.width, image.height, pixelWidth, pixelHeight)
        val bytes = (image.hardwareBuffer ?: throw IOException("RAW image has no hardware buffer")).use {
            nativeCopy(it, width, height, block, order)
        } ?: throw IOException("RAW copy failed")
        val divisor = if (block == 4) 2 else 1
        val w = width / divisor
        val h = height / divisor
        return RawImageView(w, h, image.format, image.timestamp, Rect(0, 0, w, h), bytes, w * 2, 2, 1)
    }

    fun free(view: RawImageView) {
        view.buffer?.let(::nativeFree)
    }

    /** Pure, unit-tested. Even sizes keep the colour phase. */
    fun cropSize(width: Int, height: Int, pixelWidth: Int, pixelHeight: Int): Pair<Int, Int> {
        if (pixelWidth <= 0 || pixelHeight <= 0) return width to height
        val scale = maxOf(1, (width + pixelWidth / 8) / pixelWidth)
        return minOf(width, scale * pixelWidth) to minOf(height, scale * pixelHeight)
    }

    @JvmStatic private external fun nativeCopy(buffer: android.hardware.HardwareBuffer, width: Int, height: Int, block: Int, order: Int): ByteBuffer?
    @JvmStatic private external fun nativeFree(buffer: ByteBuffer)
}
