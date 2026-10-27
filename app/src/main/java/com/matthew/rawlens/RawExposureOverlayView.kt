// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** RAW measurements rendered as camera-style diagonal stripes or a filtered false-colour image. */
class RawExposureOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    internal var settings = RawExposureAssistSettings()
        set(value) { field = value; clear(); visibility = if (value.enabled) VISIBLE else GONE }
    internal var onMeterReading: ((RawSpotReading?, RawAverageReading?) -> Unit)? = null
    var contentTransform: RectF? = null
        set(value) { field = value; invalidate() }
    private var frame: RawExposureAssistFrame? = null
    private var minimumSampleMs = 0L
    private var bitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private var zebraShader: ComposeShader? = null
    private val density = resources.displayMetrics.density
    // Screen-space 45-degree stripes: independent of the RAW grid, zoom, orientation and resolution.
    private val stripes = LinearGradient(0f, 0f, 7f * density, 7f * density,
        intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE, Color.WHITE, Color.BLACK),
        floatArrayOf(0f, 0.47f, 0.5f, 0.97f, 1f), Shader.TileMode.REPEAT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stale = Runnable {
        val f = frame
        if (f == null || SystemClock.elapsedRealtime() - f.sampledAtElapsedMs >= STALE_MS) {
            onMeterReading?.invoke(null, null)
            contentDescription = "RAW assist waiting"
            invalidate()
        }
    }

    internal fun update(value: RawExposureAssistFrame?) {
        if (value == null) { clear(); return }
        if (value.sampledAtElapsedMs < minimumSampleMs) return
        frame = value
        if (value.colors != null && settings.overlay != RawExposureOverlayMode.OFF) {
            if (bitmap?.width != value.cols || bitmap?.height != value.rows) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(value.cols, value.rows, Bitmap.Config.ARGB_8888)
                bitmapShader = BitmapShader(bitmap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                // The RAW classifier supplies coverage; camera-style white/black stripes supply colour.
                zebraShader = ComposeShader(stripes, bitmapShader!!, PorterDuff.Mode.DST_IN)
            }
            bitmap!!.setPixels(value.colors, 0, value.cols, 0, 0, value.cols, value.rows)
        }
        onMeterReading?.invoke(if (settings.spotMeter) value.spot else null, if (settings.spotMeter) value.average else null)
        contentDescription = if (!settings.spotMeter) settings.overlay.label
            else if (settings.meterMode == RawExposureMeterMode.AVERAGE) RawAverageMeterText.format(value.average)
            else RawSpotMeterText.format(value.spot)
        removeCallbacks(stale)
        postDelayed(stale, (STALE_MS - (SystemClock.elapsedRealtime() - value.sampledAtElapsedMs)).coerceAtLeast(1))
        invalidate()
    }
    fun clear() {
        removeCallbacks(stale)
        frame = null; minimumSampleMs = SystemClock.elapsedRealtime()
        onMeterReading?.invoke(null, null)
        contentDescription = if (settings.enabled) "RAW assist waiting" else "RAW assist off"
        invalidate()
    }
    override fun onDetachedFromWindow() { removeCallbacks(stale); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        if (!settings.enabled || width <= 0 || height <= 0 || SystemClock.elapsedRealtime() - f.sampledAtElapsedMs >= STALE_MS) return
        var cover = contentTransform ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        val record = FocusPeakingGeometry.monitorRects(f.frameWidth, f.frameHeight, f.rotation, f.mirrored,
            f.monitorCrop, width, height)
        val save = canvas.save()
        if (record != null) {
            cover = RectF(record.first[0], record.first[1], record.first[2], record.first[3])
            val c = record.second; canvas.clipRect(c[0], c[1], c[2], c[3])
        }
        fun point(u: Float, v: Float): Pair<Float, Float> {
            val p = FocusPeakingGeometry.viewPoint(u, v, f.rotation, f.mirrored)
            return cover.left + p.first * cover.width() to cover.top + p.second * cover.height()
        }
        if (settings.overlay != RawExposureOverlayMode.OFF && f.colors != null) bitmap?.let { b ->
            val a = point(0f, 0f); val x = point(1f, 0f); val y = point(0f, 1f)
            val matrix = Matrix()
            matrix.setPolyToPoly(floatArrayOf(0f, 0f, b.width.toFloat(), 0f, 0f, b.height.toFloat()), 0,
                floatArrayOf(a.first, a.second, x.first, x.second, y.first, y.second), 0, 3)
            bitmapShader?.setLocalMatrix(matrix)
            paint.shader = if (settings.overlay == RawExposureOverlayMode.ZEBRAS) zebraShader else bitmapShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        if (settings.spotMeter) f.spot?.let { spot ->
            val a = point(spot.left, spot.top); val b = point(spot.right, spot.bottom)
            val rect = RectF(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
            line.color = Color.BLACK; line.strokeWidth = 3f * density; canvas.drawRect(rect, line)
            line.color = Color.WHITE; line.strokeWidth = density; canvas.drawRect(rect, line)
        }
        canvas.restoreToCount(save)
    }
    private companion object { const val STALE_MS = 1500L }
}
