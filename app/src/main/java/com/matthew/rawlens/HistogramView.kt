// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Live RGB histogram, always sampled from the sensor mosaic. Linear RAW matches
 * the DNG data; the AgX-curved variant predicts the developed JPEG and is selected
 * automatically with a JPEG capture format. darktable look tuned for sunlight: the
 * three channel fills blend ADD on an almost opaque plate, so overlaps read
 * yellow / cyan / magenta / white, with no grid (clutter at this size). Vertical scale is linear
 * by default with logarithmic as the second option, like darktable's toggle.
 * Each channel's top edge gets a crisp outline where it has data (empty bins
 * draw nothing, so no coloured line runs along the floor). Blown highlights —
 * any channel piling up in its top bin — light a small red marker in the top
 * right corner; crushed RAW blacks are normal sensor noise and get none.
 * Tapping is handled by the host. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val bins = Array(3) { IntArray(BIN_COUNT) }
    private val heights = FloatArray(BIN_COUNT)
    private val fillPaths = Array(3) { Path() }
    private val linePaths = Array(3) { Path() }
    private val dp = resources.displayMetrics.density
    private val scrimPaint = Paint().apply {
        style = Paint.Style.FILL
        color = ScopeStyle.SCRIM
    }
    // ADD: overlapping channels sum toward white, darktable-style.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ScopeStyle.BORDER_WIDTH_DP * dp
        color = ScopeStyle.BORDER.toInt()
    }
    private val clipPath = Path()
    private val plotClip = Path()
    private val plotRect = RectF()
    private val plotRadius = ScopeStyle.plotCornerRadiusDp() * dp
    private val bounds = RectF()
    private val cornerRadius = ScopeStyle.CORNER_RADIUS_DP * dp
    private var agxSelected = false

    /** Logarithmic vertical scale; linear (false) is the default. */
    var logScale = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Source selection follows the capture format; stale queued frames are rejected. */
    fun setAgxApplied(agx: Boolean) {
        if (agxSelected == agx) return
        agxSelected = agx
        bins.forEach { it.fill(0) }
        invalidate()
    }

    fun update(histogram: RgbHistogram) {
        if (histogram.agxApplied != agxSelected) return
        copyResampled(histogram.red, bins[RED])
        copyResampled(histogram.green, bins[GREEN])
        copyResampled(histogram.blue, bins[BLUE])
        invalidate()
    }

    // Sized to the bottom-left thirds cell (wrap_content in the layout hands
    // the root width down as AT_MOST), so the scope never covers a grid line.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val marginStart = (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        val w = ScopeStyle.slotWidthPx(parentWidth, marginStart, resources.displayMetrics.density)
        setMeasuredDimension(w, (w * ScopeStyle.ASPECT).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val right = width.toFloat()
        val bottom = height.toFloat()
        bounds.set(0f, 0f, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        // Inset plot: the rounded wall never clips the 0 % / 100 % bins.
        val plotLeft = ScopeStyle.PLOT_INSET_X_DP * dp
        val plotRight = right - plotLeft
        val plotTop = ScopeStyle.PLOT_INSET_Y_DP * dp
        val plotBottom = bottom - plotTop
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(0f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(0f)
        val max = bins.maxOf { channel -> channel.maxOrNull() ?: 0 }.coerceAtLeast(1)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, right, bottom, scrimPaint)
        // Data lives in a concentric rounded window: soft corners, no cut edges.
        plotClip.reset()
        plotRect.set(plotLeft, plotTop, plotRight, plotBottom)
        plotClip.addRoundRect(plotRect, plotRadius, plotRadius, Path.Direction.CW)
        canvas.clipPath(plotClip)
        for (channel in 0..2) {
            buildPaths(channel, plotLeft, plotBottom, plotWidth, plotHeight, max)
            fillPaint.color = FILL_COLORS[channel]
            canvas.drawPath(fillPaths[channel], fillPaint)
        }
        for (channel in 0..2) {
            linePaint.color = LINE_COLORS[channel]
            canvas.drawPath(linePaths[channel], linePaint)
        }
        drawClipMarker(canvas, plotTop, plotRight)
        canvas.restore()
        val halfStroke = borderPaint.strokeWidth / 2f
        bounds.set(halfStroke, halfStroke, right - halfStroke, bottom - halfStroke)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    /** Small red dot inside the top-right corner while highlights clip. */
    private fun drawClipMarker(canvas: Canvas, top: Float, right: Float) {
        if (bins.none { clipped(it, BIN_COUNT - 1) }) return
        val radius = 3f * dp
        clipPaint.color = ScopeStyle.CLIP_HIGH
        canvas.drawCircle(right - radius - 4f * dp, top + radius + 2f * dp, radius, clipPaint)
    }

    private fun clipped(channel: IntArray, bin: Int): Boolean {
        val total = channel.sum()
        return total > 0 && channel[bin] > total * ScopeStyle.CLIP_FRACTION
    }

    /**
     * Closed fill path and open top-edge trace for one channel. Linear or log
     * vertical scale ([logScale]), light 1-2-1 smoothing against single-bin
     * spikes, no edge taper: the extreme bins are where clipping shows.
     */
    private fun buildPaths(
        channel: Int,
        left: Float,
        bottom: Float,
        plotWidth: Float,
        plotHeight: Float,
        max: Int
    ) {
        val counts = bins[channel]
        val logMax = kotlin.math.ln(1f + max)
        fun level(index: Int): Float =
            if (logScale) kotlin.math.ln(1f + counts[index]) / logMax
            else counts[index].toFloat() / max
        val last = counts.size - 1
        for (index in counts.indices) {
            val smoothed = 0.25f * level(maxOf(index - 1, 0)) + 0.5f * level(index) +
                0.25f * level(minOf(index + 1, last))
            heights[index] = bottom - smoothed * plotHeight
        }
        val fill = fillPaths[channel]
        val line = linePaths[channel]
        fill.reset()
        line.reset()
        fun xAt(index: Int): Float = left + index * plotWidth / last.coerceAtLeast(1)
        fill.moveTo(xAt(0), bottom)
        for (index in 0..last) fill.lineTo(xAt(index), heights[index])
        fill.lineTo(xAt(last), bottom)
        fill.close()
        // Outline only where the channel has data: runs along the floor stay
        // undrawn instead of stacking into a coloured baseline.
        var drawing = false
        for (index in 0..last) {
            val empty = heights[index] >= bottom - 0.5f
            val neighbourHasData = (index > 0 && heights[index - 1] < bottom - 0.5f) ||
                (index < last && heights[index + 1] < bottom - 0.5f)
            if (empty && !neighbourHasData) {
                drawing = false
                continue
            }
            if (drawing) line.lineTo(xAt(index), heights[index]) else line.moveTo(xAt(index), heights[index])
            drawing = true
        }
    }

    private fun copyResampled(source: IntArray, target: IntArray) {
        target.fill(0)
        source.forEachIndexed { index, value ->
            val targetIndex = index * (target.size - 1) / (source.size - 1).coerceAtLeast(1)
            target[targetIndex] += value
        }
    }

    private companion object {
        const val BIN_COUNT = 64
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        val FILL_COLORS = intArrayOf(
            ScopeStyle.RED.withAlpha(ScopeStyle.HISTOGRAM_FILL_ALPHA),
            ScopeStyle.GREEN.withAlpha(ScopeStyle.HISTOGRAM_FILL_ALPHA),
            ScopeStyle.BLUE.withAlpha(ScopeStyle.HISTOGRAM_FILL_ALPHA)
        )
        val LINE_COLORS = intArrayOf(
            ScopeStyle.RED.withAlpha(ScopeStyle.HISTOGRAM_LINE_ALPHA),
            ScopeStyle.GREEN.withAlpha(ScopeStyle.HISTOGRAM_LINE_ALPHA),
            ScopeStyle.BLUE.withAlpha(ScopeStyle.HISTOGRAM_LINE_ALPHA)
        )

        private fun Int.withAlpha(alpha: Int): Int = Color.argb(
            alpha, Color.red(this), Color.green(this), Color.blue(this)
        )
    }
}
