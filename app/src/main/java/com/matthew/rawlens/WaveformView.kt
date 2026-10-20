// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Live RGB waveform parade, always sampled from the sensor mosaic: horizontal position
 * follows the frame columns, vertical position is level (black bottom, white top).
 * Linear RAW matches the DNG data; the AgX-curved variant predicts the developed JPEG
 * and is selected automatically with a JPEG capture format. The density raster
 * sits on a near-opaque dark scrim with no graticule — just a hairline
 * black-level baseline. Tapping is handled by the host. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = Array(3) { IntArray(COLUMNS * LEVELS) }
    private var raster = Bitmap.createBitmap(COLUMNS, LEVELS, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(COLUMNS * LEVELS)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint().apply {
        style = Paint.Style.FILL
        color = ScopeStyle.SCRIM
    }
    // Hairline black-level anchor; the only chrome besides the border.
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
    private val plotRect = RectF()
    private val cornerRadius = ScopeStyle.CORNER_RADIUS_DP * resources.displayMetrics.density
    private var agxSelected = false

    /** Source selection follows the capture format; stale queued frames are rejected. */
    fun setAgxApplied(agx: Boolean) {
        if (agxSelected == agx) return
        agxSelected = agx
        density.forEach { it.fill(0) }
        raster.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun update(waveform: RgbWaveform) {
        if (waveform.agxApplied != agxSelected) return
        copyResampled(waveform.red, waveform.columns, waveform.levels, density[RED])
        copyResampled(waveform.green, waveform.columns, waveform.levels, density[GREEN])
        copyResampled(waveform.blue, waveform.columns, waveform.levels, density[BLUE])
        composeRaster()
        invalidate()
    }

    private fun composeRaster() {
        // Area normalization: each channel scales against its expected per-column
        // samples, so thin traces survive hot peaks elsewhere. Empty cells stay
        // transparent; the scrim beneath shows through.
        val totals = IntArray(3) { channel -> density[channel].sum() }
        val scales = FloatArray(3) { channel ->
            ScopeStyle.areaScale(LEVELS, COLUMNS, totals[channel])
        }
        for (column in 0 until COLUMNS) {
            for (level in 0 until LEVELS) {
                val cell = column * LEVELS + level
                // Bitmap row 0 is the top: level 0 (black) sits at the bottom.
                val pixel = column + (LEVELS - 1 - level) * COLUMNS
                // Rounded edges: dissolve border cells so dense edge data never
                // draws a hard frame against the pill wall.
                val lit = ScopeStyle.compositeWaveformPixel(
                    ScopeStyle.hlgOetf((density[RED][cell] * scales[RED]).coerceAtMost(1f)),
                    ScopeStyle.hlgOetf((density[GREEN][cell] * scales[GREEN]).coerceAtMost(1f)),
                    ScopeStyle.hlgOetf((density[BLUE][cell] * scales[BLUE]).coerceAtMost(1f))
                )
                pixels[pixel] = ScopeStyle.applyEdgeFade(
                    lit, ScopeStyle.rasterEdgeFade(column, level, COLUMNS, LEVELS)
                )
            }
        }
        raster.setPixels(pixels, 0, COLUMNS, 0, 0, COLUMNS, LEVELS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // One piece: same rounded full-bleed scrim as the histogram so the two
        // scopes swap seamlessly with no inner sharp box inside an outer shell.
        if (width <= 0 || height <= 0) return
        val left = 0f
        val top = 0f
        val right = width.toFloat()
        val bottom = height.toFloat()
        bounds.set(left, top, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(left, top, right, bottom, scrimPaint)
        // Data floor rides 1dp above the pill wall: no dead band under the
        // raster, just enough clearance to keep the hairline from clipping.
        // Full-bleed: the raster spans the whole pill and the rounded clip
        // wraps it — no inner plot box, no margins inside the scope.
        plotRect.set(left, top, right, bottom)
        canvas.drawBitmap(raster, null, plotRect, bitmapPaint)
        canvas.drawLine(
            plotRect.left, plotRect.bottom, plotRect.right, plotRect.bottom, baselinePaint
        )
        canvas.restore()
        val halfStroke = borderPaint.strokeWidth / 2f
        bounds.set(left + halfStroke, top + halfStroke, right - halfStroke, bottom - halfStroke)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    private fun copyResampled(
        source: IntArray,
        sourceColumns: Int,
        sourceLevels: Int,
        target: IntArray
    ) {
        target.fill(0)
        if (sourceColumns == COLUMNS && sourceLevels == LEVELS && source.size == target.size) {
            source.copyInto(target)
            return
        }
        for (column in 0 until sourceColumns) for (level in 0 until sourceLevels) {
            val targetColumn = column * (COLUMNS - 1) / (sourceColumns - 1).coerceAtLeast(1)
            val targetLevel = level * (LEVELS - 1) / (sourceLevels - 1).coerceAtLeast(1)
            target[targetColumn * LEVELS + targetLevel] += source[column * sourceLevels + level]
        }
    }

    private companion object {
        const val COLUMNS = RawWaveformSampler.COLUMNS
        const val LEVELS = RawWaveformSampler.LEVELS
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
    }
}
