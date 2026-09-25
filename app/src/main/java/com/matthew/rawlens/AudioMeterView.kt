// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Blackmagic-style mono audio meter for the video HUD: horizontal bar with
 * dB ticks (-40..0), green/yellow/red segments and a peak-hold line.
 * Fed with [AudioPcmRecorder.lastPeak] at ~10 Hz; hold decays on draw so a
 * stale source fades instead of freezing. Stays fixed-orientation (meters
 * don't rotate with the device — same as the reference hardware).
 */
class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density

    private val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        textSize = 8f * density
    }
    private val barPaint = Paint()
    private val holdPaint = Paint().apply { color = Color.WHITE; strokeWidth = 2f * density }

    @Volatile private var level = 0f
    private var hold = 0f

    /** 0..1 mono peak. Call on the UI thread (HUD meter tick). */
    fun setLevel(peak: Float) {
        level = peak.coerceIn(0f, 1f)
        hold = max(hold, level)
        invalidate()
    }

    fun reset() {
        level = 0f
        hold = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val pad = 4f * density
        val labelW = 22f * density
        val barX = pad + labelW
        val barW = (w - barX - pad).coerceAtLeast(1f)
        val barTop = h * 0.32f
        val barH = h * 0.36f
        // dB scale: -40dB maps to 0 width, 0dB to full.
        fun dbWidth(peak: Float): Float {
            if (peak <= 0f) return 0f
            val db = 20f * kotlin.math.log10(peak.coerceAtLeast(1e-4f))
            return (((db + 40f) / 40f).coerceIn(0f, 1f)) * barW
        }
        // Segmented bar: green to -12dB, yellow to -3dB, red above.
        val lvl = dbWidth(level)
        val y12 = dbWidth(0.251f)
        val y3 = dbWidth(0.708f)
        barPaint.color = Color.rgb(46, 204, 113)
        canvas.drawRect(barX, barTop, barX + minOf(lvl, y12), barTop + barH, barPaint)
        if (lvl > y12) {
            barPaint.color = Color.rgb(241, 196, 15)
            canvas.drawRect(barX + y12, barTop, barX + minOf(lvl, y3), barTop + barH, barPaint)
        }
        if (lvl > y3) {
            barPaint.color = Color.rgb(231, 76, 60)
            canvas.drawRect(barX + y3, barTop, barX + lvl, barTop + barH, barPaint)
        }
        // Ticks at -30/-20/-12/-6/-3/0 dB.
        for (db in intArrayOf(-30, -20, -12, -6, -3)) {
            val x = barX + ((db + 40f) / 40f) * barW
            canvas.drawLine(x, barTop, x, barTop + barH, tickPaint)
        }
        canvas.drawText("M", pad, barTop + barH, tickPaint)
        // Peak hold, decaying per draw.
        val holdX = barX + dbWidth(hold)
        canvas.drawLine(holdX, barTop - 2f * density, holdX, barTop + barH + 2f * density, holdPaint)
        hold *= 0.96f
        if (level > 0f || hold > 0.01f) postInvalidateOnAnimation()
    }
}
