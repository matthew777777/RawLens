// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Live RGB + luminance histogram, always sampled from the sensor mosaic. Linear RAW
 * matches the DNG data; the AgX-curved variant predicts the developed JPEG and is
 * selected automatically with a JPEG capture format. Channels are ADD-blended
 * darktable primaries; the white trace is Rec.709 luminance drawn on top (kept from
 * the original design, darktable draws no luma trace). Tapping is handled by the
 * host. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val bins = Array(4) { IntArray(BIN_COUNT) }
    private val paths = Array(4) { Path() }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = ScopeStyle.BACKGROUND
    }
    // darktable paints the ADD-blended channel group at 0.5 alpha (BlendMode.PLUS
    // is Android's saturating add). ADD is linear, so per-channel half alpha is
    // identical and order-independent.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        blendMode = BlendMode.PLUS
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        blendMode = BlendMode.PLUS
    }
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    // darktable histogram grid: dt_draw_grid(num=4) quarters under the data.
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ScopeStyle.GRID
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ScopeStyle.BORDER_WIDTH_DP * resources.displayMetrics.density
        color = ScopeStyle.BORDER.toInt()
    }
    private val clipPath = Path()
    private val bounds = RectF()
    private val cornerRadius = ScopeStyle.CORNER_RADIUS_DP * resources.displayMetrics.density
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
        // One piece: the view draws its own rounded surface full-bleed and clips
        // the graph to it, so there is no inner sharp box inside an outer shell.
        if (width <= 0 || height <= 0) return
        val left = 0f
        val top = 0f
        val right = width.toFloat()
        val bottom = height.toFloat()
        bounds.set(left, top, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        val max = bins.maxOf { channel -> channel.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val plotHeight = bottom - top
        val plotWidth = right - left
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(left, top, right, bottom, bgPaint)
        for (quarter in 1..3) {
            val x = left + quarter * plotWidth / 4
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val y = bottom - quarter * plotHeight / 4
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        for (channel in 0..2) {
            buildPath(channel, left, bottom, plotWidth, plotHeight, max)
            fillPaint.color = FILL_COLORS[channel]
            linePaint.color = LINE_COLORS[channel]
            canvas.drawPath(paths[channel], fillPaint)
            canvas.drawPath(paths[channel], linePaint)
        }
        buildPath(LUMINANCE, left, bottom, plotWidth, plotHeight, max)
        lumaPaint.color = LUMA_COLOR
        // Luminance redraws as an outline only: the RGB fills already carry the mass.
        canvas.drawPath(paths[LUMINANCE], lumaPaint)
        canvas.restore()
        val halfStroke = borderPaint.strokeWidth / 2f
        bounds.set(left + halfStroke, top + halfStroke, right - halfStroke, bottom - halfStroke)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    private fun buildPath(
        channel: Int,
        left: Float,
        bottom: Float,
        plotWidth: Float,
        plotHeight: Float,
        max: Int
    ) {
        val path = paths[channel]
        path.reset()
        path.moveTo(left, bottom)
        bins[channel].forEachIndexed { index, count ->
            val x = left + index * plotWidth / (BIN_COUNT - 1)
            val normalized = kotlin.math.sqrt(count.toFloat() / max)
            path.lineTo(x, bottom - normalized * plotHeight)
        }
        path.lineTo(left + plotWidth, bottom)
        path.close()
    }

    private fun copyResampled(source: IntArray, target: IntArray) {
        target.fill(0)
        source.forEachIndexed { index, value ->
            val targetIndex = index * (target.size - 1) / (source.size - 1).coerceAtLeast(1)
            target[targetIndex] += value
        }
    }

    private companion object {
        const val BIN_COUNT = 48
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        const val LUMINANCE = 3
        val FILL_COLORS = intArrayOf(
            ScopeStyle.RED.withGroupAlpha(),
            ScopeStyle.GREEN.withGroupAlpha(),
            ScopeStyle.BLUE.withGroupAlpha()
        )
        val LINE_COLORS = intArrayOf(
            ScopeStyle.RED.withLineAlpha(),
            ScopeStyle.GREEN.withLineAlpha(),
            ScopeStyle.BLUE.withLineAlpha()
        )
        const val LUMA_COLOR = 0xFFE8E8E8.toInt()

        private fun Int.withGroupAlpha(): Int = Color.argb(
            ScopeStyle.HISTOGRAM_GROUP_ALPHA,
            Color.red(this), Color.green(this), Color.blue(this)
        )

        private fun Int.withLineAlpha(): Int = Color.argb(
            217, Color.red(this), Color.green(this), Color.blue(this)
        )
    }
}
