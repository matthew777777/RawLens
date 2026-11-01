// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/** Native RAW16/10/12 fast path. Kotlin reference remains the fallback and device parity gate. */
internal object NativeFocusPeaking {
    private val available = try { System.loadLibrary("rawLensFocusPeaking"); true }
        catch (_: LinkageError) { false } catch (_: SecurityException) { false }

    fun sample(raw: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
               packedBits: Int, cfa: Int, black: FloatArray, white: Float,
               profile: DoubleArray?, cols: Int, rows: Int): IntArray? {
        if (!available || !raw.isDirect) return null
        val points = IntArray(cols * rows)
        val calibration = floatArrayOf(white, black[0], black[1], black[2], black[3])
        return try {
            if (sampleNative(raw, width, height, rowStride, pixelStride, packedBits, cfa,
                calibration, profile ?: DoubleArray(8), cols, rows, points)) points else null
        } catch (_: LinkageError) { null } catch (_: IllegalArgumentException) { null }
    }

    private external fun sampleNative(raw: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int, packedBits: Int, cfa: Int, calibration: FloatArray, profile: DoubleArray,
        cols: Int, rows: Int, points: IntArray): Boolean
}
