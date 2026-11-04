package com.rawlens.srpipeline.stages

import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Colour-difference accumulation (checklist M / Z1, C15): the merge zipper.
 *
 * Root cause (C13a/C13b, mfsr-wronski-2.md): Wronski's Eq. 1 accumulates R
 * and B independently from their 2 px Bayer lattices; along edges the kernel
 * is narrower than that lattice, so R/B become a nearest-sample pick that
 * alternates by Bayer site: dots/squares alternating dark/bright. Widening
 * the kernel does not fix it (measured). Demosaicking practice does:
 * interpolate colour *differences*, which are smooth (Hamilton & Adams 1997
 * US 5,629,734 green estimate; AHD, Hirakawa & Parks 2005; for multi-frame
 * demosaicing Farsiu, Elad & Milanfar 2006: ignoring the cross-channel
 * relation causes colour artefacts).
 *
 * R/B samples enter the accumulation as D = wb_c * C - G_hat, where G_hat
 * is the frame's Hamilton-Adams directional green at that site; G samples
 * are unchanged. The difference is white-balanced so neutral = 0 (smoothing
 * raw R-G across a dark/bright edge shifts colour: the stage-2 "green cast"
 * lesson). After the divide: C = (G + D) / wb_c.
 *
 * Kernels and robustness keep reading the ORIGINAL frame; only the splat
 * input changes. Crop A/B (burst 170823, 8 frames): R/B edge zipper ratio
 * 1.40 -> 1.13 (green's own 1.09), Y noise +4 %, sharpness +0.7 %.
 *
 * Layout: RGGB-normalised rows (R at (0,0), B at (1,1)).
 */
object ColourDiff {

    private fun gHat(f: Array<FloatArray>, y: Int, x: Int): Float {
        val h = f.size
        val w = f[0].size
        fun at(yy: Int, xx: Int) = f[yy.coerceIn(0, h - 1)][xx.coerceIn(0, w - 1)]
        val c = f[y][x]
        val lapH = 2 * c - at(y, x - 2) - at(y, x + 2)
        val lapV = 2 * c - at(y - 2, x) - at(y + 2, x)
        val gh = (at(y, x - 1) + at(y, x + 1)) / 2 + lapH / 4
        val gv = (at(y - 1, x) + at(y + 1, x)) / 2 + lapV / 4
        val dh = kotlin.math.abs(at(y, x - 1) - at(y, x + 1)) + kotlin.math.abs(lapH)
        val dv = kotlin.math.abs(at(y - 1, x) - at(y + 1, x)) + kotlin.math.abs(lapV)
        return when {
            dh < dv -> gh
            dv < dh -> gv
            else -> (gh + gv) / 2
        }
    }

    /** Splat-input value at (y, x). */
    fun valueAt(f: Array<FloatArray>, y: Int, x: Int, wbR: Float, wbB: Float): Float {
        val v = f[y][x]
        return when {
            (y + x) and 1 == 1 -> v
            y and 1 == 0 -> wbR * v - gHat(f, y, x)
            else -> wbB * v - gHat(f, y, x)
        }
    }

    /** New rows for the CPU path. */
    fun toRows(f: Array<FloatArray>, wbR: Float, wbB: Float): Array<FloatArray> =
        Array(f.size) { y -> FloatArray(f[0].size) { x -> valueAt(f, y, x, wbR, wbB) } }

    /** Packs the splat input straight into a native-order float staging buffer (GPU path, no extra rows). */
    fun packInto(f: Array<FloatArray>, buf: ByteBuffer, wbR: Float, wbB: Float): ByteBuffer {
        buf.rewind()
        val fb = buf.asFloatBuffer()
        val row = FloatArray(f[0].size)
        for (y in f.indices) {
            for (x in row.indices) row[x] = valueAt(f, y, x, wbR, wbB)
            fb.put(row)
        }
        buf.rewind()
        return buf
    }

    /** After the divide: interleaved RGB (R, G, B per pixel) back from (D_R, G, D_B). */
    fun reconstruct(rgb: FloatBuffer, pixels: Int, wbR: Float, wbB: Float) {
        for (i in 0 until pixels) {
            val g = rgb.get(3 * i + 1)
            rgb.put(3 * i, (g + rgb.get(3 * i)) / wbR)
            rgb.put(3 * i + 2, (g + rgb.get(3 * i + 2)) / wbB)
        }
    }

    /** CPU-path twin of [reconstruct] on `[h][w][3]`. */
    fun reconstruct(img: Array<Array<FloatArray>>, wbR: Float, wbB: Float) {
        for (row in img) for (p in row) {
            p[0] = (p[1] + p[0]) / wbR
            p[2] = (p[1] + p[2]) / wbB
        }
    }
}
