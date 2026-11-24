// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.math.roundToInt
import android.view.View

/**
 * RAW focus contours and an optional magnified view of the active preview.
 * Touches go to the metering overlay. The tap/manual-focus policy owns visibility.
 */
class FocusPeakingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var peakingColor: FocusPeakingColor = FocusPeakingColor.GREEN
        set(value) {
            field = value
            paint.color = value.argb
            paint.alpha = PEAKING_ALPHA
            contentDescription = "Focus peaking ${value.label.lowercase()}"
            invalidate()
        }

    /**
     * Aspect-fill frame→view transform in view pixels: the cover rect in
     * this view's own coordinates (negative origin on the cropped axis).
     * Null = identity (the view spans the frame exactly).
     */
    var contentTransform: RectF? = null
        set(value) {
            if (field == value) return
            field = value
            previewPeaksDirty = true
            discardInset()
        }

    internal var previewSource: (() -> SurfaceView?)? = null
    internal var magnifierEnabled = false
        set(value) {
            if (field == value) return
            field = value
            discardInset()
            if (value) requestInset()
        }

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val overlayLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private val inset = RectF()
    private val sampledArea = RectF()
    private val insetClip = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var targetX = Float.NaN
    private var targetY = Float.NaN
    private var insetBitmap: Bitmap? = null
    private var spareBitmap: Bitmap? = null
    private var insetReady = false
    private val insetPeaks = PeakLines()
    private val previewPeaks = PeakLines()
    private var insetSource: SurfaceView? = null
    private var copyPending = false
    private var copyGeneration = 0

    internal fun setTarget(x: Float, y: Float, source: View) {
        if (source.width <= 0 || source.height <= 0) return
        targetX = (x / source.width).coerceIn(0f, 1f)
        targetY = (y / source.height).coerceIn(0f, 1f)
    }

    internal fun resetTarget() {
        targetX = Float.NaN
        targetY = Float.NaN
        discardInset()
    }

    private fun discardInset() {
        copyGeneration++
        insetReady = false
        insetSource = null
        invalidate()
    }

    private fun requestInset() {
        val current = frame ?: return
        if (!magnifierEnabled || !isShown || copyPending || width <= 0 || height <= 0) return
        val source = previewSource?.invoke() ?: return
        val surfaceFrame = source.holder.surfaceFrame
        if (source.width <= 0 || source.height <= 0 || surfaceFrame.isEmpty) return
        getLocationInWindow(overlayLocation)
        source.getLocationInWindow(sourceLocation)
        val offsetX = sourceLocation[0] - overlayLocation[0]
        val offsetY = sourceLocation[1] - overlayLocation[1]
        val visible = RectF(offsetX.toFloat(), offsetY.toFloat(),
            (offsetX + source.width).toFloat(), (offsetY + source.height).toFloat())
        clipBounds?.let { if (!visible.intersect(RectF(it))) return }
        if (!visible.intersect(0f, 0f, width.toFloat(), height.toFloat())) return

        val margin = 12f * density
        val size = minOf(144f * density, visible.width() * 0.36f, visible.height() * 0.36f)
        val cropSize = size / MAGNIFICATION
        if (cropSize < 2f) return
        val x = if (targetX.isNaN()) visible.centerX() else targetX * width
        val y = if (targetY.isNaN()) visible.centerY() else targetY * height
        val left = (x - cropSize / 2).coerceIn(visible.left, visible.right - cropSize)
        val top = (y - cropSize / 2).coerceIn(visible.top, visible.bottom - cropSize)
        val crop = RectF(left, top, left + cropSize, top + cropSize)
        val scaleX = surfaceFrame.width().toFloat() / source.width
        val scaleY = surfaceFrame.height().toFloat() / source.height
        val sourceRect = Rect(
            ((crop.left - offsetX) * scaleX).roundToInt(),
            ((crop.top - offsetY) * scaleY).roundToInt(),
            ((crop.right - offsetX) * scaleX).roundToInt(),
            ((crop.bottom - offsetY) * scaleY).roundToInt()
        )
        if (sourceRect.isEmpty) return
        val insetTop = visible.top + (visible.height() - size) * 0.38f
        val nearInset = crop.bottom >= insetTop && crop.top <= insetTop + size
        val right = if (nearInset && crop.centerX() > visible.centerX())
            visible.left + margin + size else visible.right - margin
        val destination = RectF(right - size, insetTop, right, insetTop + size)
        // Copy at crop resolution and let Canvas magnify the thumbnail.
        val copyWidth = sourceRect.width().coerceAtMost(MAX_COPY_DIMENSION)
        val copyHeight = sourceRect.height().coerceAtMost(MAX_COPY_DIMENSION)
        val buffer = spareBitmap?.takeIf { it.width == copyWidth && it.height == copyHeight }
            ?: Bitmap.createBitmap(copyWidth, copyHeight, Bitmap.Config.ARGB_8888)
        spareBitmap = null
        val generation = copyGeneration
        val transform = contentTransform?.let(::RectF)
        copyPending = true
        try {
            PixelCopy.request(source, sourceRect, buffer, { result ->
                copyPending = false
                if (result == PixelCopy.SUCCESS && generation == copyGeneration &&
                    width > 0 && height > 0 && transform == contentTransform &&
                    isShown && previewSource?.invoke() === source) {
                    spareBitmap = insetBitmap
                    insetBitmap = buffer
                    updatePeaks(insetPeaks, current, crop)
                    insetReady = true
                    insetSource = source
                    sampledArea.set(crop)
                    inset.set(destination)
                    invalidate()
                } else {
                    spareBitmap = buffer
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            copyPending = false
            spareBitmap = buffer
        }
    }

    private var frame: FocusPeakingFrame? = null
    private var previewPeaksDirty = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = peakingColor.argb
        alpha = PEAKING_ALPHA
    }

    init {
        contentDescription = "Focus peaking ${peakingColor.label.lowercase()}"
    }

    fun update(frame: FocusPeakingFrame) {
        this.frame = frame
        previewPeaksDirty = true
        requestInset()
        invalidate()
    }

    fun clear() {
        frame = null
        discardInset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        if (width <= 0 || height <= 0) return
        if (current.cols <= 0 || current.rows <= 0) return
        if (current.mask.size != current.cols * current.rows) return
        if (previewPeaksDirty) {
            updatePeaks(previewPeaks, current)
            previewPeaksDirty = false
        }
        drawPeaks(canvas, previewPeaks)
        drawInset(canvas)
    }

    private fun drawInset(canvas: Canvas) {
        val image = insetBitmap ?: return
        if (!insetReady) return
        if (previewSource?.invoke() !== insetSource) return
        uiPaint.color = Color.WHITE
        uiPaint.style = Paint.Style.STROKE
        uiPaint.strokeWidth = 1.5f * density
        canvas.drawRect(sampledArea, uiPaint)
        val radius = 8f * density
        insetClip.rewind()
        insetClip.addRoundRect(inset, radius, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(insetClip)
        canvas.drawBitmap(image, null, inset, bitmapPaint)
        canvas.translate(inset.left, inset.top)
        canvas.scale(inset.width() / sampledArea.width(), inset.height() / sampledArea.height())
        canvas.translate(-sampledArea.left, -sampledArea.top)
        drawPeaks(canvas, insetPeaks, MAGNIFICATION)
        canvas.restoreToCount(saved)
        canvas.drawRoundRect(inset, radius, radius, uiPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        previewPeaksDirty = true
        discardInset()
    }

    private class PeakLines {
        var xy = FloatArray(0)
        var count = 0
    }

    private fun updatePeaks(out: PeakLines, current: FocusPeakingFrame, crop: RectF? = null) {
        out.count = 0
        val t = contentTransform
        val frameWidth = t?.width() ?: width.toFloat()
        val frameHeight = t?.height() ?: height.toFloat()
        val left = t?.left ?: 0f
        val top = t?.top ?: 0f
        FocusPeakingContours.forEachSegment(current) { u0, v0, u1, v1 ->
            val (x0, y0) = FocusPeakingGeometry.viewPoint(u0, v0, current.rotation, current.mirrored)
            val (x1, y1) = FocusPeakingGeometry.viewPoint(u1, v1, current.rotation, current.mirrored)
            val ax = left + x0 * frameWidth
            val ay = top + y0 * frameHeight
            val bx = left + x1 * frameWidth
            val by = top + y1 * frameHeight
            if (crop == null || (maxOf(ax, bx) >= crop.left && minOf(ax, bx) <= crop.right &&
                    maxOf(ay, by) >= crop.top && minOf(ay, by) <= crop.bottom)) {
                if (out.count + 4 > out.xy.size) out.xy = out.xy.copyOf(maxOf(64, out.xy.size * 2))
                out.xy[out.count++] = ax
                out.xy[out.count++] = ay
                out.xy[out.count++] = bx
                out.xy[out.count++] = by
            }
        }
    }

    private fun drawPeaks(canvas: Canvas, lines: PeakLines, zoom: Float = 1f) {
        if (lines.count == 0) return
        paint.strokeWidth = maxOf(1f, 0.9f * density) / zoom
        canvas.drawLines(lines.xy, 0, lines.count, paint)
    }

    private companion object {
        const val PEAKING_ALPHA = 240
        const val MAGNIFICATION = 4f
        const val MAX_COPY_DIMENSION = 96

    }
}
