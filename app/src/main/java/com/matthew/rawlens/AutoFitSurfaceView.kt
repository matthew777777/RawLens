// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * System ISP preview target, PhotonCamera style: a [SurfaceView] whose buffer the
 * camera fills directly and SurfaceFlinger composites, so showing it costs the
 * app no GPU work. Its buffer size is pinned with [SurfaceHolder.setFixedSize]
 * to the session's preview size; the view measures to the requested aspect.
 *
 * The surface must stay alive while a preview session runs (a destroyed output
 * kills the session), so it is never hidden with GONE/INVISIBLE: the RAW/BGU VF
 * surfaces sit above it (media-overlay Z order) and are removed to reveal it.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle), SurfaceHolder.Callback {

    interface Listener {
        fun onSurfaceAvailable()
        fun onSurfaceSizeChanged(width: Int, height: Int)
        fun onSurfaceDestroyed()
    }

    private var ratioWidth = 0
    private var ratioHeight = 0
    @Volatile private var valid = false
    @Volatile private var bufferWidth = 0
    @Volatile private var bufferHeight = 0
    private var fixedSize: Size? = null
    var listener: Listener? = null

    /** Landscape camera UI fills its viewport (the buffer scales to the view). */
    var fillViewport = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init { holder.addCallback(this) }

    val isAvailable: Boolean get() = valid && holder.surface.isValid

    /** The live surface, or null; never released by callers (the holder owns it). */
    val surface: Surface? get() = holder.surface.takeIf { valid && it.isValid }

    /** True once the surface buffer is exactly [size] (camera outputs must match a supported size). */
    fun hasBufferSize(size: Size): Boolean = valid && bufferWidth == size.width && bufferHeight == size.height

    /** UI thread: pins the buffer size; [Listener.onSurfaceSizeChanged] fires when applied. */
    fun requestBufferSize(size: Size) {
        if (fixedSize == size) return
        fixedSize = size
        holder.setFixedSize(size.width, size.height)
    }

    fun setAspectRatio(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "Size cannot be negative." }
        if (ratioWidth == width && ratioHeight == height) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (fillViewport || 0 == ratioWidth || 0 == ratioHeight) {
            setMeasuredDimension(width, height)
        } else if (width < height * ratioWidth / ratioHeight) {
            setMeasuredDimension(width, width * ratioHeight / ratioWidth)
        } else {
            setMeasuredDimension(height * ratioWidth / ratioHeight, height)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        valid = true
        listener?.onSurfaceAvailable()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        valid = true
        bufferWidth = width
        bufferHeight = height
        listener?.onSurfaceSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        valid = false
        bufferWidth = 0
        bufferHeight = 0
        // A new surface starts at the view size; re-pin on the next session.
        fixedSize = null
        listener?.onSurfaceDestroyed()
    }
}
