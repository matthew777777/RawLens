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

/** Minimal viewfinder guides which stay independent from camera metering gestures. */
class CameraGuideOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var gridEnabled = true
        set(value) {
            field = value
            invalidate()
        }

    var levelEnabled = false
        set(value) {
            field = value
            invalidate()
        }

    private var levelRollDegrees = 0f
    private var levelPitchDegrees = 0f

    private val density = resources.displayMetrics.density
    private var contentTop = 0f
    private var contentEnd = 0f
    private var contentBottom = 0f
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(92, 255, 255, 255)
        strokeWidth = density
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 226, 230)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val levelAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** Updates the horizon using roll and fore/aft tilt in screen coordinates. */
    fun updateLevel(rollDegrees: Float, pitchDegrees: Float) {
        if (abs(levelRollDegrees - rollDegrees) < 0.05f &&
            abs(levelPitchDegrees - pitchDegrees) < 0.05f
        ) return
        levelRollDegrees = rollDegrees.coerceIn(-45f, 45f)
        levelPitchDegrees = pitchDegrees.coerceIn(-35f, 35f)
        if (levelEnabled) postInvalidateOnAnimation()
    }

    fun setContentInsets(top: Int, end: Int, bottom: Int) {
        contentTop = top.toFloat()
        contentEnd = end.toFloat()
        contentBottom = bottom.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridEnabled) drawThirds(canvas)
        if (levelEnabled) drawLevel(canvas)
    }

    private fun drawThirds(canvas: Canvas) {
        val right = (width - contentEnd).coerceAtLeast(0f)
        val bottom = (height - contentBottom).coerceAtLeast(contentTop)
        val thirdX = right / 3f
        val thirdY = (bottom - contentTop) / 3f
        canvas.drawLine(thirdX, contentTop, thirdX, bottom, gridPaint)
        canvas.drawLine(thirdX * 2f, contentTop, thirdX * 2f, bottom, gridPaint)
        canvas.drawLine(0f, contentTop + thirdY, right, contentTop + thirdY, gridPaint)
        canvas.drawLine(0f, contentTop + thirdY * 2f, right, contentTop + thirdY * 2f, gridPaint)
    }

    private fun drawLevel(canvas: Canvas) {
        val centerX = (width - contentEnd) / 2f
        val centerY = contentTop + (height - contentTop - contentBottom) / 2f
        val lineHalf = minOf(112f * density, (width - contentEnd) * 0.29f)
        val targetHalf = 36f * density
        val targetGap = 22f * density
        val reticleHalf = 7f * density
        val pitchOffset = (levelPitchDegrees.coerceIn(-30f, 30f) / 30f) * 38f * density
        val isLevel = abs(levelRollDegrees) <= 1.5f && abs(levelPitchDegrees) <= 1.5f
        levelAccentPaint.color = if (isLevel) Color.rgb(214, 255, 51) else Color.WHITE

        // Fixed marks stay aligned with the preview; the virtual horizon follows the device.
        canvas.drawLine(centerX - lineHalf, centerY, centerX - targetGap, centerY, levelPaint)
        canvas.drawLine(centerX + targetGap, centerY, centerX + lineHalf, centerY, levelPaint)
        canvas.drawLine(centerX - reticleHalf, centerY, centerX + reticleHalf, centerY, levelPaint)
        canvas.drawLine(centerX, centerY - reticleHalf, centerX, centerY + reticleHalf, levelPaint)
        canvas.save()
        // PhotonCamera rotates the rendered horizon opposite to the measured device roll.
        canvas.rotate(-levelRollDegrees, centerX, centerY + pitchOffset)
        canvas.drawLine(
            centerX - lineHalf,
            centerY + pitchOffset,
            centerX + lineHalf,
            centerY + pitchOffset,
            levelAccentPaint
        )
        val pitchGuide = abs(pitchOffset)
        if (pitchGuide > 2f * density) {
            canvas.drawLine(centerX - targetHalf, centerY - pitchGuide, centerX + targetHalf, centerY - pitchGuide, levelPaint)
            canvas.drawLine(centerX - targetHalf, centerY + pitchGuide, centerX + targetHalf, centerY + pitchGuide, levelPaint)
        }
        canvas.restore()
    }
}
