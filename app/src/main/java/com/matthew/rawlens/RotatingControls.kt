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
    override var contentRotation = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.rotate(contentRotation, width / 2f, height / 2f)
        super.onDraw(canvas)
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
