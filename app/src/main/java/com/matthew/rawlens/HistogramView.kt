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
import android.os.SystemClock

/** Live RGB + luminance histogram. Shows the processed preview (YUV) or the sensor
 * mosaic (RAW); the source label tracks whichever updated the bins last. The white
 * trace is Rec.709 luminance drawn on top. Tapping is handled by the host. */
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
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 7f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private var lastRawUpdateMillis = 0L
    private var sourceLabel = PREVIEW_LABEL

    /** Allow the next preview bitmap to replace RAW immediately after leaving/falling out of ZSL. */
    fun allowPreviewImmediately() {
        lastRawUpdateMillis = 0L
        sourceLabel = PREVIEW_LABEL
        invalidate()
    }

    fun update(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return
        if (lastRawUpdateMillis != 0L &&
            SystemClock.uptimeMillis() - lastRawUpdateMillis < RAW_HOLD_MILLIS
        ) {
            bitmap.recycle()
            return
        }
        bins.forEach { it.fill(0) }
        val pixels = IntArray(bitmap.width * bitmap.height)
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
        sourceLabel = PREVIEW_LABEL
        bitmap.recycle()
        invalidate()
    }

    fun update(histogram: RgbHistogram) {
        copyResampled(histogram.red, bins[RED])
        copyResampled(histogram.green, bins[GREEN])
        copyResampled(histogram.blue, bins[BLUE])
        copyResampled(histogram.luminance, bins[LUMINANCE])
        if (histogram.fromRaw) {
            lastRawUpdateMillis = SystemClock.uptimeMillis()
            sourceLabel = RAW_LABEL
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val max = bins.maxOf { channel -> channel.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val baseline = height.toFloat()
        for (channel in DRAW_ORDER) {
            val path = paths[channel]
            path.reset()
            path.moveTo(0f, baseline)
            bins[channel].forEachIndexed { index, count ->
                val x = index * width.toFloat() / (BIN_COUNT - 1)
                val normalized = kotlin.math.sqrt(count.toFloat() / max)
                path.lineTo(x, baseline - normalized * height)
            }
            path.lineTo(width.toFloat(), baseline)
            path.close()
            fillPaint.color = FILL_COLORS[channel]
            linePaint.color = LINE_COLORS[channel]
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, linePaint)
        }
        canvas.drawText(sourceLabel, width - dp(5f), dp(10f), sourcePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun copyResampled(source: IntArray, target: IntArray) {
        target.fill(0)
        source.forEachIndexed { index, value ->
            val targetIndex = index * (target.size - 1) / (source.size - 1).coerceAtLeast(1)
            target[targetIndex] += value
        }
    }

    private companion object {
        const val BIN_COUNT = 48
        const val RAW_HOLD_MILLIS = 2_000L
        const val RAW_LABEL = "RAW SENSOR"
        const val PREVIEW_LABEL = "PREVIEW • PROCESSED YUV"
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
