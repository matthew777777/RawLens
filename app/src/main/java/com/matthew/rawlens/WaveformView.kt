// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Live RGB waveform parade, always sampled from the sensor mosaic: horizontal position
 * follows the frame columns, vertical position is level (black bottom, white top).
 * Linear RAW matches the DNG data; the AgX-curved variant predicts the developed JPEG
 * and is selected automatically with a JPEG capture format. Tapping is handled by
 * the host. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = Array(3) { IntArray(COLUMNS * LEVELS) }
    private var raster = Bitmap.createBitmap(COLUMNS, LEVELS, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(COLUMNS * LEVELS)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // darktable waveform graticule (dt_draw_waveform_lines): subdivisions under the
    // data with emphasized middle and near-white lines, dashed. darktable uses ninths
    // with white at 8/9 height for HDR headroom; scope data here caps at 1.0 (the top
    // edge), so eighths hit 0.5 exactly and the near-white line sits at 0.875.
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ScopeStyle.GRID
    }
    private val gridMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ScopeStyle.GRID
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val gridWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ScopeStyle.GRID
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
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
        // darktable area normalization: each channel scales against its expected
        // per-column samples, so thin traces survive hot peaks elsewhere.
        val totals = IntArray(3) { channel -> density[channel].sum() }
        val scales = FloatArray(3) { channel ->
            ScopeStyle.areaScale(LEVELS, COLUMNS, totals[channel])
        }
        for (column in 0 until COLUMNS) {
            for (level in 0 until LEVELS) {
                val cell = column * LEVELS + level
                // Bitmap row 0 is the top: level 0 (black) sits at the bottom.
                val pixel = column + (LEVELS - 1 - level) * COLUMNS
                pixels[pixel] = ScopeStyle.compositeWaveformPixel(
                    ScopeStyle.hlgOetf((density[RED][cell] * scales[RED]).coerceAtMost(1f)),
                    ScopeStyle.hlgOetf((density[GREEN][cell] * scales[GREEN]).coerceAtMost(1f)),
                    ScopeStyle.hlgOetf((density[BLUE][cell] * scales[BLUE]).coerceAtMost(1f))
                )
            }
        }
        raster.setPixels(pixels, 0, COLUMNS, 0, 0, COLUMNS, LEVELS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Same padded plot box as the histogram so the two scopes swap seamlessly.
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat().coerceAtLeast(left)
        val bottom = (height - paddingBottom).toFloat().coerceAtLeast(top)
        if (right <= left || bottom <= top) return
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        // The raster carries the darktable background, composited in software. The
        // opaque raster covers an under-grid, so the graticule draws over it (1px
        // dark lines read the same at this size).
        canvas.drawBitmap(raster, null, android.graphics.RectF(left, top, right, bottom), bitmapPaint)
        for (k in 1..7) {
            val y = bottom - k * (bottom - top) / 8
            val paint = when (k) {
                4 -> gridMidPaint
                7 -> gridWhitePaint
                else -> gridPaint
            }
            canvas.drawLine(left, y, right, y, paint)
        }
        canvas.restore()
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
