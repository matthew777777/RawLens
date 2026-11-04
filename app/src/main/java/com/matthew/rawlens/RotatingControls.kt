// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView

/** Rotates only foreground content; measured bounds and backgrounds stay fixed. */
interface RotatingContent {
    var contentRotation: Float
}

class RotatingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr), RotatingContent {
    var fitRotatedText = false

    override var contentRotation = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.rotate(contentRotation, width / 2f, height / 2f)
        if (fitRotatedText) {
            val quarterTurn = kotlin.math.abs(kotlin.math.sin(Math.toRadians(contentRotation.toDouble()))) > 0.5
            val inset = 6f * resources.displayMetrics.density
            val availableWidth = (if (quarterTurn) height else width) - inset * 2
            val availableHeight = (if (quarterTurn) width else height) - inset * 2
            paint.color = currentTextColor
            drawFittedText(canvas, text.toString().replace("Auto · ~", "Auto\n~"), paint, width / 2f, height / 2f,
                availableWidth, availableHeight)
        } else {
            super.onDraw(canvas)
        }
        canvas.restoreToCount(checkpoint)
    }
}

class RotatingImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.imageButtonStyle
) : ImageButton(context, attrs, defStyleAttr), RotatingContent {
    override var contentRotation = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.rotate(contentRotation, width / 2f, height / 2f)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}

class RotatingButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr), RotatingContent {
    override var contentRotation = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.rotate(contentRotation, width / 2f, height / 2f)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}

/** Lay out within the visible bounds after rotation, preserving the full value. */
internal fun drawFittedText(
    canvas: Canvas, text: String, source: android.graphics.Paint,
    centerX: Float, centerY: Float, width: Float, height: Float
) {
    if (width < 1f || height < 1f) return
    val paint = android.text.TextPaint(source).apply { textAlign = android.graphics.Paint.Align.LEFT }
    var layout: android.text.StaticLayout
    do {
        layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val fits = layout.height <= height && (0 until layout.lineCount).all {
            layout.getLineWidth(it) <= width
        }
        if (fits || paint.textSize <= 1f) break
        paint.textSize *= 0.95f
    } while (true)
    val checkpoint = canvas.save()
    canvas.translate(centerX - width.toInt() / 2f, centerY - layout.height / 2f)
    layout.draw(canvas)
    canvas.restoreToCount(checkpoint)
}
