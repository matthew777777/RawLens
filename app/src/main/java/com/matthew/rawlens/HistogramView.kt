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
 * Each channel's top edge gets a crisp outline, and a channel piling up in its
 * extreme bin lights a clip bar on that wall (blue = crushed, red = blown).
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
    private val clipPaint = Paint().apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ScopeStyle.BORDER_WIDTH_DP * dp
        color = ScopeStyle.BORDER.toInt()
    }
    private val clipPath = Path()
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
        for (channel in 0..2) {
            buildPaths(channel, plotLeft, plotBottom, plotWidth, plotHeight, max)
            fillPaint.color = FILL_COLORS[channel]
            canvas.drawPath(fillPaths[channel], fillPaint)
        }
        for (channel in 0..2) {
            linePaint.color = LINE_COLORS[channel]
            canvas.drawPath(linePaths[channel], linePaint)
        }
        drawClipBars(canvas, plotTop, plotBottom, right)
        canvas.restore()
        val halfStroke = borderPaint.strokeWidth / 2f
        bounds.set(halfStroke, halfStroke, right - halfStroke, bottom - halfStroke)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    /** Full-height bar on a wall when any channel piles up in that extreme bin. */
    private fun drawClipBars(canvas: Canvas, top: Float, bottom: Float, right: Float) {
        val barWidth = 3f * dp
        if (bins.any { clipped(it, 0) }) {
            clipPaint.color = ScopeStyle.CLIP_LOW
            canvas.drawRect(0f, top, barWidth, bottom, clipPaint)
        }
        if (bins.any { clipped(it, BIN_COUNT - 1) }) {
            clipPaint.color = ScopeStyle.CLIP_HIGH
            canvas.drawRect(right - barWidth, top, right, bottom, clipPaint)
        }
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
        line.moveTo(xAt(0), heights[0])
        for (index in 1..last) line.lineTo(xAt(index), heights[index])
        fill.set(line)
        fill.lineTo(xAt(last), bottom)
        fill.lineTo(xAt(0), bottom)
        fill.close()
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
