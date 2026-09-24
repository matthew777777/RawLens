// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Live RGB + luminance histogram. Shows the processed preview (YUV) or the sensor
 * mosaic (RAW); the source is exposed via accessibility only so the graph stays
 * compact. The white trace is Rec.709 luminance drawn on top. Tapping is handled
 * by the host. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val bins = Array(4) { IntArray(BIN_COUNT) }
    private val paths = Array(4) { Path() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private var rawSourceSelected = true
    private var previewPixels = IntArray(0)

    /** Source selection is persistent until the user changes it, including capture gaps. */
    fun setSourceRaw(raw: Boolean) {
        if (rawSourceSelected == raw) return
        rawSourceSelected = raw
        bins.forEach { it.fill(0) }
        invalidate()
    }

    /** Reads synchronously; pass false when the caller owns a reusable bitmap. */
    fun update(bitmap: Bitmap?, recycleBitmap: Boolean = true) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return
        if (rawSourceSelected) {
            if (recycleBitmap) bitmap.recycle()
            return
        }
        bins.forEach { it.fill(0) }
        val count = Math.multiplyExact(bitmap.width, bitmap.height)
        if (previewPixels.size != count) previewPixels = IntArray(count)
        val pixels = previewPixels
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (color in pixels) {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            bins[RED][r * (BIN_COUNT - 1) / 255]++
            bins[GREEN][g * (BIN_COUNT - 1) / 255]++
            bins[BLUE][b * (BIN_COUNT - 1) / 255]++
            bins[LUMINANCE][((0.2126 * r + 0.7152 * g + 0.0722 * b) + 0.5).toInt()
                .coerceIn(0, 255) * (BIN_COUNT - 1) / 255]++
        }
        if (recycleBitmap) bitmap.recycle()
        invalidate()
    }

    fun update(histogram: RgbHistogram) {
        if (histogram.fromRaw != rawSourceSelected) return
        copyResampled(histogram.red, bins[RED])
        copyResampled(histogram.green, bins[GREEN])
        copyResampled(histogram.blue, bins[BLUE])
        copyResampled(histogram.luminance, bins[LUMINANCE])
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Stay inside the rounded background box: the drawable carries padding
        // (8dp sides, 6dp top/bottom) and the graph must not paint over it.
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat().coerceAtLeast(left)
        val bottom = (height - paddingBottom).toFloat().coerceAtLeast(top)
        if (right <= left || bottom <= top) return
        val max = bins.maxOf { channel -> channel.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val plotHeight = bottom - top
        val plotWidth = right - left
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for (channel in DRAW_ORDER) {
            val path = paths[channel]
            path.reset()
            path.moveTo(left, bottom)
            bins[channel].forEachIndexed { index, count ->
                val x = left + index * plotWidth / (BIN_COUNT - 1)
                val normalized = kotlin.math.sqrt(count.toFloat() / max)
                path.lineTo(x, bottom - normalized * plotHeight)
            }
            path.lineTo(right, bottom)
            path.close()
            fillPaint.color = FILL_COLORS[channel]
            linePaint.color = LINE_COLORS[channel]
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, linePaint)
        }
        canvas.restore()
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
        val DRAW_ORDER = intArrayOf(BLUE, RED, GREEN, LUMINANCE)
        val FILL_COLORS = intArrayOf(
            Color.argb(64, 255, 70, 70),
            Color.argb(64, 70, 255, 110),
            Color.argb(64, 70, 130, 255),
            Color.argb(64, 225, 225, 225)
        )
        val LINE_COLORS = intArrayOf(
            Color.rgb(255, 90, 90),
            Color.rgb(90, 255, 125),
            Color.rgb(90, 145, 255),
            Color.rgb(235, 235, 235)
        )
    }
}
