// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Gcam-style virtual horizon: a center line that stays world-horizontal in
 * every physical orientation. The moving segments rotate by the live tilt; a
 * short fixed witness at the center sits on the nearest 90° quadrant. Level
 * snaps the moving line onto the witness and turns both accent.
 *
 * Portrait-locked canvas: in landscape the line draws vertical, which the user
 * holding the rotated phone sees as horizontal. Hidden while the device points
 * far enough up/down that roll is noise ([VirtualHorizon.State.isFlat]).
 */
class LevelOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var state: VirtualHorizon.State? = null
        private set

    private val density = resources.displayMetrics.density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(140, 0, 0, 0)
        strokeWidth = 3f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Virtual horizon off"
    }

    fun setState(next: VirtualHorizon.State) {
        val previous = state
        state = next
        if (previous == null || previous.isLevel != next.isLevel ||
            previous.isFlat != next.isFlat ||
            abs(next.tiltDegrees - previous.tiltDegrees) >= 0.05f
        ) {
            contentDescription = describe(next)
            invalidate()
        }
    }

    fun setFlat() {
        state = null
        contentDescription = "Virtual horizon flat, tilt unavailable"
        invalidate()
    }

    private fun describe(state: VirtualHorizon.State): String {
        if (state.isFlat) return "Virtual horizon flat, tilt unavailable"
        if (state.isLevel) return "Virtual horizon, level"
        val degrees = abs(state.errorDegrees)
        return String.format(java.util.Locale.US, "Virtual horizon, %.1f degrees off level", degrees)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = state ?: return
        if (current.isFlat) return
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val lineLength = min(width, height) * 0.30f
        val gapHalf = 14f * density
        val witnessHalf = 6f * density
        val half = lineLength / 2f
        if (half <= gapHalf) return

        val drawDegrees = current.drawDegrees()
        val snapped = VirtualHorizon.wrap180(
            kotlin.math.round(current.tiltDegrees / 90f) * 90f
        )
        linePaint.color = if (current.isLevel) LEVEL_COLOR else TILT_COLOR

        // Moving horizon: two segments with a center gap, rotated by live tilt.
        canvas.save()
        canvas.rotate(drawDegrees, cx, cy)
        drawSegment(canvas, cx - half, cy, cx - gapHalf, cy)
        drawSegment(canvas, cx + gapHalf, cy, cx + half, cy)
        canvas.restore()

        // Fixed witness: short center tick on the quadrant axis. Aligned with
        // the moving line exactly when level.
        canvas.save()
        canvas.rotate(snapped, cx, cy)
        drawSegment(canvas, cx - witnessHalf, cy, cx + witnessHalf, cy)
        canvas.restore()
    }

    private fun drawSegment(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.drawLine(x0, y0, x1, y1, outlinePaint)
        canvas.drawLine(x0, y0, x1, y1, linePaint)
    }

    private companion object {
        const val LEVEL_COLOR = 0xFFD6FF33.toInt()
        const val TILT_COLOR = 0xE6FFFFFF.toInt()
    }
}
