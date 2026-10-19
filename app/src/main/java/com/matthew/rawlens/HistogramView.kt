// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Live RGB + luminance histogram, always sampled from the sensor mosaic. Linear RAW
 * matches the DNG data; the AgX-curved variant predicts the developed JPEG and is
 * selected automatically with a JPEG capture format. The scope sits on a
 * near-opaque dark scrim with no grid — softly filled SRC_OVER curves under
 * full-strength rounded traces, plus a white Rec.709 luminance outline.
 * Tapping is handled by the host. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val bins = Array(4) { IntArray(BIN_COUNT) }
    private val heights = FloatArray(BIN_COUNT)
    private val fillPaths = Array(4) { Path() }
    private val linePaths = Array(4) { Path() }
    private val scrimPaint = Paint().apply {
        style = Paint.Style.FILL
        color = ScopeStyle.SCRIM
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    // Hairline anchoring the data floor; the only chrome besides the border.
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ScopeStyle.BASELINE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ScopeStyle.BORDER_WIDTH_DP * resources.displayMetrics.density
        color = ScopeStyle.BORDER.toInt()
    }
    private val clipPath = Path()
    private val bounds = RectF()
    private val cornerRadius = ScopeStyle.CORNER_RADIUS_DP * resources.displayMetrics.density
    private val insetH = 4f * resources.displayMetrics.density
    private val insetTop = 5f * resources.displayMetrics.density
    private val insetBottom = 3f * resources.displayMetrics.density
    private var agxSelected = false

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
        copyResampled(histogram.luminance, bins[LUMINANCE])
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // One piece: the view draws its own rounded scrim full-bleed and clips
        // the curves to it, so there is no inner sharp box inside an outer shell.
        if (width <= 0 || height <= 0) return
        val left = 0f
        val top = 0f
        val right = width.toFloat()
        val bottom = height.toFloat()
        bounds.set(left, top, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        val max = bins.maxOf { channel -> channel.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val plotLeft = left + insetH
        val plotRight = right - insetH
        val plotTop = top + insetTop
        val plotBottom = bottom - insetBottom
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(0f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(0f)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(left, top, right, bottom, scrimPaint)
        for (channel in 0..2) {
            buildSmoothPaths(channel, plotLeft, plotBottom, plotWidth, plotHeight, max)
            fillPaint.color = FILL_COLORS[channel]
            linePaint.color = LINE_COLORS[channel]
            canvas.drawPath(fillPaths[channel], fillPaint)
            canvas.drawPath(linePaths[channel], linePaint)
        }
        buildSmoothPaths(LUMINANCE, plotLeft, plotBottom, plotWidth, plotHeight, max)
        lumaPaint.color = LUMA_COLOR
        // Luminance redraws as an outline only: the RGB fills already carry the mass.
        canvas.drawPath(linePaths[LUMINANCE], lumaPaint)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, baselinePaint)
        canvas.restore()
        val halfStroke = borderPaint.strokeWidth / 2f
        bounds.set(left + halfStroke, top + halfStroke, right - halfStroke, bottom - halfStroke)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    /**
     * Builds a closed soft-fill path and an open smoothly-curved trace path.
     * Bin mass is sqrt-normalized, lightly smoothed to kill single-bin spikes,
     * and joined with midpoint quadratics so the trace reads as a curve.
     */
    private fun buildSmoothPaths(
        channel: Int,
        left: Float,
        bottom: Float,
        plotWidth: Float,
        plotHeight: Float,
        max: Int
    ) {
        val counts = bins[channel]
        for (index in counts.indices) {
            val raw = kotlin.math.sqrt(counts[index].toFloat() / max)
            val previous = if (index > 0) {
                kotlin.math.sqrt(counts[index - 1].toFloat() / max)
            } else {
                raw
            }
            val next = if (index < counts.size - 1) {
                kotlin.math.sqrt(counts[index + 1].toFloat() / max)
            } else {
                raw
            }
            heights[index] = bottom - (0.25f * previous + 0.5f * raw + 0.25f * next) * plotHeight
        }
        val fill = fillPaths[channel]
        val line = linePaths[channel]
        fill.reset()
        line.reset()
        val last = counts.size - 1
        fun xAt(index: Int): Float = left + index * plotWidth / last.coerceAtLeast(1)
        line.moveTo(xAt(0), heights[0])
        for (index in 1..last) {
            val midX = (xAt(index - 1) + xAt(index)) / 2f
            val midY = (heights[index - 1] + heights[index]) / 2f
            line.quadTo(xAt(index - 1), heights[index - 1], midX, midY)
        }
        line.lineTo(xAt(last), heights[last])
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
        const val LUMINANCE = 3
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
        const val LUMA_COLOR = 0xFFF5F5F5.toInt()

        private fun Int.withAlpha(alpha: Int): Int = Color.argb(
            alpha, Color.red(this), Color.green(this), Color.blue(this)
        )
    }
}
