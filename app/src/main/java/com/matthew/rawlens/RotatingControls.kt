// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
    var exposureReadout = false
    var exposureAutomatic = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override var contentRotation = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.rotate(contentRotation, width / 2f, height / 2f)
        if (exposureReadout) {
            val quarterTurn = kotlin.math.abs(kotlin.math.sin(Math.toRadians(contentRotation.toDouble()))) > 0.5
            val inset = 5f * resources.displayMetrics.density
            val availableWidth = (if (quarterTurn) height else width) - inset * 2
            val availableHeight = (if (quarterTurn) width else height) - inset * 2
            val content = text.toString()
            val label = content.substringBefore('\n').let {
                if (it.startsWith("S")) "SS" + it.drop(1) else it
            }
            val rawValue = content.substringAfter('\n', "")
            val estimatedWb = rawValue.startsWith("Auto · ~") && label == "WB"
            val unavailable = rawValue == "--" || rawValue == "—"
            val value = when {
                unavailable -> "—"
                estimatedWb -> rawValue.removePrefix("Auto · ")
                else -> rawValue
            }
            val automatic = (exposureAutomatic || estimatedWb) && !label.endsWith("🔒")
            val display = if (automatic && !value.equals("Auto", ignoreCase = true))
                "$label · A\n$value" else "$label\n$value"
            val readout = SpannableString(display)
            val labelEnd = display.indexOf('\n')
            if (labelEnd > 0) {
                readout.setSpan(RelativeSizeSpan(0.72f), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                readout.setSpan(ForegroundColorSpan(context.getColor(R.color.text_secondary)),
                    0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            paint.color = context.getColor(if (unavailable) R.color.text_secondary else R.color.text_primary)
            drawFittedText(canvas, readout, paint, width / 2f, height / 2f, availableWidth, availableHeight, maxLines = 2)
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
    canvas: Canvas, text: CharSequence, source: android.graphics.Paint,
    centerX: Float, centerY: Float, width: Float, height: Float, maxLines: Int = Int.MAX_VALUE
) {
    if (width < 1f || height < 1f) return
    val paint = android.text.TextPaint(source).apply { textAlign = android.graphics.Paint.Align.LEFT }
    var layout: android.text.StaticLayout
    do {
        layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val fits = layout.lineCount <= maxLines && layout.height <= height && (0 until layout.lineCount).all {
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
