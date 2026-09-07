// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Native libjpeg-turbo encoder for SDR output. Ultra HDR remains on Android's JPEG/R writer. */
object NativeJpegEncoder {
    init { System.loadLibrary("dngCreator") }

    @Throws(IOException::class)
    fun encode(bitmap: Bitmap, descriptor: ParcelFileDescriptor, settings: JpegOutputSettings) {
        val resolved = settings.resolvedForPlatform()
        val icc = JpegIccProfileProvider.profile(resolved.displayP3)
        val error = nativeEncode(
            bitmap,
            descriptor.fd,
            resolved.jpegQuality,
            resolved.chromaSubsampling.nativeValue,
            icc
        )
        if (error != null) throw IOException(error)
    }

    private external fun nativeEncode(
        bitmap: Bitmap,
        fd: Int,
        quality: Int,
        subsampling: Int,
        iccProfile: ByteArray
    ): String?
}

enum class JpegChromaSubsampling(val label: String, val nativeValue: Int) {
    YUV_422("4:2:2", 422),
    YUV_444("4:4:4", 444);

    fun next(): JpegChromaSubsampling = if (this == YUV_422) YUV_444 else YUV_422

    companion object {
        fun fromPreference(value: String?): JpegChromaSubsampling =
            entries.firstOrNull { it.name == value } ?: YUV_422
    }
}

/** Extracts Android's own ICC payload from a tiny color-tagged JPEG, so native files keep the
 * same sRGB / Display-P3 interpretation as the previous Bitmap.compress path. */
private object JpegIccProfileProvider {
    private val cache = HashMap<Boolean, ByteArray>()

    @Synchronized
    fun profile(displayP3: Boolean): ByteArray = cache.getOrPut(displayP3) {
        val colorSpace = ColorSpace.get(if (displayP3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, false, colorSpace)
        try {
            probe.eraseColor(Color.WHITE)
            val encoded = ByteArrayOutputStream(1024).use { output ->
                check(probe.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
            extractIcc(encoded)
        } finally {
            probe.recycle()
        }
    }

    private fun extractIcc(jpeg: ByteArray): ByteArray {
        var offset = 2
        val chunks = sortedMapOf<Int, ByteArray>()
        var expected = 0
        while (offset + 4 <= jpeg.size && (jpeg[offset].toInt() and 0xff) == 0xff) {
            val marker = jpeg[offset + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            if (marker == 0xd8 || marker in 0xd0..0xd7 || marker == 0x01) {
                offset += 2
                continue
            }
            val length = ((jpeg[offset + 2].toInt() and 0xff) shl 8) or (jpeg[offset + 3].toInt() and 0xff)
            if (length < 2 || offset + 2 + length > jpeg.size) break
            if (marker == 0xe2 && length >= 16) {
                val payload = offset + 4
                val signature = byteArrayOf(0x49,0x43,0x43,0x5f,0x50,0x52,0x4f,0x46,0x49,0x4c,0x45,0x00)
                var matches = true
                for (i in signature.indices) if (jpeg[payload + i] != signature[i]) { matches = false; break }
                if (matches) {
                    val sequence = jpeg[payload + 12].toInt() and 0xff
                    expected = jpeg[payload + 13].toInt() and 0xff
                    chunks[sequence] = jpeg.copyOfRange(payload + 14, offset + 2 + length)
                }
            }
            offset += 2 + length
        }
        if (expected == 0 || chunks.size != expected) return ByteArray(0)
        val total = chunks.values.sumOf { it.size }
        return ByteArray(total).also { output ->
            var at = 0
            for (index in 1..expected) {
                val chunk = chunks[index] ?: return ByteArray(0)
                chunk.copyInto(output, at)
                at += chunk.size
            }
        }
    }
}
