// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import java.io.File

/**
 * debug.rawlens.portrait_dump=1: writes the network thumbnail, raw depth,
 * person mask and fused disparity (sensor orientation) as PNGs to
 * Android/data/<pkg>/files/portrait/ (adb pull-able).
 */
internal object PortraitDebugDump {
    @Volatile var dir: File? = null

    fun write(rgb: FloatArray, depth: FloatArray, person: FloatArray?, fused: FloatArray, n: Int) {
        val out = dir ?: return
        runCatching {
            out.mkdirs()
            val stamp = System.currentTimeMillis()
            png(File(out, "${stamp}_thumb.png"), n) { i ->
                rgb8(rgb[i * 3]) shl 16 or (rgb8(rgb[i * 3 + 1]) shl 8) or rgb8(rgb[i * 3 + 2])
            }
            val lo = depth.min(); val span = (depth.max() - lo).coerceAtLeast(1e-6f)
            png(File(out, "${stamp}_depth.png"), n) { i -> grey((depth[i] - lo) / span) }
            person?.let { p -> png(File(out, "${stamp}_person.png"), n) { i -> grey(p[i]) } }
            png(File(out, "${stamp}_fused.png"), n) { i -> grey(fused[i]) }
            Log.i("RawLensPortrait", "Portrait debug maps written to $out ($stamp)")
        }.onFailure { Log.w("RawLensPortrait", "Portrait debug dump failed", it) }
    }

    private fun rgb8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    private fun grey(v: Float) = rgb8(v).let { it shl 16 or (it shl 8) or it }

    private inline fun png(file: File, n: Int, rgb: (Int) -> Int) {
        val px = IntArray(n * n) { 0xff shl 24 or rgb(it) }
        val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}
