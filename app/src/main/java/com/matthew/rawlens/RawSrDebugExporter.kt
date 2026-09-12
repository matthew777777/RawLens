// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

object RawSrDebugExporter {
    /** Residual: fixed [0,0.12] white scale; confidence: exact black/white zero/one mask. */
    fun writeScalarPng(flow: FloatArray, columns: Int, rows: Int, destination: File,
                       confidence: Boolean) {
        require(flow.size == columns * rows * 4)
        destination.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until rows) for (x in 0 until columns) {
                val p = (y * columns + x) * 4
                val value = if (confidence) flow[p + 3] else flow[p + 2] / 0.12f
                val level = (value.coerceIn(0f, 1f) * 255f).toInt()
                bitmap.setPixel(x, y, Color.rgb(level, level, level))
            }
            FileOutputStream(destination).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** RGBA flow layout is dx, dy, residual, reliable. Intended only for debug capture fixtures. */
    fun writeFlowPng(flow: FloatArray, columns: Int, rows: Int, destination: File) {
        require(flow.size == columns * rows * 4)
        destination.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until rows) for (x in 0 until columns) {
                val p = (y * columns + x) * 4
                val valid = flow[p + 3] > 0.5f
                val magnitude = sqrt(flow[p] * flow[p] + flow[p + 1] * flow[p + 1])
                val hue = ((atan2(flow[p + 1], flow[p]) * 180f / Math.PI.toFloat()) + 360f) % 360f
                val value = if (valid) min(1f, 0.35f + magnitude / 8f) else 0.12f
                val saturation = if (valid) 0.9f else 0f
                bitmap.setPixel(x, y, Color.HSVToColor(floatArrayOf(hue, saturation, value)))
            }
            FileOutputStream(destination).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Generic quad-grid scalar field: CSV with one value per cell. */
    fun writeFieldCsv(values: FloatArray, columns: Int, rows: Int, name: String, destination: File) {
        require(values.size == columns * rows)
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { output ->
            output.appendLine("x,y,$name")
            for (y in 0 until rows) for (x in 0 until columns)
                output.append(x.toString()).append(',').append(y.toString()).append(',')
                    .appendLine(values[y * columns + x].toString())
        }
    }

    /** Generic quad-grid scalar field: white = max, black = 0. */
    fun writeFieldPng(values: FloatArray, columns: Int, rows: Int, max: Float, destination: File) {
        require(values.size == columns * rows && max > 0f)
        destination.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until rows) for (x in 0 until columns) {
                val level = ((values[y * columns + x] / max).coerceIn(0f, 1f) * 255f).toInt()
                bitmap.setPixel(x, y, Color.rgb(level, level, level))
            }
            FileOutputStream(destination).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** Rejection flags as integers: one row per cell, exact values. */
    fun writeFlagsCsv(flags: IntArray, columns: Int, rows: Int, destination: File) {
        require(flags.size == columns * rows)
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { output ->
            output.appendLine("x,y,flags")
            for (y in 0 until rows) for (x in 0 until columns)
                output.append(x.toString()).append(',').append(y.toString()).append(',')
                    .appendLine(flags[y * columns + x].toString())
        }
    }

    /** Rejection flags: black = accepted, hue-coded by first set flag bit. */
    fun writeFlagsPng(flags: IntArray, columns: Int, rows: Int, destination: File) {
        require(flags.size == columns * rows)
        destination.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until rows) for (x in 0 until columns) {
                val flag = flags[y * columns + x]
                val color = if (flag == 0) Color.rgb(0, 0, 0)
                else Color.HSVToColor(floatArrayOf((Integer.numberOfTrailingZeros(flag) * 47) % 360f, 0.9f, 1f))
                bitmap.setPixel(x, y, color)
            }
            FileOutputStream(destination).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    fun writeFlowCsv(flow: FloatArray, columns: Int, rows: Int, destination: File) {
        require(flow.size == columns * rows * 4)
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { output ->
            output.appendLine("tile_x,tile_y,dx,dy,residual,reliable")
            for (y in 0 until rows) for (x in 0 until columns) {
                val p = (y * columns + x) * 4
                output.append(x.toString()).append(',').append(y.toString()).append(',')
                    .append(flow[p].toString()).append(',').append(flow[p + 1].toString()).append(',')
                    .append(flow[p + 2].toString()).append(',').appendLine(flow[p + 3].toString())
            }
        }
    }
}
